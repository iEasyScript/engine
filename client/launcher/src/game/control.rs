//! Client control plane for the Project X engine's hot-reload feature.
//!
//! Once injected, the engine runs a permanent supervisor that listens on a
//! per-pid Unix-domain socket (see `client-plugin-engine-supervisor`'s `ControlSocket`). The
//! launcher's "Clients" panel uses this module to discover running `rs2client`
//! processes and drive uninject/reinject without restarting the client.
//!
//! Protocol (one request line, one response line):
//!   STATUS   -> `OK <state> pid=<p> version=<v> reloads=<n>`
//!               (state: active|unloaded|loading|error)
//!   PING     -> `OK pong`
//!   INJECT   -> starts a load if unloaded    -> `OK loading` / `OK active version=<v>`
//!   UNINJECT -> tears the engine down        -> `OK unloaded`
//!   REINJECT -> unload + reload the jar      -> `OK loading`
//!   (unknown -> `ERR <msg>`)
//!
//! INJECT/REINJECT acknowledge before the load finishes: the supervisor serves one
//! command at a time on one thread, so running a multi-second load inline would stall
//! the STATUS polls that report its progress. Completion is observed by polling STATUS.
//!
//! The FIRST injection of a process still goes through [`crate::game::inject`]
//! (GDB-`dlopen` on Linux, `LoadLibraryW` on Windows); once the bootstrap is
//! mapped, every subsequent control action is a plain socket command.

use serde::Serialize;
use std::path::PathBuf;

#[cfg(unix)]
use std::os::unix::net::UnixStream;
#[cfg(windows)]
use uds_windows::UnixStream;

/// One discovered `rs2client` process and its engine state.
///
/// `state` is one of:
///   - `"not-injected"` — a live `rs2client` with the engine `.so` not yet mapped
///   - `"starting"` — engine coming up, from either loading window: the `.so` is mapped
///     but the socket is not up yet (still inside the grace window), or the socket is up
///     and the supervisor reports a load in flight
///   - `"active"` — engine loaded (from the supervisor's STATUS reply)
///   - `"unloaded"` — engine injected but currently uninjected (vanilla client)
///   - `"error"` — `.so` is mapped and the socket stayed unreachable past the grace
///     window, or the supervisor reported an error state
#[derive(Debug, Clone, Serialize)]
pub struct ClientStatus {
    pub pid: u32,
    pub state: String,
    pub version: Option<String>,
    pub reloads: u32,
    /// Why a client is stuck, for the states where the supervisor never answered and so has
    /// nothing to say for itself. Read from the bootstrap's own log — see [`bootstrap_complaint`].
    pub detail: Option<String>,
}

/// Per-pid control-socket path, matching the engine supervisor's
/// `ControlSocket.resolveSocketPath` exactly:
///   - `$XDG_RUNTIME_DIR/projectx/<pid>.sock` when `XDG_RUNTIME_DIR` is set+non-empty
///   - else `<java.io.tmpdir>/projectx-<user.name>/<pid>.sock`
pub fn socket_path(pid: u32) -> PathBuf {
    let base = match std::env::var("XDG_RUNTIME_DIR") {
        Ok(runtime) if !runtime.is_empty() => PathBuf::from(runtime).join("projectx"),
        _ => jvm_temp_dir().join(format!("projectx-{}", jvm_user_name())),
    };
    base.join(format!("{}.sock", pid))
}

/// The JVM's `java.io.tmpdir` default. On Windows it comes from `GetTempPath`,
/// which is what `std::env::temp_dir` uses; on Linux the JVM hardcodes `/tmp`
/// rather than honouring `$TMPDIR`, so it cannot go through `temp_dir` there.
#[cfg(windows)]
fn jvm_temp_dir() -> PathBuf {
    std::env::temp_dir()
}

#[cfg(not(windows))]
fn jvm_temp_dir() -> PathBuf {
    PathBuf::from("/tmp")
}

/// The JVM's `user.name` system property, which the supervisor falls back to
/// `"user"` for.
fn jvm_user_name() -> String {
    #[cfg(windows)]
    let vars = ["USERNAME"];
    #[cfg(not(windows))]
    let vars = ["USER", "LOGNAME"];

    vars.iter()
        .filter_map(|v| std::env::var(v).ok())
        .find(|s| !s.is_empty())
        .unwrap_or_else(|| "user".to_string())
}

