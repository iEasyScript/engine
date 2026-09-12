//! Cross-platform JDK home discovery.
//!
//! `JAVA_HOME` is only reliably set for processes started from a shell. Launched
//! from a desktop entry, dock or file manager the launcher inherits the session
//! environment instead, where the JDK the user installed via sdkman/asdf/rc-file
//! export is invisible. Rather than give up, resolve in widening order: the
//! inherited env, then the user's login shell, then `java` on PATH, then the
//! well-known install roots for the platform.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// The engine bootstraps a JVM in-process; anything older than the toolchain the
/// engine is built against will fail to load its class files.
const MIN_FEATURE_VERSION: u32 = 25;

/// Cap on the login-shell probe. An rc file that blocks (a prompt, a network
/// call, `read`) must not wedge the launcher during startup.
const SHELL_PROBE_TIMEOUT: Duration = Duration::from_secs(5);

const PROBE_PREFIX: &str = "__PROJECTX_JAVA_HOME__";
const PROBE_SUFFIX: &str = "__END__";

/// Resolve a JDK home of at least [`MIN_FEATURE_VERSION`].
///
/// Sources are tried cheapest-first and the first *valid* hit wins, so an
/// explicit `JAVA_HOME` still takes precedence over discovery — but a stale or
/// too-old `JAVA_HOME` no longer blocks an otherwise working install.
pub fn resolve_home() -> Option<PathBuf> {
    if let Some(home) = env_home().and_then(validated) {
        log::debug!("JDK resolved from JAVA_HOME: {}", home.display());
        return Some(home);
    }

    if let Some(home) = login_shell_home().and_then(validated) {
        log::info!(
            "JDK resolved from login shell JAVA_HOME: {}",
            home.display()
        );
        return Some(home);
    }

    if let Some(home) = path_java_home().and_then(validated) {
        log::info!("JDK resolved from `java` on PATH: {}", home.display());
        return Some(home);
    }

    if let Some(home) = newest_installed() {
        log::info!("JDK resolved by scanning install roots: {}", home.display());
        return Some(home);
    }

    None
}

/// Human-readable description of where we looked, for the "no JDK" error.
pub fn searched_locations() -> String {
    let roots: Vec<String> = install_roots()
        .iter()
        .map(|p| p.display().to_string())
        .collect();
    format!(
        "JAVA_HOME, the login shell environment, `java` on PATH, and {}",
        roots.join(", ")
    )
}

fn env_home() -> Option<PathBuf> {
    match std::env::var("JAVA_HOME") {
        Ok(v) if !v.trim().is_empty() => Some(PathBuf::from(v.trim())),
        _ => None,
    }
}

/// Ask the user's login shell what `JAVA_HOME` is. This is what recovers a
/// version-manager JDK (sdkman, asdf, jenv) whose export lives in an rc file
/// that a GUI session never sources.
#[cfg(not(windows))]
fn login_shell_home() -> Option<PathBuf> {
    let shell = std::env::var("SHELL")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "/bin/sh".to_string());

    // `-l` sources the profile, `-i` the rc file — version managers use both
    // depending on the shell. Markers around the value keep it separable from
    // whatever banner or prompt noise an interactive rc emits.
    let script = format!(
        "printf '\\n{}%s{}\\n' \"$JAVA_HOME\"",
        PROBE_PREFIX, PROBE_SUFFIX
    );
    let child = Command::new(&shell)
        .arg("-lic")
        .arg(&script)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;

    let output = wait_with_timeout(child, SHELL_PROBE_TIMEOUT)?;
    let stdout = String::from_utf8_lossy(&output);
    let start = stdout.find(PROBE_PREFIX)? + PROBE_PREFIX.len();
    let rest = &stdout[start..];
    let end = rest.find(PROBE_SUFFIX)?;
    let value = rest[..end].trim();
    if value.is_empty() {
        return None;
    }
    Some(PathBuf::from(value))
}

#[cfg(windows)]
fn login_shell_home() -> Option<PathBuf> {
    // Windows has no login-shell rc equivalent; the env block a GUI process
    // inherits already reflects the user's persisted environment.
    None
}

/// Reap a child, killing it if it outstays `timeout`. Returns its stdout.
#[cfg(not(windows))]
fn wait_with_timeout(mut child: std::process::Child, timeout: Duration) -> Option<Vec<u8>> {
    use std::io::Read;

    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    log::debug!("Login-shell JAVA_HOME probe timed out; ignoring it.");
                    return None;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => return None,
        }
    }

    let mut buf = Vec::new();
    child.stdout.take()?.read_to_end(&mut buf).ok()?;
    Some(buf)
}

/// Follow `java` on PATH back to its home. Version managers install a shim, so
/// the link has to be resolved before walking up out of `bin/`.
fn path_java_home() -> Option<PathBuf> {
    let exe = which_java()?;
    let exe = std::fs::canonicalize(&exe).unwrap_or(exe);
    // <home>/bin/java -> <home>
    exe.parent()?.parent().map(|p| p.to_path_buf())
}

fn which_java() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let candidate = dir.join(java_exe_name());
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

const fn java_exe_name() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}

/// Scan the platform's install roots and return the highest version that clears
/// [`MIN_FEATURE_VERSION`]. Newest-wins rather than first-wins, so a machine with
/// several JDKs gets the one the engine can actually use.
fn newest_installed() -> Option<PathBuf> {
    let mut best: Option<(u32, PathBuf)> = None;

    for root in install_roots() {
        let entries = match std::fs::read_dir(&root) {
            Ok(e) => e,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            for home in candidate_homes(&entry.path()) {
                if let Some(version) = feature_version(&home) {
                    if version >= MIN_FEATURE_VERSION
                        && best.as_ref().is_none_or(|(b, _)| version > *b)
                    {
                        best = Some((version, home));
                    }
                }
            }
        }
    }

    best.map(|(_, path)| path)
}

