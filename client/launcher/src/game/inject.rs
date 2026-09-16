//! Optional Project X engine injection after the client is spawned.
//!
//! Mirrors `launch/run-projectx.sh`'s `inject_engine()`: once `rs2client` is up
//! and initialized, load the engine bootstrap into it so the engine (funchook
//! hooks, ImGui overlay, in-process MCP, TcpIn sniffer) runs in-process.
//!
//! The mechanism is platform-specific: GDB-`dlopen` of `libprojectxbootstrap.so`
//! on Linux, `CreateRemoteThread(LoadLibraryW)` of `projectxbootstrap.dll` via
//! `projectx_engine_injector.exe` on Windows. macOS has no implementation.
//!
//! Everything here is best-effort: a failure to locate the library, attach, or
//! obtain privileges logs a warning and returns — it never blocks or kills the
//! client launch.

#[cfg(all(unix, not(target_os = "macos")))]
use linux as platform;
#[cfg(windows)]
use windows as platform;

/// Refuses injection when the engine's offset tables do not cover the client build.
///
/// The tables are raw addresses, so an engine built for a different revision does not
/// degrade gracefully — hooks land mid-instruction and struct reads return garbage,
/// which surfaces as a client crash with no obvious cause. Checking first turns that
/// into a message the user can act on.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub(crate) mod revision {
    use std::path::Path;

    fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
        hay.windows(needle.len()).position(|w| w == needle)
    }

    fn digits_at(data: &[u8], from: usize) -> (String, usize) {
        let mut i = from;
        while i < data.len() && data[i].is_ascii_digit() {
            i += 1;
        }
        (String::from_utf8_lossy(&data[from..i]).into_owned(), i)
    }

    /// The Vulkan Windows client is tagged with this platform name; the OpenGL build of
    /// the same revision only carries `NXT-Windows-64`.
    const VULKAN_TAG: &[u8] = b"NXT-Windows-64-Vulkan";
    const VULKAN_TABLE_SUFFIX: &str = "-vulkan";

    /// Jagex ships each build once per renderer, and the two binaries share a build id
    /// while their offsets differ, so a revision is the pair.
    #[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
    pub struct Revision {
        pub build: String,
        pub renderer: &'static str,
    }

    impl std::fmt::Display for Revision {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{} ({})", self.build, self.renderer)
        }
    }

    /// Build id the client binary reports (e.g. `949-4`), read from the same
    /// `RS2Engine-<major>-NXT-<minor>` marker the offset updater keys off, together with
    /// the renderer its platform tag names.
    pub fn detect_client_revision(exe: &Path) -> Option<Revision> {
        revision_of_binary(&std::fs::read(exe).ok()?)
    }

    fn revision_of_binary(data: &[u8]) -> Option<Revision> {
        let at = find(data, b"RS2Engine-")?;
        let (major, next) = digits_at(data, at + b"RS2Engine-".len());
        if major.is_empty() || !data[next..].starts_with(b"-NXT-") {
            return None;
        }
        let (minor, _) = digits_at(data, next + b"-NXT-".len());
        if minor.is_empty() {
            return None;
        }
        let renderer = if find(data, VULKAN_TAG).is_some() { "vulkan" } else { "opengl" };
        Some(Revision { build: format!("{}-{}", major, minor), renderer })
    }

    /// `linux-x86_64-949-4` -> `949-4` (opengl), `windows-x86_64-950-1-vulkan` -> `950-1`
    /// (vulkan): the build is the trailing two dash groups once the renderer suffix is off.
    fn revision_from_table_name(stem: &str) -> Option<Revision> {
        let (stem, renderer) = match stem.strip_suffix(VULKAN_TABLE_SUFFIX) {
            Some(rest) => (rest, "vulkan"),
            None => (stem, "opengl"),
        };
        let mut parts = stem.rsplitn(3, '-');
        let minor = parts.next()?;
        let major = parts.next()?;
        if major.is_empty() || minor.is_empty()
            || !major.bytes().all(|b| b.is_ascii_digit()) || !minor.bytes().all(|b| b.is_ascii_digit())
        {
            return None;
        }
        Some(Revision { build: format!("{}-{}", major, minor), renderer })
    }

    /// Revisions the engine jar carries offset tables for. Entry names sit in the zip's
    /// local headers uncompressed, so they can be read without a zip dependency.
    pub fn engine_supported_revisions(jar: &Path) -> Vec<Revision> {
        let Ok(data) = std::fs::read(jar) else {
            return Vec::new();
        };
        let mut out: Vec<Revision> = Vec::new();
        let mut i = 0usize;
        while let Some(rel) = find(&data[i..], b"offsets/") {
            let start = i + rel + b"offsets/".len();
            let window = &data[start..data.len().min(start + 128)];
            if let Some(end) = find(window, b".json") {
                if let Ok(stem) = std::str::from_utf8(&window[..end]) {
                    if let Some(revision) = revision_from_table_name(stem) {
                        out.push(revision);
                    }
                }
            }
            i = start;
        }
        out.sort();
        out.dedup();
        out
    }

    /// Whether the installed engine carries any offset table for `renderer`.
    ///
    /// Checked before a launch, where the build is not known yet — the client has not
    /// been downloaded — so this asks only whether the renderer is covered at all.
    /// A table for the wrong build still gets caught by [`guard`] at injection time.
    ///
    /// An engine that cannot be read is reported as covering the renderer: the same
    /// reasoning as [`guard`]'s, in that missing evidence is not evidence of a
    /// mismatch, and letting it through leaves the clear message from `guard` to be
    /// the one the user sees.
    pub fn engine_has_renderer(engine_home: &Path, renderer: &str) -> bool {
        let Some(jar) = crate::engine::installed_engine_jar(engine_home) else {
            return true;
        };
        let supported = engine_supported_revisions(&jar);
        supported.is_empty() || supported.iter().any(|r| r.renderer == renderer)
    }

    /// `Ok(())` when the engine covers this client, or when either side cannot be
    /// determined — an unreadable binary or a jar without tables is not evidence of a
    /// mismatch, and blocking on it would make injection fail for the wrong reason.
    pub fn guard(client_exe: &Path, engine_home: &Path) -> anyhow::Result<()> {
        let Some(client_revision) = detect_client_revision(client_exe) else {
            log::warn!(
                "Could not read a build marker from {}; skipping the engine revision check.",
                client_exe.display()
            );
            return Ok(());
        };
        let Some(jar) = crate::engine::installed_engine_jar(engine_home) else {
            log::warn!(
                "No engine jar found in {}; skipping the engine revision check.",
                engine_home.display()
            );
            return Ok(());
        };
        let supported = engine_supported_revisions(&jar);
        if supported.is_empty() {
            log::warn!(
                "{} carries no offset tables; skipping the engine revision check.",
                jar.display()
            );
            return Ok(());
        }
        if supported.contains(&client_revision) {
            log::info!(
                "Engine revision check passed: client build {} is covered.",
                client_revision
            );
            return Ok(());
        }
        anyhow::bail!(
            "The Project X plugin engine is out of date and injection was refused.\n\n\
             This client is build {}, but the engine only has offset tables for {}.\n\n\
             Injecting it would hook wrong addresses and crash the client. Re-run the offset \
             updater for build {}, then rebuild the engine with\n  \
             ./gradlew :client-plugin-engine:shadowJar",
            client_revision,
            supported.iter().map(|r| r.to_string()).collect::<Vec<_>>().join(", "),
            client_revision
        )
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn a_vulkan_table_name_is_the_same_build_with_the_vulkan_renderer() {
            assert_eq!(
                revision_from_table_name("windows-x86_64-950-1-vulkan"),
                Some(Revision { build: "950-1".into(), renderer: "vulkan" })
            );
            assert_eq!(
                revision_from_table_name("windows-x86_64-950-1"),
                Some(Revision { build: "950-1".into(), renderer: "opengl" })
            );
            assert_eq!(revision_from_table_name("index"), None);
        }

        #[test]
        fn the_platform_tag_tells_the_two_builds_of_a_revision_apart() {
            let opengl = b"...RS2Engine-950-NXT-1\0...NXT-Windows-64\0...".to_vec();
            let vulkan = b"...RS2Engine-950-NXT-1\0...NXT-Windows-64-Vulkan\0...".to_vec();
            assert_eq!(revision_of_binary(&opengl).unwrap().renderer, "opengl");
            assert_eq!(revision_of_binary(&vulkan).unwrap().renderer, "vulkan");
            assert_eq!(revision_of_binary(&vulkan).unwrap().build, "950-1");
        }
    }
}

