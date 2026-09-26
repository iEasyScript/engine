use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::sleep;

use crate::auth::session::create_session;
use crate::auth::types::{Account, Session};
use crate::auth::user::get_accounts;
use crate::config::{Config, Credentials, Paths, PluginsConfig, SavedSession, ServerMode};
use crate::game::control::ClientStatus;
use crate::game::process::{host_os_dir, launcher_binary_name, LaunchParams, PatchEnv};
use crate::game::rs3::{with_host_binary_type, JS5_RSA_PARAM, LOGIN_RSA_PARAM};
use crate::plugins::{self, Catalog, PluginChannel, PluginsSnapshot, CHANNELS};
use url::Url;

/// Messages from JS to Rust
#[derive(Debug, Deserialize)]
#[serde(tag = "type")]
pub enum IpcMessage {
    /// Sent once by app.js when the frontend has booted and installed
    /// `window.__projectx_callback`. We reply with `Init` (no startup sleep/race).
    #[serde(rename = "ready")]
    Ready,
    #[serde(rename = "login")]
    Login,
    #[serde(rename = "logout")]
    Logout { user_id: String },
    #[serde(rename = "launch")]
    Launch {
        account_id: String,
        display_name: String,
    },
    #[serde(rename = "launch_custom")]
    LaunchCustom,
    #[serde(rename = "save_config")]
    SaveConfig { config: Config },
    #[serde(rename = "close")]
    Close,
    /// Discover running rs2client processes + their engine state (Clients panel).
    #[serde(rename = "list_clients")]
    ListClients,
    /// First-inject the engine into a specific rs2client pid (GDB-dlopen path).
    #[serde(rename = "inject_client")]
    InjectClient { pid: u32 },
    /// Uninject the engine from a pid via the control socket (no elevation).
    #[serde(rename = "uninject_client")]
    UninjectClient { pid: u32 },
    /// Reinject (unload + reload the rebuilt jar) via the control socket.
    #[serde(rename = "reinject_client")]
    ReinjectClient { pid: u32 },
    /// Avatar and hiscore data for one saved character, answered by [`IpcEvent::CharacterInfo`].
    #[serde(rename = "character_info")]
    CharacterInfo { account_id: String, display_name: String },
    /// Launch a list of accounts one after another, with a gap between each.
    #[serde(rename = "scheduler_start")]
    SchedulerStart {
        accounts: Vec<ScheduledAccount>,
        delay_seconds: u64,
        #[serde(default)]
        repeat: bool,
    },
    /// Stop the run after the launch in flight; nothing already started is killed.
    #[serde(rename = "scheduler_stop")]
    SchedulerStop,
    /// Persist the character last selected under a Jagex account (no response).
    #[serde(rename = "select_character")]
    SelectCharacter { user_id: String, account_id: String },
    /// Render the Plugins tab. `force` re-fetches the release catalog instead of
    /// reusing the cached one.
    #[serde(rename = "plugins_status")]
    PluginsStatus {
        #[serde(default)]
        force: bool,
    },
    /// Download the newest published jar for a channel into the scripts folder.
    #[serde(rename = "plugin_install")]
    PluginInstall { id: String },
    /// Delete the launcher-installed jar for a channel.
    #[serde(rename = "plugin_remove")]
    PluginRemove { id: String },
    /// Turn managed updates for a channel on or off. Turning one on installs
    /// straight away, since that is what the user just asked for.
    #[serde(rename = "plugin_set_auto")]
    PluginSetAuto { id: String, enabled: bool },
    /// Reveal the scripts folder in the desktop file manager.
    #[serde(rename = "plugins_open_dir")]
    PluginsOpenDir,
    /// Open a release or download link from the Plugins tab in the browser.
    #[serde(rename = "open_url")]
    OpenUrl { url: String },
}

/// Events from Rust to JS
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type")]
pub enum IpcEvent {
    #[serde(rename = "init")]
    Init {
        config: Config,
        sessions: Vec<SessionInfo>,
    },
    /// A background startup refresh re-fetched the account list from Jagex; the
    /// UI replaces its cached sessions with this fresher set.
    #[serde(rename = "sessions_updated")]
    SessionsUpdated { sessions: Vec<SessionInfo> },
    #[serde(rename = "login_complete")]
    LoginComplete { session: SessionInfo },
    #[serde(rename = "login_error")]
    LoginError { message: String },
    #[serde(rename = "logout_complete")]
    LogoutComplete { user_id: String },
    /// Progress line for a launch in flight. Completion is signalled by
    /// [`IpcEvent::LaunchComplete`], never by the text of a status message.
    #[serde(rename = "launch_status")]
    LaunchStatus { message: String },
    #[serde(rename = "launch_complete")]
    LaunchComplete,
    #[serde(rename = "launch_error")]
    LaunchError { message: String },
    #[serde(rename = "config_saved")]
    ConfigSaved,
    /// What RuneScape knows about a character. Every field is optional: a character with no avatar
    /// and no hiscore entry is normal, not an error.
    #[serde(rename = "character_info")]
    CharacterInfo {
        account_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        avatar: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        total: Option<u64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        rank: Option<u64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        xp: Option<u64>,
    },
    /// Where a scheduler run has got to. `running` false means it has finished or been stopped.
    #[serde(rename = "scheduler_status")]
    SchedulerStatus {
        running: bool,
        message: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        index: Option<usize>,
        #[serde(skip_serializing_if = "Option::is_none")]
        total: Option<usize>,
    },
    /// The current list of discovered rs2client processes + their engine state.
    #[serde(rename = "clients_list")]
    ClientsList { clients: Vec<ClientStatus> },
    /// Result of an inject/uninject/reinject action on a specific pid.
    #[serde(rename = "client_control_result")]
    ClientControlResult {
        pid: u32,
        ok: bool,
        message: String,
    },
    /// Everything the Plugins tab renders: the scripts folder, the release it is
    /// tracking, and one row per channel.
    #[serde(rename = "plugins_status")]
    PluginsStatus { snapshot: PluginsSnapshot },
    /// Outcome of an install/remove for one channel.
    #[serde(rename = "plugin_result")]
    PluginResult {
        id: String,
        ok: bool,
        message: String,
    },
}

#[derive(Debug, Clone, Serialize)]
pub struct SessionInfo {
    pub user_id: String,
    pub display_name: String,
    pub accounts: Vec<Account>,
    pub session_id: String,
    pub last_account_id: Option<String>,
}

/// Which control action the Clients panel requested for a given pid.
#[derive(Debug, Clone, Copy)]
enum ClientAction {
    Inject,
    Uninject,
    Reinject,
}

/// Commands sent from IPC handler to the main event loop
pub enum AppCommand {
    OpenLoginWindow,
    CloseWindow,
    SendToWebview(String),
}

/// Serialize an event and route it to the webview via `cmd_tx`. Shared by the
/// spawned launch tasks so the JS-callback formatting lives in one place.
fn send_webview_event(cmd_tx: &mpsc::UnboundedSender<AppCommand>, event: &IpcEvent) {
    let js = format!(
        "window.__projectx_callback({})",
        serde_json::to_string(event).unwrap()
    );
    let _ = cmd_tx.send(AppCommand::SendToWebview(js));
}

/// Reports launch progress, completion and failure to the webview. Shared by
/// the live and custom launch tasks and by the helpers they call.
#[derive(Clone)]
struct LaunchReporter {
    cmd_tx: mpsc::UnboundedSender<AppCommand>,
}

impl LaunchReporter {
    fn status(&self, message: impl Into<String>) {
        send_webview_event(
            &self.cmd_tx,
            &IpcEvent::LaunchStatus {
                message: message.into(),
            },
        );
    }

