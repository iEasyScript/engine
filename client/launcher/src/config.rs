use anyhow::{Context, Result};
use directories::ProjectDirs;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::game::process::launcher_binary_name;
use crate::game::renderer::RendererPref;

#[derive(Debug, Clone, Serialize)]
pub enum ServerMode {
    Live,
    Custom,
}

impl Default for ServerMode {
    fn default() -> Self {
        ServerMode::Live
    }
}

/// Deserialize `ServerMode` leniently so configs written by older launcher
/// builds keep loading. The removed `Proxy` variant (deprecated in favour of the
/// Project X engine's in-process sniffer) and any unknown future string both fall
/// back to `Live` rather than failing the whole config parse.
impl<'de> Deserialize<'de> for ServerMode {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let raw = String::deserialize(deserializer)?;
        Ok(match raw.as_str() {
            "Custom" => ServerMode::Custom,
            "Live" => ServerMode::Live,
            // "Proxy" (removed) and anything unrecognised → safe default.
            other => {
                log::warn!("Unknown server_mode {:?} in config; defaulting to Live", other);
                ServerMode::Live
            }
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    #[serde(default)]
    pub close_after_launch: bool,
    #[serde(default)]
    pub custom_launch_command: Option<String>,
    #[serde(default)]
    pub server_mode: ServerMode,
    #[serde(default)]
    pub custom_server_host: Option<String>,
    #[serde(default)]
    pub custom_server_port: Option<u16>,
    #[serde(default)]
    pub custom_config_uri: Option<String>,
    #[serde(default)]
    pub custom_rsa_modulus: Option<String>,
    /// When true, after the launcher spawns `rs2client` it injects the Project X
    /// engine: GDB-`dlopen` of `libprojectxbootstrap.so` on Linux (the same
    /// mechanism as `launch/run-projectx.sh`), `projectx_engine_injector.exe` loading
    /// `projectxbootstrap.dll` on Windows. macOS has no implementation and logs
    /// a skip. Best-effort: a failure never blocks or kills the client launch.
    #[serde(default)]
    pub auto_inject_projectx: bool,
    /// Raises the log level to `debug`, which includes the per-message IPC trace. The UI polls
    /// (`ListClients` every few seconds while the Clients panel is open), so that trace is noise
    /// during normal use and detail only when something is being diagnosed. `RUST_LOG` still wins.
    #[serde(default)]
    pub debug_logging: bool,
    /// Which renderer build of the client to launch. `Auto` (the default) prefers
    /// the Vulkan client and falls back to OpenGL when the host has no Vulkan
    /// loader or the installed engine carries no Vulkan offset table. Pin it to
    /// `"Vulkan"` or `"OpenGl"` to force one while diagnosing a renderer problem.
    #[serde(default)]
    pub renderer: RendererPref,
    /// Managed script-jar channels. Every channel starts opted out: building the
    /// scripts yourself is the default workflow, and the launcher must never put
    /// a jar the user did not ask for into the engine's scan path.
    #[serde(default)]
    pub plugins: PluginsConfig,
}

/// Which script channels the launcher keeps current, and where it fetches them
/// from. The host/project overrides exist so a fork can point the same launcher
/// at its own GitLab project without a rebuild.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PluginsConfig {
    #[serde(default)]
    pub official_auto_update: bool,
    #[serde(default)]
    pub community_auto_update: bool,
    /// Alias kept so a config written before the move to GitHub still loads.
    #[serde(default, alias = "gitlab_host")]
    pub api_host: Option<String>,
    #[serde(default)]
    pub project_path: Option<String>,
    /// Per-channel overrides. Each channel's jar comes from its own public
    /// repository, so these are what point the launcher somewhere else.
    #[serde(default)]
    pub official_repo: Option<String>,
    #[serde(default)]
    pub community_repo: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            close_after_launch: false,
            custom_launch_command: None,
            server_mode: ServerMode::default(),
            custom_server_host: None,
            custom_server_port: None,
            custom_config_uri: None,
            custom_rsa_modulus: None,
            auto_inject_projectx: false,
            debug_logging: false,
            renderer: RendererPref::default(),
            plugins: PluginsConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Credentials {
    pub sessions: Vec<SavedSession>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavedSession {
    pub user_id: String,
    pub display_name: String,
    pub access_token: String,
    pub id_token: String,
    pub refresh_token: String,
    pub expiry: u64,
    #[serde(default)]
    pub accounts: Vec<crate::auth::types::Account>,
    #[serde(default)]
    pub session_id: Option<String>,
    /// Consent id_token (from the consent flow, different from launcher id_token).
    /// Used to create fresh game sessions at launch time.
    #[serde(default)]
    pub consent_id_token: Option<String>,
    /// The character (`Account::account_id`) last selected/launched under this
    /// Jagex account, restored as the default when the user switches back to it.
    #[serde(default)]
    pub last_account_id: Option<String>,
}

pub struct Paths {
    pub config_dir: PathBuf,
    pub data_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub config_file: PathBuf,
    pub creds_file: PathBuf,
    pub lock_file: PathBuf,
    pub rs3_binary: PathBuf,
    /// Records the `.deb` digest the launcher was installed from, so only Linux
    /// — the one host that installs from a package index — ever reads it.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub rs3_hash: PathBuf,
}

impl Paths {
    pub fn new() -> Result<Self> {
        let dirs = ProjectDirs::from("", "", "project-x-launcher")
            .context("Failed to determine project directories")?;

        let config_dir = dirs.config_dir().to_path_buf();
        let data_dir = dirs.data_dir().to_path_buf();
        let runtime_dir = dirs
            .runtime_dir()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| data_dir.join("run"));

        let launcher_name = launcher_binary_name();

        Ok(Self {
            config_file: config_dir.join("config.json"),
            creds_file: data_dir.join("creds.json"),
            lock_file: runtime_dir.join("lock"),
            rs3_binary: data_dir.join(launcher_name),
            rs3_hash: data_dir.join(format!("{}.sha256", launcher_name)),
            config_dir,
            data_dir,
            runtime_dir,
        })
    }

    pub fn ensure_dirs(&self) -> Result<()> {
        fs::create_dir_all(&self.config_dir)?;
        fs::create_dir_all(&self.data_dir)?;
        fs::create_dir_all(&self.runtime_dir)?;
        // The config/data dirs hold OAuth tokens — keep them owner-only.
        restrict_dir_permissions(&self.config_dir);
        restrict_dir_permissions(&self.data_dir);
        Ok(())
    }

    /// The data directory (and `HOME`-redirect target) for a given server mode.
    ///
    /// * [`ServerMode::Live`] → the canonical shared data dir
    ///   (`~/.local/share/project-x-launcher`). This is the location the injected
    ///   Project X engine reads from for official play, so live cache/prefs/
    ///   binaries stay where the engine and any existing install expect them.
    /// * [`ServerMode::Custom`] → a `custom/` subdir of it
    ///   (`~/.local/share/project-x-launcher/custom`), so a private server's cache,
    ///   preferences, `rs3linux`/`rs2client` binaries and creds never mix with
    ///   the official install.
    ///
    /// A subdir (rather than a sibling) is the cleanest fit for how paths are
    /// built here: it stays under the single `ProjectDirs` root that
    /// `ensure_dirs` already creates and restricts to 0700, and that
    /// `migrate_data_dir` relocates as one unit.
    pub fn data_dir_for_mode(&self, mode: &ServerMode) -> PathBuf {
        match mode {
            ServerMode::Live => self.data_dir.clone(),
            ServerMode::Custom => self.data_dir.join("custom"),
        }
    }
}

/// The NXT client cache directory under a given data dir. The client writes its
/// SQLite JS5 cache to `$HOME/Jagex/RuneScape`, and the launcher redirects
/// `HOME` to the mode-selected data dir — so this is the same path exported as
/// `RS_CACHE_DIR` for the injected engine to read.
pub fn cache_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("Jagex").join("RuneScape")
}

/// Restrict a directory to owner-only access (0700). No-op on non-unix.
fn restrict_dir_permissions(dir: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Err(e) = fs::set_permissions(dir, fs::Permissions::from_mode(0o700)) {
            log::warn!("Failed to set 0700 on {}: {}", dir.display(), e);
        }
    }
    #[cfg(not(unix))]
    let _ = dir;
}

