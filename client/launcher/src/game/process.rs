use crate::config::ServerMode;
use crate::game::renderer::Renderer;
use anyhow::{Context, Result};
use std::path::Path;
use std::process::{Command, Stdio};

pub struct LaunchParams<'a> {
    pub session_id: &'a str,
    pub account_id: &'a str,
    pub display_name: &'a str,
}

/// Environment variables controlling the runtime patcher. Only applied when the
/// patcher shared library / injector is actually located (see `build_*_command`).
#[derive(Default, Clone, Copy)]
pub struct PatchEnv<'a> {
    /// Hex-encoded login RSA modulus (param=99 from jav_config.ws). When
    /// `Some`, the patcher replaces the client's embedded login public key.
    /// Also gates whether the patcher library is loaded at all.
    pub rsa_modulus: Option<&'a str>,
    /// Hex-encoded JS5 RSA modulus (param=100 from jav_config.ws). When
    /// `Some`, the patcher replaces the client's embedded JS5 master-index key.
    pub js5_modulus: Option<&'a str>,
    /// Override port for the client's hardcoded HTTP content port 80. Derived
    /// from the config-server URL's explicit port (e.g. 8829 for
    /// `http://localhost:8829/jav_config.ws`).
    pub http_port: Option<u16>,
}

/// Launch the RS3 client binary
///
/// If `rsa_modulus` is provided (hex-encoded 1024-bit modulus), the patcher
/// shared library will be loaded into the client to replace its embedded RSA
/// public key with the provided one. The mechanism is platform-specific:
///   * Linux  — LD_PRELOAD on the `rs3linux` launcher process; the env var
///     propagates to the child `rs2client` process and the dynamic linker
///     loads `libprojectx_patcher.so` before any client code runs.
///   * Windows — the launcher invokes `projectx_injector.exe <binary> [args...]`,
///     which spawns the target suspended and remote-LoadLibraryW's
///     `projectx_patcher.dll` into it before resuming. The injector inherits the
///     env block we set here, and the DLL is env-gated on `PROJECTX_RSA_MODULUS`.
///
/// `data_dir` is the mode-selected dir (the `HOME` redirect target, so live and
/// custom keep separate caches/prefs/binaries). `deps_dir` is the launcher's
/// single base data dir: the provisioned legacy libraries are shared by every
/// mode and are only ever staged there, so looking them up under `data_dir`
/// would leave a custom-mode launch without `libgdk-x11-2.0.so.0` and the client
/// would die at dynamic-link time.
///
/// Returns the spawned process id on success so callers can drive follow-up work
/// against the exact child (e.g. Project X engine injection) rather than scanning
/// `/proc`.
pub fn launch_rs3(
    binary: &Path,
    config_uri: &str,
    params: Option<&LaunchParams>,
    data_dir: &Path,
    deps_dir: &Path,
    custom_command: Option<&str>,
    patch: PatchEnv<'_>,
    working_dir: Option<&Path>,
    server_mode: &ServerMode,
) -> Result<u32> {
    let binary_str = binary.to_string_lossy().to_string();
    let data_dir_str = data_dir.to_string_lossy().to_string();
    let needs_patcher = patch.rsa_modulus.is_some();

    // Decide on the program + args to run. On a normal launch we run the
    // target binary directly with `--configURI <uri>`. A custom_command
    // overrides this with the user's launch wrapper (e.g. `gamemoderun
    // %command%`), expanding %command% to the default argv.
    let target_argv: Vec<String> = if let Some(launch_cmd) = custom_command {
        resolve_launch_command(launch_cmd, &binary_str, config_uri)
    } else {
        vec![
            binary_str.clone(),
            "--configURI".to_string(),
            config_uri.to_string(),
        ]
    };

    // Build the actual Command. On Windows in patch mode we substitute the
    // injector as argv[0] and shift the real argv into the injector's args;
    // the injector spawns the target itself. Otherwise we spawn target_argv
    // directly. Each platform builder also wires up its own patcher env
    // (LD_PRELOAD + PROJECTX_* on Linux when the .so is found, PROJECTX_* always
    // on Windows when the injector is found — preserves historical gating).
    #[cfg(windows)]
    let mut cmd = build_windows_command(&target_argv, needs_patcher, &patch);
    #[cfg(unix)]
    let mut cmd = build_unix_command(&target_argv, binary, needs_patcher, &patch);
    #[cfg(not(any(windows, unix)))]
    let mut cmd = {
        let _ = (needs_patcher, patch);
        command_from_argv(&target_argv)
    };

    if let Some(dir) = working_dir {
        cmd.current_dir(dir);
    }

    // Set environment variables consumed by the client itself (Jagex's auth
    // hand-off) — unchanged across platforms.
    if let Some(p) = params {
        cmd.env("JX_SESSION_ID", p.session_id);
        cmd.env("JX_CHARACTER_ID", p.account_id);
        cmd.env("JX_DISPLAY_NAME", p.display_name);
    }

    apply_engine_env(&mut cmd, server_mode);

    // Linux-only client-runtime env (X11/PulseAudio hints + HOME redirect so
    // the NXT client lands in our data dir, not the user's real ~).
    #[cfg(unix)]
    {
        cmd.env("HOME", &data_dir_str);
        // Export the resolved NXT cache dir from the SAME mode-selected data_dir
        // as HOME, so it propagates rs3linux -> rs2client -> the injected
        // Project X engine, which treats RS_CACHE_DIR as the authoritative cache
        // location. This guarantees the engine reads the exact cache the client
        // just wrote, in both live and custom modes, with no path guessing.
        let cache_dir = crate::config::cache_dir(data_dir);
        cmd.env("RS_CACHE_DIR", &cache_dir);
        cmd.env("SDL_VIDEODRIVER", "x11");
        cmd.env("SDL_VIDEO_X11_WMCLASS", "RuneScape");
        cmd.env(
            "PULSE_PROP_OVERRIDE",
            "application.name='RuneScape' application.icon_name='runescape' media.role='game'",
        );

        // Reach the provisioned legacy libraries (openssl 1.1, gtk2) without
        // installing them where anything else on the host could pick them up.
        #[cfg(target_os = "linux")]
        if let Some(lib_dir) = crate::game::nativedeps::provisioned_prefix(deps_dir) {
            let value = match std::env::var_os("LD_LIBRARY_PATH") {
                Some(existing) if !existing.is_empty() => {
                    std::env::join_paths([lib_dir.into_os_string(), existing]).ok()
                }
                _ => Some(lib_dir.into_os_string()),
            };
            if let Some(value) = value {
                cmd.env("LD_LIBRARY_PATH", value);
            }
        }
    }
    #[cfg(not(unix))]
    let _ = data_dir_str;
    #[cfg(not(target_os = "linux"))]
    let _ = deps_dir;

    cmd.stdin(Stdio::null());

    // Redirect the client's stdout + stderr to a persistent append-mode log so
    // that LD_PRELOAD/patcher diagnostics survive a GUI launch. Best-effort:
    // fall back to inheriting the launcher's streams if the file can't be opened
    // (read-only data dir, permission error, etc.).
    let log_path = data_dir.join("launcher-client.log");
    rotate_client_log(&log_path);
    match std::fs::OpenOptions::new().create(true).append(true).open(&log_path) {
        Ok(out_file) => match out_file.try_clone() {
            Ok(err_file) => {
                cmd.stdout(Stdio::from(out_file));
                cmd.stderr(Stdio::from(err_file));
            }
            Err(e) => {
                log::warn!(
                    "Could not dup client log handle for {}: {}. Inheriting launcher streams.",
                    log_path.display(),
                    e
                );
            }
        },
        Err(e) => {
            log::warn!(
                "Could not open client log {}: {}. Inheriting launcher streams.",
                log_path.display(),
                e
            );
        }
    }

    let child = cmd.spawn().context("Failed to spawn RS3 process")?;
    let pid = child.id();
    log::info!("Spawned RS3 process with pid {}", pid);

    // On Linux the patcher rides LD_PRELOAD into every descendant, so the game inherits it
    // from the launcher. Windows injection stops at the process we spawned, so the game --
    // a child of rs3windows.exe -- has to be patched separately or it keeps Jagex's RSA keys.
    #[cfg(windows)]
    if needs_patcher {
        crate::game::inject::windows::patch_client(pid);
    }

    Ok(pid)
}

