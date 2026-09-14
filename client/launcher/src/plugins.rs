//! Plugin channel management: discovering, installing and updating the script
//! jars the injected Project X engine loads from `~/.projectx/scripts/`.
//!
//! Nothing here runs unless the user asks for it. The launcher ships with every
//! channel opted out, because building the scripts yourself is the expected
//! workflow — a managed install is the convenience path for people who do not
//! want a JDK and a Gradle build in their loop.
//!
//! Releases are the unit of distribution: the CI tag pipeline attaches a
//! `plugins-manifest.json` asset listing each jar's version, size, sha256 and
//! package-registry URL, so an update check costs one small JSON fetch and an
//! install is verifiable.

use anyhow::{anyhow, bail, Context, Result};
use directories::BaseDirs;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use url::Url;

use crate::config::PluginsConfig;
use crate::HTTP_DOWNLOAD_TIMEOUT;

const DEFAULT_API_HOST: &str = "https://api.github.com";
const DEFAULT_WEB_HOST: &str = "https://github.com";
/// The public distribution repository. The engine repository is private, so its
/// release assets 404 for users; this is where the engine home artifacts and the
/// manifest are published.
const DEFAULT_PROJECT_PATH: &str = "iEasyScript/launcher";

/// The Release asset link the CI publish job attaches. Version-free by design so
/// the lookup is a name match rather than a parse.
const MANIFEST_LINK: &str = "plugins-manifest.json";
/// Release asset link name for the engine shadow jar.
const ENGINE_LINK: &str = "projectx-engine.jar";

/// How long a fetched catalog is reused before the Plugins tab re-checks. Long
/// enough that opening and closing the tab does not hammer the API, short enough
/// that a release published while the launcher is open is still noticed.
const CATALOG_TTL: Duration = Duration::from_secs(10 * 60);

/// Bounds the release page request used to learn a channel's latest tag, which
/// is only read for its redirect.
const RELEASE_PAGE_TIMEOUT: Duration = Duration::from_secs(20);

/// A distributable script module. The id is the contract shared with the CI
/// manifest and with the persisted install state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PluginChannel {
    Official,
    Community,
}

pub const CHANNELS: [PluginChannel; 2] = [PluginChannel::Official, PluginChannel::Community];

impl PluginChannel {
    pub fn id(self) -> &'static str {
        match self {
            PluginChannel::Official => "official",
            PluginChannel::Community => "community",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        CHANNELS.into_iter().find(|c| c.id() == id)
    }

    /// Fallback identity for a release that predates the manifest asset.
    fn label(self) -> &'static str {
        match self {
            PluginChannel::Official => "Official Scripts",
            PluginChannel::Community => "Community Scripts",
        }
    }

    fn blurb(self) -> &'static str {
        match self {
            PluginChannel::Official => {
                "First-party scripts maintained by the Project X team."
            }
            PluginChannel::Community => {
                "Community-contributed scripts, built from the opt-in community module."
            }
        }
    }

    /// Gradle's `archivesName` for the module, and therefore the leading segment
    /// of every jar — built locally or downloaded — that belongs to the channel.
    fn jar_prefix(self) -> &'static str {
        match self {
            PluginChannel::Official => "official-scripts",
            PluginChannel::Community => "community-scripts",
        }
    }

    /// The Release asset link name used when a release carries no manifest.
    fn jar_link(self) -> &'static str {
        match self {
            PluginChannel::Official => "official-scripts.jar",
            PluginChannel::Community => "community-scripts.jar",
        }
    }

    /// The public repository whose releases carry this channel's jar. Separate
    /// per channel so the engine repository can stay private while the scripts
    /// people install are public.
    fn default_repo(self) -> &'static str {
        match self {
            PluginChannel::Official => "iEasyScript/official-scripts",
            PluginChannel::Community => "iEasyScript/community-scripts",
        }
    }

    fn repo(self, cfg: &PluginsConfig) -> String {
        let configured = match self {
            PluginChannel::Official => cfg.official_repo.as_deref(),
            PluginChannel::Community => cfg.community_repo.as_deref(),
        };
        configured
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(self.default_repo())
            .trim_matches('/')
            .to_string()
    }
}

// ---------------------------------------------------------------------------
// Remote catalog
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct ManifestPlugin {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub version: String,
    pub file: String,
    pub url: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
}

/// One published launcher build. Users who already have the launcher do not need
/// these, but they are the links to hand to someone who does not.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LauncherDownload {
    pub platform: String,
    pub file: String,
    pub url: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
    /// Filled in from `platform` on the way to the UI, so the frontend never has
    /// to know the platform vocabulary.
    #[serde(default)]
    pub label: String,
}

/// A release artifact the launcher installs into the engine home: the engine
/// shadow jar, the supervisor jar, or a platform's native bootstrap. None of
/// them are plugins — they go to the engine home rather than the scripts dir —
/// but they ride the same manifest so one fetch answers "what does this release
/// offer?", and so the engine jar can never be updated past the bootstrap that
/// has to load it.
///
/// `platform` is set only for the bootstrap, whose artifact is per-OS.
#[derive(Debug, Clone, Deserialize)]
pub struct ReleaseArtifact {
    pub file: String,
    #[serde(default)]
    pub version: String,
    pub url: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub platform: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct PluginManifest {
    #[serde(default)]
    version: String,
    #[serde(default)]
    plugins: Vec<ManifestPlugin>,
    #[serde(default)]
    launcher: Vec<LauncherDownload>,
    #[serde(default)]
    engine: Option<ReleaseArtifact>,
    #[serde(default)]
    supervisor: Option<ReleaseArtifact>,
    #[serde(default)]
    bootstrap: Vec<ReleaseArtifact>,
}

/// This build's manifest platform tag, or `None` on a platform the release
/// pipeline publishes nothing for.
pub fn platform_tag() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => Some("linux-x86_64"),
        ("windows", "x86_64") => Some("windows-x86_64"),
        ("macos", _) => Some("macos-universal"),
        _ => None,
    }
}

/// Human-facing name for a manifest platform tag.
fn platform_label(platform: &str) -> String {
    match platform {
        "linux-x86_64" => "Linux (x86-64)".to_string(),
        "windows-x86_64" => "Windows (x86-64)".to_string(),
        "macos-universal" => "macOS (Universal)".to_string(),
        other => other.replace('-', " "),
    }
}