    fn complete(&self) {
        send_webview_event(&self.cmd_tx, &IpcEvent::LaunchComplete);
    }

    fn error(&self, message: impl Into<String>) {
        send_webview_event(
            &self.cmd_tx,
            &IpcEvent::LaunchError {
                message: message.into(),
            },
        );
    }
}

/// Re-fetch a session's account list from Jagex, renewing the game session_id if
/// the stored one is stale. Returns the fetched accounts plus a fresh session_id
/// when one had to be created (so the caller can persist it).
async fn fetch_accounts_refreshed(
    client: &reqwest::Client,
    session_id: Option<String>,
    consent_id_token: Option<String>,
) -> Result<(Vec<Account>, Option<String>)> {
    if let Some(sid) = &session_id {
        match get_accounts(client, sid).await {
            Ok(accounts) => return Ok((accounts, None)),
            Err(e) => log::warn!(
                "Accounts fetch with stored session failed ({}); trying a fresh session",
                e
            ),
        }
    }

    let cit = consent_id_token
        .ok_or_else(|| anyhow!("no stored session and no consent_id_token to renew one"))?;
    let new_session_id = create_session(client, &cit)
        .await
        .context("failed to create a fresh game session")?;
    let accounts = get_accounts(client, &new_session_id)
        .await
        .context("accounts fetch with fresh session failed")?;
    Ok((accounts, Some(new_session_id)))
}

/// Launch the live client and report the result to the webview. Dedupes the two
/// identical launch call sites in `handle_launch` (the normal path and the
/// "package check failed, launch existing binary" fallback).
#[allow(clippy::too_many_arguments)]
fn do_launch_live(
    reporter: &LaunchReporter,
    binary: &Path,
    config_uri: &str,
    params: &LaunchParams,
    data_dir: &Path,
    deps_dir: &Path,
    custom_cmd: Option<&str>,
    patch: PatchEnv<'_>,
    auto_inject: bool,
    close_after: bool,
) {
    let mut attempt = 1;
    loop {
        let pid = match crate::game::process::launch_rs3(
            binary,
            config_uri,
            Some(params),
            data_dir,
            deps_dir,
            custom_cmd,
            patch,
            None, // no working_dir for live mode
            &ServerMode::Live,
        ) {
            Ok(pid) => pid,
            Err(e) => return reporter.error(format!("Failed to launch: {}", e)),
        };

        #[cfg(windows)]
        if custom_cmd.is_none() && attempt < LAUNCHER_ATTEMPTS {
            use crate::game::inject::windows::{watch_launcher_start, LauncherStart};
            let outcome =
                tokio::task::block_in_place(|| watch_launcher_start(pid, LAUNCHER_START_TIMEOUT));
            if let LauncherStart::Crashed(code) = outcome {
                log::warn!(
                    "The Jagex launcher (pid {}) crashed with 0x{:08X} before starting the client; retrying",
                    pid,
                    code
                );
                reporter.status("The RuneScape launcher crashed while updating the client; trying again...");
                attempt += 1;
                continue;
            }
        }
        #[cfg(not(windows))]
        let _ = attempt;

        return finish_launch(reporter, pid, auto_inject, close_after);
    }
}

/// How many times a live launch starts the Jagex launcher when it keeps crashing
/// while it updates the client.
#[cfg(windows)]
const LAUNCHER_ATTEMPTS: u32 = 3;

/// Long enough for the Jagex launcher to download a full client on a slow line.
#[cfg(windows)]
const LAUNCHER_START_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(180);

/// Common tail of a successful spawn: announce completion, optionally inject the
/// Project X engine, optionally close the launcher.
fn finish_launch(reporter: &LaunchReporter, pid: u32, auto_inject: bool, close_after: bool) {
    reporter.complete();
    if auto_inject {
        crate::game::inject::maybe_inject_projectx(pid);
    }
    if close_after {
        let _ = reporter.cmd_tx.send(AppCommand::CloseWindow);
    }
}

/// Install or update the Jagex launcher at `paths.rs3_binary` for a live launch.
///
/// The two hosts acquire it from different places: Linux installs the
/// `runescape-launcher` Debian package (hash-gated against the archive index, so
/// a published build updates in place), while Windows and macOS take the launcher
/// from `data/client/<host-os>/`, auto-acquiring it from Jagex's installer when
/// that slot is empty. Running the Debian flow off-Linux writes an ELF that the
/// host cannot execute, which surfaces only as a failed spawn.
async fn ensure_live_launcher(
    reporter: &LaunchReporter,
    paths: &Paths,
    client: &reqwest::Client,
) -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        use crate::game::deb::extract_rs3_binary;
        use crate::game::rs3::{
            download_deb, fetch_package_info, is_up_to_date, save_hash, verify_hash,
        };
        use tokio::task::spawn_blocking;

        // Runs before the up-to-date short-circuit: an unchanged client still
        // fails to start if the host is missing openssl 1.1 or gtk2.
        reporter.status("Checking runtime libraries...");
        if let Err(e) = crate::game::nativedeps::ensure(client, &paths.data_dir).await {
            log::warn!("Runtime library provisioning failed: {}", e);
        }

        let pkg_info = fetch_package_info(client).await?;
        if is_up_to_date(&paths.rs3_hash, &pkg_info.sha256) {
            reporter.status("Client is up-to-date");
            return Ok(());
        }

        reporter.status("Downloading client...");
        let deb_bytes = download_deb(client, &pkg_info.filename).await?;
        if !verify_hash(&deb_bytes, &pkg_info.sha256) {
            return Err(anyhow!("Hash verification failed"));
        }

        // Extract off the async workers — xz decompress + tar walk + the atomic
        // temp-file write are all blocking. `extract_rs3_binary` writes via temp
        // file + rename, so the binary is never truncated.
        reporter.status("Extracting client...");
        let out = paths.rs3_binary.clone();
        spawn_blocking(move || extract_rs3_binary(deb_bytes, &out)).await??;

        if let Err(e) = save_hash(&paths.rs3_hash, &pkg_info.sha256) {
            log::error!("Failed to save hash: {}", e);
        }
        reporter.status("Client updated successfully");
        Ok(())
    }

    #[cfg(not(target_os = "linux"))]
    {
        let _ = client;
        ensure_launcher_binary(reporter, &paths.data_dir).await.map(|_| ())
    }
}

/// Install what the Project X engine needs before the client starts, so the
/// inject that follows the spawn has a complete engine home to load from.
///
/// Best-effort: a client that launches without the engine is still a client that
/// launches, so a failure is reported and the launch continues.
async fn ensure_engine_installed(reporter: &LaunchReporter, plugins_cfg: &PluginsConfig) {
    let progress = |message: String| reporter.status(message);
    match crate::engine::ensure(&crate::http_client(), plugins_cfg, &progress).await {
        Ok(home) => log::info!("Engine home ready at {}", home.display()),
        Err(e) => {
            log::warn!("Engine provisioning failed: {}", e);
            reporter.status(format!("Continuing without the engine: {}", e));
        }
    }
}

