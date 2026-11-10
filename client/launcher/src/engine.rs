//! Provisioning of the Project X engine home — the directory the injected
//! bootstrap treats as `PROJECTX_HOME_DIR`.
//!
//! The bootstrap resolves the supervisor jar and the engine shadow jar relative
//! to the library it was loaded from, so "install the engine" means putting
//! three files in one writable directory: the native bootstrap, the supervisor
//! jar and the engine shadow jar.
//!
//! All three are tracked against the same GitLab release the Plugins tab reads,
//! and all three move together. That is the point: a script jar built against a
//! new engine API needs the engine jar that defines it, and an engine jar with a
//! new native entry point needs the bootstrap that exports it. Updating only
//! what happens to ride in the launcher download would let a user run new
//! scripts on an old bootstrap.
//!
//! The two small ones also ship beside the launcher, so a fresh bundle installs
//! without a download and an unreachable release still yields a working home.
//! The ~150 MB shadow jar is release-only, which is what keeps a launcher
//! download in the tens of megabytes.
//!
//! A source tree is left alone: when the Gradle output already holds a bootstrap
//! and an engine jar, that directory *is* the home and nothing is copied or
//! downloaded, so a rebuilt jar takes effect on the next inject.

use anyhow::{anyhow, bail, Context, Result};
use std::path::{Path, PathBuf};

use crate::config::{Paths, PluginsConfig};
use crate::plugins::{self, Catalog, ReleaseArtifact};

#[cfg(target_os = "windows")]
pub const BOOTSTRAP_NAME: &str = "projectxbootstrap.dll";
#[cfg(target_os = "macos")]
pub const BOOTSTRAP_NAME: &str = "libprojectxbootstrap.dylib";
#[cfg(all(unix, not(target_os = "macos")))]
pub const BOOTSTRAP_NAME: &str = "libprojectxbootstrap.so";

const SUPERVISOR_JAR: &str = "projectx-supervisor.jar";

/// Where the launcher provisions the engine when it is not running from a
/// source tree. Its own directory rather than the data dir root: the engine jar
/// is large enough that a user should be able to see what is taking the space.
pub fn home_dir() -> Result<PathBuf> {
    Ok(Paths::new()?.data_dir.join("engine"))
}

/// The engine home an injection would actually use, or `None` when no engine is
/// installed.
///
/// The bootstrap makes the directory it was loaded from the home the supervisor
/// resolves the jar in, so this mirrors what the injectors do — take the parent
/// of the bootstrap [`find_bootstrap`] picked — rather than assuming
/// [`home_dir`]. A source tree or `PROJECTX_HOME_DIR` therefore reads the same
/// way before a launch as it does at injection time.
pub fn resolved_home() -> Option<PathBuf> {
    find_bootstrap().and_then(|so| so.parent().map(|p| p.to_path_buf()))
}