#[derive(Debug, Deserialize)]
struct AssetLink {
    name: String,
    #[serde(rename = "browser_download_url")]
    url: String,
}

#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    assets: Vec<AssetLink>,
}

/// What the newest release offers, resolved down to per-channel entries.
#[derive(Debug, Clone)]
pub struct Catalog {
    pub release_tag: String,
    pub release_url: String,
    pub plugins: Vec<ManifestPlugin>,
    pub launcher: Vec<LauncherDownload>,
    pub engine: Option<ReleaseArtifact>,
    pub supervisor: Option<ReleaseArtifact>,
    pub bootstrap: Vec<ReleaseArtifact>,
    pub fetched_at: SystemTime,
}

impl Catalog {
    pub fn plugin(&self, channel: PluginChannel) -> Option<&ManifestPlugin> {
        self.plugins.iter().find(|p| p.id == channel.id())
    }

    pub fn bootstrap_for(&self, platform: &str) -> Option<&ReleaseArtifact> {
        self.bootstrap.iter().find(|a| a.platform == platform)
    }

    pub fn is_stale(&self) -> bool {
        self.fetched_at
            .elapsed()
            .map(|age| age > CATALOG_TTL)
            .unwrap_or(true)
    }
}

/// The `major.minor.patch` of a released version, or `None` for anything that is
/// not one.
///
/// A pre-release — what a branch pipeline and a local `cargo build` stamp — is
/// deliberately unparseable here rather than sorted below every release. A
/// developer's own build is not behind the release, it is beside it, and a
/// prompt that fires on every launch is a prompt that gets ignored on the one
/// launch it mattered.
fn release_version(version: &str) -> Option<(u32, u32, u32)> {
    let version = version.trim().trim_start_matches('v');
    if version.contains('-') || version.contains('+') {
        return None;
    }
    let mut parts = version.split('.');
    let mut next = || parts.next()?.parse::<u32>().ok();
    let parsed = (next()?, next()?, next()?);
    parts.next().is_none().then_some(parsed)
}

/// The launcher build to offer, when the release publishes one for this platform
/// that is newer than what is running.
pub fn launcher_update(catalog: &Catalog) -> Option<&LauncherDownload> {
    let current = release_version(crate::VERSION)?;
    let available = release_version(&catalog.release_tag)?;
    if available <= current {
        return None;
    }
    let platform = platform_tag()?;
    catalog.launcher.iter().find(|l| l.platform == platform)
}

fn api_host(cfg: &PluginsConfig) -> String {
    cfg.api_host
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(DEFAULT_API_HOST)
        .trim_end_matches('/')
        .to_string()
}

fn project_path(cfg: &PluginsConfig) -> String {
    cfg.project_path
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(DEFAULT_PROJECT_PATH)
        .trim_matches('/')
        .to_string()
}

/// The project's web page, shown in the UI so the source of an install is never
/// a mystery. Derived from the API host so a self-hosted or test endpoint keeps
/// the two consistent.
pub fn source_url(cfg: &PluginsConfig) -> String {
    let host = api_host(cfg);
    let web = match host.strip_prefix("https://api.github.com") {
        Some(_) => DEFAULT_WEB_HOST.to_string(),
        None => host,
    };
    format!("{}/{}", web, project_path(cfg))
}

fn api_project_base(cfg: &PluginsConfig) -> String {
    format!("{}/repos/{}", api_host(cfg), project_path(cfg))
}

async fn get_json<T: DeserializeOwned>(
    client: &reqwest::Client,
    url: &str,
) -> Result<T> {
    let response = client
        .get(url)
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;
    let status = response.status();
    if !status.is_success() {
        bail!("GET {} returned {}", url, status);
    }
    response
        .json::<T>()
        .await
        .with_context(|| format!("GET {} returned a body this launcher cannot read", url))
}

/// The GitHub web host to read releases from without the REST API, or `None` when
/// a different API host is configured (a self-hosted or test endpoint), which
/// has no such web routes.
///
/// Unauthenticated API calls are limited to 60 an hour per IP, and every catalog
/// fetch spends three. A launcher restarted a few times in an hour ran out, and
/// the failed check left it on the engine it already had. Release pages and asset
/// downloads are not counted against that limit.
fn web_host(cfg: &PluginsConfig) -> Option<&'static str> {
    (api_host(cfg) == DEFAULT_API_HOST).then_some(DEFAULT_WEB_HOST)
}

async fn fetch_channel_plugin(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    channel: PluginChannel,
) -> Result<ManifestPlugin> {
    if let Some(web) = web_host(cfg) {
        match fetch_channel_plugin_web(client, cfg, channel, web).await {
            Ok(plugin) => return Ok(plugin),
            Err(e) => log::warn!(
                "{} channel: release download links failed ({}); asking the GitHub API instead",
                channel.id(),
                e
            ),
        }
    }
    fetch_channel_plugin_api(client, cfg, channel).await
}

/// Resolves a channel from its release page's redirect and the publish job's
/// fixed asset names: `<repo>-<version>.jar` with a `.sha256` beside it. Fetching
/// the checksum doubles as the check that the jar really is under that name.
async fn fetch_channel_plugin_web(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    channel: PluginChannel,
    web: &str,
) -> Result<ManifestPlugin> {
    let repo = channel.repo(cfg);
    let tag = latest_release_tag(web, &repo).await?;
    let version = tag.trim_start_matches('v').to_string();
    let file = format!("{}-{}.jar", channel.jar_prefix(), version);
    let base = format!("{}/{}/releases/download/{}", web, repo, tag);

    let checksum = fetch_text(client, &format!("{}/{}.sha256", base, file)).await?;
    let sha256 = checksum
        .split_whitespace()
        .next()
        .filter(|sha| sha.len() == 64)
        .ok_or_else(|| anyhow!("{repo}: {file}.sha256 holds no checksum"))?
        .to_string();

    Ok(ManifestPlugin {
        id: channel.id().to_string(),
        name: channel.label().to_string(),
        description: channel.blurb().to_string(),
        version,
        url: format!("{}/{}", base, file),
        file,
        sha256,
        size: 0,
    })
}