const CLIENT_LOG_ROTATE_BYTES: u64 = 64 * 1024 * 1024;

/// Roll the client log over before a launch so an append-mode handle can't grow
/// without bound across sessions. The previous session is kept as `.1` and any
/// older generation is discarded, capping the pair at ~2x the threshold.
fn rotate_client_log(log_path: &Path) {
    let too_big = std::fs::metadata(log_path)
        .map(|m| m.len() >= CLIENT_LOG_ROTATE_BYTES)
        .unwrap_or(false);
    if !too_big {
        return;
    }
    let previous = log_path.with_extension("log.1");
    if let Err(e) = std::fs::rename(log_path, &previous) {
        log::warn!(
            "Could not rotate client log {}: {}. Truncating instead.",
            log_path.display(),
            e
        );
        if let Err(e) = std::fs::File::create(log_path) {
            log::warn!(
                "Could not truncate client log {}: {}",
                log_path.display(),
                e
            );
        }
    }
}

/// The dynamic-linker preload variable for this unix host.
#[cfg(all(unix, not(target_os = "macos")))]
const PRELOAD_VAR: &str = "LD_PRELOAD";
#[cfg(target_os = "macos")]
const PRELOAD_VAR: &str = "DYLD_INSERT_LIBRARIES";

fn command_from_argv(argv: &[String]) -> Command {
    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..]);
    cmd
}