/// The bootstrap library to inject, or `None` when the engine is not installed.
///
/// `PROJECTX_HOME_DIR` wins so a developer can point the launcher at any build.
/// Otherwise a *complete* directory — one holding an engine jar as well — is
/// preferred over one that merely has the library: the bootstrap makes the
/// directory it was loaded from the home the supervisor resolves the jar in, so
/// loading it out of an incomplete directory starts a JVM with nothing to run.
/// Within each pass the Gradle output comes first, so a source tree stays
/// authoritative over an installed copy.
pub fn find_bootstrap() -> Option<PathBuf> {
    if let Some(home) = env_home() {
        let candidate = home.join(BOOTSTRAP_NAME);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    let dirs = search_dirs();
    dirs.iter()
        .find(|dir| is_complete_home(dir))
        .or_else(|| dirs.iter().find(|dir| dir.join(BOOTSTRAP_NAME).is_file()))
        .map(|dir| dir.join(BOOTSTRAP_NAME))
}

/// Every directory that may hold an engine home, in preference order.
fn search_dirs() -> Vec<PathBuf> {
    gradle_output_dirs()
        .into_iter()
        .chain(home_dir().ok())
        .chain(bundle_dirs())
        .collect()
}

fn is_complete_home(dir: &Path) -> bool {
    dir.join(BOOTSTRAP_NAME).is_file() && installed_engine_jar(dir).is_some()
}

/// Make an engine home usable for injection, downloading what is missing or
/// superseded. Returns the directory the bootstrap will be loaded from.
///
/// One catalog fetch covers all three artifacts, so they can only ever be
/// installed from the same release — the bootstrap cannot drift behind the
/// engine jar that has to run on it.
pub async fn ensure(
    client: &reqwest::Client,
    cfg: &PluginsConfig,
    progress: &(dyn Fn(String) + Sync),
) -> Result<PathBuf> {
    if let Some(dir) = source_tree_home() {
        log::info!("Engine home resolved to the source tree at {}", dir.display());
        return Ok(dir);
    }

    let home = home_dir()?;
    std::fs::create_dir_all(&home)
        .with_context(|| format!("Failed to create {}", home.display()))?;
    discard_displaced(&home);

    let catalog = match plugins::fetch_catalog(client, cfg).await {
        Ok(catalog) => Some(catalog),
        Err(e) => {
            log::warn!("Engine update check failed ({}); using what is already installed", e);
            None
        }
    };

    // No tag means a platform the pipeline publishes no bootstrap for, which
    // leaves the bootstrap to whatever shipped beside the launcher rather than
    // installing another platform's.
    let bootstrap = catalog
        .as_ref()
        .zip(plugins::platform_tag())
        .and_then(|(catalog, platform)| catalog.bootstrap_for(platform));
    ensure_pinned(client, bootstrap, BOOTSTRAP_NAME, &home, progress).await?;
    let supervisor = catalog.as_ref().and_then(|c| c.supervisor.as_ref());
    ensure_pinned(client, supervisor, SUPERVISOR_JAR, &home, progress).await?;
    ensure_engine_jar(client, catalog.as_ref(), &home, progress).await?;

    Ok(home)
}

/// Keep a fixed-name engine-home file in step with the release.
///
/// The bootstrap and the supervisor install under the names the injector and the
/// bootstrap resolve by, so their version cannot live in the filename the way the
/// engine jar's does — the published checksum is what identifies them. Nothing to
/// compare against (a release with no manifest, or a platform the pipeline does
/// not build) falls back to the copy that shipped beside the launcher.
async fn ensure_pinned(
    client: &reqwest::Client,
    artifact: Option<&ReleaseArtifact>,
    name: &str,
    home: &Path,
    progress: &(dyn Fn(String) + Sync),
) -> Result<()> {
    let Some(artifact) = artifact.filter(|a| !a.sha256.is_empty()) else {
        return stage(name, home);
    };

    let target = home.join(name);
    if file_sha256(&target).is_some_and(|sha| sha.eq_ignore_ascii_case(&artifact.sha256)) {
        return Ok(());
    }

    // A bundle that already carries the release's own copy makes the download
    // pure waste — and is the whole install on a machine that just unpacked one.
    if let Some(bytes) = bundled_copy_of(name, &artifact.sha256) {
        log::info!("Installing {} {} from the bundle", name, artifact.version);
        return replace(&target, &bytes);
    }

    progress(format!("Updating {}...", name));
    match download_verified(client, artifact).await {
        Ok(bytes) => {
            log::info!("Installing {} {}", name, artifact.version);
            replace(&target, &bytes)
        }
        // An install that already has the file keeps running on it; only a home
        // that has nothing at all is actually broken by a failed download.
        Err(e) if target.is_file() => {
            log::warn!("Could not update {} ({}); keeping the installed one", name, e);
            Ok(())
        }
        Err(e) => {
            log::warn!("Could not download {} ({}); falling back to the bundle", name, e);
            stage(name, home)
        }
    }
}

/// A Gradle output directory that holds a complete engine — the developer case,
/// where provisioning would only get in the way of the build/inject loop.
fn source_tree_home() -> Option<PathBuf> {
    gradle_output_dirs().into_iter().find(|dir| is_complete_home(dir))
}

fn env_home() -> Option<PathBuf> {
    let home = std::env::var("PROJECTX_HOME_DIR").ok()?;
    if home.is_empty() {
        return None;
    }
    Some(PathBuf::from(home))
}

/// `client-plugin-engine/build/libs`, resolved against the working directory and
/// against every ancestor of the launcher executable (which sits in
/// `client/launcher/target/<profile>/` in a source tree).
fn gradle_output_dirs() -> Vec<PathBuf> {
    let rel = Path::new("client-plugin-engine").join("build").join("libs");
    let mut dirs = Vec::new();
    if let Ok(cwd) = std::env::current_dir() {
        dirs.push(cwd.join(&rel));
    }
    if let Ok(exe) = std::env::current_exe() {
        let mut dir = exe.parent();
        while let Some(d) = dir {
            dirs.push(d.join(&rel));
            dir = d.parent();
        }
    }
    dirs
}

/// Where a release bundle puts the pieces it ships: an `engine/` directory
/// beside the launcher, or the launcher's own directory for a flat layout.
fn bundle_dirs() -> Vec<PathBuf> {
    let Ok(exe) = std::env::current_exe() else {
        return Vec::new();
    };
    let Some(dir) = exe.parent() else {
        return Vec::new();
    };
    vec![dir.join("engine"), dir.to_path_buf()]
}

/// The engine jar in `dir`, by the rule the supervisor itself applies: a `.jar`
/// that is not the supervisor's own. Keeping the two definitions identical is
/// what makes "the launcher installed it" and "the supervisor will load it" the
/// same statement.
pub fn installed_engine_jar(dir: &Path) -> Option<PathBuf> {
    // Newest wins, not whatever the directory happens to list first. A leftover
    // jar is supposed to be removed once its replacement lands, but a Windows
    // file lock on the copy an injected client still has open defeats that - and
    // then read_dir order alone decided which engine ran, which is how an old
    // jar quietly outlived its replacement.
    engine_jars(dir)
        .into_iter()
        .max_by(|a, b| jar_version(a).cmp(&jar_version(b)))
}

/// The resource an engine jar carries once it can load `.pxe` scripts.
const ENCRYPTED_SCRIPTS_MARKER: &[u8] = b"projectx/capabilities/script-format-pxe1";

type EncryptedScriptsProbe = (PathBuf, u64, Option<std::time::SystemTime>, bool);

static ENCRYPTED_SCRIPTS_PROBE: std::sync::Mutex<Option<EncryptedScriptsProbe>> =
    std::sync::Mutex::new(None);

/// Whether the engine an injection would use can load encrypted paid scripts.
///
/// An engine older than them would find nothing it can load, so its paid scripts
/// would vanish. With no engine installed yet the answer is yes: the one
/// [`ensure`] installs is the current release.
pub fn engine_reads_encrypted_scripts() -> bool {
    let Some(jar) = resolved_home().and_then(|home| installed_engine_jar(&home)) else {
        return true;
    };
    let meta = std::fs::metadata(&jar).ok();
    let len = meta.as_ref().map(|m| m.len()).unwrap_or(0);
    let modified = meta.and_then(|m| m.modified().ok());

    let mut probe = ENCRYPTED_SCRIPTS_PROBE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((path, l, m, answer)) = probe.as_ref() {
        if *path == jar && *l == len && *m == modified {
            return *answer;
        }
    }
    let answer = jar_lists_entry(&jar, ENCRYPTED_SCRIPTS_MARKER);
    *probe = Some((jar, len, modified, answer));
    answer
}

/// Whether `name` is an entry in the jar. Only the central directory is read when
/// it can be found; the engine jar is far too large to read whole for one name.
fn jar_lists_entry(jar: &Path, name: &[u8]) -> bool {
    let haystack = central_directory(jar).or_else(|| std::fs::read(jar).ok());
    haystack.is_some_and(|bytes| bytes.windows(name.len()).any(|w| w == name))
}

fn central_directory(jar: &Path) -> Option<Vec<u8>> {
    use std::io::{Read, Seek, SeekFrom};

    const EOCD: [u8; 4] = [0x50, 0x4b, 0x05, 0x06];
    const ZIP64_LOCATOR: [u8; 4] = [0x50, 0x4b, 0x06, 0x07];
    const ZIP64_EOCD: [u8; 4] = [0x50, 0x4b, 0x06, 0x06];
    const EOCD_LEN: usize = 22;
    const MAX_COMMENT: u64 = 0xFFFF;
    const ZIP64_LOCATOR_LEN: usize = 20;

    let u32_at = |b: &[u8], i: usize| b.get(i..i + 4).map(|s| u32::from_le_bytes(s.try_into().unwrap()) as u64);
    let u64_at = |b: &[u8], i: usize| b.get(i..i + 8).map(|s| u64::from_le_bytes(s.try_into().unwrap()));

    let mut file = std::fs::File::open(jar).ok()?;
    let len = file.metadata().ok()?.len();
    let tail_len = len.min(EOCD_LEN as u64 + MAX_COMMENT + ZIP64_LOCATOR_LEN as u64);
    file.seek(SeekFrom::Start(len - tail_len)).ok()?;
    let mut tail = vec![0u8; tail_len as usize];
    file.read_exact(&mut tail).ok()?;

    let eocd = (0..=tail.len().checked_sub(EOCD_LEN)?)
        .rev()
        .find(|&i| tail[i..i + 4] == EOCD)?;
    let (mut size, mut offset) = (u32_at(&tail, eocd + 12)?, u32_at(&tail, eocd + 16)?);

    if size == 0xFFFF_FFFF || offset == 0xFFFF_FFFF {
        let locator = eocd.checked_sub(ZIP64_LOCATOR_LEN)?;
        if tail[locator..locator + 4] != ZIP64_LOCATOR {
            return None;
        }
        file.seek(SeekFrom::Start(u64_at(&tail, locator + 8)?)).ok()?;
        let mut record = [0u8; 56];
        file.read_exact(&mut record).ok()?;
        if record[..4] != ZIP64_EOCD {
            return None;
        }
        size = u64_at(&record, 40)?;
        offset = u64_at(&record, 48)?;
    }

    if offset.checked_add(size)? > len {
        return None;
    }
    file.seek(SeekFrom::Start(offset)).ok()?;
    let mut directory = vec![0u8; usize::try_from(size).ok()?];
    file.read_exact(&mut directory).ok()?;
    Some(directory)
}

/// The version in `projectx-engine-<version>.jar`, as numbers so 1.0.34 beats
/// 1.0.9. A name that carries none sorts lowest, which keeps a published jar
/// ahead of anything hand-dropped beside it.
fn jar_version(path: &Path) -> Vec<u64> {
    let Some(name) = path.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return Vec::new();
    };
    let stem = name.trim_end_matches(".jar");
    let Some(version) = stem.rsplit_once('-').map(|(_, v)| v) else {
        return Vec::new();
    };
    let parts: Vec<u64> = version.split('.').map(|p| p.parse().unwrap_or(0)).collect();
    if version.split('.').all(|p| p.parse::<u64>().is_ok()) && !parts.is_empty() {
        parts
    } else {
        Vec::new()
    }
}