/// The newest release's tag, read from where `/releases/latest` redirects rather
/// than from the API. The redirect is not followed: the page behind it is only
/// wanted for its address.
async fn latest_release_tag(web: &str, repo: &str) -> Result<String> {
    let url = format!("{}/{}/releases/latest", web, repo);
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(RELEASE_PAGE_TIMEOUT)
        .build()
        .context("Failed to build the release page client")?;
    let response = client
        .get(&url)
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;
    let location = response
        .headers()
        .get(reqwest::header::LOCATION)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(|| anyhow!("GET {} returned {} with no redirect", url, response.status()))?;
    tag_from_release_location(location)
        .ok_or_else(|| anyhow!("{} redirected to {}, which names no release tag", url, location))
}

/// The tag at the end of a `/releases/tag/<tag>` address. `/releases` alone is
/// where GitHub sends a repository with no release, so it yields nothing.
fn tag_from_release_location(location: &str) -> Option<String> {
    let tag = location.split_once("/releases/tag/")?.1;
    let tag = tag.split(['?', '#']).next()?.trim_end_matches('/');
    (!tag.is_empty() && !tag.contains('/')).then(|| tag.to_string())
}

async fn fetch_channel_plugin_api(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    channel: PluginChannel,
) -> Result<ManifestPlugin> {
    let repo = channel.repo(cfg);
    let base = format!("{}/repos/{}", api_host(cfg), repo);

    let release: Release = match get_json(client, &format!("{}/releases/latest", base)).await {
        Ok(release) => release,
        Err(e) => {
            log::debug!("{repo}: latest release unavailable ({e}); falling back to the list");
            let mut releases: Vec<Release> =
                get_json(client, &format!("{}/releases?per_page=1", base)).await?;
            if releases.is_empty() {
                bail!("{repo} has published no releases");
            }
            releases.remove(0)
        }
    };

    let prefix = format!("{}-", channel.jar_prefix());
    let jar = release
        .assets
        .iter()
        .find(|a| a.name.starts_with(&prefix) && a.name.ends_with(".jar"))
        .ok_or_else(|| anyhow!("{repo} release {} carries no {prefix}*.jar", release.tag_name))?;

    // The publish job writes `sha256sum` output, so the digest is the first field.
    let sha = match release.assets.iter().find(|a| a.name == format!("{}.sha256", jar.name)) {
        Some(asset) => fetch_text(client, &asset.url)
            .await
            .ok()
            .and_then(|body| body.split_whitespace().next().map(str::to_string))
            .unwrap_or_default(),
        None => {
            log::warn!("{repo}: {} has no .sha256 beside it; install cannot be verified", jar.name);
            String::new()
        }
    };

    Ok(ManifestPlugin {
        id: channel.id().to_string(),
        name: channel.label().to_string(),
        description: channel.blurb().to_string(),
        version: release.tag_name.trim_start_matches('v').to_string(),
        file: jar.name.clone(),
        url: jar.url.clone(),
        sha256: sha,
        size: 0,
    })
}

async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String> {
    let response = client
        .get(url)
        .send()
        .await
        .with_context(|| format!("GET {url} failed"))?;
    if !response.status().is_success() {
        bail!("GET {url} returned {}", response.status());
    }
    Ok(response.text().await?)
}

/// Fetch the newest release and turn it into per-channel entries.
///
/// The manifest asset is the good path — it carries checksums. A release without
/// one (hand-cut, or from before the manifest existed) still installs: the jar
/// links alone are enough to download, just not to verify.
pub async fn fetch_catalog(client: &reqwest::Client, cfg: &PluginsConfig) -> Result<Catalog> {
    // Script channels live in their own public repositories, so they resolve even
    // when the engine repository is private or has published nothing yet.
    let mut channel_plugins = Vec::new();
    for channel in CHANNELS {
        match fetch_channel_plugin(client, cfg, channel).await {
            Ok(plugin) => {
                log::info!("{} channel: {} {}", channel.id(), plugin.file, plugin.version);
                channel_plugins.push(plugin);
            }
            Err(e) => log::warn!("{} channel unavailable: {}", channel.id(), e),
        }
    }

    match fetch_engine_catalog(client, cfg).await {
        Ok(mut catalog) => {
            // A channel's own repository is the authority for its jar; the engine
            // repository's manifest only fills in what no channel repo published.
            catalog.plugins.retain(|p| !channel_plugins.iter().any(|c| c.id == p.id));
            catalog.plugins.extend(channel_plugins);
            Ok(catalog)
        }
        Err(e) => {
            if channel_plugins.is_empty() {
                return Err(e);
            }
            // The engine repository is where the engine jar, supervisor and
            // bootstraps come from. Without it the script channels still install;
            // only the engine-home artifacts are missing.
            log::warn!("engine catalog unavailable ({}); serving script channels only", e);
            Ok(Catalog {
                release_tag: String::new(),
                release_url: String::new(),
                plugins: channel_plugins,
                launcher: Vec::new(),
                engine: None,
                supervisor: None,
                bootstrap: Vec::new(),
                fetched_at: SystemTime::now(),
            })
        }
    }
}

async fn fetch_engine_catalog(client: &reqwest::Client, cfg: &PluginsConfig) -> Result<Catalog> {
    if let Some(web) = web_host(cfg) {
        match fetch_engine_catalog_web(client, cfg, web).await {
            Ok(catalog) => return Ok(catalog),
            Err(e) => log::warn!(
                "release manifest download failed ({}); asking the GitHub API instead",
                e
            ),
        }
    }
    fetch_engine_catalog_api(client, cfg).await
}

/// Reads the newest release's manifest through GitHub's `latest/download` link,
/// which redirects to the asset without touching the API. The manifest carries
/// its own version, which is all the release tag is needed for.
async fn fetch_engine_catalog_web(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    web: &str,
) -> Result<Catalog> {
    let url = format!(
        "{}/{}/releases/latest/download/{}",
        web,
        project_path(cfg),
        MANIFEST_LINK
    );
    let manifest: PluginManifest = get_json(client, &url).await?;
    let version = manifest.version.trim().trim_start_matches('v');
    if version.is_empty() {
        bail!("{} carries no version", url);
    }
    let tag = format!("v{}", version);
    Ok(catalog_from_manifest(cfg, tag, manifest))
}