/// Ensure the Jagex `rs3*` launcher exists in `dir`, seeding it from a copy
/// already on this machine and downloading it from Jagex when there is none.
async fn ensure_launcher_binary(reporter: &LaunchReporter, dir: &Path) -> Result<PathBuf> {
    let launcher_name = launcher_binary_name();
    let target = dir.join(launcher_name);
    if target.exists() {
        return Ok(target);
    }

    if let Some(source) = local_launcher_candidates(launcher_name)
        .into_iter()
        .flatten()
        .find(|c| c.is_file())
    {
        reporter.status("Installing launcher binary...");
        tokio::fs::copy(&source, &target).await.with_context(|| {
            format!("Failed to copy {} to {}", source.display(), dir.display())
        })?;
        set_executable(&target);
        return Ok(target);
    }

    reporter.status("Downloading Jagex launcher...");
    crate::game::rs3::acquire_host_launcher(&crate::http_client(), &target)
        .await
        .with_context(|| format!("Could not obtain {}", launcher_name))?;
    set_executable(&target);
    Ok(target)
}

/// Local slots that may already hold the Jagex launcher, in priority order.
///
/// Every entry is absolute (or resolved against the running executable): the
/// launcher is started from wherever its shortcut points, so a path relative to
/// the working directory only ever resolves in a source tree.
fn local_launcher_candidates(launcher_name: &str) -> Vec<Option<PathBuf>> {
    let per_os = PathBuf::from("data").join("client").join(host_os_dir()).join(launcher_name);
    let exe_dir = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf));
    vec![
        // Live mode's own copy: custom mode gets its own data dir, and there is
        // no reason for it to re-download what live mode already fetched.
        Paths::new().ok().map(|p| p.data_dir.join(launcher_name)),
        // Beside the launcher executable — the release bundle's layout.
        exe_dir.as_ref().map(|d| d.join(launcher_name)),
        exe_dir.as_ref().map(|d| d.join(&per_os)),
        // A source tree, whether the launcher runs from the repo root or from
        // its own target/<profile>/ directory.
        Some(per_os.clone()),
        exe_dir.map(|d| d.join("..").join("..").join("..").join("..").join(&per_os)),
    ]
}

/// Put the host's patcher library in `dir`, preferring the copy served by the
/// config server (so a server-side rebuild propagates) and falling back to local
/// candidate slots. Best-effort: the caller's hard-fail check reports a total
/// miss with actionable text.
///
/// The file is only rewritten when its bytes actually change — the client may be
/// about to `dlopen` it, and rewriting it in place on every launch is needless.
async fn sync_patcher_library(reporter: &LaunchReporter, dir: &Path, config_uri: &str) {
    let patcher_name = crate::game::process::patcher_lib_name();
    let target = dir.join(patcher_name);
    let patcher_url = format!("{}{}", config_server_base_url(config_uri), patcher_name);

    match fetch_patcher_bytes(&patcher_url).await {
        Ok(bytes) => {
            match write_if_changed(&target, &bytes).await {
                Ok(true) => {
                    set_executable(&target);
                    reporter.status(format!(
                        "Fetched patcher from {} ({} bytes)",
                        patcher_url,
                        bytes.len()
                    ));
                }
                Ok(false) => log::info!("Patcher at {} already current", target.display()),
                Err(e) => log::warn!("Failed to write patcher from {}: {}", patcher_url, e),
            }
            return;
        }
        Err(e) => log::warn!("Patcher fetch from {} failed: {}", patcher_url, e),
    }

    for candidate in local_patcher_candidates(patcher_name).into_iter().flatten() {
        if !candidate.exists() {
            continue;
        }
        match tokio::fs::read(&candidate).await {
            Ok(bytes) => match write_if_changed(&target, &bytes).await {
                Ok(true) => set_executable(&target),
                Ok(false) => {}
                Err(e) => log::warn!("Failed to update patcher {}: {}", target.display(), e),
            },
            Err(e) => log::warn!("Failed to read patcher {}: {}", candidate.display(), e),
        }
        break;
    }
}

async fn fetch_patcher_bytes(url: &str) -> Result<bytes::Bytes> {
    let resp = crate::http_client()
        .get(url)
        .timeout(crate::HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(anyhow!("HTTP {}", resp.status()));
    }
    let bytes = resp.bytes().await?;
    if bytes.is_empty() {
        return Err(anyhow!("empty body"));
    }
    Ok(bytes)
}

/// Write `bytes` to `path` unless the file already holds exactly those bytes.
/// Returns whether a write happened.
async fn write_if_changed(path: &Path, bytes: &[u8]) -> std::io::Result<bool> {
    if tokio::fs::read(path).await.map(|old| old == bytes).unwrap_or(false) {
        return Ok(false);
    }
    tokio::fs::write(path, bytes).await?;
    Ok(true)
}

/// Local fallback slots for the patcher library, in priority order.
fn local_patcher_candidates(patcher_name: &str) -> Vec<Option<PathBuf>> {
    vec![
        Some(
            PathBuf::from("data")
                .join("client")
                .join(crate::game::process::host_os_dir())
                .join(patcher_name),
        ),
        std::env::current_exe()
            .ok()
            .and_then(|e| e.parent().map(|p| p.join(patcher_name))),
        std::env::current_exe().ok().and_then(|e| {
            e.parent().map(|p| {
                p.join("..")
                    .join("..")
                    .join("..")
                    .join(crate::game::process::patcher_crate())
                    .join("target")
                    .join("release")
                    .join(patcher_name)
            })
        }),
    ]
}

/// Strip the final path segment (and any query) off a config URI to get the
/// directory the config server serves from:
/// `http://h:8829/jav_config.ws` → `http://h:8829/`,
/// `http://h/k=5/l=0/jav_config.ws` → `http://h/k=5/l=0/`.
fn config_server_base_url(config_uri: &str) -> String {
    let without_query = config_uri.split('?').next().unwrap_or(config_uri);
    match without_query.rfind('/') {
        Some(last_slash) => format!("{}/", &without_query[..last_slash]),
        None => format!("{}/", without_query),
    }
}

/// Seed `preferences.cfg` so the client keeps its cache and user data inside the
/// custom-mode data dir. Never overwrites an existing file.
async fn write_default_preferences(dir: &Path) {
    let path = dir.join("preferences.cfg");
    if path.exists() {
        return;
    }
    let contents = format!(
        "cache_folder={dir}\nLanguage=0\nuser_folder={dir}\n",
        dir = dir.display()
    );
    if let Err(e) = tokio::fs::write(&path, contents).await {
        log::warn!("Failed to write preferences.cfg: {}", e);
    }
}

fn set_executable(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Err(e) = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)) {
            log::warn!("Failed to chmod 0755 {}: {}", path.display(), e);
        }
    }
    #[cfg(not(unix))]
    let _ = path;
}

/// RAII guard that clears the launch-in-progress flag when dropped, so the flag
/// is released on every exit path of the spawned launch task (success, error,
/// or early return).
struct LaunchGuard(Arc<AtomicBool>);

impl Drop for LaunchGuard {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

/// Holds a channel's action slot for the lifetime of an install or removal.
struct PluginGuard {
    state: Arc<IpcState>,
    id: String,
}

impl Drop for PluginGuard {
    fn drop(&mut self) {
        self.state
            .plugin_busy
            .lock()
            .unwrap()
            .retain(|b| b != &self.id);
    }
}

pub struct IpcState {
    pub config: Mutex<Config>,
    pub credentials: Mutex<Credentials>,
    pub paths: Arc<Paths>,
    pub sessions: Mutex<Vec<Session>>,
    pub cmd_tx: mpsc::UnboundedSender<AppCommand>,
    /// True while a launch task (live or custom) is downloading/extracting/
    /// launching. Prevents concurrent launches from racing on the same binary.
    launching: Arc<AtomicBool>,
    /// Set once the startup account-list refresh has been kicked off, so a
    /// repeated `Ready` can't spawn a second refetch.
    startup_refresh_started: AtomicBool,
    /// Raised to abort the consent callback server's wait when the login window
    /// is closed, so it stops holding port 80.
    consent_cancel: Arc<AtomicBool>,
    /// Last successfully fetched release catalog, reused until it goes stale so
    /// opening the Plugins tab costs nothing.
    plugin_catalog: Mutex<Option<Catalog>>,
    /// Channel ids with an install or removal in flight, so a double-click
    /// cannot run two downloads over the same jar.
    plugin_busy: Mutex<Vec<String>>,
    /// True while a scheduler run is working through its accounts. Cleared to stop it.
    scheduler_running: Arc<AtomicBool>,
}

/// One account in a scheduler run.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ScheduledAccount {
    pub account_id: String,
    pub display_name: String,
}