fn engine_jars(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.file_name()
                .map(|n| n.to_string_lossy().to_ascii_lowercase())
                .is_some_and(|n| n.ends_with(".jar") && !n.starts_with("projectx-supervisor"))
        })
        .collect()
}

/// Copy `name` from the bundle into `home` unless the bytes already match — the
/// fallback for a file the release cannot vouch for, where the launcher's own
/// download is the only version statement available.
///
/// A file already in `home` with no bundled counterpart is left as it is — an
/// installed engine must keep working when the launcher is run from elsewhere.
fn stage(name: &str, home: &Path) -> Result<()> {
    let target = home.join(name);
    let source = bundle_dirs()
        .into_iter()
        .chain(gradle_output_dirs())
        .map(|dir| dir.join(name))
        .find(|c| c.is_file());

    let Some(source) = source else {
        if target.is_file() {
            return Ok(());
        }
        bail!(
            "{} was not found beside the launcher or in {}. Install the launcher from a \
             release bundle, or build the engine with \
             `./gradlew :client-plugin-engine:buildNativeBootstrap`.",
            name,
            home.display()
        );
    };

    let bytes = std::fs::read(&source)
        .with_context(|| format!("Failed to read {}", source.display()))?;
    if std::fs::read(&target).map(|old| old == bytes).unwrap_or(false) {
        return Ok(());
    }
    std::fs::write(&target, &bytes)
        .with_context(|| format!("Failed to write {}", target.display()))?;
    log::info!("Staged {} into {}", name, home.display());
    Ok(())
}