/// Kick off Project X engine injection for a freshly-spawned client.
///
/// `launcher_pid` is the pid the launcher spawned (`rs3linux` / `rs3windows.exe`).
/// The real game process is its `rs2client` descendant, so the injector resolves
/// the actual target by enumerating processes for the newest non-injected
/// `rs2client`, preferring one descended from `launcher_pid`.
///
/// The wait + inject runs on a detached background thread so the launcher UI is
/// never blocked for the init delay.
pub fn maybe_inject_projectx(launcher_pid: u32) {
    #[cfg(any(all(unix, not(target_os = "macos")), windows))]
    {
        std::thread::Builder::new()
            .name("projectx-inject".to_string())
            .spawn(move || platform::inject(launcher_pid))
            .map(|_| ())
            .unwrap_or_else(|e| {
                log::warn!("Failed to spawn Project X injection thread: {}", e);
            });
    }

    #[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
    {
        let _ = launcher_pid;
        log::warn!(
            "Auto-inject Project X is enabled but not supported on this platform; skipping injection."
        );
    }
}

/// Inject the Project X engine into a SPECIFIC, already-running `rs2client` pid.
/// Unlike [`maybe_inject_projectx`], this does not scan for the newest descendant
/// — the caller picks the exact target (e.g. the Clients panel injecting one
/// row). Runs synchronously and may block on a pkexec/sudo prompt on Linux, so
/// callers must invoke it off the UI thread.
#[cfg(any(all(unix, not(target_os = "macos")), windows))]
pub fn inject_into_pid(pid: u32) -> anyhow::Result<()> {
    platform::inject_pid(pid)
}

#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
pub fn inject_into_pid(pid: u32) -> anyhow::Result<()> {
    let _ = pid;
    anyhow::bail!("Project X engine injection is not supported on this platform")
}

