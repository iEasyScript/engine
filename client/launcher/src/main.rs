mod auth;
mod characters;
mod config;
mod engine;
mod game;
mod java;
mod plugins;
mod ui;

use anyhow::{Context, Result};
use fs2::FileExt;
use std::fs::File;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::dpi::LogicalSize;
use tao::window::{Icon, Window, WindowBuilder};
use tokio::sync::mpsc;

use config::{load_config, load_credentials, Paths};
use ui::ipc::{AppCommand, IpcState};
use ui::webview::UserEvent;

/// Process-wide shared reqwest client. Building one Client per request creates a
/// fresh TLS config + connection pool each time, so keep-alive is lost across the
/// token-exchange → session-create → user-fetch → accounts-fetch chain. Reuse one.
static HTTP_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

/// The release this launcher was built as, stamped by `build.rs`. Compared
/// against the catalog so a launcher that has fallen behind the release it
/// installs plugins and the engine from can say so.
pub const VERSION: &str = env!("PROJECTX_LAUNCHER_VERSION");

/// Default per-request ceiling. Without it a stalled endpoint — most plausibly a
/// user-typed private-server host that black-holes packets — parks a launch task
/// forever, and the UI stays disabled because no error is ever emitted.
const HTTP_TIMEOUT: Duration = Duration::from_secs(30);
const HTTP_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Ceiling for multi-megabyte artifact downloads (client .deb, installers,
/// patcher library), which legitimately outlive [`HTTP_TIMEOUT`] on a slow link.
pub const HTTP_DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(300);

/// Returns the shared HTTP client (cheap clone — the inner state is `Arc`-backed).
pub fn http_client() -> reqwest::Client {
    HTTP_CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                // GitHub's API rejects requests without one.
                .user_agent(concat!("project-x-launcher/", env!("CARGO_PKG_VERSION")))
                .connect_timeout(HTTP_CONNECT_TIMEOUT)
                .timeout(HTTP_TIMEOUT)
                .build()
                .unwrap_or_else(|e| {
                    log::error!("Failed to build HTTP client ({}); falling back to defaults", e);
                    reqwest::Client::new()
                })
        })
        .clone()
}

/// Ordinal of the `ICON` entry in assets/projectx.rc, embedded by build.rs.
#[cfg(windows)]
const APP_ICON_ORDINAL: u16 = 1;

/// Title-bar icon (`ICON_SMALL`), read back from the executable's own icon resource so it
/// cannot drift from the one Explorer shows. Only Windows embeds one — elsewhere the
/// window keeps the toolkit default.
fn window_icon() -> Option<Icon> {
    #[cfg(windows)]
    {
        use tao::platform::windows::IconExtWindows;

        match Icon::from_resource(APP_ICON_ORDINAL, None) {
            Ok(icon) => Some(icon),
            Err(e) => {
                log::warn!("Embedded icon resource unavailable ({}); using default", e);
                None
            }
        }
    }
    #[cfg(not(windows))]
    None
}

/// Sets the taskbar button's icon. Separate from [`window_icon`] because tao's
/// `with_window_icon` only ever sets `ICON_SMALL`, and a window that leaves `ICON_BIG`
/// unset shows the generic executable glyph on the taskbar and in Alt-Tab.
fn apply_taskbar_icon(window: &Window) {
    #[cfg(windows)]
    {
        use tao::dpi::PhysicalSize;
        use tao::platform::windows::{IconExtWindows, WindowExtWindows};

        // tao documents 256 as the ceiling for ICON_BIG; Windows scales it down to
        // whatever the taskbar and Alt-Tab ask for at the current DPI.
        let size = PhysicalSize::new(256, 256);
        match Icon::from_resource(APP_ICON_ORDINAL, Some(size)) {
            Ok(icon) => window.set_taskbar_icon(Some(icon)),
            Err(e) => log::warn!("Taskbar icon resource unavailable ({}); using default", e),
        }
    }
    #[cfg(not(windows))]
    let _ = window;
}

/// Set when `RUST_LOG` was supplied, so [`apply_log_level`] leaves an explicit filter alone.
static RUST_LOG_EXPLICIT: OnceLock<bool> = OnceLock::new();

/// Raise or lower the effective log ceiling at runtime, so the Settings toggle applies without a
/// restart. The logger itself is built permissive; this is the gate the `log!` macros consult.
pub fn apply_log_level(debug_logging: bool) {
    if *RUST_LOG_EXPLICIT.get().unwrap_or(&false) {
        return;
    }
    log::set_max_level(if debug_logging {
        log::LevelFilter::Debug
    } else {
        log::LevelFilter::Info
    });
}