/// Download the engine shadow jar into `home` when the installed one is absent
/// or superseded. A release that cannot be reached is not fatal for an install
/// that already has a jar — it just cannot be updated right now.
async fn ensure_engine_jar(
    client: &reqwest::Client,
    catalog: Option<&Catalog>,
    home: &Path,
    progress: &(dyn Fn(String) + Sync),
) -> Result<()> {
    let installed = installed_engine_jar(home);

    let Some(catalog) = catalog else {
        if installed.is_some() {
            return Ok(());
        }
        bail!("Could not reach the release that publishes the engine jar");
    };

    let Some(artifact) = catalog.engine.as_ref() else {
        if installed.is_some() {
            log::warn!("The current release publishes no engine jar; keeping the installed one");
            return Ok(());
        }
        bail!("The current release publishes no engine jar, so the engine cannot be installed");
    };

    if installed
        .as_ref()
        .and_then(|p| p.file_name())
        .is_some_and(|n| n.to_string_lossy() == artifact.file)
    {
        return Ok(());
    }

    download_engine_jar(client, artifact, home, progress).await?;

    // One engine jar, always: the supervisor picks whichever it likes best out of
    // whatever is in the home, so a leftover would decide the engine version.
    for stale in engine_jars(home) {
        if stale.file_name().is_some_and(|n| n.to_string_lossy() == artifact.file) {
            continue;
        }
        match std::fs::remove_file(&stale) {
            Ok(()) => log::info!("Removed superseded {}", stale.display()),
            Err(e) => log::warn!("Could not remove superseded {}: {}", stale.display(), e),
        }
    }
    Ok(())
}