#[cfg(all(unix, not(target_os = "macos")))]
pub(crate) mod linux {
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};
    use std::time::Duration;

    pub(crate) const ENGINE_SO_NAME: &str = crate::engine::BOOTSTRAP_NAME;
    /// `dlopen` flags used by `client-plugin-engine/inject` / run-projectx.sh:
    /// `RTLD_NOW | RTLD_GLOBAL | RTLD_NODELETE` == 0x2 | 0x100 | 0x1000 == 4362.
    const DLOPEN_FLAGS: i32 = 4362;
    const DEFAULT_INJECT_DELAY_SECS: u64 = 8;
    const DISCOVERY_POLL_INTERVAL: Duration = Duration::from_millis(250);
    const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(300);

    /// Resolved injection inputs: the absolute `.so` path, the JDK home, and the
    /// `PROJECTX_HOME_DIR` (the directory containing the `.so`). Shared by both the
    /// auto-inject (newest-descendant) path and the per-pid path so the env
    /// resolution lives in one place.
    struct InjectEnv {
        engine_so: PathBuf,
        java_home: String,
        home_dir: PathBuf,
    }

    /// Locate the `.so`, canonicalize it (pkexec/sudo reset cwd, so the path handed
    /// to gdb must be absolute), and read JAVA_HOME. Returns an error string the
    /// caller can log or surface.
    fn resolve_inject_env() -> anyhow::Result<InjectEnv> {
        use anyhow::anyhow;

        let engine_so = locate_engine_so().ok_or_else(|| {
            anyhow!(
                "{} not found. Build it with `./gradlew :client-plugin-engine:buildNativeBootstrap`.",
                ENGINE_SO_NAME
            )
        })?;
        let engine_so = std::fs::canonicalize(&engine_so).unwrap_or(engine_so);

        let java_home = crate::java::resolve_home()
            .ok_or_else(|| {
                anyhow!(
                    "no JDK 25+ install could be found. Searched {}. Set JAVA_HOME to a JDK 25 home.",
                    crate::java::searched_locations()
                )
            })?
            .to_string_lossy()
            .into_owned();

        // PROJECTX_HOME_DIR = the directory containing the .so (matches run-projectx.sh).
        let home_dir = engine_so
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| PathBuf::from("."));

        Ok(InjectEnv {
            engine_so,
            java_home,
            home_dir,
        })
    }

    pub fn inject(launcher_pid: u32) {
        let env = match resolve_inject_env() {
            Ok(e) => e,
            Err(e) => {
                log::warn!("Auto-inject enabled but {}. Skipping injection.", e);
                return;
            }
        };

        // `rs3linux` downloads/verifies before it execs the game, so how long
        // rs2client takes to appear varies by minutes on a cold install. Poll for
        // it rather than assuming it exists after a fixed wait, then give the
        // process the settle time the engine needs to find initialized state.
        let target_pid = match wait_for_target_pid(launcher_pid, true) {
            Some(p) => p,
            None => {
                log::warn!(
                    "Auto-inject: no live, non-injected rs2client process appeared within {}s; skipping.",
                    DISCOVERY_TIMEOUT.as_secs()
                );
                return;
            }
        };

        let settle = inject_delay();
        log::info!(
            "Auto-inject: rs2client pid {} found; letting it initialize for {}s",
            target_pid,
            settle.as_secs()
        );
        std::thread::sleep(settle);

        if is_already_injected(target_pid) {
            log::info!(
                "Auto-inject: {} already mapped in pid {}; skipping.",
                ENGINE_SO_NAME, target_pid
            );
            return;
        }

        if let Ok(exe) = std::fs::read_link(format!("/proc/{}/exe", target_pid)) {
            if let Err(e) = super::revision::guard(&exe, &env.home_dir) {
                log::error!("Auto-inject: {}", e);
                return;
            }
        }

        log::info!(
            "Auto-inject: injecting {} into rs2client pid {} (PROJECTX_HOME_DIR={})",
            env.engine_so.display(),
            target_pid,
            env.home_dir.display()
        );

        match run_gdb_inject(target_pid, &env.engine_so, &env.java_home, &env.home_dir) {
            Ok(()) => log::info!("Auto-inject: Project X engine injected into pid {}", target_pid),
            Err(e) => log::warn!("Auto-inject: injection failed (continuing): {}", e),
        }
    }

    /// First-inject into a SPECIFIC pid. Unlike [`inject`], this does not wait or
    /// scan for a descendant — the caller already chose the exact target. Returns
    /// an error so the UI can report failure (the auto path only logs).
    pub fn inject_pid(pid: u32) -> anyhow::Result<()> {
        use anyhow::{anyhow, bail};

        if !proc_is_rs2client(pid) {
            bail!("pid {} is not a live rs2client process", pid);
        }
        if is_already_injected(pid) {
            // Already mapped — the engine is in-process; the caller should drive
            // reinject/uninject via the control socket, not GDB.
            return Ok(());
        }

        let env = resolve_inject_env().map_err(|e| anyhow!("{}", e))?;

        if let Ok(exe) = std::fs::read_link(format!("/proc/{}/exe", pid)) {
            super::revision::guard(&exe, &env.home_dir)?;
        }

        log::info!(
            "Inject: injecting {} into rs2client pid {} (PROJECTX_HOME_DIR={})",
            env.engine_so.display(),
            pid,
            env.home_dir.display()
        );

        run_gdb_inject(pid, &env.engine_so, &env.java_home, &env.home_dir)
    }

    /// How long to let a freshly-appeared rs2client initialize before injecting.
    fn inject_delay() -> Duration {
        let secs = std::env::var("INJECT_DELAY")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_INJECT_DELAY_SECS);
        Duration::from_secs(secs)
    }

    /// Poll for an injectable rs2client until [`DISCOVERY_TIMEOUT`] elapses.
    fn wait_for_target_pid(launcher_pid: u32, require_uninjected: bool) -> Option<u32> {
        let deadline = std::time::Instant::now() + DISCOVERY_TIMEOUT;
        loop {
            if let Some(pid) = resolve_target_pid(launcher_pid, require_uninjected) {
                return Some(pid);
            }
            if std::time::Instant::now() >= deadline {
                return None;
            }
            std::thread::sleep(DISCOVERY_POLL_INTERVAL);
        }
    }

    /// Locate `libprojectxbootstrap.so`. The launcher may run from anywhere, so
    /// check several sensible slots in order:
    /// 1. `$PROJECTX_HOME_DIR/<so>` if the env var is set
    /// 2. relative to the current working dir: `client-plugin-engine/build/libs/<so>`
    /// 3. walk up from the launcher exe looking for `client-plugin-engine/build/libs/<so>`
    ///    (handles `client/launcher/target/<profile>/bolt-rs3` dev layouts and
    ///    deploys nested under the repo)
    fn locate_engine_so() -> Option<PathBuf> {
        crate::engine::find_bootstrap()
    }

    /// Find the rs2client pid to inject into. Scans `/proc` for the newest
    /// `rs2client` process that is not already injected, preferring one that is a
    /// descendant of `launcher_pid` (the rs3linux we spawned).
    /// `require_uninjected` excludes clients that already carry the engine bootstrap.
    /// Only the engine path wants that; it is a parameter so the flag is stated at the
    /// call site rather than baked in.
    fn resolve_target_pid(launcher_pid: u32, require_uninjected: bool) -> Option<u32> {
        let mut best: Option<(u32, u64)> = None; // (pid, start_time)
        let mut best_descendant: Option<(u32, u64)> = None;

        let entries = std::fs::read_dir("/proc").ok()?;
        for entry in entries.flatten() {
            let name = entry.file_name();
            let pid: u32 = match name.to_str().and_then(|s| s.parse().ok()) {
                Some(p) => p,
                None => continue,
            };

            if !proc_is_rs2client(pid) {
                continue;
            }
            if require_uninjected && is_already_injected(pid) {
                continue;
            }

            let start = proc_start_time(pid).unwrap_or(0);

            if is_descendant_of(pid, launcher_pid) {
                if best_descendant.map(|(_, s)| start >= s).unwrap_or(true) {
                    best_descendant = Some((pid, start));
                }
            }
            if best.map(|(_, s)| start >= s).unwrap_or(true) {
                best = Some((pid, start));
            }
        }

        best_descendant.or(best).map(|(pid, _)| pid)
    }

    pub(crate) fn proc_is_rs2client(pid: u32) -> bool {
        let cmdline = match std::fs::read(format!("/proc/{}/cmdline", pid)) {
            Ok(c) => c,
            Err(_) => return false,
        };
        // cmdline is NUL-separated; argv[0] is the executable path.
        cmdline
            .split(|&b| b == 0)
            .next()
            .map(|arg0| String::from_utf8_lossy(arg0).contains("rs2client"))
            .unwrap_or(false)
    }

    pub(crate) fn is_already_injected(pid: u32) -> bool {
        match std::fs::read_to_string(format!("/proc/{}/maps", pid)) {
            Ok(maps) => maps.contains(ENGINE_SO_NAME),
            Err(_) => false,
        }
    }

    /// Process start time in clock ticks since boot — field 22 of
    /// `/proc/<pid>/stat`. Parsed after the last `)` because the `comm` field is
    /// parenthesized and may itself contain spaces or parens.
    fn proc_start_time(pid: u32) -> Option<u64> {
        let stat = std::fs::read_to_string(format!("/proc/{}/stat", pid)).ok()?;
        let after_comm = &stat[stat.rfind(')')? + 1..];
        // Fields resume at `state` (field 3), so starttime (field 22) is the
        // 20th token of the remainder.
        after_comm.split_whitespace().nth(19)?.parse().ok()
    }

    /// Walk the `PPid` chain of `pid` up to a small depth, returning true if
    /// `ancestor` is reached. Cheap guard against picking an unrelated rs2client.
    fn is_descendant_of(pid: u32, ancestor: u32) -> bool {
        let mut current = pid;
        for _ in 0..8 {
            if current == ancestor {
                return true;
            }
            match proc_ppid(current) {
                Some(0) | None => return false,
                Some(ppid) => current = ppid,
            }
        }
        false
    }

    fn proc_ppid(pid: u32) -> Option<u32> {
        let status = std::fs::read_to_string(format!("/proc/{}/status", pid)).ok()?;
        for line in status.lines() {
            if let Some(rest) = line.strip_prefix("PPid:") {
                return rest.trim().parse().ok();
            }
        }
        None
    }

    /// Invoke GDB to `dlopen` the engine `.so` into the target.
    ///
    /// Attaching to an already-running, non-child process needs privileges
    /// (`ptrace_scope` is typically `1`). Escalation order, preferring the most
    /// user-friendly automatic prompt:
    ///   1. plain `gdb` when `ptrace_scope == 0` (no elevation needed);
    ///   2. `pkexec gdb` — PolicyKit pops a GRAPHICAL password dialog that works
    ///      in a GUI app with no TTY (the desktop polkit agent renders it);
    ///   3. `sudo -n gdb` — non-interactive fallback for NOPASSWD/headless setups.
    ///
    /// On total failure we report cleanly (never hang on a prompt, never kill the
    /// launch). pkexec/sudo reset cwd+env, but the `.so` is passed as an absolute
    /// path and the target's JAVA_HOME/PROJECTX_HOME_DIR are set via gdb `setenv`
    /// calls inside the target, so the reset is harmless.
    fn run_gdb_inject(
        pid: u32,
        engine_so: &Path,
        java_home: &str,
        home_dir: &Path,
    ) -> anyhow::Result<()> {
        use anyhow::{anyhow, Context};

        let so = gdb_quote(&engine_so.to_string_lossy());
        let home = gdb_quote(&home_dir.to_string_lossy());
        let java_home = gdb_quote(java_home);

        let gdb_args = |program: &str, extra: &[&str]| -> Vec<String> {
            let mut v: Vec<String> = extra.iter().map(|s| s.to_string()).collect();
            v.push(program.to_string());
            v.extend([
                "-p".to_string(),
                pid.to_string(),
                "-batch".to_string(),
                "-ex".to_string(),
                format!("call (int) setenv(\"JAVA_HOME\", \"{}\", 1)", java_home),
                "-ex".to_string(),
                format!("call (int) setenv(\"PROJECTX_HOME_DIR\", \"{}\", 1)", home),
                "-ex".to_string(),
                format!("call (void*) dlopen(\"{}\", {})", so, DLOPEN_FLAGS),
                "-ex".to_string(),
                "call (char*) dlerror()".to_string(),
                "-ex".to_string(),
                "detach".to_string(),
                "-ex".to_string(),
                "quit".to_string(),
            ]);
            v
        };

        // Attempt 1: plain gdb, only worthwhile when ptrace is unrestricted.
        if ptrace_scope_allows_plain() {
            log::info!("Auto-inject: ptrace_scope permits unprivileged attach; trying plain gdb");
            let args = gdb_args("gdb", &[]);
            match run_command(&args[0], &args[1..]) {
                Ok(true) => return Ok(()),
                Ok(false) => log::warn!("Auto-inject: plain gdb attach failed; escalating via pkexec"),
                Err(e) => log::warn!("Auto-inject: plain gdb not runnable ({}); escalating via pkexec", e),
            }
        }

        // Attempt 2: pkexec gdb — PolicyKit shows a GRAPHICAL auth dialog via the
        // desktop's polkit agent, which works in a GUI app with no controlling
        // terminal. This is the user-friendly elevation path.
        let args = gdb_args("gdb", &["pkexec"]);
        match run_command(&args[0], &args[1..]) {
            Ok(true) => return Ok(()),
            Ok(false) => log::warn!(
                "Auto-inject: pkexec gdb did not succeed (auth dismissed, or attach rejected); \
                 trying sudo -n gdb"
            ),
            Err(e) => log::warn!("Auto-inject: pkexec unavailable ({}); trying sudo -n gdb", e),
        }

        // Attempt 3: sudo -n gdb (non-interactive — for NOPASSWD/headless setups;
        // -n makes sudo fail fast instead of hanging on a TTY-less password prompt).
        let args = gdb_args("gdb", &["sudo", "-n"]);
        match run_command(&args[0], &args[1..]) {
            Ok(true) => Ok(()),
            Ok(false) => Err(anyhow!(
                "Engine injection could not get privileges: the pkexec prompt was \
                 dismissed/unavailable and `sudo -n gdb` failed. Install a PolicyKit \
                 agent for a graphical prompt, add a NOPASSWD sudoers rule for gdb, or \
                 relax /proc/sys/kernel/yama/ptrace_scope."
            )),
            Err(e) => Err(e).context("Failed to run sudo -n gdb for injection"),
        }
    }

    /// Escape a value for embedding in a gdb string literal, so a path holding a
    /// quote or backslash can't corrupt the command.
    fn gdb_quote(value: &str) -> String {
        value.replace('\\', "\\\\").replace('"', "\\\"")
    }

    fn ptrace_scope_allows_plain() -> bool {
        std::fs::read_to_string("/proc/sys/kernel/yama/ptrace_scope")
            .map(|s| s.trim() == "0")
            .unwrap_or(false)
    }

    /// Run a command to completion, returning Ok(true) on a zero exit status.
    /// stdin is `/dev/null` so `sudo -n` can never stall waiting for input.
    fn run_command(program: &str, args: &[String]) -> anyhow::Result<bool> {
        use anyhow::Context;
        let status = Command::new(program)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .with_context(|| format!("Failed to spawn {}", program))?;
        Ok(status.success())
    }
}