/// Apply the PROJECTX_* env the runtime patcher reads. Only the modulus is
/// mandatory; the JS5 modulus and HTTP-port override are optional.
fn apply_patch_env(cmd: &mut Command, patch: &PatchEnv<'_>) {
    if let Some(modulus) = patch.rsa_modulus {
        cmd.env("PROJECTX_RSA_MODULUS", modulus);
    }
    if let Some(js5) = patch.js5_modulus {
        cmd.env("PROJECTX_JS5_RSA_MODULUS", js5);
    }
    if let Some(port) = patch.http_port {
        cmd.env("PROJECTX_HTTP_PORT", port.to_string());
    }
}

/// Unix command construction (Linux + macOS). Spawns the target argv directly
/// and, when the patcher is needed AND its library is locatable, sets the host's
/// dynamic-linker preload variable plus the PROJECTX_* env the library reads. The
/// preload propagates to the rs3linux→rs2client child chain.
///
/// The PROJECTX_* vars are only set when the library is found, so a missing
/// patcher degrades to a normal unpatched launch rather than a broken one.
///
/// macOS additionally needs `DYLD_FORCE_FLAT_NAMESPACE=1`: rs2client is a
/// two-level-namespace binary, and forcing the flat namespace is the documented
/// way to guarantee an inserted library's constructor runs before the host's
/// `main()`.
#[cfg(unix)]
fn build_unix_command(
    target_argv: &[String],
    binary: &Path,
    needs_patcher: bool,
    patch: &PatchEnv<'_>,
) -> Command {
    let mut cmd = command_from_argv(target_argv);
    if !needs_patcher {
        return cmd;
    }

    match find_patcher_library(binary) {
        Some(patcher_path) => {
            log::info!("Setting {} to {}", PRELOAD_VAR, patcher_path.display());
            cmd.env(PRELOAD_VAR, &patcher_path);
            #[cfg(target_os = "macos")]
            cmd.env("DYLD_FORCE_FLAT_NAMESPACE", "1");
            apply_patch_env(&mut cmd, patch);
        }
        None => log::warn!(
            "Patcher needed but {} not found. Build it with: \
             cd client/launcher/{} && cargo build --release",
            patcher_lib_name(),
            patcher_crate()
        ),
    }
    cmd
}

/// Windows command construction. In live/unpatched mode we spawn the target
/// argv directly. In patched mode we look up `projectx_injector.exe` and rewrite
/// the command into `injector.exe <target.exe> [target args...]`. The injector
/// inherits our env block, including PROJECTX_* vars set by the caller above.
///
/// If the injector cannot be found we fall back to spawning the target
/// directly and emit a warning — same shape as the Linux missing-patcher path
/// (unpatched launch beats no launch at all).
/// Which server this client is about to talk to. The injected engine keeps live and custom-server
/// packet captures in separate databases and cannot tell them apart on its own, so the launcher —
/// which chose the mode — states it outright.
///
/// Deliberately not inside a per-OS block. It lived in the unix-only one once, and on Windows the
/// engine saw no declaration, filed every session to quarantine and uploaded none of them; the loss
/// only shows up later, in captures that were never going to arrive.
fn apply_engine_env(cmd: &mut Command, server_mode: &ServerMode) {
    cmd.env(
        "PROJECTX_SERVER_PROFILE",
        match server_mode {
            ServerMode::Live => "live",
            ServerMode::Custom => "local",
        },
    );
}