impl IpcState {
    pub fn new(
        config: Config,
        credentials: Credentials,
        paths: Arc<Paths>,
        cmd_tx: mpsc::UnboundedSender<AppCommand>,
        initial_sessions: Vec<Session>,
    ) -> Self {
        Self {
            config: Mutex::new(config),
            credentials: Mutex::new(credentials),
            paths,
            sessions: Mutex::new(initial_sessions),
            cmd_tx,
            launching: Arc::new(AtomicBool::new(false)),
            startup_refresh_started: AtomicBool::new(false),
            consent_cancel: Arc::new(AtomicBool::new(false)),
            plugin_catalog: Mutex::new(None),
            plugin_busy: Mutex::new(Vec::new()),
            scheduler_running: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Arm a fresh consent wait and hand back its cancel flag.
    pub fn begin_consent_wait(&self) -> Arc<AtomicBool> {
        self.consent_cancel.store(false, Ordering::SeqCst);
        self.consent_cancel.clone()
    }

    /// Abort any consent wait in flight (the login window was closed).
    pub fn cancel_consent_wait(&self) {
        self.consent_cancel.store(true, Ordering::SeqCst);
    }

    /// Try to claim the launch slot. Returns a guard on success; on failure a
    /// launch is already in progress and the caller should reject the request.
    fn try_begin_launch(&self) -> Option<LaunchGuard> {
        if self.launching.swap(true, Ordering::SeqCst) {
            None
        } else {
            Some(LaunchGuard(self.launching.clone()))
        }
    }

    pub fn handle_message(self: &Arc<Self>, msg_str: &str) -> Result<()> {
        let msg: IpcMessage = serde_json::from_str(msg_str)?;
        // The Clients panel polls, so its message would otherwise beat once per interval forever.
        // Everything else is a discrete user action and worth an INFO line.
        if matches!(msg, IpcMessage::ListClients) {
            log::debug!("IPC message: {:?}", msg);
        } else {
            log::info!("IPC message: {:?}", msg);
        }

        match msg {
            IpcMessage::Ready => {
                let sessions = self.build_session_infos();
                let config = self.config.lock().unwrap().clone();
                self.send_event(&IpcEvent::Init { config, sessions });
                self.spawn_startup_account_refresh();
                self.spawn_startup_update_check();
            }
            IpcMessage::Login => {
                self.cmd_tx.send(AppCommand::OpenLoginWindow)?;
            }
            IpcMessage::Logout { ref user_id } => {
                self.handle_logout(user_id);
            }
            IpcMessage::Launch {
                ref account_id,
                ref display_name,
            } => {
                self.handle_launch(account_id, display_name);
            }
            IpcMessage::LaunchCustom => {
                self.handle_launch_custom();
            }
            IpcMessage::SaveConfig { ref config } => {
                self.handle_save_config(config);
            }
            IpcMessage::Close => {
                let _ = self.cmd_tx.send(AppCommand::CloseWindow);
            }
            IpcMessage::ListClients => {
                self.handle_list_clients();
            }
            IpcMessage::InjectClient { pid } => {
                self.handle_client_control(pid, ClientAction::Inject);
            }
            IpcMessage::UninjectClient { pid } => {
                self.handle_client_control(pid, ClientAction::Uninject);
            }
            IpcMessage::ReinjectClient { pid } => {
                self.handle_client_control(pid, ClientAction::Reinject);
            }
            IpcMessage::CharacterInfo {
                ref account_id,
                ref display_name,
            } => {
                self.handle_character_info(account_id.clone(), display_name.clone());
            }
            IpcMessage::SchedulerStart {
                ref accounts,
                delay_seconds,
                repeat,
            } => {
                self.handle_scheduler_start(accounts.clone(), delay_seconds, repeat);
            }
            IpcMessage::SchedulerStop => {
                self.scheduler_running.store(false, Ordering::SeqCst);
                self.send_event(&IpcEvent::SchedulerStatus {
                    running: false,
                    message: "Stopping after the launch in flight.".to_string(),
                    index: None,
                    total: None,
                });
            }
            IpcMessage::SelectCharacter {
                ref user_id,
                ref account_id,
            } => {
                self.persist_last_account(user_id, account_id);
            }
            IpcMessage::PluginsStatus { force } => {
                self.handle_plugins_status(force);
            }
            IpcMessage::PluginInstall { id } => {
                self.handle_plugin_install(id);
            }
            IpcMessage::PluginRemove { id } => {
                self.handle_plugin_remove(id);
            }
            IpcMessage::PluginSetAuto { id, enabled } => {
                self.handle_plugin_set_auto(id, enabled);
            }
            IpcMessage::PluginsOpenDir => {
                if let Err(e) = plugins::open_scripts_dir() {
                    log::warn!("Could not open the scripts folder: {}", e);
                }
            }
            IpcMessage::OpenUrl { ref url } => {
                if let Err(e) = plugins::open_url(url) {
                    log::warn!("Could not open {}: {}", url, e);
                }
            }
        }

        Ok(())
    }

    /// Kick off a one-shot background refetch of every saved session's account
    /// list, so characters created since the last launch appear. Runs off the UI
    /// path and never blocks startup; on any failure the cached accounts stand.
    fn spawn_startup_account_refresh(self: &Arc<Self>) {
        if self.startup_refresh_started.swap(true, Ordering::SeqCst) {
            return;
        }
        let state = Arc::clone(self);
        tokio::spawn(async move {
            state.refresh_all_accounts().await;
        });
    }

    async fn refresh_all_accounts(&self) {
        let snapshot: Vec<(String, Option<String>, Option<String>)> = {
            let creds = self.credentials.lock().unwrap();
            creds
                .sessions
                .iter()
                .map(|s| {
                    (
                        s.user_id.clone(),
                        s.session_id.clone(),
                        s.consent_id_token.clone(),
                    )
                })
                .collect()
        };
        if snapshot.is_empty() {
            return;
        }

        let client = crate::http_client();
        let mut updates: Vec<(String, Vec<Account>, Option<String>)> = Vec::new();
        for (user_id, session_id, consent_id_token) in snapshot {
            match fetch_accounts_refreshed(&client, session_id, consent_id_token).await {
                Ok((accounts, new_session_id)) => {
                    log::info!("Refreshed {} account(s) for {}", accounts.len(), user_id);
                    updates.push((user_id, accounts, new_session_id));
                }
                Err(e) => log::warn!(
                    "Keeping cached accounts for {} (refresh failed: {})",
                    user_id,
                    e
                ),
            }
        }
        if updates.is_empty() {
            return;
        }

        {
            let mut creds = self.credentials.lock().unwrap();
            for (user_id, accounts, new_session_id) in &updates {
                if let Some(saved) = creds.sessions.iter_mut().find(|s| &s.user_id == user_id) {
                    saved.accounts = accounts.clone();
                    if let Some(sid) = new_session_id {
                        saved.session_id = Some(sid.clone());
                    }
                }
            }
            let _ = crate::config::save_credentials(&self.paths.creds_file, &creds);
        }
        {
            let mut sessions = self.sessions.lock().unwrap();
            for (user_id, accounts, new_session_id) in &updates {
                if let Some(sess) = sessions.iter_mut().find(|s| &s.tokens.sub == user_id) {
                    sess.accounts = accounts.clone();
                    if let Some(sid) = new_session_id {
                        sess.session_id = sid.clone();
                    }
                }
            }
        }

        let sessions = self.build_session_infos();
        self.send_event(&IpcEvent::SessionsUpdated { sessions });
    }

    fn persist_last_account(&self, user_id: &str, account_id: &str) {
        let mut creds = self.credentials.lock().unwrap();
        if let Some(saved) = creds.sessions.iter_mut().find(|s| s.user_id == user_id) {
            saved.last_account_id = Some(account_id.to_string());
            let _ = crate::config::save_credentials(&self.paths.creds_file, &creds);
        }
    }

    fn persist_last_account_for(&self, account_id: &str) {
        let mut creds = self.credentials.lock().unwrap();
        if let Some(saved) = creds
            .sessions
            .iter_mut()
            .find(|s| s.accounts.iter().any(|a| a.account_id == account_id))
        {
            saved.last_account_id = Some(account_id.to_string());
            let _ = crate::config::save_credentials(&self.paths.creds_file, &creds);
        }
    }

    /// Discover clients off the UI thread (the `/proc` scan + per-pid STATUS socket
    /// round-trips are blocking) and emit a `ClientsList` event.
    fn handle_list_clients(&self) {
        let cmd_tx = self.cmd_tx.clone();
        tokio::task::spawn_blocking(move || {
            let clients = crate::game::control::discover_clients();
            send_webview_event(&cmd_tx, &IpcEvent::ClientsList { clients });
        });
    }

    /// Run an inject/uninject/reinject for `pid` off the UI thread, emit a
    /// `ClientControlResult`, then re-emit the refreshed `ClientsList`.
    ///
    /// Inject is the GDB-`dlopen` first-injection (may block on a pkexec/sudo
    /// prompt); uninject/reinject are plain control-socket commands.
    fn handle_client_control(&self, pid: u32, action: ClientAction) {
        let cmd_tx = self.cmd_tx.clone();
        let plugins_cfg = self.plugins_config();
        tokio::spawn(async move {
            // A first injection makes the bootstrap's own directory the engine
            // home, and the supervisor resolves the engine jar out of it — so an
            // incomplete home starts a JVM with nothing to run. The launch paths
            // provision it, but the Clients panel can inject into a client this
            // launcher never spawned, which is how a home with no engine jar
            // reaches the injector.
            //
            // Every inject, not only the first. An inject into a client that is
            // already injected used to skip this, so a client started before a
            // release and re-injected after it kept running the old engine while
            // the launcher sat on the new one - and scripts built against the new
            // API then failed on methods the running engine did not have. The
            // check costs one catalog fetch and downloads nothing when the
            // installed files already match.
            let injecting = matches!(action, ClientAction::Inject);
            let already_injected = injecting && crate::game::control::is_injected(pid);
            if injecting {
                let progress = |message: String| log::info!("{}", message);
                if let Err(e) =
                    crate::engine::ensure(&crate::http_client(), &plugins_cfg, &progress).await
                {
                    // A re-inject has a working home by definition, so a release
                    // that cannot be reached must not stop it; only a first
                    // injection genuinely needs the download to have succeeded.
                    if already_injected {
                        log::warn!("Engine update check failed ({}); injecting what is installed", e);
                    } else {
                        send_webview_event(
                            &cmd_tx,
                            &IpcEvent::ClientControlResult {
                                pid,
                                ok: false,
                                message: format!("The engine is not installed: {}", e),
                            },
                        );
                        return;
                    }
                }
            }

            let result = tokio::task::spawn_blocking(move || match action {
                // Inject re-enables the engine. If the .so is already mapped (the
                // engine was previously injected then UNINJECTed -> "unloaded"
                // state), re-enable it via the supervisor's socket INJECT. Only a
                // truly fresh process needs the GDB-dlopen first injection.
                ClientAction::Inject => {
                    if crate::game::control::is_injected(pid) {
                        crate::game::control::send_command(pid, "INJECT")
                    } else {
                        crate::game::control::inject_pid(pid)
                            .map(|()| format!("Injected engine into pid {}", pid))
                    }
                }
                ClientAction::Uninject => crate::game::control::send_command(pid, "UNINJECT"),
                ClientAction::Reinject => crate::game::control::send_command(pid, "REINJECT"),
            })
            .await
            .unwrap_or_else(|e| Err(anyhow!("inject task failed: {}", e)));

            let (ok, message) = match result {
                Ok(msg) => (true, msg),
                Err(e) => (false, format!("{}", e)),
            };

            send_webview_event(
                &cmd_tx,
                &IpcEvent::ClientControlResult { pid, ok, message },
            );

            // Refresh the list so the UI reflects the new state immediately. The
            // scan is a process walk plus a socket round-trip per client, so it
            // stays off the async runtime.
            if let Ok(clients) =
                tokio::task::spawn_blocking(crate::game::control::discover_clients).await
            {
                send_webview_event(&cmd_tx, &IpcEvent::ClientsList { clients });
            }
        });
    }

    fn handle_logout(&self, user_id: &str) {
        // Remove from credentials, grabbing the access token first so we can
        // revoke it server-side on a best-effort basis.
        let access_token = {
            let mut creds = self.credentials.lock().unwrap();
            let token = creds
                .sessions
                .iter()
                .find(|s| s.user_id == user_id)
                .map(|s| s.access_token.clone());
            creds.sessions.retain(|s| s.user_id != user_id);
            let _ = crate::config::save_credentials(&self.paths.creds_file, &creds);
            token
        };

        {
            let mut sessions = self.sessions.lock().unwrap();
            sessions.retain(|s| s.tokens.sub != user_id);
        }

        // Best-effort token revocation: fire-and-forget, never block logout on it.
        if let Some(token) = access_token {
            let client = crate::http_client();
            tokio::spawn(async move {
                if let Err(e) = crate::auth::oauth::revoke_token(&client, &token).await {
                    log::warn!("Token revocation failed during logout (ignored): {}", e);
                }
            });
        }

        let event = IpcEvent::LogoutComplete {
            user_id: user_id.to_string(),
        };
        self.send_event(&event);
    }

    /// Looks a character up and answers when it has something; cached on disk, so a second paint
    /// of the Accounts list costs nothing.
    fn handle_character_info(self: &Arc<Self>, account_id: String, display_name: String) {
        let state = self.clone();
        tokio::spawn(async move {
            let found = crate::characters::load(
                &crate::http_client(),
                &state.paths.data_dir,
                &display_name,
            )
            .await;
            state.send_event(&IpcEvent::CharacterInfo {
                account_id,
                avatar: found.avatar,
                total: found.total,
                rank: found.rank,
                xp: found.xp,
            });
        });
    }

    /// Launches each account in turn, waiting for one to finish before starting the next and
    /// pausing between them.
    ///
    /// It drives [`Self::handle_launch`] rather than repeating it: a launch downloads, patches and
    /// spawns, and only one may be in flight, which is exactly the serialisation a run needs. The
    /// launch slot is therefore the progress signal - taken while a client is coming up, free once
    /// it is.
    fn handle_scheduler_start(
        self: &Arc<Self>,
        accounts: Vec<ScheduledAccount>,
        delay_seconds: u64,
        repeat: bool,
    ) {
        if accounts.is_empty() {
            self.send_event(&IpcEvent::SchedulerStatus {
                running: false,
                message: "Add an account before starting.".to_string(),
                index: None,
                total: None,
            });
            return;
        }
        if self.scheduler_running.swap(true, Ordering::SeqCst) {
            self.send_event(&IpcEvent::SchedulerStatus {
                running: true,
                message: "A run is already going.".to_string(),
                index: None,
                total: None,
            });
            return;
        }

        let state = self.clone();
        tokio::spawn(async move {
            let total = accounts.len();
            'run: loop {
                for (index, account) in accounts.iter().enumerate() {
                    if !state.scheduler_running.load(Ordering::SeqCst) {
                        break 'run;
                    }
                    state.progress(
                        format!("Waiting for the previous launch to finish"),
                        index,
                        total,
                    );
                    state.await_launch_slot().await;
                    if !state.scheduler_running.load(Ordering::SeqCst) {
                        break 'run;
                    }

                    state.progress(format!("Launching {}", account.display_name), index, total);
                    state.handle_launch(&account.account_id, &account.display_name);

                    // The launch claims the slot from its own task, so let it take hold before
                    // treating a free slot as "finished".
                    sleep(Duration::from_secs(2)).await;
                    state.await_launch_slot().await;
                    state.progress(format!("{} is up", account.display_name), index, total);

                    let last = index + 1 == total;
                    if !last || repeat {
                        for remaining in (1..=delay_seconds).rev() {
                            if !state.scheduler_running.load(Ordering::SeqCst) {
                                break 'run;
                            }
                            state.progress(format!("Next in {}s", remaining), index, total);
                            sleep(Duration::from_secs(1)).await;
                        }
                    }
                }
                if !repeat {
                    break;
                }
            }

            let stopped = !state.scheduler_running.swap(false, Ordering::SeqCst);
            state.send_event(&IpcEvent::SchedulerStatus {
                running: false,
                message: if stopped { "Stopped.".to_string() } else { "Run finished.".to_string() },
                index: None,
                total: None,
            });
        });
    }

    fn progress(&self, message: String, index: usize, total: usize) {
        self.send_event(&IpcEvent::SchedulerStatus {
            running: true,
            message,
            index: Some(index),
            total: Some(total),
        });
    }

    /// Resolves once no launch is in flight.
    async fn await_launch_slot(&self) {
        while self.launching.load(Ordering::SeqCst) {
            sleep(Duration::from_millis(500)).await;
        }
    }

    fn handle_launch(&self, account_id: &str, display_name: &str) {
        let (stored_session_id, consent_id_token) = {
            let sessions = self.sessions.lock().unwrap();
            let session = sessions
                .iter()
                .find(|s| s.accounts.iter().any(|a| a.account_id == account_id));

            match session {
                Some(s) => (
                    s.session_id.clone(),
                    s.consent_id_token.clone(),
                ),
                None => {
                    self.send_event(&IpcEvent::LaunchError {
                        message: "No active session found for this account".to_string(),
                    });
                    return;
                }
            }
        };

        self.persist_last_account_for(account_id);

        // Reject re-entrant launches so two concurrent launch IPCs can't race on
        // downloading/extracting/writing the same binary.
        let guard = match self.try_begin_launch() {
            Some(g) => g,
            None => {
                self.send_event(&IpcEvent::LaunchError {
                    message: "A launch is already in progress.".to_string(),
                });
                return;
            }
        };

        let config = self.config.lock().unwrap().clone();
        let paths = self.paths.clone();
        let account_id = account_id.to_string();
        let display_name = display_name.to_string();
        let reporter = LaunchReporter {
            cmd_tx: self.cmd_tx.clone(),
        };
        // A scheduled run injects whatever the toggle says - that is the point of it - and must not
        // close the window it is being driven from.
        let scheduled = self.scheduler_running.load(Ordering::SeqCst);
        let close_after = config.close_after_launch && !scheduled;
        let custom_cmd = config.custom_launch_command.clone();
        let auto_inject = config.auto_inject_projectx || scheduled;
        let plugins_cfg = config.plugins.clone();
        let renderer_pref = config.renderer;
        let base_config_uri = config
            .custom_config_uri
            .clone()
            .unwrap_or_else(|| crate::game::rs3::DEFAULT_CONFIG_URI.to_string());

        tokio::spawn(async move {
            // Held for the whole task; clears the launch flag on drop (all paths).
            let _guard = guard;

            let client = crate::http_client();

            // Create a fresh game session using the consent id_token.
            // The consent id_token (from the consent flow) is different from the
            // launcher id_token (from OAuth refresh) — only the consent one works.
            reporter.status("Preparing game session...");
            let session_id = match &consent_id_token {
                Some(cit) => {
                    match crate::auth::session::create_session(&client, cit).await {
                        Ok(sid) => {
                            log::info!("Created fresh game session");
                            sid
                        }
                        Err(e) => {
                            log::warn!("Session creation failed: {}. Using stored session.", e);
                            stored_session_id.clone()
                        }
                    }
                }
                None => {
                    log::info!("No consent_id_token saved (pre-migration login). Using stored session.");
                    stored_session_id.clone()
                }
            };

            reporter.status("Checking for updates...");
            if let Err(e) = ensure_live_launcher(&reporter, &paths, &client).await {
                if paths.rs3_binary.exists() {
                    log::warn!("Client preparation failed: {}. Launching the installed client.", e);
                    reporter.status("Launching existing client...");
                } else {
                    reporter.error(format!("Failed to prepare the game client: {}", e));
                    return;
                }
            }

            if auto_inject {
                reporter.status("Checking the engine...");
                ensure_engine_installed(&reporter, &plugins_cfg).await;
            }

            // Chosen only now, against the engine that will be injected: the update
            // above can add a Vulkan offset table the engine did not have before.
            let renderer = crate::game::renderer::resolve_for(
                renderer_pref,
                crate::engine::resolved_home().as_deref(),
                auto_inject,
            );
            log::info!("Launching the {} client (binaryType selects the build).", renderer);
            let config_uri = with_host_binary_type(&base_config_uri, renderer);

            reporter.status("Launching game...");
            let params = LaunchParams {
                session_id: &session_id,
                account_id: &account_id,
                display_name: &display_name,
            };
            do_launch_live(
                &reporter,
                &paths.rs3_binary,
                &config_uri,
                &params,
                &paths.data_dir,
                &paths.data_dir,
                custom_cmd.as_deref(),
                PatchEnv::default(),
                auto_inject,
                close_after,
            );
        });
    }

    pub(crate) fn handle_launch_custom(&self) {
        // Reject re-entrant launches (shares the flag with live launches).
        let guard = match self.try_begin_launch() {
            Some(g) => g,
            None => {
                self.send_event(&IpcEvent::LaunchError {
                    message: "A launch is already in progress.".to_string(),
                });
                return;
            }
        };

        let config = self.config.lock().unwrap().clone();
        let reporter = LaunchReporter {
            cmd_tx: self.cmd_tx.clone(),
        };
        let close_after = config.close_after_launch;
        let custom_cmd = config.custom_launch_command.clone();
        let auto_inject = config.auto_inject_projectx;
        let plugins_cfg = config.plugins.clone();

        let host = config.custom_server_host.as_deref().unwrap_or("localhost");
        let renderer = crate::game::renderer::resolve_for(
            config.renderer,
            crate::engine::resolved_home().as_deref(),
            auto_inject,
        );
        log::info!("Launching the {} client (binaryType selects the build).", renderer);
        let config_uri = with_host_binary_type(
            &config
                .custom_config_uri
                .clone()
                .unwrap_or_else(|| format!("http://{}:8829/jav_config.ws", host)),
            renderer,
        );

        // Custom/private-server mode gets its OWN data dir (a `custom/` subdir of
        // the launcher data dir) so its cache, prefs, rs3linux/rs2client binaries
        // and creds never mix with the official install. This is both the
        // HOME-redirect target (set in process.rs) and the client's CWD.
        let projectx_dir = self.paths.data_dir_for_mode(&ServerMode::Custom);
        // The legacy-library prefix is shared by both modes and staged only in
        // the base data dir, so it is looked up and provisioned there.
        let base_data_dir = self.paths.data_dir.clone();

        tokio::spawn(async move {
            // Held for the whole task; clears the launch flag on drop (all paths).
            let _guard = guard;

            if let Err(e) = tokio::fs::create_dir_all(&projectx_dir).await {
                reporter.error(format!("Failed to create {}: {}", projectx_dir.display(), e));
                return;
            }

            #[cfg(target_os = "linux")]
            {
                reporter.status("Checking runtime libraries...");
                if let Err(e) =
                    crate::game::nativedeps::ensure(&crate::http_client(), &base_data_dir).await
                {
                    log::warn!("Runtime library provisioning failed: {}", e);
                }
            }

            let target_binary = match ensure_launcher_binary(&reporter, &projectx_dir).await {
                Ok(path) => path,
                Err(e) => {
                    reporter.error(format!("{}", e));
                    return;
                }
            };

            sync_patcher_library(&reporter, &projectx_dir, &config_uri).await;
            write_default_preferences(&projectx_dir).await;

            reporter.status("Fetching config from server...");
            let jav_params = match crate::game::rs3::fetch_jav_config_params(
                &crate::http_client(),
                &config_uri,
            )
            .await
            {
                Ok(params) => params,
                Err(e) => {
                    reporter.error(format!("Failed to fetch jav_config.ws: {}", e));
                    return;
                }
            };
            log::info!("Parsed {} jav_config params", jav_params.len());

            // Login RSA modulus: explicit config wins, else param=99. The JS5
            // modulus is param=100. The HTTP port override comes from the config
            // URL's explicit port, and stays None when it has none.
            let rsa_modulus = config
                .custom_rsa_modulus
                .clone()
                .or_else(|| crate::game::rs3::extract_modulus(&jav_params, LOGIN_RSA_PARAM));
            let js5_modulus = crate::game::rs3::extract_modulus(&jav_params, JS5_RSA_PARAM);
            let http_port = Url::parse(&config_uri).ok().and_then(|u| u.port());

            // Hard-fail on Linux if the patcher or RSA modulus is missing.
            // Without the patcher, rs3linux downloads a binary that fails Jagex
            // RSA verification and exits with "Error saving file (14)". On
            // Windows the injector handles its own DLL lookup, so we don't fail
            // there; on macOS the same logic applies but is not yet enforced.
            #[cfg(all(unix, not(target_os = "macos")))]
            {
                if crate::game::process::find_patcher_library(&target_binary).is_none() {
                    reporter.error(
                        "Custom server requires libprojectx_patcher.so but it was not found in \
                         any search location. Run the patcher build+deploy script, which \
                         compiles it and copies it to every location the launcher looks in:\n\
                         \n  client/launcher/patcher/build.sh\n\
                         \nThen launch again.",
                    );
                    return;
                }
                if rsa_modulus.is_none() {
                    reporter.error(
                        "Custom server requires an RSA modulus (for the patcher) but none was \
                         found. Either set custom_rsa_modulus in the launcher config, or ensure \
                         the jav_config.ws served by the custom server includes param=99 with \
                         the hex modulus.",
                    );
                    return;
                }
            }

            if auto_inject {
                reporter.status("Checking the engine...");
                ensure_engine_installed(&reporter, &plugins_cfg).await;
            }

            // Launch from the custom data dir — rs3linux auto-downloads rs2client
            // from the config server. HOME + RS_CACHE_DIR are redirected here by
            // launch_rs3, so cache/prefs/binaries all land under this dir.
            reporter.status(format!("Launching {} (Custom server)...", launcher_binary_name()));
            match crate::game::process::launch_rs3(
                &target_binary,
                &config_uri,
                None,
                &projectx_dir,
                &base_data_dir,
                custom_cmd.as_deref(),
                PatchEnv {
                    rsa_modulus: rsa_modulus.as_deref(),
                    js5_modulus: js5_modulus.as_deref(),
                    http_port,
                },
                Some(&projectx_dir), // CWD = custom data dir
                &ServerMode::Custom,
            ) {
                Ok(pid) => finish_launch(&reporter, pid, auto_inject, close_after),
                Err(e) => reporter.error(format!("Failed to launch: {}", e)),
            }
        });
    }

    // ---- Plugins tab (managed script jars) ----

    /// Emit what is known now, then refresh in the background when the cached
    /// catalog is missing, stale, or the user pressed refresh. The tab therefore
    /// paints instantly on open and fills in the release data when it lands.
    fn handle_plugins_status(self: &Arc<Self>, force: bool) {
        self.emit_plugins_status(None);

        let needs_fetch = {
            let cached = self.plugin_catalog.lock().unwrap();
            force || cached.as_ref().map(Catalog::is_stale).unwrap_or(true)
        };
        if !needs_fetch {
            return;
        }

        let state = Arc::clone(self);
        tokio::spawn(async move {
            let error = state.refresh_plugin_catalog().await.err().map(|e| format!("{}", e));
            state.emit_plugins_status(error);
        });
    }

    /// Fetch the newest release catalog and cache it.
    async fn refresh_plugin_catalog(&self) -> Result<Catalog> {
        let cfg = self.plugins_config();
        let catalog = plugins::fetch_catalog(&crate::http_client(), &cfg).await?;
        *self.plugin_catalog.lock().unwrap() = Some(catalog.clone());
        Ok(catalog)
    }

    /// The cached catalog if it is still fresh, otherwise a freshly fetched one.
    async fn plugin_catalog(&self) -> Result<Catalog> {
        let cached = {
            let guard = self.plugin_catalog.lock().unwrap();
            guard.clone()
        };
        match cached {
            Some(catalog) if !catalog.is_stale() => Ok(catalog),
            _ => self.refresh_plugin_catalog().await,
        }
    }

    fn plugins_config(&self) -> PluginsConfig {
        self.config.lock().unwrap().plugins.clone()
    }

    fn emit_plugins_status(&self, error: Option<String>) {
        let cfg = self.plugins_config();
        let catalog = {
            let guard = self.plugin_catalog.lock().unwrap();
            guard.clone()
        };
        let snapshot = plugins::snapshot(
            &self.paths.config_dir,
            &cfg,
            catalog.as_ref(),
            error,
        );
        self.send_event(&IpcEvent::PluginsStatus { snapshot });
    }

    /// Claim the per-channel action slot. `None` means an action is already in
    /// flight for that channel and this one must be dropped.
    fn try_claim_plugin(self: &Arc<Self>, id: &str) -> Option<PluginGuard> {
        let mut busy = self.plugin_busy.lock().unwrap();
        if busy.iter().any(|b| b == id) {
            return None;
        }
        busy.push(id.to_string());
        Some(PluginGuard {
            state: Arc::clone(self),
            id: id.to_string(),
        })
    }

    fn handle_plugin_install(self: &Arc<Self>, id: String) {
        let Some(guard) = self.try_claim_plugin(&id) else {
            self.send_event(&IpcEvent::PluginResult {
                id,
                ok: false,
                message: "Already working on this plugin".to_string(),
            });
            return;
        };

        let state = Arc::clone(self);
        tokio::spawn(async move {
            let _guard = guard;
            let (ok, message) = match state.install_plugin(&id).await {
                Ok(message) => (true, message),
                Err(e) => (false, format!("{}", e)),
            };
            state.send_event(&IpcEvent::PluginResult {
                id: id.clone(),
                ok,
                message,
            });
            state.emit_plugins_status(None);
        });
    }

    async fn install_plugin(&self, id: &str) -> Result<String> {
        let channel =
            PluginChannel::from_id(id).ok_or_else(|| anyhow!("Unknown plugin {}", id))?;
        let catalog = self.plugin_catalog().await?;
        let plugin = catalog
            .plugin(channel)
            .ok_or_else(|| {
                anyhow!(
                    "Release {} publishes no jar for this plugin",
                    catalog.release_tag
                )
            })?
            .clone();

        let entry = plugins::install(
            &crate::http_client(),
            &self.paths.config_dir,
            channel,
            &plugin,
        )
        .await?;
        Ok(format!("Installed {} {}", plugin.name, entry.version))
    }

    fn handle_plugin_remove(self: &Arc<Self>, id: String) {
        let Some(guard) = self.try_claim_plugin(&id) else {
            self.send_event(&IpcEvent::PluginResult {
                id,
                ok: false,
                message: "Already working on this plugin".to_string(),
            });
            return;
        };

        let state = Arc::clone(self);
        tokio::task::spawn_blocking(move || {
            let _guard = guard;
            let result = PluginChannel::from_id(&id)
                .ok_or_else(|| anyhow!("Unknown plugin {}", id))
                .and_then(|channel| plugins::remove(&state.paths.config_dir, channel));
            let (ok, message) = match result {
                Ok(message) => (true, message),
                Err(e) => (false, format!("{}", e)),
            };
            state.send_event(&IpcEvent::PluginResult {
                id: id.clone(),
                ok,
                message,
            });
            state.emit_plugins_status(None);
        });
    }

    /// Persist a channel's managed-update toggle. Enabling it also installs,
    /// because "keep this current" with nothing installed would otherwise do
    /// nothing until the next launcher start.
    fn handle_plugin_set_auto(self: &Arc<Self>, id: String, enabled: bool) {
        let Some(channel) = PluginChannel::from_id(&id) else {
            log::warn!("Ignoring auto-update toggle for unknown plugin {}", id);
            return;
        };

        {
            let mut config = self.config.lock().unwrap();
            plugins::set_auto_update(&mut config.plugins, channel, enabled);
            let _ = crate::config::save_config(&self.paths.config_file, &config);
        }
        self.emit_plugins_status(None);

        if enabled {
            self.handle_plugin_install(id);
        }
    }

    /// On startup, bring every channel the user opted into up to the newest
    /// release. Channels left at their default do nothing and cost no request.
    /// One startup catalog fetch, whether or not any plugin channel is managed.
    ///
    /// Every channel ships opted out, so gating this on auto-update meant the
    /// default install never looked at a release at all — and a launcher that
    /// never looks is a launcher that can never notice it has fallen behind the
    /// engine and plugins it installs.
    fn spawn_startup_update_check(self: &Arc<Self>) {
        let cfg = self.plugins_config();
        let channels: Vec<PluginChannel> = CHANNELS
            .into_iter()
            .filter(|c| plugins::auto_update_enabled(&cfg, *c))
            .collect();

        let state = Arc::clone(self);
        tokio::spawn(async move {
            state.run_startup_update_check(channels).await;
        });
    }

    async fn run_startup_update_check(&self, channels: Vec<PluginChannel>) {
        let catalog = match self.refresh_plugin_catalog().await {
            Ok(catalog) => catalog,
            Err(e) => {
                log::warn!("Update check failed: {}", e);
                self.emit_plugins_status(Some(format!("{}", e)));
                return;
            }
        };

        if let Some(update) = plugins::launcher_update(&catalog) {
            log::info!(
                "This launcher is {}; {} publishes {}",
                crate::VERSION,
                catalog.release_tag,
                update.file
            );
        }

        let client = crate::http_client();
        for channel in channels {
            let Some(plugin) = catalog.plugin(channel) else {
                continue;
            };
            if !plugins::needs_install(&self.paths.config_dir, channel, plugin) {
                continue;
            }
            let (ok, message) =
                match plugins::install(&client, &self.paths.config_dir, channel, plugin).await {
                    Ok(entry) => (true, format!("Updated {} to {}", plugin.name, entry.version)),
                    Err(e) => (false, format!("{}", e)),
                };
            log::info!("Plugin auto-update [{}]: {}", channel.id(), message);
            self.send_event(&IpcEvent::PluginResult {
                id: channel.id().to_string(),
                ok,
                message,
            });
        }

        self.emit_plugins_status(None);
    }

    fn handle_save_config(&self, new_config: &Config) {
        let mut config = self.config.lock().unwrap();
        *config = new_config.clone();
        crate::apply_log_level(config.debug_logging);
        let _ = crate::config::save_config(&self.paths.config_file, &config);
        self.send_event(&IpcEvent::ConfigSaved);
    }

    pub fn send_event(&self, event: &IpcEvent) {
        let js = format!(
            "window.__projectx_callback({})",
            serde_json::to_string(event).unwrap()
        );
        let _ = self.cmd_tx.send(AppCommand::SendToWebview(js));
    }

    pub fn build_session_infos(&self) -> Vec<SessionInfo> {
        let last_by_user: HashMap<String, Option<String>> = {
            let creds = self.credentials.lock().unwrap();
            creds
                .sessions
                .iter()
                .map(|s| (s.user_id.clone(), s.last_account_id.clone()))
                .collect()
        };
        let sessions = self.sessions.lock().unwrap();
        sessions
            .iter()
            .map(|s| SessionInfo {
                user_id: s.tokens.sub.clone(),
                display_name: s.user.display_name.clone(),
                accounts: s.accounts.clone(),
                session_id: s.session_id.clone(),
                last_account_id: last_by_user
                    .get(&s.tokens.sub)
                    .cloned()
                    .flatten(),
            })
            .collect()
    }

    pub fn add_session(&self, session: Session) {
        {
            let mut creds = self.credentials.lock().unwrap();
            creds
                .sessions
                .retain(|s| s.user_id != session.tokens.sub);
            creds.sessions.push(SavedSession {
                user_id: session.tokens.sub.clone(),
                display_name: session.user.display_name.clone(),
                access_token: session.tokens.access_token.clone(),
                id_token: session.tokens.id_token.clone(),
                refresh_token: session.tokens.refresh_token.clone(),
                expiry: session.tokens.expiry,
                accounts: session.accounts.clone(),
                session_id: Some(session.session_id.clone()),
                consent_id_token: session.consent_id_token.clone(),
                last_account_id: None,
            });
            let _ = crate::config::save_credentials(&self.paths.creds_file, &creds);
        }

        let info = SessionInfo {
            user_id: session.tokens.sub.clone(),
            display_name: session.user.display_name.clone(),
            accounts: session.accounts.clone(),
            session_id: session.session_id.clone(),
            last_account_id: None,
        };

        {
            let mut sessions = self.sessions.lock().unwrap();
            sessions.retain(|s| s.tokens.sub != session.tokens.sub);
            sessions.push(session);
        }

        self.send_event(&IpcEvent::LoginComplete { session: info });
    }
}