/// Connect the per-pid control socket, write `cmd\n`, and read exactly one
/// response line. Uses short connect/read/write timeouts so an unresponsive
/// supervisor can never hang the UI dispatch.
#[cfg(any(unix, windows))]
pub fn send_command(pid: u32, cmd: &str) -> anyhow::Result<String> {
    use anyhow::Context;
    use std::io::{BufRead, BufReader, Write};
    use std::time::Duration;

    const TIMEOUT: Duration = Duration::from_secs(5);

    let path = socket_path(pid);
    let stream = UnixStream::connect(&path)
        .with_context(|| format!("connecting to control socket {}", path.display()))?;
    stream.set_read_timeout(Some(TIMEOUT))?;
    stream.set_write_timeout(Some(TIMEOUT))?;

    let mut writer = stream.try_clone().context("cloning control socket")?;
    writer
        .write_all(cmd.as_bytes())
        .context("writing control command")?;
    writer.write_all(b"\n").context("writing command newline")?;
    writer.flush().context("flushing control command")?;

    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader
        .read_line(&mut line)
        .context("reading control response")?;
    let line = line.trim_end_matches(['\r', '\n']).to_string();
    if line.is_empty() {
        anyhow::bail!("empty response from control socket");
    }
    Ok(line)
}

#[cfg(not(any(unix, windows)))]
pub fn send_command(pid: u32, cmd: &str) -> anyhow::Result<String> {
    let _ = (pid, cmd);
    anyhow::bail!("control socket is not supported on this platform")
}

/// Report the engine state of every live `rs2client` process. Reuses the
/// process-scanning helpers in [`crate::game::inject`] so the rs2client /
/// injected detection stays in one place.
///
/// Per pid:
///   - bootstrap not mapped                -> `"not-injected"`
///   - bootstrap mapped + STATUS reachable  -> parse state/version/reloads from the reply
///   - bootstrap mapped + socket unreachable, within the grace window -> `"starting"`
///   - bootstrap mapped + socket unreachable, past it / malformed reply -> `"error"`
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn discover_clients() -> Vec<ClientStatus> {
    let pids = live_client_pids();
    mapping::forget_exited(&pids);
    let mut clients: Vec<ClientStatus> = pids.into_iter().map(classify).collect();
    clients.sort_by_key(|c| c.pid);
    clients
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn discover_clients() -> Vec<ClientStatus> {
    Vec::new()
}

/// Tracks when each pid was first *observed* carrying the bootstrap, so a client whose
/// engine is still coming up can be told apart from one whose supervisor never will.
///
/// Keyed on observation rather than on the user clicking Inject: auto-inject and a
/// launcher restart both have to land in the grace window with no click behind them.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
mod mapping {
    use std::collections::HashMap;
    use std::sync::{Mutex, OnceLock};
    use std::time::{Duration, Instant};

    /// Bounds the whole gap between the loader mapping the bootstrap and the supervisor
    /// binding its socket — the socket is the LAST thing `Supervisor.start` does, after
    /// the JVM boots, the shadow jar loads, and the engine finishes installing its hooks.
    /// Measured across the first injection of eleven real sessions at 5.4s–8.6s with a
    /// warm page cache; the bound is deliberately several times that because a cold cache
    /// or an on-access scan of the ~170 MB jar has no comparable ceiling. Overshooting
    /// only delays a real failure turning red — undershooting is the false "error".
    const DEFAULT_GRACE_SECS: u64 = 60;

    fn first_seen() -> &'static Mutex<HashMap<u32, Instant>> {
        static SEEN: OnceLock<Mutex<HashMap<u32, Instant>>> = OnceLock::new();
        SEEN.get_or_init(|| Mutex::new(HashMap::new()))
    }

    fn grace() -> Duration {
        let secs = std::env::var("INJECT_GRACE")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_GRACE_SECS);
        Duration::from_secs(secs)
    }

    pub fn observe(pid: u32) -> Instant {
        *first_seen()
            .lock()
            .unwrap()
            .entry(pid)
            .or_insert_with(Instant::now)
    }

    pub fn forget_exited(live: &[u32]) {
        first_seen()
            .lock()
            .unwrap()
            .retain(|pid, _| live.contains(pid));
    }

    pub fn still_starting(since: Instant) -> bool {
        since.elapsed() < grace()
    }
}

#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn classify(pid: u32) -> ClientStatus {
    if !is_injected(pid) {
        return ClientStatus {
            pid,
            state: "not-injected".to_string(),
            version: None,
            reloads: 0,
            detail: None,
        };
    }

    let mapped_since = mapping::observe(pid);

    match send_command(pid, "STATUS") {
        Ok(resp) => parse_status(pid, &resp),
        Err(e) => {
            let starting = mapping::still_starting(mapped_since);
            log::debug!(
                "discover_clients: pid {} has the engine mapped but STATUS failed after {:?}: {}",
                pid,
                mapped_since.elapsed(),
                e
            );
            // The supervisor is the only thing that answers the socket, so a client that never
            // binds one can only be explained by the bootstrap that would have started it.
            let detail = if starting { None } else { bootstrap_complaint(pid) };
            ClientStatus {
                pid,
                state: if starting { "starting" } else { "error" }.to_string(),
                version: None,
                reloads: 0,
                detail,
            }
        }
    }
}