fn catalog_from_manifest(cfg: &PluginsConfig, release_tag: String, mut manifest: PluginManifest) -> Catalog {
    log::info!(
        "Plugin catalog {} lists {} plugin(s), {} launcher build(s), {} engine, {} supervisor \
         and {} bootstrap(s)",
        release_tag,
        manifest.plugins.len(),
        manifest.launcher.len(),
        if manifest.engine.is_some() { "an" } else { "no" },
        if manifest.supervisor.is_some() { "a" } else { "no" },
        manifest.bootstrap.len()
    );
    for download in &mut manifest.launcher {
        download.label = platform_label(&download.platform);
    }
    Catalog {
        release_url: format!("{}/releases/tag/{}", source_url(cfg), release_tag),
        release_tag,
        plugins: manifest.plugins,
        launcher: manifest.launcher,
        engine: manifest.engine,
        supervisor: manifest.supervisor,
        bootstrap: manifest.bootstrap,
        fetched_at: SystemTime::now(),
    }
}

async fn fetch_engine_catalog_api(client: &reqwest::Client, cfg: &PluginsConfig) -> Result<Catalog> {
    let base = api_project_base(cfg);

    let release: Release = match get_json(client, &format!("{}/releases/latest", base)).await {
        Ok(release) => release,
        Err(e) => {
            // `releases/latest` 404s when every release is a draft or a prerelease;
            // the list endpoint still returns those.
            log::debug!("latest release unavailable ({}); falling back to the list", e);
            let mut releases: Vec<Release> =
                get_json(client, &format!("{}/releases?per_page=1", base)).await?;
            if releases.is_empty() {
                bail!("no releases published yet");
            }
            releases.remove(0)
        }
    };

    let manifest_url = release
        .assets
        .iter()
        .find(|l| l.name == MANIFEST_LINK)
        .map(|l| l.url.clone());

    let manifest = match manifest_url {
        Some(url) => get_json::<PluginManifest>(client, &url).await?,
        // A release with no manifest is hand-cut or predates the asset. Its raw
        // links still name the plugin and engine jars, but nothing names a
        // checksum — and the bootstrap and supervisor install under fixed names,
        // so a checksum is the only thing that could tell a stale one from a
        // current one. They are left to whatever shipped beside the launcher.
        None => PluginManifest {
            plugins: plugins_from_links(&release),
            launcher: launcher_from_links(&release),
            engine: engine_from_links(&release),
            ..PluginManifest::default()
        },
    };

    Ok(catalog_from_manifest(cfg, release.tag_name, manifest))
}

/// Derive launcher builds from a release's raw asset links, for a release with
/// no manifest. The publish job names those links `project-x-launcher-<platform>`.
fn launcher_from_links(release: &Release) -> Vec<LauncherDownload> {
    release
        .assets
        .iter()
        .filter_map(|link| {
            let platform = link.name.strip_prefix("project-x-launcher-")?;
            // The link name carries the archive/executable extension; the
            // platform is what is left of it.
            let platform = [".tar.gz", ".zip", ".exe"]
                .iter()
                .find_map(|ext| platform.strip_suffix(ext))
                .unwrap_or(platform);
            Some(LauncherDownload {
                platform: platform.to_string(),
                file: link.name.clone(),
                url: link.url.clone(),
                sha256: String::new(),
                size: 0,
                label: String::new(),
            })
        })
        .collect()
}

/// Derive the engine jar from a release's raw asset links, for a release with no
/// manifest. Unversioned and unchecksummed, so it installs but cannot be
/// compared against what is already on disk.
fn engine_from_links(release: &Release) -> Option<ReleaseArtifact> {
    let link = release
        .assets
        .iter()
        .find(|l| l.name == ENGINE_LINK)?;
    let version = release.tag_name.trim_start_matches('v').to_string();
    Some(ReleaseArtifact {
        file: format!("projectx-engine-{}.jar", version),
        version,
        url: link.url.clone(),
        sha256: String::new(),
        size: 0,
        platform: String::new(),
    })
}