fn main() -> Result<()> {
    let _ = RUST_LOG_EXPLICIT.set(std::env::var_os("RUST_LOG").is_some());
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("debug")).init();
    apply_log_level(false);

    // Work around GBM buffer creation failures on some GPU/driver combos
    if std::env::var("WEBKIT_DISABLE_DMABUF_RENDERER").is_err() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    // One-time rebrand migration: relocate the launcher's data dir from the old
    // `bolt-rs3` ProjectDirs path to the new `project-x-launcher` path. Must run
    // before any data-dir access (Paths::new resolves the NEW dir). Best-effort:
    // never blocks launch.
    migrate_data_dir();

    let paths = Arc::new(Paths::new().context("Failed to initialize paths")?);
    paths.ensure_dirs()?;

    let lock_file = File::create(&paths.lock_file).context("Failed to create lockfile")?;
    if lock_file.try_lock_exclusive().is_err() {
        log::error!("Another instance is already running");
        std::process::exit(1);
    }

    let cfg = load_config(&paths.config_file);
    apply_log_level(cfg.debug_logging);
    let creds = load_credentials(&paths.creds_file);

    if std::env::args().any(|a| a == "--headless") {
        return run_headless(cfg, creds, paths);
    }

    // Build event loop first (initializes GTK)
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event()
        .build();
    let proxy = event_loop.create_proxy();

    // Setup tokio runtime after GTK init
    let runtime = tokio::runtime::Runtime::new().context("Failed to create tokio runtime")?;
    let _guard = runtime.enter();

    // Refresh saved sessions on startup, then restore into active Session objects
    let creds = runtime.block_on(refresh_saved_sessions(creds, &paths));
    let sessions = restore_sessions(&creds);

    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<AppCommand>();

    let state = Arc::new(IpcState::new(cfg.clone(), creds, paths.clone(), cmd_tx, sessions));

    let main_window = WindowBuilder::new()
        .with_title("Project X Launcher")
        .with_window_icon(window_icon())
        .with_inner_size(LogicalSize::new(940.0, 600.0))
        .with_min_inner_size(LogicalSize::new(720.0, 460.0))
        .build(&event_loop)
        .context("Failed to create main window")?;

    apply_taskbar_icon(&main_window);

    let webview =
        ui::webview::create_main_webview(&main_window, state.clone(), proxy.clone())?;

    // The Init event is no longer pushed on a timer (a fixed sleep both adds
    // latency and races JS readiness). Instead the frontend posts a `ready` IPC
    // message once app.js boots, and `IpcState::handle_message` responds with
    // Init — see IpcMessage::Ready. This guarantees the payload lands after the
    // page's __projectx_callback is installed.

    // Auth window state
    let mut auth_window: Option<Window> = None;
    let mut auth_webview: Option<wry::WebView> = None;

    let poll_proxy = proxy.clone();
    std::thread::spawn(move || {
        while let Some(cmd) = cmd_rx.blocking_recv() {
            match cmd {
                AppCommand::OpenLoginWindow => {
                    let _ = poll_proxy.send_event(UserEvent::OpenLogin);
                }
                AppCommand::CloseWindow => {
                    let _ = poll_proxy.send_event(UserEvent::CloseApp);
                }
                AppCommand::SendToWebview(js) => {
                    let _ = poll_proxy.send_event(UserEvent::EvalScript(js));
                }
            }
        }
    });

    event_loop.run(move |event, event_loop, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                window_id,
                ..
            } => {
                if auth_window
                    .as_ref()
                    .map(|w| w.id() == window_id)
                    .unwrap_or(false)
                {
                    auth_webview = None;
                    auth_window = None;
                    // Release the consent callback server's hold on port 80 so an
                    // immediate retry can bind it again.
                    state.cancel_consent_wait();
                } else {
                    *control_flow = ControlFlow::Exit;
                }
            }

            Event::UserEvent(user_event) => match user_event {
                UserEvent::EvalScript(js) => {
                    if js.starts_with("__AUTH_NAVIGATE__:") {
                        let url = &js["__AUTH_NAVIGATE__:".len()..];
                        if let Some(ref wv) = auth_webview {
                            let _ = wv.load_url(url);
                        }
                    } else if js == "__AUTH_CLOSE__" {
                        auth_webview = None;
                        auth_window = None;
                    } else {
                        let _ = webview.evaluate_script(&js);
                    }
                }
                UserEvent::CloseApp => {
                    *control_flow = ControlFlow::Exit;
                }
                UserEvent::OpenLogin => {
                    if auth_window.is_some() {
                        if let Some(ref w) = auth_window {
                            w.set_focus();
                        }
                        return;
                    }

                    let pkce = auth::oauth::generate_pkce();
                    let login_url = auth::oauth::build_login_url(&pkce);

                    let window = WindowBuilder::new()
                        .with_title("Login - Jagex Account")
                        .with_window_icon(window_icon())
                        .with_inner_size(LogicalSize::new(480.0, 720.0))
                        .build(event_loop)
                        .expect("Failed to create auth window");

                    apply_taskbar_icon(&window);

                    let wv = ui::webview::create_auth_webview(
                        &window,
                        &login_url,
                        state.clone(),
                        proxy.clone(),
                        pkce,
                    )
                    .expect("Failed to create auth webview");

                    auth_window = Some(window);
                    auth_webview = Some(wv);
                }
            },

            _ => {}
        }
    });
}