/// Stream the jar to a temp file beside its target: it is large enough that
/// holding it in memory is wasteful, and large enough that a partial download
/// left under the real name would be injected and fail confusingly.
async fn download_engine_jar(
    client: &reqwest::Client,
    artifact: &ReleaseArtifact,
    home: &Path,
    progress: &(dyn Fn(String) + Sync),
) -> Result<()> {
    use sha2::{Digest, Sha256};
    use tokio::io::AsyncWriteExt;

    let target = home.join(&artifact.file);
    let temp = home.join(format!(".{}.part", artifact.file));

    log::info!(
        "Installing engine {} ({})",
        if artifact.version.is_empty() { "jar" } else { artifact.version.as_str() },
        artifact.file
    );

    let mut response = client
        .get(&artifact.url)
        .timeout(crate::HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("Failed to download {}", artifact.file))?;
    if !response.status().is_success() {
        bail!("Download of {} returned {}", artifact.file, response.status());
    }

    let total = response.content_length().unwrap_or(artifact.size);
    let mut file = tokio::fs::File::create(&temp)
        .await
        .with_context(|| format!("Failed to create {}", temp.display()))?;
    let mut hasher = Sha256::new();
    let mut written: u64 = 0;
    let mut last_reported = 0u64;

    while let Some(chunk) = response.chunk().await.context("Engine download interrupted")? {
        hasher.update(&chunk);
        file.write_all(&chunk).await.context("Failed to write the engine jar")?;
        written += chunk.len() as u64;
        if written - last_reported >= PROGRESS_STEP_BYTES {
            last_reported = written;
            progress(format!(
                "Downloading engine... {}",
                progress_text(written, total)
            ));
        }
    }
    file.flush().await.ok();
    drop(file);

    if !artifact.sha256.is_empty() {
        let actual = hex(hasher.finalize().as_slice());
        if !actual.eq_ignore_ascii_case(&artifact.sha256) {
            let _ = std::fs::remove_file(&temp);
            bail!(
                "Checksum mismatch for {} (expected {}, got {})",
                artifact.file,
                artifact.sha256,
                actual
            );
        }
    }

    std::fs::rename(&temp, &target).map_err(|e| {
        let _ = std::fs::remove_file(&temp);
        anyhow!("Failed to install {}: {}", target.display(), e)
    })?;
    log::info!("Installed engine jar {}", target.display());
    Ok(())
}

const PROGRESS_STEP_BYTES: u64 = 8 * 1024 * 1024;