/// A directory under an install root may itself be the home, or wrap it (the
/// macOS bundle layout).
fn candidate_homes(dir: &Path) -> Vec<PathBuf> {
    let mut homes = vec![dir.to_path_buf()];
    if cfg!(target_os = "macos") {
        homes.push(dir.join("Contents").join("Home"));
    }
    homes
}

fn install_roots() -> Vec<PathBuf> {
    let mut roots: Vec<PathBuf> = Vec::new();

    if let Some(home) = home_dir() {
        // Version managers and IDE-managed toolchains.
        roots.push(home.join(".sdkman").join("candidates").join("java"));
        roots.push(home.join(".jdks"));
        roots.push(home.join(".gradle").join("jdks"));
        roots.push(home.join(".asdf").join("installs").join("java"));
        if cfg!(target_os = "macos") {
            roots.push(
                home.join("Library")
                    .join("Java")
                    .join("JavaVirtualMachines"),
            );
        }
    }

    #[cfg(target_os = "macos")]
    {
        roots.push(PathBuf::from("/Library/Java/JavaVirtualMachines"));
    }

    #[cfg(windows)]
    {
        for var in ["ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"] {
            if let Some(base) = std::env::var_os(var) {
                let base = PathBuf::from(base);
                roots.push(base.join("Java"));
                roots.push(base.join("Eclipse Adoptium"));
                roots.push(base.join("Microsoft"));
                roots.push(base.join("Amazon Corretto"));
                roots.push(base.join("Zulu"));
                roots.push(base.join("Programs").join("Eclipse Adoptium"));
            }
        }
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    {
        roots.push(PathBuf::from("/usr/lib/jvm"));
        roots.push(PathBuf::from("/usr/lib64/jvm"));
        roots.push(PathBuf::from("/usr/java"));
        roots.push(PathBuf::from("/opt/java"));
        roots.push(PathBuf::from("/opt/jdk"));
    }

    roots
}

fn home_dir() -> Option<PathBuf> {
    let var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    std::env::var_os(var)
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
}

/// A home is usable only if it has a launcher *and* is new enough.
fn validated(home: PathBuf) -> Option<PathBuf> {
    let version = feature_version(&home)?;
    if version < MIN_FEATURE_VERSION {
        log::warn!(
            "Ignoring JDK {} at {}: the engine needs {} or newer.",
            version,
            home.display(),
            MIN_FEATURE_VERSION
        );
        return None;
    }
    Some(home)
}

/// Feature version of the JDK at `home`, or `None` if it isn't one.
///
/// Prefers the `release` file every JDK 9+ ships, falling back to running the
/// launcher for repackaged distributions that drop it.
fn feature_version(home: &Path) -> Option<u32> {
    if !home.join("bin").join(java_exe_name()).is_file() {
        return None;
    }
    release_file_version(home).or_else(|| launcher_version(home))
}

fn release_file_version(home: &Path) -> Option<u32> {
    let release = std::fs::read_to_string(home.join("release")).ok()?;
    let line = release
        .lines()
        .find(|l| l.starts_with("JAVA_VERSION="))?
        .trim_start_matches("JAVA_VERSION=")
        .trim_matches('"');
    parse_feature_version(line)
}

fn launcher_version(home: &Path) -> Option<u32> {
    let output = Command::new(home.join("bin").join(java_exe_name()))
        .arg("-version")
        .stdin(Stdio::null())
        .output()
        .ok()?;
    // `java -version` writes to stderr.
    let text = String::from_utf8_lossy(&output.stderr);
    let quoted = text.split('"').nth(1)?;
    parse_feature_version(quoted)
}

/// `"25.0.2"` / `"25"` / `"1.8.0_402"` -> feature version.
fn parse_feature_version(raw: &str) -> Option<u32> {
    let raw = raw.trim();
    let first = raw.split(['.', '-', '+', '_']).next()?;
    let major: u32 = first.parse().ok()?;
    if major == 1 {
        // Legacy 1.x scheme: the feature version is the second component.
        return raw.split('.').nth(1)?.parse().ok();
    }
    Some(major)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_modern_versions() {
        assert_eq!(parse_feature_version("25"), Some(25));
        assert_eq!(parse_feature_version("25.0.2"), Some(25));
        assert_eq!(parse_feature_version("21.0.5+11"), Some(21));
        assert_eq!(parse_feature_version("17-ea"), Some(17));
    }

    #[test]
    fn parses_legacy_versions() {
        assert_eq!(parse_feature_version("1.8.0_402"), Some(8));
        assert_eq!(parse_feature_version("1.7.0"), Some(7));
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(parse_feature_version(""), None);
        assert_eq!(parse_feature_version("unknown"), None);
    }

    /// Machine-dependent smoke test for the discovery chain — run it on a host
    /// that reproduces a "no JDK found" report:
    /// `env -u JAVA_HOME cargo test -- --ignored --nocapture resolves`
    #[test]
    #[ignore]
    fn resolves_on_this_host() {
        let home = resolve_home().expect("no JDK 25+ found");
        println!("resolved: {}", home.display());
        println!("version:  {:?}", feature_version(&home));
        assert!(home.join("bin").join(java_exe_name()).is_file());
    }

    #[test]
    fn rejects_home_without_launcher() {
        let dir = std::env::temp_dir().join("projectx-java-probe-empty");
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(feature_version(&dir), None);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