/// Load and deserialize a JSON file, distinguishing three cases:
/// - absent → default (first run, expected)
/// - present but unreadable → log error, default (do not destroy the file)
/// - present but unparseable → log error, back the file up to `<name>.bak`
///   (numbered if a backup already exists), then default. This prevents the
///   next save from silently overwriting recoverable data.
fn load_or_default<T: DeserializeOwned + Default>(path: &Path, label: &str) -> T {
    match fs::read_to_string(path) {
        Ok(contents) => match serde_json::from_str(&contents) {
            Ok(value) => value,
            Err(e) => {
                log::error!(
                    "Failed to parse {} at {}: {}. Backing up and using defaults.",
                    label,
                    path.display(),
                    e
                );
                backup_corrupt_file(path);
                T::default()
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => T::default(),
        Err(e) => {
            log::error!(
                "Failed to read {} at {}: {}. Using defaults (file left intact).",
                label,
                path.display(),
                e
            );
            T::default()
        }
    }
}

/// Move a corrupt file aside to `<path>.bak`, or `<path>.bak.N` if that exists.
fn backup_corrupt_file(path: &Path) {
    let mut backup = {
        let mut name = path.as_os_str().to_owned();
        name.push(".bak");
        PathBuf::from(name)
    };
    let mut n: u32 = 1;
    while backup.exists() {
        let mut name = path.as_os_str().to_owned();
        name.push(format!(".bak.{}", n));
        backup = PathBuf::from(name);
        n += 1;
    }
    match fs::rename(path, &backup) {
        Ok(()) => log::warn!(
            "Backed up corrupt file {} to {}",
            path.display(),
            backup.display()
        ),
        Err(e) => log::error!(
            "Failed to back up corrupt file {}: {}",
            path.display(),
            e
        ),
    }
}

/// Atomically write `contents` to `path`: write to a temp file in the same
/// directory (0600 on unix so tokens never land world-readable), fsync, then
/// rename over the target. The parent dir is created and restricted to 0700.
fn atomic_write(path: &Path, contents: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .context("Cannot determine parent directory for atomic write")?;
    fs::create_dir_all(parent)
        .with_context(|| format!("Failed to create {}", parent.display()))?;
    restrict_dir_permissions(parent);

    let file_name = path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "config".to_string());
    let tmp = parent.join(format!(".{}.{}.tmp", file_name, std::process::id()));

    let write_result = (|| -> Result<()> {
        let mut file = fs::File::create(&tmp)
            .with_context(|| format!("Failed to create temp file {}", tmp.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(fs::Permissions::from_mode(0o600))
                .context("Failed to set 0600 on temp file")?;
        }
        file.write_all(contents)
            .context("Failed to write temp file contents")?;
        file.sync_all().context("Failed to fsync temp file")?;
        Ok(())
    })();

    if let Err(e) = write_result {
        let _ = fs::remove_file(&tmp);
        return Err(e);
    }

    fs::rename(&tmp, path).with_context(|| {
        format!("Failed to rename {} over {}", tmp.display(), path.display())
    })?;
    Ok(())
}

pub fn load_config(path: &Path) -> Config {
    load_or_default(path, "config.json")
}

pub fn save_config(path: &Path, config: &Config) -> Result<()> {
    let json = serde_json::to_string_pretty(config)?;
    atomic_write(path, json.as_bytes())
}

pub fn load_credentials(path: &Path) -> Credentials {
    load_or_default(path, "creds.json")
}

pub fn save_credentials(path: &Path, creds: &Credentials) -> Result<()> {
    let json = serde_json::to_string_pretty(creds)?;
    atomic_write(path, json.as_bytes())
}