fn progress_text(written: u64, total: u64) -> String {
    let mib = |bytes: u64| bytes as f64 / (1024.0 * 1024.0);
    if total > 0 {
        format!("{:.0} MB of {:.0} MB", mib(written), mib(total))
    } else {
        format!("{:.0} MB", mib(written))
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex(Sha256::digest(bytes).as_slice())
}

fn file_sha256(path: &Path) -> Option<String> {
    std::fs::read(path).ok().map(|bytes| sha256_hex(&bytes))
}

/// A copy of `name` shipped beside the launcher (or left by a Gradle build)
/// whose contents are exactly what the release publishes.
fn bundled_copy_of(name: &str, sha256: &str) -> Option<Vec<u8>> {
    bundle_dirs()
        .into_iter()
        .chain(gradle_output_dirs())
        .filter_map(|dir| std::fs::read(dir.join(name)).ok())
        .find(|bytes| sha256_hex(bytes).eq_ignore_ascii_case(sha256))
}

async fn download_verified(
    client: &reqwest::Client,
    artifact: &ReleaseArtifact,
) -> Result<Vec<u8>> {
    let response = client
        .get(&artifact.url)
        .timeout(crate::HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("Failed to download {}", artifact.file))?;
    if !response.status().is_success() {
        bail!("Download of {} returned {}", artifact.file, response.status());
    }
    let bytes = response
        .bytes()
        .await
        .with_context(|| format!("Failed to read {}", artifact.file))?;

    let actual = sha256_hex(&bytes);
    if !actual.eq_ignore_ascii_case(&artifact.sha256) {
        bail!(
            "Checksum mismatch for {} (expected {}, got {})",
            artifact.file,
            artifact.sha256,
            actual
        );
    }
    Ok(bytes.to_vec())
}

/// Suffix for a file moved out of the way because it could not be overwritten.
const DISPLACED_SUFFIX: &str = ".superseded";

/// Write `bytes` to `target` atomically, replacing whatever is there.
///
/// Windows refuses to overwrite a DLL that is mapped into a running client, so a
/// target that will not yield is renamed aside instead: the new file lands under
/// the real name for the next inject, and the displaced one is swept on the next
/// run, once nothing holds it.
fn replace(target: &Path, bytes: &[u8]) -> Result<()> {
    let dir = target.parent().unwrap_or_else(|| Path::new("."));
    let name = target.file_name().unwrap_or_default().to_string_lossy().to_string();
    let temp = dir.join(format!(".{}.part", name));

    std::fs::write(&temp, bytes)
        .with_context(|| format!("Failed to write {}", temp.display()))?;

    if std::fs::rename(&temp, target).is_ok() {
        return Ok(());
    }

    let displaced = dir.join(format!("{}{}", name, DISPLACED_SUFFIX));
    let _ = std::fs::remove_file(&displaced);
    if let Err(e) = std::fs::rename(target, &displaced) {
        let _ = std::fs::remove_file(&temp);
        return Err(anyhow!("Failed to replace {}: {}", target.display(), e));
    }
    std::fs::rename(&temp, target).map_err(|e| {
        let _ = std::fs::rename(&displaced, target);
        let _ = std::fs::remove_file(&temp);
        anyhow!("Failed to install {}: {}", target.display(), e)
    })
}

/// Delete files an earlier update had to rename aside because they were still
/// mapped into a client.
fn discard_displaced(home: &Path) {
    let Ok(entries) = std::fs::read_dir(home) else {
        return;
    };
    for path in entries.flatten().map(|e| e.path()) {
        if path.to_string_lossy().ends_with(DISPLACED_SUFFIX) {
            let _ = std::fs::remove_file(&path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A zip of empty stored entries, each holding `content`, optionally with a zip64 end record.
    fn stored_zip(names: &[&str], content: &[u8], zip64: bool) -> Vec<u8> {
        let mut out = Vec::new();
        let mut central = Vec::new();
        for name in names {
            let offset = out.len() as u32;
            out.extend_from_slice(&[0x50, 0x4b, 0x03, 0x04, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(name.len() as u16).to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(name.as_bytes());
            out.extend_from_slice(content);

            central.extend_from_slice(&[0x50, 0x4b, 0x01, 0x02, 20, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
            central.extend_from_slice(&(content.len() as u32).to_le_bytes());
            central.extend_from_slice(&(content.len() as u32).to_le_bytes());
            central.extend_from_slice(&(name.len() as u16).to_le_bytes());
            central.extend_from_slice(&[0; 12]);
            central.extend_from_slice(&offset.to_le_bytes());
            central.extend_from_slice(name.as_bytes());
        }
        let cd_offset = out.len() as u64;
        let cd_size = central.len() as u64;
        out.extend_from_slice(&central);
        let count = names.len() as u16;
        if zip64 {
            let record_offset = out.len() as u64;
            out.extend_from_slice(&[0x50, 0x4b, 0x06, 0x06]);
            out.extend_from_slice(&44u64.to_le_bytes());
            out.extend_from_slice(&[45, 0, 45, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
            out.extend_from_slice(&(count as u64).to_le_bytes());
            out.extend_from_slice(&(count as u64).to_le_bytes());
            out.extend_from_slice(&cd_size.to_le_bytes());
            out.extend_from_slice(&cd_offset.to_le_bytes());
            out.extend_from_slice(&[0x50, 0x4b, 0x06, 0x07, 0, 0, 0, 0]);
            out.extend_from_slice(&record_offset.to_le_bytes());
            out.extend_from_slice(&1u32.to_le_bytes());
        }
        out.extend_from_slice(&[0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0]);
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&count.to_le_bytes());
        let (size, offset) = if zip64 { (u32::MAX, u32::MAX) } else { (cd_size as u32, cd_offset as u32) };
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out
    }

    fn written(label: &str, bytes: &[u8]) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("projectx-engine-marker-{}", label));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("projectx-engine-1.0.0.jar");
        std::fs::write(&jar, bytes).unwrap();
        jar
    }

    #[test]
    fn an_engine_jar_that_lists_the_marker_reads_encrypted_scripts() {
        let names = ["com/projectx/A.class", "projectx/capabilities/script-format-pxe1"];
        let jar = written("present", &stored_zip(&names, b"x", false));
        assert!(jar_lists_entry(&jar, ENCRYPTED_SCRIPTS_MARKER));
        assert!(central_directory(&jar).is_some());

        let jar = written("zip64", &stored_zip(&names, b"x", true));
        assert!(central_directory(&jar).is_some());
        assert!(jar_lists_entry(&jar, ENCRYPTED_SCRIPTS_MARKER));
    }

    #[test]
    fn an_older_engine_jar_does_not_read_encrypted_scripts() {
        let jar = written("absent", &stored_zip(&["com/projectx/A.class"], b"x", false));
        assert!(!jar_lists_entry(&jar, ENCRYPTED_SCRIPTS_MARKER));
    }

    /// The central directory is what is searched, so a name that only appears in some entry's bytes is no proof.
    #[test]
    fn the_marker_inside_an_entry_is_not_an_entry() {
        let jar = written("content", &stored_zip(&["com/projectx/A.class"], ENCRYPTED_SCRIPTS_MARKER, false));
        assert!(!jar_lists_entry(&jar, ENCRYPTED_SCRIPTS_MARKER));
    }

    #[test]
    fn picks_the_engine_jar_and_never_the_supervisor() {
        let dir = std::env::temp_dir().join("projectx-engine-jar-pick");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(SUPERVISOR_JAR), b"supervisor").unwrap();
        std::fs::write(dir.join(BOOTSTRAP_NAME), b"native").unwrap();

        assert!(installed_engine_jar(&dir).is_none());

        std::fs::write(dir.join("projectx-engine-1.2.3.jar"), b"engine").unwrap();
        let found = installed_engine_jar(&dir).expect("engine jar");
        assert_eq!(found.file_name().unwrap(), "projectx-engine-1.2.3.jar");
        assert_eq!(engine_jars(&dir).len(), 1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A superseded jar the launcher could not delete - Windows holds it open
    /// while a client is injected - must not outrank the one that replaced it,
    /// whichever order the directory happens to list them in.
    #[test]
    fn picks_the_newest_engine_jar_beside_a_leftover() {
        let dir = std::env::temp_dir().join("projectx-engine-jar-newest");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(SUPERVISOR_JAR), b"supervisor").unwrap();

        // The older one is deliberately the larger, which is what size-based
        // picking got wrong.
        std::fs::write(dir.join("projectx-engine-1.0.31.jar"), vec![b'x'; 4096]).unwrap();
        std::fs::write(dir.join("projectx-engine-1.0.34.jar"), b"small").unwrap();

        let found = installed_engine_jar(&dir).expect("engine jar");
        assert_eq!(found.file_name().unwrap(), "projectx-engine-1.0.34.jar");

        // Numbers, not text: 1.0.9 must lose to 1.0.34.
        std::fs::write(dir.join("projectx-engine-1.0.9.jar"), b"older").unwrap();
        let found = installed_engine_jar(&dir).expect("engine jar");
        assert_eq!(found.file_name().unwrap(), "projectx-engine-1.0.34.jar");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_versionless_jar_never_outranks_a_published_one() {
        let dir = std::env::temp_dir().join("projectx-engine-jar-versionless");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        std::fs::write(dir.join("engine.jar"), vec![b'x'; 8192]).unwrap();
        std::fs::write(dir.join("projectx-engine-1.0.1.jar"), b"tiny").unwrap();

        let found = installed_engine_jar(&dir).expect("engine jar");
        assert_eq!(found.file_name().unwrap(), "projectx-engine-1.0.1.jar");

        let _ = std::fs::remove_dir_all(&dir);
    }

    fn published_artifact(bytes: &[u8]) -> ReleaseArtifact {
        ReleaseArtifact {
            file: "libprojectxbootstrap-1.2.3.so".into(),
            version: "1.2.3".into(),
            // Unreachable on purpose: every case below must resolve without a
            // download, so a request escaping to the network is a failure.
            url: "http://127.0.0.1:1/never".into(),
            sha256: sha256_hex(bytes),
            size: bytes.len() as u64,
            platform: "linux-x86_64".into(),
        }
    }

    /// The bootstrap and the supervisor install under fixed names, so the
    /// published checksum — not a filename — is what decides staleness.
    #[tokio::test]
    async fn a_pinned_artifact_updates_only_when_its_checksum_differs() {
        let home = std::env::temp_dir().join("projectx-engine-pinned");
        let _ = std::fs::remove_dir_all(&home);
        std::fs::create_dir_all(&home).unwrap();

        let client = reqwest::Client::new();
        let progress = |_: String| {};
        let published = b"the release's bootstrap".to_vec();
        let artifact = published_artifact(&published);
        let target = home.join(BOOTSTRAP_NAME);

        std::fs::write(&target, &published).unwrap();
        ensure_pinned(&client, Some(&artifact), BOOTSTRAP_NAME, &home, &progress)
            .await
            .expect("an up-to-date bootstrap needs no work");
        assert_eq!(std::fs::read(&target).unwrap(), published);

        // Stale bytes with the release unreachable: the installed one is kept
        // rather than leaving the home with no bootstrap at all.
        std::fs::write(&target, b"an older bootstrap").unwrap();
        ensure_pinned(&client, Some(&artifact), BOOTSTRAP_NAME, &home, &progress)
            .await
            .expect("a failed update must not fail the launch");
        assert_eq!(std::fs::read(&target).unwrap(), b"an older bootstrap");

        let _ = std::fs::remove_dir_all(&home);
    }

    #[test]
    fn a_displaced_file_is_swept_and_never_shadows_the_real_name() {
        let home = std::env::temp_dir().join("projectx-engine-replace");
        let _ = std::fs::remove_dir_all(&home);
        std::fs::create_dir_all(&home).unwrap();

        let target = home.join(BOOTSTRAP_NAME);
        std::fs::write(&target, b"old").unwrap();
        replace(&target, b"new").unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"new");

        let displaced = home.join(format!("{}{}", BOOTSTRAP_NAME, DISPLACED_SUFFIX));
        std::fs::write(&displaced, b"left behind by a locked overwrite").unwrap();
        discard_displaced(&home);
        assert!(!displaced.exists());
        assert!(target.is_file());
        // A displaced jar must never be mistaken for the engine's own.
        assert!(installed_engine_jar(&home).is_none());

        let _ = std::fs::remove_dir_all(&home);
    }
}