/// Log the bootstrap left behind in the temp dir, written by the copy of itself loaded into `pid`
/// (`projectx_bootstrap.cpp`'s `projectx_bootstrap_start`).
///
/// Everything the bootstrap says about starting the JVM goes there and nowhere else, which is why a
/// client that maps the library and then fails to come up reaches the user as an unexplained
/// "Error" — the launcher log is silent because the launcher's part went fine.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn bootstrap_log_path(pid: u32) -> PathBuf {
    jvm_temp_dir().join(format!("projectx_log_{}.txt", pid))
}

/// How many trailing lines of the bootstrap log to put in the launcher log.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
const BOOTSTRAP_LOG_TAIL_LINES: usize = 20;

/// The bootstrap's account of why this client never came up: its last line for the UI, and the tail
/// around it into the launcher log for whoever has to read it afterwards.
///
/// Only called once a pid has already gone to "error", so re-reading the file per poll is bounded
/// by how long the user leaves a dead client on screen.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn bootstrap_complaint(pid: u32) -> Option<String> {
    let path = bootstrap_log_path(pid);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!(
                "pid {} has the engine mapped but never bound its control socket, and its \
                 bootstrap log {} could not be read: {}",
                pid,
                path.display(),
                e
            );
            return None;
        }
    };

    let lines: Vec<&str> = text.lines().map(str::trim_end).filter(|l| !l.is_empty()).collect();
    if lines.is_empty() {
        return None;
    }

    let from = lines.len().saturating_sub(BOOTSTRAP_LOG_TAIL_LINES);
    log::warn!(
        "pid {} has the engine mapped but never bound its control socket. Last {} lines of {}:\n{}",
        pid,
        lines.len() - from,
        path.display(),
        lines[from..].join("\n")
    );
    lines.last().map(|line| (*line).to_string())
}

#[cfg(all(unix, not(target_os = "macos")))]
fn live_client_pids() -> Vec<u32> {
    use crate::game::inject::linux::proc_is_rs2client;

    let entries = match std::fs::read_dir("/proc") {
        Ok(e) => e,
        Err(e) => {
            log::warn!("discover_clients: cannot read /proc: {}", e);
            return Vec::new();
        }
    };

    entries
        .flatten()
        .filter_map(|entry| entry.file_name().to_str().and_then(|s| s.parse().ok()))
        .filter(|pid| proc_is_rs2client(*pid))
        .collect()
}

#[cfg(windows)]
fn live_client_pids() -> Vec<u32> {
    crate::game::inject::windows::rs2client_pids()
}

/// First-inject the engine into a specific pid (delegates to
/// [`crate::game::inject::inject_into_pid`]). May block on a pkexec/sudo prompt
/// on Linux, so callers must run this off the UI thread.
pub fn inject_pid(pid: u32) -> anyhow::Result<()> {
    crate::game::inject::inject_into_pid(pid)
}

/// True when the engine bootstrap is currently mapped into `pid` — i.e. the
/// engine was injected at least once, so control actions go through the socket
/// rather than the first-injection path.
#[cfg(all(unix, not(target_os = "macos")))]
pub fn is_injected(pid: u32) -> bool {
    crate::game::inject::linux::is_already_injected(pid)
}

#[cfg(windows)]
pub fn is_injected(pid: u32) -> bool {
    crate::game::inject::windows::is_already_injected(pid)
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn is_injected(_pid: u32) -> bool {
    false
}

/// Parse a supervisor `STATUS` reply into a [`ClientStatus`].
///
/// Active/unloaded form: `OK <state> pid=<p> version=<v> reloads=<n>` with an
/// optional trailing ` error=<...>`. Anything that isn't a recognizable `OK`
/// reply (including `ERR ...`) maps to `"error"`.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn parse_status(pid: u32, resp: &str) -> ClientStatus {
    let mut tokens = resp.split_whitespace();

    if tokens.next() != Some("OK") {
        return ClientStatus {
            pid,
            state: "error".to_string(),
            version: None,
            reloads: 0,
            // The supervisor answered, so it is the authority on its own failure — not a log
            // written before it existed.
            detail: Some(resp.trim().to_string()),
        };
    }

    // The supervisor calls its own in-flight load "loading"; the UI already has one
    // in-progress state for the not-yet-reachable case and it is called "starting".
    let state = match tokens.next().unwrap_or("error") {
        "loading" => "starting",
        reported => reported,
    }
    .to_string();

    let mut version: Option<String> = None;
    let mut reloads: u32 = 0;
    for tok in tokens {
        if let Some(v) = tok.strip_prefix("version=") {
            // The supervisor sends `-` for an unloaded engine; treat as None.
            version = if v == "-" || v.is_empty() {
                None
            } else {
                Some(v.to_string())
            };
        } else if let Some(n) = tok.strip_prefix("reloads=") {
            reloads = n.parse().unwrap_or(0);
        }
    }

    ClientStatus {
        pid,
        state,
        version,
        reloads,
        detail: None,
    }
}