#[cfg(windows)]
fn build_windows_command(
    target_argv: &[String],
    needs_patcher: bool,
    patch: &PatchEnv<'_>,
) -> Command {
    if needs_patcher {
        if let Some(injector) = find_injector_exe() {
            log::info!("Using DLL injector at {}", injector.display());
            // Diagnostic: surface whether the DLL is locatable from the
            // launcher's perspective. The injector does its own lookup at
            // runtime (and may find it in a slot we don't check), so a None
            // here is only a hint, not an error.
            if let Some(dll) = find_patcher_dll() {
                log::info!("Patcher DLL located at {}", dll.display());
            } else {
                log::warn!(
                    "projectx_patcher.dll not visible to the launcher's search; \
                     the injector will perform its own search at spawn time."
                );
            }
            let mut cmd = Command::new(&injector);
            // injector usage: projectx_injector.exe <path-to-rs2client.exe> [args...]
            // target_argv[0] is the client exe path; target_argv[1..] are its args.
            cmd.arg(&target_argv[0]);
            for arg in &target_argv[1..] {
                cmd.arg(arg);
            }
            // PROJECTX_* are inherited by the injector and forwarded into the
            // suspended target by CreateProcessW's default env block. The DLL
            // is no-op without PROJECTX_RSA_MODULUS, so omitting it = unpatched
            // run from the DLL's perspective even if injection succeeded.
            apply_patch_env(&mut cmd, patch);
            return cmd;
        } else {
            log::warn!(
                "Patcher needed but projectx_injector.exe not found in any known \
                 location. Launching un-patched. Build it with: \
                 cd client/launcher/patcher-win && cargo build --release"
            );
        }
    }

    // Unpatched / live mode (or injector missing): spawn target directly.
    command_from_argv(target_argv)
}

/// The host OS folder name under `data/client/` for the current platform.
///
/// The per-OS data layout (introduced alongside the Kotlin client-manager) is:
///   data/client/linux/    rs2client     rs3linux       libprojectx_patcher.so
///   data/client/windows/  rs2client.exe rs3windows.exe projectx_patcher.dll + projectx_injector.exe
///   data/client/macos/    rs2client     rs3mac         libprojectx_patcher.dylib
///
/// The launcher always operates on the host's folder — there is no manual
/// override; we cross-compile per target and auto-detect at runtime.
pub fn host_os_dir() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

/// The NXT `binaryType` identifying this host's client build. It is the query
/// parameter a config server keys its per-OS client descriptor on, and the
/// counterpart of [`host_os_dir`] on the wire.
///
/// Windows has one value per renderer — 2 is the OpenGL client, 10 the Vulkan
/// one — so the caller resolves a [`Renderer`] first (see
/// [`crate::game::renderer::resolve`]). Linux and macOS ship one build each and
/// ignore the renderer.
pub fn host_binary_type(renderer: Renderer) -> u8 {
    if cfg!(target_os = "windows") {
        match renderer {
            Renderer::Vulkan => 10,
            Renderer::OpenGl => 2,
        }
    } else if cfg!(target_os = "macos") {
        3
    } else {
        4
    }
}

/// The workspace crate that builds this host's patcher library — `patcher-mac`
/// on macOS, `patcher` elsewhere. Used for the dev-build lookup slot and for the
/// "build it with" hint in the missing-patcher warning.
pub fn patcher_crate() -> &'static str {
    if cfg!(target_os = "macos") {
        "patcher-mac"
    } else {
        "patcher"
    }
}

/// The host's patcher shared-library file name for the current platform.
///   linux   → libprojectx_patcher.so
///   windows → projectx_patcher.dll
///   macos   → libprojectx_patcher.dylib
pub fn patcher_lib_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "projectx_patcher.dll"
    } else if cfg!(target_os = "macos") {
        "libprojectx_patcher.dylib"
    } else {
        "libprojectx_patcher.so"
    }
}

/// Determine the Jagex launcher (`rs3*`) binary name for the current platform.
///   linux   → rs3linux
///   windows → rs3windows.exe
///   macos   → rs3mac
pub fn launcher_binary_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "rs3windows.exe"
    } else if cfg!(target_os = "macos") {
        "rs3mac"
    } else {
        "rs3linux"
    }
}