/// Restore saved sessions from disk into active Session objects.
/// Reconstructs locally from saved data — no Jagex API calls needed.
/// Runs a custom-server launch with no window, so the launch, patch and injection path
/// stays reachable where no webview runtime exists — Wine/Proton, and headless hosts.
/// Progress that would go to the UI is logged instead.
fn run_headless(
    cfg: config::Config,
    creds: config::Credentials,
    paths: Arc<Paths>,
) -> Result<()> {
    let runtime = tokio::runtime::Runtime::new().context("Failed to create tokio runtime")?;
    let _guard = runtime.enter();

    let creds = runtime.block_on(refresh_saved_sessions(creds, &paths));
    let sessions = restore_sessions(&creds);

    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<AppCommand>();
    let state = Arc::new(IpcState::new(cfg, creds, paths, cmd_tx, sessions));

    log::info!("Headless launch starting (custom server)");
    state.handle_launch_custom();

    // The launch runs on its own thread and the injector polls for the client well after
    // it spawns, so stay alive draining progress until interrupted.
    runtime.block_on(async move {
        while let Some(cmd) = cmd_rx.recv().await {
            if let AppCommand::SendToWebview(js) = cmd {
                log::info!("{}", js);
            }
        }
    });
    Ok(())
}

fn restore_sessions(creds: &config::Credentials) -> Vec<auth::types::Session> {
    let mut sessions = Vec::new();

    for saved in &creds.sessions {
        // Skip sessions that don't have saved accounts (pre-migration creds.json)
        if saved.accounts.is_empty() {
            log::warn!(
                "Skipping session for {} — no saved accounts (re-login required)",
                saved.display_name
            );
            continue;
        }

        let session_id = match &saved.session_id {
            Some(id) => id.clone(),
            None => {
                log::warn!(
                    "Skipping session for {} — no saved session_id (re-login required)",
                    saved.display_name
                );
                continue;
            }
        };

        log::info!(
            "Restored session for {} ({} accounts)",
            saved.display_name,
            saved.accounts.len()
        );
        sessions.push(auth::types::Session {
            user: auth::types::User {
                id: None,
                user_id: saved.user_id.clone(),
                display_name: saved.display_name.clone(),
                suffix: String::new(),
            },
            accounts: saved.accounts.clone(),
            tokens: auth::types::AuthTokens {
                access_token: saved.access_token.clone(),
                id_token: saved.id_token.clone(),
                refresh_token: saved.refresh_token.clone(),
                sub: saved.user_id.clone(),
                expiry: saved.expiry,
            },
            session_id,
            consent_id_token: saved.consent_id_token.clone(),
        });
    }

    sessions
}

/// Refresh any saved sessions that are expired or near expiry.
///
/// After refreshing OAuth tokens, also creates a new game session_id
/// from the fresh id_token. Game sessions expire independently of OAuth
/// tokens, so the stored session_id must be renewed too.
async fn refresh_saved_sessions(
    mut creds: config::Credentials,
    paths: &Paths,
) -> config::Credentials {
    let client = http_client();
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or(std::time::Duration::ZERO)
        .as_millis() as u64;

    let mut to_remove = Vec::new();

    for (i, session) in creds.sessions.iter_mut().enumerate() {
        // Refresh if expired or within 5 minutes of expiry
        if session.expiry.saturating_sub(now_ms) < 300_000 {
            log::info!("Refreshing token for user {}", session.display_name);
            match auth::oauth::refresh_token(&client, &session.refresh_token).await {
                Ok(tokens) => {
                    session.access_token = tokens.access_token;
                    session.id_token = tokens.id_token;
                    session.refresh_token = tokens.refresh_token;
                    session.expiry = tokens.expiry;
                    // Note: we do NOT create a new game session here because
                    // create_session requires the consent id_token (from the
                    // consent flow), not the launcher id_token from refresh.
                    // Fresh game sessions are created at launch time using
                    // the stored consent_id_token.
                }
                Err(e) => {
                    log::warn!(
                        "Failed to refresh token for {}: {}. Removing.",
                        session.display_name,
                        e
                    );
                    to_remove.push(i);
                }
            }
        }
    }

    // Remove failed sessions (in reverse order to preserve indices)
    for i in to_remove.into_iter().rev() {
        creds.sessions.remove(i);
    }

    let _ = config::save_credentials(&paths.creds_file, &creds);
    creds
}

