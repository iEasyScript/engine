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
    engine_jars(dir).into_iter().next()
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