/// Locate the host's patcher shared library on a unix host.
///   Linux → `libprojectx_patcher.so`   macOS → `libprojectx_patcher.dylib`
///
/// Search order:
/// 1. `data/client/<host-os>/` relative to the launcher's working dir — the new
///    canonical per-OS layout, checked first so a fresh per-OS build always wins.
/// 2. Next to the launcher executable itself
/// 3. In the same directory as the client binary
/// 4. ~/projectx-3/ (project runtime directory used by custom mode)
/// 5. In ../patcher{,-mac}/target/release/ relative to the launcher exe (dev builds)
///
/// On Windows this returns None — the equivalent DLL lookup is exposed via
/// `find_patcher_dll`, which is a diagnostic affordance only. The Windows
/// runtime patcher is loaded by `projectx_injector.exe`, which performs its own
/// independent DLL search at injection time.
/// Canonicalize a located patcher path to an absolute path.
///
/// The returned path is set as `LD_PRELOAD` (Linux) / `DYLD_INSERT_LIBRARIES`
/// (macOS) on the spawned client, whose working directory is the mode-selected
/// data dir — NOT the launcher's CWD. A relative hit (notably the CWD-relative
/// `data/client/<os>/` slot) would be resolved by `ld.so` against the child's
/// CWD, fail to open, and be silently ignored — leaving the client unpatched and
/// causing rs3linux to reject our binary with "Error saving file". Falls back to
/// the original path if canonicalization fails (the caller already verified it
/// exists).
#[cfg(unix)]
fn absolutize_patcher_path(p: std::path::PathBuf) -> std::path::PathBuf {
    std::fs::canonicalize(&p).unwrap_or(p)
}

#[cfg(unix)]
pub fn find_patcher_library(client_binary: &Path) -> Option<std::path::PathBuf> {
    let lib_name = patcher_lib_name();

    // Canonical per-OS layout: data/client/<host-os>/<lib>. This is the slot the
    // user requires the launcher to load from at launch time.
    let os_slot = std::path::PathBuf::from("data")
        .join("client")
        .join(host_os_dir())
        .join(lib_name);
    if os_slot.exists() {
        return Some(absolutize_patcher_path(os_slot));
    }

    // Next to the launcher executable
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let candidate = parent.join(lib_name);
            if candidate.exists() {
                return Some(absolutize_patcher_path(candidate));
            }
        }
    }

    // Next to the client binary
    if let Some(parent) = client_binary.parent() {
        let candidate = parent.join(lib_name);
        if candidate.exists() {
            return Some(absolutize_patcher_path(candidate));
        }
    }

    // ~/projectx-3/ — the project runtime directory used by custom mode
    if let Ok(home) = std::env::var("HOME") {
        let candidate = std::path::PathBuf::from(home).join("projectx-3").join(lib_name);
        if candidate.exists() {
            return Some(absolutize_patcher_path(candidate));
        }
    }

    // Dev build location: relative to launcher exe at ../patcher{,-mac}/target/release/.
    let dev_crate = patcher_crate();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let candidate = parent
                .join("..")
                .join("..")
                .join("..")
                .join(dev_crate)
                .join("target")
                .join("release")
                .join(lib_name);
            if candidate.exists() {
                return Some(absolutize_patcher_path(candidate));
            }
        }
    }

    None
}

#[cfg(windows)]
pub fn find_patcher_library(_client_binary: &Path) -> Option<std::path::PathBuf> {
    // Windows uses CreateRemoteThread+LoadLibraryW via projectx_injector.exe.
    // Direct preloading is not how it gets into the process. Surface the
    // DLL via find_patcher_dll() for diagnostics, but don't pretend it's
    // a preload candidate.
    None
}

/// Locate `projectx_injector.exe` for runtime DLL injection into `rs2client.exe`.
///
/// Returns the first hit from the canonical search slots, in order. On miss,
/// logs every slot we tried so the user can see where to drop the binary.
///
/// Slot order:
/// 1. Next to the running project-x-launcher executable (release deploy layout)
/// 2. The launcher's data dir (ProjectDirs root for `project-x-launcher`)
/// 3. `<data_dir>\Jagex\launcher\` (matches Jagex's installed-launcher layout)
/// 4. `%APPDATA%\ProjectX\`
/// 5. `%LOCALAPPDATA%\ProjectX\`
/// 6. Dev convenience: `<project tree>\client\launcher\patcher-win\target\release\`
///    — only searched in debug builds or when `PROJECTX_DEV` is set, so release
///    artifacts never leak a dependency on the source tree.
#[cfg(windows)]
pub fn find_injector_exe() -> Option<std::path::PathBuf> {
    let exe_name = "projectx_injector.exe";
    let candidates = injector_search_slots(exe_name);
    for c in &candidates {
        if c.is_file() {
            log::debug!("found {}: {}", exe_name, c.display());
            return Some(c.clone());
        }
    }
    log::warn!(
        "{} not found in any of:\n  {}",
        exe_name,
        candidates
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join("\n  ")
    );
    None
}