/// Derive channel entries from a release's raw asset links, for releases that
/// carry no manifest. No checksum is available, so installs from these are
/// downloaded and written unverified.
fn plugins_from_links(release: &Release) -> Vec<ManifestPlugin> {
    let version = release.tag_name.trim_start_matches('v').to_string();
    CHANNELS
        .into_iter()
        .filter_map(|channel| {
            let link = release
                .assets
                .iter()
                .find(|l| l.name == channel.jar_link())?;
            Some(ManifestPlugin {
                id: channel.id().to_string(),
                name: channel.label().to_string(),
                description: channel.blurb().to_string(),
                version: version.clone(),
                file: format!("{}-{}.jar", channel.jar_prefix(), version),
                url: link.url.clone(),
                sha256: String::new(),
                size: 0,
            })
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Local state
// ---------------------------------------------------------------------------

/// One managed jar the launcher put in the scripts directory. Recorded so a
/// later install knows what to replace and the UI can tell a managed jar apart
/// from one the user built.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledPlugin {
    pub id: String,
    pub version: String,
    pub file: String,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub installed_at: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct InstalledPlugins {
    #[serde(default)]
    pub entries: Vec<InstalledPlugin>,
}

impl InstalledPlugins {
    fn get(&self, channel: PluginChannel) -> Option<&InstalledPlugin> {
        self.entries.iter().find(|e| e.id == channel.id())
    }

    fn set(&mut self, entry: InstalledPlugin) {
        self.entries.retain(|e| e.id != entry.id);
        self.entries.push(entry);
    }

    fn clear(&mut self, channel: PluginChannel) {
        self.entries.retain(|e| e.id != channel.id());
    }
}

/// Where the injected engine scans for script jars.
///
/// The engine reads `user.home`, which the JVM resolves from the account's home
/// directory — not from the `HOME` the launcher redirects for the client
/// process — so this must be the real home, exactly as the Gradle
/// `copyJarToProjectXScripts` task uses it.
pub fn scripts_dir() -> Result<PathBuf> {
    #[cfg(test)]
    if let Some(home) = tests::HOME_OVERRIDE.lock().unwrap_or_else(|e| e.into_inner()).clone() {
        let dir = home.join(".projectx").join("scripts");
        fs::create_dir_all(&dir)?;
        return Ok(dir);
    }
    let home = BaseDirs::new()
        .map(|dirs| dirs.home_dir().to_path_buf())
        .context("Could not determine the home directory")?;
    let dir = home.join(".projectx").join("scripts");
    fs::create_dir_all(&dir)
        .with_context(|| format!("Failed to create {}", dir.display()))?;
    Ok(dir)
}

pub fn state_file(config_dir: &Path) -> PathBuf {
    config_dir.join("plugins.json")
}

pub fn load_state(config_dir: &Path) -> InstalledPlugins {
    match fs::read_to_string(state_file(config_dir)) {
        Ok(contents) => serde_json::from_str(&contents).unwrap_or_else(|e| {
            log::warn!("Ignoring unreadable plugins.json: {}", e);
            InstalledPlugins::default()
        }),
        Err(_) => InstalledPlugins::default(),
    }
}

fn save_state(config_dir: &Path, state: &InstalledPlugins) -> Result<()> {
    let json = serde_json::to_string_pretty(state)?;
    fs::write(state_file(config_dir), json)
        .with_context(|| format!("Failed to write {}", state_file(config_dir).display()))
}

/// Every jar in the scripts directory that belongs to `channel`, newest name
/// first. Used both to spot a user's own build and to clear stale copies.
fn channel_jars(dir: &Path, channel: PluginChannel) -> Vec<String> {
    let prefix = channel.jar_prefix();
    let mut found: Vec<String> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().to_string();
            let is_channel_jar = name.ends_with(".jar")
                && (name.starts_with(&format!("{}-", prefix))
                    || name == format!("{}.jar", prefix));
            is_channel_jar.then_some(name)
        })
        .collect();
    found.sort();
    found
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

/// One row of the Plugins tab.
#[derive(Debug, Clone, Serialize)]
pub struct PluginStatus {
    pub id: String,
    pub name: String,
    pub description: String,
    pub auto_update: bool,
    pub installed_version: Option<String>,
    pub installed_file: Option<String>,
    pub available_version: Option<String>,
    pub available_size: u64,
    pub update_available: bool,
    /// A jar for this channel that the launcher did not install — almost always
    /// the user's own Gradle build. Surfaced because installing would replace it.
    pub local_jar: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct PluginsSnapshot {
    pub scripts_dir: String,
    pub source_url: String,
    pub release_tag: Option<String>,
    pub release_url: Option<String>,
    /// Unix seconds of the last successful catalog fetch.
    pub checked_at: Option<u64>,
    pub error: Option<String>,
    pub plugins: Vec<PluginStatus>,
    /// Launcher builds published by the tracked release, for handing to someone
    /// who does not have the launcher yet.
    pub launcher: Vec<LauncherDownload>,
    /// The version of the launcher that is running.
    pub launcher_version: String,
    /// This platform's build, present only when the release is newer than what
    /// is running. The launcher does not replace itself — an executable cannot
    /// overwrite itself while it runs, and doing it behind the user's back is
    /// not what a download link is for — so this is a prompt, not an install.
    pub launcher_update: Option<LauncherDownload>,
}

fn unix_seconds(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn auto_update_enabled(cfg: &PluginsConfig, channel: PluginChannel) -> bool {
    match channel {
        PluginChannel::Official => cfg.official_auto_update,
        PluginChannel::Community => cfg.community_auto_update,
    }
}

pub fn set_auto_update(cfg: &mut PluginsConfig, channel: PluginChannel, enabled: bool) {
    match channel {
        PluginChannel::Official => cfg.official_auto_update = enabled,
        PluginChannel::Community => cfg.community_auto_update = enabled,
    }
}

/// Build what the tab renders from the persisted install state, the actual
/// contents of the scripts directory, and whatever catalog is currently known.
pub fn snapshot(
    config_dir: &Path,
    cfg: &PluginsConfig,
    catalog: Option<&Catalog>,
    error: Option<String>,
) -> PluginsSnapshot {
    let dir = scripts_dir();
    let state = load_state(config_dir);

    let plugins = CHANNELS
        .into_iter()
        .map(|channel| {
            let remote = catalog.and_then(|c| c.plugin(channel));
            let installed = state.get(channel).filter(|entry| {
                // State that names a jar which is no longer on disk is stale —
                // the user deleted it, or wiped the scripts directory.
                dir.as_ref()
                    .map(|d| d.join(&entry.file).is_file())
                    .unwrap_or(false)
            });

            let local_jar = dir
                .as_ref()
                .ok()
                .and_then(|d| {
                    channel_jars(d, channel)
                        .into_iter()
                        .find(|name| Some(name.as_str()) != installed.map(|i| i.file.as_str()))
                });

            let update_available = match (installed, remote) {
                (Some(i), Some(r)) => i.version != r.version,
                (None, Some(_)) => false,
                _ => false,
            };

            PluginStatus {
                id: channel.id().to_string(),
                name: remote.map(|r| r.name.clone()).unwrap_or_else(|| channel.label().to_string()),
                description: remote
                    .map(|r| r.description.clone())
                    .filter(|d| !d.is_empty())
                    .unwrap_or_else(|| channel.blurb().to_string()),
                auto_update: auto_update_enabled(cfg, channel),
                installed_version: installed.map(|i| i.version.clone()),
                installed_file: installed.map(|i| i.file.clone()),
                available_version: remote.map(|r| r.version.clone()),
                available_size: remote.map(|r| r.size).unwrap_or(0),
                update_available,
                local_jar,
            }
        })
        .collect();

    PluginsSnapshot {
        scripts_dir: dir
            .as_ref()
            .map(|d| d.display().to_string())
            .unwrap_or_else(|e| e.to_string()),
        source_url: source_url(cfg),
        release_tag: catalog.map(|c| c.release_tag.clone()),
        release_url: catalog.map(|c| c.release_url.clone()),
        checked_at: catalog.map(|c| unix_seconds(c.fetched_at)),
        error,
        plugins,
        launcher: catalog.map(|c| c.launcher.clone()).unwrap_or_default(),
        launcher_version: crate::VERSION.to_string(),
        launcher_update: catalog.and_then(launcher_update).cloned(),
    }
}

// ---------------------------------------------------------------------------
// Install / remove
// ---------------------------------------------------------------------------

fn hex_digest(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

/// Download `plugin` and make it the only jar for its channel in the scripts
/// directory.
///
/// Replacing rather than adding is the whole point: two jars for one channel put
/// two copies of every script class on the engine's scan path, so the engine
/// would list each script twice and run whichever it saw last.
pub async fn install(
    client: &reqwest::Client,
    config_dir: &Path,
    channel: PluginChannel,
    plugin: &ManifestPlugin,
) -> Result<InstalledPlugin> {
    let dir = scripts_dir()?;

    let response = client
        .get(&plugin.url)
        .timeout(HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("Failed to download {}", plugin.file))?;
    if !response.status().is_success() {
        bail!("Download of {} returned {}", plugin.file, response.status());
    }
    let bytes = response
        .bytes()
        .await
        .with_context(|| format!("Failed to read {}", plugin.file))?;

    if !plugin.sha256.is_empty() {
        let actual = hex_digest(&bytes);
        if !actual.eq_ignore_ascii_case(&plugin.sha256) {
            bail!(
                "Checksum mismatch for {} (expected {}, got {})",
                plugin.file,
                plugin.sha256,
                actual
            );
        }
    } else {
        log::warn!(
            "{} was published without a checksum; installing unverified",
            plugin.file
        );
    }

    // Write beside the target so the rename is atomic on the same filesystem: a
    // half-written jar in the scan path would break every script, not just this
    // channel's.
    let target = dir.join(&plugin.file);
    let temp = dir.join(format!(".{}.part", plugin.file));
    fs::write(&temp, &bytes)
        .with_context(|| format!("Failed to write {}", temp.display()))?;
    if let Err(e) = fs::rename(&temp, &target) {
        let _ = fs::remove_file(&temp);
        // Windows refuses to replace a jar another process has open. Engines before the script
        // loader copied its jars held every scanned jar open for the life of the client.
        if e.kind() == ErrorKind::PermissionDenied {
            return Err(anyhow!(
                "Could not update {}: a running client is still using it. Close the RuneScape clients \
                 (or update their engine) and try again.",
                plugin.file
            ));
        }
        return Err(anyhow!("Failed to install {}: {}", target.display(), e));
    }

    for stale in channel_jars(&dir, channel) {
        if stale == plugin.file {
            continue;
        }
        match fs::remove_file(dir.join(&stale)) {
            Ok(()) => log::info!("Removed superseded {}", stale),
            Err(e) => log::warn!("Could not remove superseded {}: {}", stale, e),
        }
    }

    let entry = InstalledPlugin {
        id: channel.id().to_string(),
        version: plugin.version.clone(),
        file: plugin.file.clone(),
        sha256: plugin.sha256.clone(),
        installed_at: unix_seconds(SystemTime::now()),
    };

    let mut state = load_state(config_dir);
    state.set(entry.clone());
    save_state(config_dir, &state)?;

    Ok(entry)
}

/// Delete the managed jar for `channel` and forget it. A jar the user built is
/// left alone — the launcher only removes what it installed.
pub fn remove(config_dir: &Path, channel: PluginChannel) -> Result<String> {
    let mut state = load_state(config_dir);
    let entry = state
        .get(channel)
        .cloned()
        .ok_or_else(|| anyhow!("{} is not installed by the launcher", channel.label()))?;

    let dir = scripts_dir()?;
    let path = dir.join(&entry.file);
    match fs::remove_file(&path) {
        Ok(()) => {}
        Err(e) if e.kind() == ErrorKind::NotFound => {}
        Err(e) => bail!("Failed to remove {}: {}", path.display(), e),
    }

    state.clear(channel);
    save_state(config_dir, &state)?;
    Ok(format!("Removed {}", entry.file))
}

/// Whether an install would change anything: no managed jar yet, or one at a
/// different version than the catalog offers.
pub fn needs_install(config_dir: &Path, channel: PluginChannel, plugin: &ManifestPlugin) -> bool {
    let state = load_state(config_dir);
    let Some(entry) = state.get(channel) else {
        return true;
    };
    if entry.version != plugin.version {
        return true;
    }
    scripts_dir()
        .map(|dir| !dir.join(&entry.file).is_file())
        .unwrap_or(false)
}

/// Hand a web link to the desktop browser.
///
/// The scheme is checked rather than trusted: the URLs come from a downloaded
/// manifest, and `file:`/`javascript:` reaching a system opener would turn a
/// compromised manifest into local code execution.
pub fn open_url(url: &str) -> Result<()> {
    let parsed = Url::parse(url).with_context(|| format!("Not a URL: {}", url))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        bail!("Refusing to open a {} link", parsed.scheme());
    }
    open_with_desktop(parsed.as_str())
}

/// Reveal the scripts folder in the desktop file manager, so a user who prefers
/// to drop in their own build can get there without typing the path.
pub fn open_scripts_dir() -> Result<()> {
    let dir = scripts_dir()?;
    open_with_desktop(&dir.to_string_lossy())
}

/// Hand a path or URL to whatever the desktop uses to open things.
///
/// Windows goes through the shell's own "open" verb rather than `explorer.exe`: Explorer
/// mis-parses a URL with a query string and opens a File Explorer window instead of the
/// browser.
#[cfg(target_os = "windows")]
fn open_with_desktop(target: &str) -> Result<()> {
    use windows_sys::Win32::UI::Shell::ShellExecuteW;
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }
    let verb = wide("open");
    let file = wide(target);
    // SAFETY: both strings are NUL-terminated UTF-16 buffers that outlive the call; the
    // window handle, parameters and directory are optional and passed as null.
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            file.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    };
    // ShellExecuteW reports success as a value greater than 32.
    if (result as isize) <= 32 {
        bail!("Windows could not open {} (error {})", target, result as isize);
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn open_with_desktop(target: &str) -> Result<()> {
    run_opener(&["open"], target)
}

/// `xdg-open` covers nearly every Linux desktop; `gio open` is the fallback for minimal
/// installs that ship GLib but not xdg-utils.
#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn open_with_desktop(target: &str) -> Result<()> {
    run_opener(&["xdg-open"], target).or_else(|first| {
        run_opener(&["gio", "open"], target).map_err(|_| first)
    })
}

/// Run an opener and wait for it on a background thread, so it is reaped rather than
/// left as a zombie. The opener hands off to the browser or file manager and exits
/// straight away, so this does not keep anything alive.
#[cfg(not(target_os = "windows"))]
fn run_opener(command: &[&str], target: &str) -> Result<()> {
    use std::process::{Command, Stdio};

    let mut child = Command::new(command[0])
        .args(&command[1..])
        .arg(target)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("Failed to run {} {}", command.join(" "), target))?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}

#[cfg(test)]
// The home-redirect guard is deliberately held across the awaits in a test: it is
// what stops two tests from racing on the process-wide home directory.
#[allow(clippy::await_holding_lock)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicU16, Ordering};
    use std::sync::{Mutex, MutexGuard};

    /// Serves the three endpoints a catalog fetch and install touch, then stops.
    /// Hand-rolled rather than mocked so the test exercises the real HTTP client
    /// and the real GitLab URL shapes.
    struct FakeForge {
        port: u16,
    }

    impl FakeForge {
        /// `manifest` may contain `{PORT}`, substituted with the port the
        /// listener actually got — the manifest has to point back at this same
        /// server for the jar download, and the port is only known after bind.
        fn start(manifest: &str, jar: Vec<u8>, requests: u16) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let port = listener.local_addr().unwrap().port();
            let manifest = manifest.replace("{PORT}", &port.to_string());
            let served = AtomicU16::new(0);

            std::thread::spawn(move || {
                for stream in listener.incoming() {
                    let Ok(stream) = stream else { break };
                    Self::respond(stream, port, &manifest, &jar);
                    if served.fetch_add(1, Ordering::SeqCst) + 1 >= requests {
                        break;
                    }
                }
            });

            FakeForge { port }
        }

        fn respond(mut stream: TcpStream, port: u16, manifest: &str, jar: &[u8]) {
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut request_line = String::new();
            if reader.read_line(&mut request_line).is_err() {
                return;
            }
            // Drain headers so the client is not left writing into a closed pipe.
            let mut header = String::new();
            while reader.read_line(&mut header).map(|n| n > 2).unwrap_or(false) {
                header.clear();
            }

            let path = request_line.split_whitespace().nth(1).unwrap_or("").to_string();
            let (content_type, body): (&str, Vec<u8>) = if path.contains("/releases/latest") {
                let release = format!(
                    r#"{{"tag_name":"v1.2.3","assets":[
                        {{"name":"plugins-manifest.json","browser_download_url":"http://127.0.0.1:{port}/manifest"}}
                    ]}}"#
                );
                ("application/json", release.into_bytes())
            } else if path.ends_with("/manifest") {
                ("application/json", manifest.as_bytes().to_vec())
            } else {
                ("application/java-archive", jar.to_vec())
            };

            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                content_type,
                body.len()
            );
            let _ = stream.write_all(head.as_bytes());
            let _ = stream.write_all(&body);
            let _ = stream.flush();
        }

        fn config(&self) -> PluginsConfig {
            PluginsConfig {
                api_host: Some(format!("http://127.0.0.1:{}", self.port)),
                project_path: Some("acme/engine".to_string()),
                ..PluginsConfig::default()
            }
        }
    }

    fn manifest_for(jar: &[u8], sha: &str) -> String {
        format!(
            r#"{{"schema":1,"version":"1.2.3","plugins":[
                {{"id":"official","name":"Official Scripts","description":"first party",
                  "version":"1.2.3","file":"official-scripts-1.2.3.jar",
                  "url":"http://127.0.0.1:{{PORT}}/jar","sha256":"{sha}","size":{size}}}
            ]}}"#,
            size = jar.len()
        )
    }

    /// `scripts_dir` resolves against the process-wide home directory, so a test
    /// that redirects it must not overlap another one doing the same.
    static HOME_REDIRECT: Mutex<()> = Mutex::new(());

    /// Where `scripts_dir` resolves while a test holds [`HOME_REDIRECT`]. Setting `HOME` alone
    /// is not enough: on Windows the home directory comes from the user profile, so tests
    /// would install into, and delete from, the developer's real scripts folder.
    pub(super) static HOME_OVERRIDE: Mutex<Option<PathBuf>> = Mutex::new(None);

    /// Point the home directory at a scratch dir for the duration of a test and
    /// hand back that dir plus a config dir inside it.
    fn temp_home(label: &str) -> (MutexGuard<'static, ()>, PathBuf, PathBuf) {
        let guard = HOME_REDIRECT.lock().unwrap_or_else(|e| e.into_inner());
        let home = std::env::temp_dir().join(format!("projectx-plugins-test-{}", label));
        let _ = fs::remove_dir_all(&home);
        let config_dir = home.join("config");
        fs::create_dir_all(&config_dir).unwrap();
        std::env::set_var("HOME", &home);
        *HOME_OVERRIDE.lock().unwrap_or_else(|e| e.into_inner()) = Some(home.clone());
        (guard, home, config_dir)
    }

    #[tokio::test]
    async fn installs_verifies_and_replaces_the_channel_jar() {
        let jar = b"PK\x03\x04 pretend jar".to_vec();
        let sha = hex_digest(&jar);
        let (_home_guard, _home, config_dir) = temp_home("install");

        // Two channel probes, then the engine release lookup, its manifest and
        // the jar download.
        let server = FakeForge::start(&manifest_for(&jar, &sha), jar.clone(), 5);
        let cfg = server.config();

        let client = reqwest::Client::new();
        let catalog = fetch_catalog(&client, &cfg).await.expect("catalog");
        assert_eq!(catalog.release_tag, "v1.2.3");

        let plugin = catalog.plugin(PluginChannel::Official).expect("official entry");
        assert_eq!(plugin.version, "1.2.3");

        // A jar the user built earlier must not survive the install: two jars for
        // one channel put every script class on the scan path twice.
        let scripts = scripts_dir().unwrap();
        fs::write(scripts.join("official-scripts-0.9.0.jar"), b"stale").unwrap();

        let entry = install(&client, &config_dir, PluginChannel::Official, plugin)
            .await
            .expect("install");
        assert_eq!(entry.version, "1.2.3");
        assert!(scripts.join("official-scripts-1.2.3.jar").is_file());
        assert!(!scripts.join("official-scripts-0.9.0.jar").exists());

        let snapshot = snapshot(&config_dir, &cfg, Some(&catalog), None);
        let official = snapshot.plugins.iter().find(|p| p.id == "official").unwrap();
        assert_eq!(official.installed_version.as_deref(), Some("1.2.3"));
        assert!(!official.update_available);
        assert_eq!(official.local_jar, None);
        assert!(!needs_install(&config_dir, PluginChannel::Official, plugin));

        remove(&config_dir, PluginChannel::Official).expect("remove");
        assert!(!scripts.join("official-scripts-1.2.3.jar").exists());
        assert!(needs_install(&config_dir, PluginChannel::Official, plugin));
    }

    #[tokio::test]
    async fn rejects_a_jar_whose_checksum_does_not_match() {
        let jar = b"PK\x03\x04 pretend jar".to_vec();
        let (_home_guard, _home, config_dir) = temp_home("checksum");

        let server = FakeForge::start("", jar.clone(), 3);
        let plugin = ManifestPlugin {
            id: "official".into(),
            name: "Official Scripts".into(),
            description: String::new(),
            version: "1.2.3".into(),
            file: "official-scripts-1.2.3.jar".into(),
            url: format!("http://127.0.0.1:{}/jar", server.port),
            sha256: "00".repeat(32),
            size: jar.len() as u64,
        };

        let error = install(&reqwest::Client::new(), &config_dir, PluginChannel::Official, &plugin)
            .await
            .expect_err("checksum mismatch must fail the install");
        assert!(format!("{}", error).contains("Checksum mismatch"));
        assert!(!scripts_dir().unwrap().join(&plugin.file).exists());
    }

    /// The engine home's three artifacts move as one release, so the manifest
    /// has to carry all three — a bootstrap the launcher cannot see is a
    /// bootstrap it can never update past.
    #[test]
    fn a_manifest_carries_the_whole_engine_home_not_just_the_jar() {
        let manifest: PluginManifest = serde_json::from_str(
            r#"{"schema":1,"version":"3.2.0","plugins":[],"launcher":[],
                "engine":{"file":"projectx-engine-3.2.0.jar","version":"3.2.0",
                          "url":"https://example/e","sha256":"aa","size":1},
                "supervisor":{"file":"projectx-supervisor-3.2.0.jar","version":"3.2.0",
                              "url":"https://example/s","sha256":"bb","size":2},
                "bootstrap":[
                  {"platform":"linux-x86_64","file":"libprojectxbootstrap-3.2.0.so",
                   "version":"3.2.0","url":"https://example/l","sha256":"cc","size":3},
                  {"platform":"windows-x86_64","file":"projectxbootstrap-3.2.0.dll",
                   "version":"3.2.0","url":"https://example/w","sha256":"dd","size":4}]}"#,
        )
        .expect("manifest");

        let catalog = Catalog {
            release_tag: "v3.2.0".into(),
            release_url: String::new(),
            plugins: manifest.plugins,
            launcher: manifest.launcher,
            engine: manifest.engine,
            supervisor: manifest.supervisor,
            bootstrap: manifest.bootstrap,
            fetched_at: SystemTime::now(),
        };

        assert_eq!(catalog.supervisor.as_ref().expect("supervisor").sha256, "bb");
        assert_eq!(catalog.bootstrap_for("linux-x86_64").expect("linux").sha256, "cc");
        assert_eq!(catalog.bootstrap_for("windows-x86_64").expect("windows").sha256, "dd");
        assert!(catalog.bootstrap_for("macos-universal").is_none());
    }

    #[test]
    fn the_latest_release_redirect_names_its_tag() {
        assert_eq!(
            tag_from_release_location("https://github.com/iEasyScript/community-scripts/releases/tag/v1.2.0"),
            Some("v1.2.0".to_string())
        );
        assert_eq!(tag_from_release_location("/iEasyScript/launcher/releases/tag/v1.0.16/"), Some("v1.0.16".to_string()));
        assert_eq!(tag_from_release_location("https://github.com/o/r/releases/tag/v2.0.0?x=1"), Some("v2.0.0".to_string()));
        assert_eq!(tag_from_release_location("https://github.com/iEasyScript/launcher/releases"), None);
        assert_eq!(tag_from_release_location("https://github.com/o/r/releases/tag/"), None);
    }

    #[test]
    fn only_the_default_api_host_reads_releases_from_the_web() {
        let mut cfg = PluginsConfig::default();
        assert_eq!(web_host(&cfg), Some(DEFAULT_WEB_HOST));
        cfg.api_host = Some("http://127.0.0.1:9999".to_string());
        assert_eq!(web_host(&cfg), None);
    }

    #[test]
    fn only_a_newer_release_build_counts_as_an_update() {
        assert_eq!(release_version("3.2.0"), Some((3, 2, 0)));
        assert_eq!(release_version("v3.2.0"), Some((3, 2, 0)));
        assert_eq!(release_version(" 3.2.0 "), Some((3, 2, 0)));
        assert!(release_version("3.2").is_none());
        assert!(release_version("3.2.0.1").is_none());
        assert!(release_version("not-a-version").is_none());

        // A branch pipeline's stamp and a local `cargo build` must never nag: a
        // developer's own build is beside the release, not behind it.
        assert!(release_version("0.0.0-dev.12.abc1234").is_none());
        assert!(release_version(concat!(env!("CARGO_PKG_VERSION"), "-dev")).is_none());

        assert!(release_version("3.2.0") > release_version("3.1.9"));
        assert!(release_version("3.10.0") > release_version("3.9.0"));
        assert!(release_version("4.0.0") > release_version("3.99.99"));
    }

    /// A hand-cut release, or one from before the assets existed, still installs
    /// plugins — it just cannot say anything about the engine home's fixed-name
    /// files, so the launcher leaves them alone.
    #[test]
    fn a_manifest_without_the_engine_home_fields_still_parses() {
        let manifest: PluginManifest =
            serde_json::from_str(r#"{"schema":1,"version":"3.1.0","plugins":[]}"#)
                .expect("manifest");
        assert!(manifest.supervisor.is_none());
        assert!(manifest.bootstrap.is_empty());
    }

    #[test]
    fn a_jar_the_launcher_did_not_install_is_reported_as_a_local_build() {
        let (_home_guard, _home, config_dir) = temp_home("local-build");
        fs::write(
            scripts_dir().unwrap().join("community-scripts-3.0.0.jar"),
            b"built by hand",
        )
        .unwrap();

        let snapshot = snapshot(&config_dir, &PluginsConfig::default(), None, None);
        let community = snapshot.plugins.iter().find(|p| p.id == "community").unwrap();
        assert_eq!(
            community.local_jar.as_deref(),
            Some("community-scripts-3.0.0.jar")
        );
        assert_eq!(community.installed_version, None);
        assert!(!community.auto_update);
    }
}