#[cfg(windows)]
pub(crate) mod windows {
    use std::ffi::OsString;
    use std::mem::size_of;
    use std::os::windows::ffi::OsStringExt;
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};
    use std::ptr;
    use std::time::Duration;

    use windows_sys::Win32::Foundation::{
        CloseHandle, FALSE, FILETIME, HANDLE, HMODULE, INVALID_HANDLE_VALUE, MAX_PATH,
    };
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    use windows_sys::Win32::System::ProcessStatus::{EnumProcessModules, GetModuleFileNameExW};
    use windows_sys::Win32::System::Threading::{
        GetProcessTimes, OpenProcess, QueryFullProcessImageNameW, PROCESS_ACCESS_RIGHTS,
        PROCESS_NAME_WIN32, PROCESS_QUERY_INFORMATION, PROCESS_QUERY_LIMITED_INFORMATION,
        PROCESS_VM_READ,
    };

    use crate::game::process::find_deploy_artifact;

    pub(crate) const ENGINE_DLL_NAME: &str = crate::engine::BOOTSTRAP_NAME;
    const INJECTOR_EXE_NAME: &str = "projectx_engine_injector.exe";
    const TARGET_PROCESS_NAME: &str = "rs2client.exe";
    const DEFAULT_INJECT_DELAY_SECS: u64 = 8;
    const DISCOVERY_POLL_INTERVAL: Duration = Duration::from_millis(250);
    const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(300);
    const MODULE_SCAN_CAPACITY: usize = 1024;
    const MAX_ANCESTOR_DEPTH: usize = 8;
    /// Keeps the console-subsystem injector from flashing a window over the game.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    /// Resolved injection inputs: the absolute bootstrap DLL and the injector that
    /// loads it. Unlike Linux there is no JDK home or `PROJECTX_HOME_DIR` to plant
    /// — the bootstrap derives its home from its own loaded module path.
    struct InjectEnv {
        engine_dll: PathBuf,
        injector: PathBuf,
    }

    fn resolve_inject_env() -> anyhow::Result<InjectEnv> {
        use anyhow::anyhow;

        let engine_dll = locate_engine_dll().ok_or_else(|| {
            anyhow!(
                "{} not found. Build it with `./gradlew :client-plugin-engine:buildNativeBootstrap`.",
                ENGINE_DLL_NAME
            )
        })?;
        let engine_dll = std::fs::canonicalize(&engine_dll).unwrap_or(engine_dll);

        let injector = find_deploy_artifact(INJECTOR_EXE_NAME)
            .ok_or_else(|| {
                anyhow!(
                    "{} not found. Build it with `cd client/launcher/patcher-win && cargo build --release`.",
                    INJECTOR_EXE_NAME
                )
            })?;

        Ok(InjectEnv {
            engine_dll,
            injector,
        })
    }

    pub fn inject(launcher_pid: u32) {
        let env = match resolve_inject_env() {
            Ok(e) => e,
            Err(e) => {
                log::warn!("Auto-inject enabled but {}. Skipping injection.", e);
                return;
            }
        };

        // `rs3windows.exe` downloads/verifies before it starts the game, so how
        // long rs2client takes to appear varies by minutes on a cold install. Poll
        // for it rather than assuming it exists after a fixed wait, then give the
        // process the settle time the engine needs to find initialized state.
        let target_pid = match wait_for_target_pid(launcher_pid, true) {
            Some(p) => p,
            None => {
                log::warn!(
                    "Auto-inject: no live, non-injected rs2client process appeared within {}s; skipping.",
                    DISCOVERY_TIMEOUT.as_secs()
                );
                return;
            }
        };

        let settle = inject_delay();
        log::info!(
            "Auto-inject: rs2client pid {} found; letting it initialize for {}s",
            target_pid,
            settle.as_secs()
        );
        std::thread::sleep(settle);

        if is_already_injected(target_pid) {
            log::info!(
                "Auto-inject: {} already mapped in pid {}; skipping.",
                ENGINE_DLL_NAME, target_pid
            );
            return;
        }

        if let (Some(exe), Some(home)) = (image_path(target_pid), env.engine_dll.parent()) {
            if let Err(e) = super::revision::guard(Path::new(&exe), home) {
                log::error!("Auto-inject: {}", e);
                return;
            }
        }

        log::info!(
            "Auto-inject: injecting {} into rs2client pid {}",
            env.engine_dll.display(),
            target_pid
        );

        match run_injector(&env, target_pid) {
            Ok(()) => log::info!("Auto-inject: Project X engine injected into pid {}", target_pid),
            Err(e) => log::warn!("Auto-inject: injection failed (continuing): {}", e),
        }
    }

    /// First-inject into a SPECIFIC pid. Unlike [`inject`], this does not wait or
    /// scan for a descendant — the caller already chose the exact target. Returns
    /// an error so the UI can report failure (the auto path only logs).
    pub fn inject_pid(pid: u32) -> anyhow::Result<()> {
        use anyhow::bail;

        if !proc_is_rs2client(pid) {
            bail!("pid {} is not a live rs2client process", pid);
        }
        if is_already_injected(pid) {
            // Already mapped — the engine is in-process; the caller should drive
            // reinject/uninject via the control socket, not a second LoadLibrary.
            return Ok(());
        }

        let env = resolve_inject_env()?;

        if let Some(exe) = image_path(pid) {
            // PROJECTX_HOME_DIR is the directory holding the DLL, same as on Linux.
            if let Some(home) = env.engine_dll.parent() {
                super::revision::guard(Path::new(&exe), home)?;
            }
        }

        log::info!(
            "Inject: injecting {} into rs2client pid {}",
            env.engine_dll.display(),
            pid
        );

        run_injector(&env, pid)
    }

    pub(crate) const PATCHER_DLL_NAME: &str = "projectx_patcher.dll";

    /// Load `projectx_patcher.dll` into the `rs2client.exe` that `rs3windows.exe` spawned.
    ///
    /// The launcher injects the patcher into the *launcher* process, and Windows DLL
    /// injection does not follow child processes the way `LD_PRELOAD` does — so without
    /// this the game's own login/JS5 RSA moduli are never replaced and it keeps talking
    /// to Jagex's keys. The PROJECTX_* values the DLL reads are inherited down the spawn
    /// chain, so nothing has to be planted here.
    ///
    /// Runs on its own thread: discovery polls for as long as a cold client download
    /// takes. Unlike the engine there is no settle delay — the moduli are read at login,
    /// and patching a process that has only just started is both safe and necessary.
    pub fn patch_client(launcher_pid: u32) {
        std::thread::Builder::new()
            .name("projectx-patch".to_string())
            .spawn(move || {
                let Some(dll) = find_deploy_artifact(PATCHER_DLL_NAME) else {
                    log::warn!(
                        "{} not found; the client will run with Jagex's RSA keys and cannot reach \
                         a custom server. Build it with `cd client/launcher/patcher-win && cargo \
                         build --release`.",
                        PATCHER_DLL_NAME
                    );
                    return;
                };
                let Some(injector) = find_deploy_artifact(INJECTOR_EXE_NAME) else {
                    log::warn!("{} not found; cannot patch the client.", INJECTOR_EXE_NAME);
                    return;
                };

                let Some(pid) = wait_for_target_pid(launcher_pid, false) else {
                    log::warn!("No rs2client.exe appeared; skipping client patching.");
                    return;
                };
                if has_module_loaded(pid, PATCHER_DLL_NAME) {
                    log::info!("pid {} already has {} mapped.", pid, PATCHER_DLL_NAME);
                    return;
                }

                let env = InjectEnv {
                    engine_dll: std::fs::canonicalize(&dll).unwrap_or(dll),
                    injector,
                };
                match run_injector(&env, pid) {
                    Ok(()) => log::info!("Patched rs2client.exe (pid {}) with {}.", pid, PATCHER_DLL_NAME),
                    Err(e) => log::error!(
                        "Failed to patch rs2client.exe (pid {}): {}. The client will use Jagex's \
                         RSA keys and will not connect to a custom server.",
                        pid,
                        e
                    ),
                }
            })
            .map(|_| ())
            .unwrap_or_else(|e| log::warn!("Failed to spawn client-patch thread: {}", e));
    }

    /// Drive `projectx_engine_injector.exe`, which does the
    /// `VirtualAllocEx` + `WriteProcessMemory` + `CreateRemoteThread(LoadLibraryW)`
    /// dance against a process we already own. No elevation is involved, so unlike
    /// Linux there is no escalation ladder to walk.
    fn run_injector(env: &InjectEnv, pid: u32) -> anyhow::Result<()> {
        use anyhow::{bail, Context};

        let output = Command::new(&env.injector)
            .arg("--pid")
            .arg(pid.to_string())
            .arg("--dll")
            .arg(&env.engine_dll)
            .stdin(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .with_context(|| format!("Failed to spawn {}", env.injector.display()))?;

        if output.status.success() {
            return Ok(());
        }

        let detail = String::from_utf8_lossy(&output.stderr);
        let detail = detail.trim();
        if detail.is_empty() {
            bail!("{} exited with {}", INJECTOR_EXE_NAME, output.status);
        }
        bail!("{} exited with {}: {}", INJECTOR_EXE_NAME, output.status, detail)
    }

    /// How long to let a freshly-appeared rs2client initialize before injecting.
    fn inject_delay() -> Duration {
        let secs = std::env::var("INJECT_DELAY")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_INJECT_DELAY_SECS);
        Duration::from_secs(secs)
    }

    /// Poll for an injectable rs2client until [`DISCOVERY_TIMEOUT`] elapses.
    fn wait_for_target_pid(launcher_pid: u32, require_uninjected: bool) -> Option<u32> {
        let deadline = std::time::Instant::now() + DISCOVERY_TIMEOUT;
        loop {
            if let Some(pid) = resolve_target_pid(launcher_pid, require_uninjected) {
                return Some(pid);
            }
            if std::time::Instant::now() >= deadline {
                return None;
            }
            std::thread::sleep(DISCOVERY_POLL_INTERVAL);
        }
    }

    /// Locate `projectxbootstrap.dll`: `%PROJECTX_HOME_DIR%` first, then the
    /// canonical Windows deploy slots it ships in alongside the injector, then the
    /// Gradle output in a source tree (cwd-relative, then walking up from the
    /// launcher exe).
    fn locate_engine_dll() -> Option<PathBuf> {
        crate::engine::find_bootstrap().or_else(|| find_deploy_artifact(ENGINE_DLL_NAME))
    }

    /// Find the rs2client pid to inject into: the newest `rs2client.exe` that is
    /// not already injected, preferring one descended from `launcher_pid` (the
    /// rs3windows.exe we spawned).
    /// `require_uninjected` excludes clients that already carry the engine bootstrap. That
    /// is what the engine path wants, but the patcher must not inherit it: whether the engine
    /// happens to be loaded says nothing about whether the RSA keys have been replaced.
    fn resolve_target_pid(launcher_pid: u32, require_uninjected: bool) -> Option<u32> {
        let processes = snapshot_processes();
        let mut best: Option<(u32, u64)> = None;
        let mut best_descendant: Option<(u32, u64)> = None;

        for entry in processes
            .iter()
            .filter(|e| e.name.eq_ignore_ascii_case(TARGET_PROCESS_NAME))
        {
            if require_uninjected && is_already_injected(entry.pid) {
                continue;
            }

            let start = creation_time(entry.pid).unwrap_or(0);

            if is_descendant_of(&processes, entry.pid, launcher_pid) {
                if best_descendant.map(|(_, s)| start >= s).unwrap_or(true) {
                    best_descendant = Some((entry.pid, start));
                }
            }
            if best.map(|(_, s)| start >= s).unwrap_or(true) {
                best = Some((entry.pid, start));
            }
        }

        best_descendant.or(best).map(|(pid, _)| pid)
    }

    /// Every live `rs2client.exe` pid, for the Clients panel's discovery scan.
    pub(crate) fn rs2client_pids() -> Vec<u32> {
        let mut pids: Vec<u32> = snapshot_processes()
            .into_iter()
            .filter(|e| e.name.eq_ignore_ascii_case(TARGET_PROCESS_NAME))
            .map(|e| e.pid)
            .collect();
        pids.sort_unstable();
        pids
    }

    pub(crate) fn proc_is_rs2client(pid: u32) -> bool {
        match image_path(pid) {
            Some(path) => Path::new(&path)
                .file_name()
                .map(|name| name.eq_ignore_ascii_case(TARGET_PROCESS_NAME))
                .unwrap_or(false),
            None => false,
        }
    }

    pub(crate) fn is_already_injected(pid: u32) -> bool {
        has_module_loaded(pid, ENGINE_DLL_NAME)
    }

    /// Whether `pid` has a module with this file name mapped. Used both for the engine
    /// bootstrap and for the patcher DLL, which are loaded by the same mechanism.
    fn has_module_loaded(pid: u32, dll_name: &str) -> bool {
        let process = match open_process(pid, PROCESS_QUERY_INFORMATION | PROCESS_VM_READ) {
            Some(p) => p,
            None => return false,
        };

        let mut modules: [HMODULE; MODULE_SCAN_CAPACITY] = [ptr::null_mut(); MODULE_SCAN_CAPACITY];
        let mut needed: u32 = 0;
        let ok = unsafe {
            EnumProcessModules(
                process.raw(),
                modules.as_mut_ptr(),
                size_of::<[HMODULE; MODULE_SCAN_CAPACITY]>() as u32,
                &mut needed,
            )
        };
        if ok == FALSE {
            return false;
        }

        let count = (needed as usize / size_of::<HMODULE>()).min(modules.len());
        modules[..count].iter().any(|&module| {
            module_file_name(process.raw(), module)
                .map(|name| name.eq_ignore_ascii_case(dll_name))
                .unwrap_or(false)
        })
    }

    struct ProcessEntry {
        pid: u32,
        parent: u32,
        name: String,
    }

    fn snapshot_processes() -> Vec<ProcessEntry> {
        let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
        if snapshot == INVALID_HANDLE_VALUE {
            log::debug!(
                "process snapshot failed: {}",
                std::io::Error::last_os_error()
            );
            return Vec::new();
        }

        let mut entries = Vec::new();
        let mut entry: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
        entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;

        let mut ok = unsafe { Process32FirstW(snapshot, &mut entry) };
        while ok != FALSE {
            entries.push(ProcessEntry {
                pid: entry.th32ProcessID,
                parent: entry.th32ParentProcessID,
                name: wide_to_string(&entry.szExeFile),
            });
            ok = unsafe { Process32NextW(snapshot, &mut entry) };
        }
        unsafe { CloseHandle(snapshot) };

        entries
    }

    /// Walk the parent chain of `pid` up to a small depth, returning true if
    /// `ancestor` is reached. Cheap guard against picking an unrelated rs2client.
    fn is_descendant_of(processes: &[ProcessEntry], pid: u32, ancestor: u32) -> bool {
        let mut current = pid;
        for _ in 0..MAX_ANCESTOR_DEPTH {
            if current == ancestor {
                return true;
            }
            match processes
                .iter()
                .find(|e| e.pid == current)
                .map(|e| e.parent)
            {
                Some(0) | None => return false,
                Some(parent) => current = parent,
            }
        }
        false
    }

    /// Process creation time as a FILETIME tick count, the ordering key for
    /// "newest rs2client".
    fn creation_time(pid: u32) -> Option<u64> {
        let process = open_process(pid, PROCESS_QUERY_LIMITED_INFORMATION)?;

        let mut created: FILETIME = unsafe { std::mem::zeroed() };
        let mut exited: FILETIME = unsafe { std::mem::zeroed() };
        let mut kernel: FILETIME = unsafe { std::mem::zeroed() };
        let mut user: FILETIME = unsafe { std::mem::zeroed() };
        let ok = unsafe {
            GetProcessTimes(
                process.raw(),
                &mut created,
                &mut exited,
                &mut kernel,
                &mut user,
            )
        };
        if ok == FALSE {
            return None;
        }

        Some((created.dwHighDateTime as u64) << 32 | created.dwLowDateTime as u64)
    }

    fn image_path(pid: u32) -> Option<OsString> {
        let process = open_process(pid, PROCESS_QUERY_LIMITED_INFORMATION)?;

        let mut buf = [0u16; MAX_PATH as usize];
        let mut len = buf.len() as u32;
        let ok = unsafe {
            QueryFullProcessImageNameW(process.raw(), PROCESS_NAME_WIN32, buf.as_mut_ptr(), &mut len)
        };
        if ok == FALSE {
            return None;
        }

        Some(OsString::from_wide(&buf[..len as usize]))
    }

    fn module_file_name(process: HANDLE, module: HMODULE) -> Option<String> {
        let mut buf = [0u16; MAX_PATH as usize];
        let len =
            unsafe { GetModuleFileNameExW(process, module, buf.as_mut_ptr(), buf.len() as u32) };
        if len == 0 {
            return None;
        }

        PathBuf::from(OsString::from_wide(&buf[..len as usize]))
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
    }

    struct ProcessHandle(HANDLE);

    impl ProcessHandle {
        fn raw(&self) -> HANDLE {
            self.0
        }
    }

    impl Drop for ProcessHandle {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }

    /// A process we cannot open (permissions, or it just exited) is reported as
    /// absent; the caller degrades to "not injected" rather than failing.
    fn open_process(pid: u32, access: PROCESS_ACCESS_RIGHTS) -> Option<ProcessHandle> {
        let handle = unsafe { OpenProcess(access, FALSE, pid) };
        if handle.is_null() {
            None
        } else {
            Some(ProcessHandle(handle))
        }
    }

    fn wide_to_string(buf: &[u16]) -> String {
        let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..len])
    }
}