/// Diagnostic helper: locate `projectx_patcher.dll` from the launcher's
/// perspective so we can log it for the user. The injector does its own
/// independent search at spawn time — this is not authoritative.
#[cfg(windows)]
pub fn find_patcher_dll() -> Option<std::path::PathBuf> {
    let dll_name = "projectx_patcher.dll";
    let candidates = injector_search_slots(dll_name);
    for c in &candidates {
        if c.is_file() {
            return Some(c.clone());
        }
    }
    None
}

/// First existing hit for `name` in the canonical Windows deploy slots, without
/// the missing-artifact logging of [`find_injector_exe`].
#[cfg(windows)]
pub(crate) fn find_deploy_artifact(name: &str) -> Option<std::path::PathBuf> {
    injector_search_slots(name).into_iter().find(|c| c.is_file())
}

/// Build the canonical Windows search list for an artifact name. Used for both
/// the injector exe and the patcher DLL — they ship side by side in every
/// deploy slot, so they share a lookup.
#[cfg(windows)]
fn injector_search_slots(name: &str) -> Vec<std::path::PathBuf> {
    let mut slots: Vec<std::path::PathBuf> = Vec::new();

    // 0. Canonical per-OS layout: data/client/<host-os>/<name>. Same slot the unix
    // patcher lookup checks first; without it a launch silently falls back to
    // un-patched and the Jagex launcher rejects the client with "Error saving file (14)".
    slots.push(
        std::path::PathBuf::from("data")
            .join("client")
            .join(host_os_dir())
            .join(name),
    );

    // 1. Same dir as the running launcher exe
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            slots.push(dir.join(name));
        }
    }

    // 2 & 3. ProjectDirs-based slots (matches what config.rs uses for the
    // launcher's own data dir).
    if let Some(dirs) = directories::ProjectDirs::from("", "", "project-x-launcher") {
        slots.push(dirs.data_dir().join(name));
        slots.push(dirs.data_dir().join("Jagex").join("launcher").join(name));
    }

    // 3b. Per-mode data dir, where the launcher installs a patcher fetched from the
    // custom server.
    if let Some(dirs) = directories::ProjectDirs::from("", "", "project-x-launcher") {
        slots.push(dirs.data_dir().join("custom").join(name));
    }

    // 4. %APPDATA%\ProjectX\
    if let Ok(appdata) = std::env::var("APPDATA") {
        slots.push(std::path::PathBuf::from(appdata).join("ProjectX").join(name));
    }
    // 5. %LOCALAPPDATA%\ProjectX\
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        slots.push(std::path::PathBuf::from(local).join("ProjectX").join(name));
    }

    // 6. Dev convenience: walk up from the launcher exe into the source tree
    // and check the patcher-win release artifacts directory. Gated so release
    // deployments don't even try (avoids spurious paths in the failure log).
    let dev_enabled = cfg!(debug_assertions) || std::env::var_os("PROJECTX_DEV").is_some();
    if dev_enabled {
        if let Ok(exe) = std::env::current_exe() {
            if let Some(parent) = exe.parent() {
                // Typical dev layout: <repo>\client\launcher\target\<profile>\project-x-launcher.exe
                // so go up to <repo>\client\launcher\ then into patcher-win\target\release.
                slots.push(
                    parent
                        .join("..")
                        .join("..")
                        .join("patcher-win")
                        .join("target")
                        .join("release")
                        .join(name),
                );
            }
        }
    }

    slots
}

fn resolve_launch_command(launch_cmd: &str, binary: &str, config_uri: &str) -> Vec<String> {
    let mut result = Vec::new();
    let default_args = vec![binary.to_string(), "--configURI".to_string(), config_uri.to_string()];

    for part in launch_cmd.split_whitespace() {
        if part == "%command%" {
            result.extend(default_args.clone());
        } else {
            result.push(part.to_string());
        }
    }

    if result.is_empty() {
        result = default_args;
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsStr;

    #[test]
    fn every_platform_declares_the_server_profile() {
        for (mode, expected) in [(ServerMode::Live, "live"), (ServerMode::Custom, "local")] {
            let mut cmd = Command::new("client");
            apply_engine_env(&mut cmd, &mode);
            let declared = cmd
                .get_envs()
                .find(|(key, _)| *key == OsStr::new("PROJECTX_SERVER_PROFILE"))
                .and_then(|(_, value)| value);
            assert_eq!(declared, Some(OsStr::new(expected)), "{mode:?} declared nothing");
        }
    }
}