/// One-time rebrand migration onto the current ProjectDirs application name.
///
/// The name has changed twice (`bolt-rs3`, then `darkan-launcher`), and each
/// change moves the data dir (e.g. on Linux
/// `~/.local/share/bolt-rs3/` → `~/.local/share/project-x-launcher/`). That dir
/// holds live state — `creds.json` (OAuth login), the downloaded `Jagex/`
/// client, `libprojectx_patcher.so`, webview storage — so without migrating it the
/// user would be silently logged out and re-download the whole client.
///
/// If the NEW data dir is missing or empty AND the OLD one exists with content,
/// move old → new (rename, falling back to a recursive copy across filesystems).
/// Idempotent and best-effort: any failure logs a warning and lets launch
/// continue (a fresh data dir is still usable, just unmigrated).
fn migrate_data_dir() {
    use directories::ProjectDirs;

    // Newest legacy name first, so the most recent data wins if several survive.
    const LEGACY_APP_NAMES: [&str; 2] = ["darkan-launcher", "bolt-rs3"];

    let new_dirs = match ProjectDirs::from("", "", "project-x-launcher") {
        Some(d) => d,
        None => return,
    };
    let new_data = new_dirs.data_dir();

    let Some(old_dirs) = LEGACY_APP_NAMES
        .iter()
        .filter_map(|name| ProjectDirs::from("", "", name))
        .find(|d| d.data_dir() != new_data && dir_has_contents(d.data_dir()))
    else {
        return;
    };
    let old_data = old_dirs.data_dir();
    if dir_has_contents(new_data) {
        log::info!(
            "Skipping data-dir migration: {} already has data",
            new_data.display()
        );
        return;
    }

    log::info!(
        "Migrating launcher data dir {} -> {} (rebrand onto Project X Launcher)",
        old_data.display(),
        new_data.display()
    );

    // Ensure the new parent exists so a cross-dir rename can land.
    if let Some(parent) = new_data.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            log::warn!(
                "Data-dir migration: failed to create {}: {}. Continuing without migration.",
                parent.display(),
                e
            );
            return;
        }
    }

    match std::fs::rename(old_data, new_data) {
        Ok(()) => log::info!("Data-dir migration complete (renamed)."),
        Err(rename_err) => {
            // rename() fails across filesystems (EXDEV) — fall back to copy+remove.
            log::info!(
                "Data-dir rename failed ({}); falling back to recursive copy.",
                rename_err
            );
            match copy_dir_recursive(old_data, new_data) {
                Ok(()) => {
                    if let Err(e) = std::fs::remove_dir_all(old_data) {
                        log::warn!(
                            "Data-dir migration: copied to {} but failed to remove old {}: {}",
                            new_data.display(),
                            old_data.display(),
                            e
                        );
                    } else {
                        log::info!("Data-dir migration complete (copied).");
                    }
                }
                Err(e) => log::warn!(
                    "Data-dir migration: recursive copy failed: {}. Continuing without migration.",
                    e
                ),
            }
        }
    }
}

/// True if `dir` exists and contains at least one entry. A missing dir or an
/// empty dir both count as "no contents" (treat as needing/eligible-for migration).
fn dir_has_contents(dir: &std::path::Path) -> bool {
    match std::fs::read_dir(dir) {
        Ok(mut entries) => entries.next().is_some(),
        Err(_) => false,
    }
}

/// Recursively copy `src` into `dst`, creating directories as needed. Used as a
/// cross-filesystem fallback when `std::fs::rename` fails with EXDEV.
fn copy_dir_recursive(src: &std::path::Path, dst: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_recursive(&from, &to)?;
        } else {
            // Covers regular files and symlinks (copies the target contents).
            std::fs::copy(&from, &to)?;
        }
    }
    Ok(())
}
