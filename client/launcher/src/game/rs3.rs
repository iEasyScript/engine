use anyhow::{anyhow, Context, Result};
use sha2::{Digest, Sha256};
use std::path::Path;
use url::Url;

use crate::game::process::host_binary_type;
use crate::game::renderer::Renderer;

// The Debian `.deb` flow below installs the Linux launcher only. Windows and
// macOS take theirs from `launcher_acq`, so every item in the flow is inert off
// Linux — kept compiled (and type-checked) rather than cfg'd out, matching how
// `launcher_acq` stays compiled but inert on Linux.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const CONTENT_URL: &str = "https://content.runescape.com/downloads/ubuntu/";
pub const DEFAULT_CONFIG_URI: &str = "https://www.runescape.com/k=5/l=0/jav_config.ws";

/// Ensure a jav_config URL selects the client binary for the host we are on.
///
/// A config server keys its client descriptor — name, CRC and signature — on
/// `binaryType`, and falls back to Linux when the parameter is absent. A Windows
/// or macOS launch that omits it therefore hands the Jagex launcher an ELF to
/// verify, which it rejects with "Error saving file" rather than anything that
/// names the real mismatch. An explicit value in a user-supplied URI wins.
///
/// On Windows the value also picks the renderer: 2 is the OpenGL client and 10
/// the Vulkan one, so `renderer` is what decides which of the two the Jagex
/// launcher downloads.
pub fn with_host_binary_type(config_uri: &str, renderer: Renderer) -> String {
    let Ok(mut url) = Url::parse(config_uri) else {
        return config_uri.to_string();
    };
    if url.query_pairs().any(|(key, _)| key == "binaryType") {
        return config_uri.to_string();
    }
    url.query_pairs_mut()
        .append_pair("binaryType", &host_binary_type(renderer).to_string());
    url.into()
}

/// Official Jagex launcher installer URLs per OS (all verified HTTP 200).
/// The Linux launcher (`rs3linux`) comes from the Debian `.deb` flow above
/// (`fetch_package_info` + `download_deb` + `deb::extract_rs3_binary`); these
/// two cover the Windows and macOS launchers for the per-OS data layout
/// `data/client/{windows,macos}/`.
const WINDOWS_SETUP_URL: &str =
    "https://content.runescape.com/downloads/windows/RuneScape-Setup.exe";
const MACOS_DMG_URL: &str = "https://content.runescape.com/downloads/osx/RuneScape.dmg";

/// Path of the launcher binary INSIDE the macOS `RuneScape.dmg` (HFS volume).
/// Extracted with `7z` and renamed to `rs3mac`.
const MACOS_DMG_LAUNCHER_INNER: &str = "RuneScape/RuneScape.app/Contents/MacOS/RuneScape";

/// The Debian package holding the Linux client launcher.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const LAUNCHER_PACKAGE: &str = "runescape-launcher";

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub struct PackageInfo {
    pub filename: String,
    pub sha256: String,
}

/// Fetch and parse the Packages file to get current RS3 client info
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub async fn fetch_package_info(client: &reqwest::Client) -> Result<PackageInfo> {
    let url = format!("{}dists/trusty/non-free/binary-amd64/Packages", CONTENT_URL);

    let resp = client
        .get(&url)
        .send()
        .await
        .context("Failed to fetch Packages file")?;

    if !resp.status().is_success() {
        return Err(anyhow!("Packages fetch failed: {}", resp.status()));
    }

    let text = resp.text().await.context("Failed to read Packages body")?;
    parse_package_info(&text, LAUNCHER_PACKAGE)
}

/// Pull one package's `Filename` + `SHA256` out of a Debian `Packages` index.
///
/// Fields are only ever read from within a single stanza. Scanning the file
/// flat would pair one package's filename with another's hash the moment the
/// index carries more than one entry, producing a download that can never
/// verify.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn parse_package_info(text: &str, package: &str) -> Result<PackageInfo> {
    let mut name = None;
    let mut filename = None;
    let mut sha256 = None;

    // A trailing empty line flushes the final stanza without duplicating the
    // match arm below.
    for line in text.lines().chain(std::iter::once("")) {
        if line.trim().is_empty() {
            if name.as_deref() == Some(package) {
                return Ok(PackageInfo {
                    filename: filename
                        .ok_or_else(|| anyhow!("No Filename in the {} stanza", package))?,
                    sha256: sha256
                        .ok_or_else(|| anyhow!("No SHA256 in the {} stanza", package))?,
                });
            }
            name = None;
            filename = None;
            sha256 = None;
            continue;
        }

        if let Some((key, value)) = line.split_once(": ") {
            let value = value.trim().to_string();
            match key {
                "Package" => name = Some(value),
                "Filename" => filename = Some(value),
                "SHA256" => sha256 = Some(value),
                _ => {}
            }
        }
    }

    Err(anyhow!("No {} stanza in the Packages index", package))
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub fn is_up_to_date(hash_path: &Path, expected_hash: &str) -> bool {
    std::fs::read_to_string(hash_path)
        .map(|stored| stored.trim() == expected_hash)
        .unwrap_or(false)
}

/// Download the .deb file. Returns `Bytes` to avoid copying the multi-MB body —
/// `Bytes` is cheaply cloneable and derefs to `&[u8]` for hashing/extraction.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub async fn download_deb(client: &reqwest::Client, filename: &str) -> Result<bytes::Bytes> {
    let url = format!("{}{}", CONTENT_URL, filename);

    log::info!("Downloading RS3 client from {}", url);

    let resp = client
        .get(&url)
        .timeout(crate::HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await
        .context("Failed to download .deb")?;

    if !resp.status().is_success() {
        return Err(anyhow!("Download failed: {}", resp.status()));
    }

    resp.bytes().await.context("Failed to read .deb bytes")
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub fn verify_hash(data: &[u8], expected: &str) -> bool {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = format!("{:x}", hasher.finalize());
    result == expected
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub fn save_hash(hash_path: &Path, hash: &str) -> Result<()> {
    std::fs::write(hash_path, hash).context("Failed to save hash file")
}

/// Fetch jav_config.ws and parse param=N=value lines into key-value pairs
pub async fn fetch_jav_config_params(
    client: &reqwest::Client,
    config_uri: &str,
) -> Result<Vec<(String, String)>> {
    let resp = client
        .get(config_uri)
        .send()
        .await
        .context("Failed to fetch jav_config.ws")?;
    let text = resp
        .text()
        .await
        .context("Failed to read jav_config.ws body")?;
    let mut params = Vec::new();
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("param=") {
            if let Some((key, value)) = rest.split_once('=') {
                params.push((key.to_string(), value.to_string()));
            }
        }
    }
    Ok(params)
}

/// jav_config param carrying the hex login RSA modulus.
pub const LOGIN_RSA_PARAM: &str = "99";
/// jav_config param carrying the hex JS5 RSA modulus.
pub const JS5_RSA_PARAM: &str = "100";

/// Extract a hex RSA modulus from the parsed jav_config params, if present.
pub fn extract_modulus(params: &[(String, String)], param: &str) -> Option<String> {
    params
        .iter()
        .find(|(k, _)| k == param)
        .map(|(_, v)| v.clone())
}

// ---------------------------------------------------------------------------
// Cross-platform Jagex launcher (`rs3*`) acquisition.
//
// Each host has its own package to unpack the launcher out of:
//   rs3windows.exe   from RuneScape-Setup.exe   (Inno Setup installer)
//   rs3mac           from RuneScape.dmg         (HFS volume / .app bundle)
//   rs3linux         from the Debian package    (see the flow above)
//
// The caller names the file it wants written, so both the launcher's own data
// dir and a `data/client/<os>/` slot in a source tree are reachable.
//
// Extraction shells out to CLI tools (no pure-Rust Inno/HFS readers exist that
// we want to vendor). The Windows launcher carries its extractor with it — see
// `BUNDLED_INNOEXTRACT` — so the only path that can fail for want of a tool is
// the macOS one, which needs 7-Zip and says so.
//
// The Windows/macOS acquisition helpers are only *reached* on their respective
// host OS (via `acquire_host_launcher`'s cfg branches). On a Linux build they
// compile (so the code is always type-checked) but are inert, so the whole
// block lives in the `launcher_acq` submodule below, which suppresses the
// dead-code lint on non-Windows/non-macOS hosts and re-exports its public API.
// ---------------------------------------------------------------------------

// `acquire_host_launcher` is the cross-platform entry point callers use; the
// per-OS `acquire_{windows,macos}_launcher` and `download_to_file` helpers stay
// internal to the submodule (reached only via the host cfg branches).
pub use launcher_acq::acquire_host_launcher;

#[cfg_attr(
    all(not(target_os = "windows"), not(target_os = "macos")),
    allow(dead_code)
)]
mod launcher_acq {
    use super::{
        MACOS_DMG_LAUNCHER_INNER, MACOS_DMG_URL, WINDOWS_SETUP_URL,
    };
    use anyhow::{anyhow, Context, Result};
    use std::path::{Path, PathBuf};

/// The extractor the Windows launcher stages next to its download.
/// `RuneScape-Setup.exe` is an Inno Setup installer, and Windows ships nothing
/// that can open one; running the installer instead is not an alternative,
/// because it asks for elevation, installs into `Program Files`, and ends by
/// starting the Jagex launcher, which begins a multi-gigabyte cache download.
/// Provenance and licence: `client/launcher/vendor/innoextract/README.md`.
#[cfg(windows)]
const BUNDLED_INNOEXTRACT: &[u8] = include_bytes!("../../vendor/innoextract/innoextract.exe");

/// Return the path to a usable `7-Zip` CLI (`7z`, then `7zz`, then `7za`), or
/// `None` if none is on PATH. `7z` reads both HFS (`.dmg`) volumes and many
/// installer formats.
fn find_7z() -> Option<String> {
    for candidate in ["7z", "7zz", "7za"] {
        if which_on_path(candidate) {
            return Some(candidate.to_string());
        }
    }
    None
}

/// Minimal `which`: returns true if `name` resolves on the current `PATH`.
fn which_on_path(name: &str) -> bool {
    let path = match std::env::var_os("PATH") {
        Some(p) => p,
        None => return false,
    };
    std::env::split_paths(&path).any(|dir| {
        let candidate = dir.join(name);
        candidate.is_file()
            || {
                // On Windows, tools usually carry a `.exe` suffix.
                #[cfg(windows)]
                {
                    dir.join(format!("{}.exe", name)).is_file()
                }
                #[cfg(not(windows))]
                {
                    false
                }
            }
    })
}

/// Download a URL to `dest`, returning an error if the status is not success.
/// Shared by the Windows/macOS installer downloads.
pub async fn download_to_file(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
) -> Result<()> {
    log::info!("Downloading {} -> {}", url, dest.display());
    let resp = client
        .get(url)
        .timeout(crate::HTTP_DOWNLOAD_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("Failed to download {}", url))?;
    if !resp.status().is_success() {
        return Err(anyhow!("Download of {} failed: {}", url, resp.status()));
    }
    let bytes = resp
        .bytes()
        .await
        .with_context(|| format!("Failed to read body of {}", url))?;
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("Failed to create {}", parent.display()))?;
    }
    std::fs::write(dest, &bytes)
        .with_context(|| format!("Failed to write {}", dest.display()))?;
    Ok(())
}

/// Acquire the **macOS** Jagex launcher into `out` (typically
/// `data/client/macos/rs3mac`).
///
/// Pipeline: download `RuneScape.dmg` → `7z e <dmg> <inner-app-binary>` →
/// move the extracted Mach-O to `out` (chmod 0755). The dmg is an HFS+ volume;
/// `7z` reads it natively, so this works on a Linux dev host as well as macOS.
///
/// Gated on `7z`/`7zz`/`7za` being on PATH; returns an actionable error if not.
pub async fn acquire_macos_launcher(client: &reqwest::Client, out: &Path) -> Result<()> {
    let seven_zip = find_7z().ok_or_else(|| {
        anyhow!(
            "Cannot extract RuneScape.dmg: no 7-Zip CLI (7z/7zz/7za) found on PATH. \
             Install p7zip (e.g. `apt install p7zip-full` / `brew install p7zip`) and retry."
        )
    })?;

    let tmp_dir = out
        .parent()
        .map(|p| p.join(".rs3mac.extract.tmp"))
        .ok_or_else(|| anyhow!("output path {} has no parent", out.display()))?;
    let dmg_path = tmp_dir.join("RuneScape.dmg");

    // Best-effort clean of any prior partial extraction.
    let _ = std::fs::remove_dir_all(&tmp_dir);
    std::fs::create_dir_all(&tmp_dir)
        .with_context(|| format!("Failed to create temp dir {}", tmp_dir.display()))?;

    download_to_file(client, MACOS_DMG_URL, &dmg_path).await?;

    // `7z e` flattens the inner path; the extracted file lands as
    // <tmp_dir>/RuneScape (the basename of MACOS_DMG_LAUNCHER_INNER).
    let status = std::process::Command::new(&seven_zip)
        .arg("e")
        .arg("-y")
        .arg(&dmg_path)
        .arg(MACOS_DMG_LAUNCHER_INNER)
        .arg(format!("-o{}", tmp_dir.display()))
        .status()
        .with_context(|| format!("Failed to run {} on RuneScape.dmg", seven_zip))?;
    if !status.success() {
        let _ = std::fs::remove_dir_all(&tmp_dir);
        return Err(anyhow!(
            "{} exited with status {} extracting the macOS launcher from the dmg",
            seven_zip,
            status
        ));
    }

    let extracted = tmp_dir.join("RuneScape");
    if !extracted.is_file() {
        let _ = std::fs::remove_dir_all(&tmp_dir);
        return Err(anyhow!(
            "Expected extracted launcher at {} not found (dmg layout changed?)",
            extracted.display()
        ));
    }

    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("Failed to create {}", parent.display()))?;
    }
    std::fs::rename(&extracted, out)
        .or_else(|_| std::fs::copy(&extracted, out).map(|_| ()))
        .with_context(|| format!("Failed to place macOS launcher at {}", out.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(out, std::fs::Permissions::from_mode(0o755));
    }
    let _ = std::fs::remove_dir_all(&tmp_dir);
    log::info!("Installed macOS launcher to {}", out.display());
    Ok(())
}

/// Acquire the **Windows** Jagex launcher into `out` (typically
/// `rs3windows.exe` in the launcher's data dir).
///
/// Pipeline: download `RuneScape-Setup.exe` (an **Inno Setup** installer) →
/// unpack it with innoextract → locate the launcher exe in the extracted `{app}`
/// tree → move it to `out`.
pub async fn acquire_windows_launcher(client: &reqwest::Client, out: &Path) -> Result<()> {
    let tmp_dir = out
        .parent()
        .map(|p| p.join(".rs3windows.extract.tmp"))
        .ok_or_else(|| anyhow!("output path {} has no parent", out.display()))?;
    let setup_path = tmp_dir.join("RuneScape-Setup.exe");

    let _ = std::fs::remove_dir_all(&tmp_dir);
    std::fs::create_dir_all(&tmp_dir)
        .with_context(|| format!("Failed to create temp dir {}", tmp_dir.display()))?;

    let innoextract = match innoextract_command(&tmp_dir) {
        Ok(path) => path,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&tmp_dir);
            return Err(e);
        }
    };

    download_to_file(client, WINDOWS_SETUP_URL, &setup_path).await?;

    // innoextract lays files out under <tmp_dir>/app/ (the Inno `{app}` dir).
    let output = run_innoextract(&innoextract, &tmp_dir, &setup_path);
    match output {
        Ok(output) if !output.status.success() => {
            let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
            let _ = std::fs::remove_dir_all(&tmp_dir);
            return Err(anyhow!(
                "innoextract exited with status {} extracting the Windows launcher{}",
                output.status,
                if stderr.is_empty() { String::new() } else { format!(": {}", stderr) }
            ));
        }
        Err(e) => {
            let _ = std::fs::remove_dir_all(&tmp_dir);
            return Err(e).context("Failed to run innoextract on RuneScape-Setup.exe");
        }
        Ok(_) => {}
    }

    // Find the launcher exe in the extracted tree. The Jagex installer ships the
    // launcher as `RuneScape.exe` (our layout calls it `rs3windows.exe`); match
    // either, case-insensitively, preferring the name we are asked to produce.
    let exe = find_windows_launcher_exe(&tmp_dir).ok_or_else(|| {
        anyhow!(
            "No launcher .exe found in the extracted Inno Setup payload under {} \
             (expected rs3windows.exe / RuneScape.exe). The installer layout may have changed.",
            tmp_dir.display()
        )
    })?;

    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("Failed to create {}", parent.display()))?;
    }
    std::fs::rename(&exe, out)
        .or_else(|_| std::fs::copy(&exe, out).map(|_| ()))
        .with_context(|| format!("Failed to place Windows launcher at {}", out.display()))?;
    let _ = std::fs::remove_dir_all(&tmp_dir);
    log::info!("Installed Windows launcher to {}", out.display());
    Ok(())
}

/// The innoextract to run: one already on `PATH` (a dev host, or a user who
/// installed it) in preference to the copy the Windows launcher carries, so a
/// newer local build wins over the bundled one.
fn innoextract_command(staging_dir: &Path) -> Result<PathBuf> {
    if which_on_path("innoextract") {
        return Ok(PathBuf::from("innoextract"));
    }
    stage_bundled_innoextract(staging_dir)
}

#[cfg(windows)]
fn stage_bundled_innoextract(staging_dir: &Path) -> Result<PathBuf> {
    let path = staging_dir.join("innoextract.exe");
    std::fs::write(&path, BUNDLED_INNOEXTRACT)
        .with_context(|| format!("Failed to stage innoextract at {}", path.display()))?;
    Ok(path)
}

#[cfg(not(windows))]
fn stage_bundled_innoextract(_staging_dir: &Path) -> Result<PathBuf> {
    Err(anyhow!(
        "Cannot extract RuneScape-Setup.exe: `innoextract` not found on PATH. \
         RuneScape-Setup.exe is an Inno Setup installer (not NSIS), so 7-Zip cannot \
         unpack its payload. Install innoextract (e.g. `apt install innoextract` / \
         `brew install innoextract`) and retry, or place rs3windows.exe beside the \
         launcher manually."
    ))
}

/// Run innoextract with its output captured — the launcher has no console to
/// stream it to, and a failure is worth logging in full.
fn run_innoextract(
    innoextract: &Path,
    out_dir: &Path,
    setup: &Path,
) -> std::io::Result<std::process::Output> {
    let mut cmd = std::process::Command::new(innoextract);
    cmd.arg("-e")
        .arg("--progress=0")
        .arg("--color=0")
        .arg("-d")
        .arg(out_dir)
        .arg(setup);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.output()
}

/// Recursively search `root` for the Windows launcher executable, preferring
/// `rs3windows.exe`, then any `*.exe` whose stem looks like the RS launcher.
fn find_windows_launcher_exe(root: &Path) -> Option<std::path::PathBuf> {
    fn walk(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
        let entries = match std::fs::read_dir(dir) {
            Ok(e) => e,
            Err(_) => return,
        };
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                walk(&p, out);
            } else if p
                .extension()
                .map(|e| e.eq_ignore_ascii_case("exe"))
                .unwrap_or(false)
            {
                out.push(p);
            }
        }
    }
    let mut exes = Vec::new();
    walk(root, &mut exes);

    // Prefer an exact rs3windows.exe match.
    if let Some(p) = exes
        .iter()
        .find(|p| matches_name(p, "rs3windows.exe"))
    {
        return Some(p.clone());
    }
    // Then a RuneScape-launcher-looking exe (not the unins*/setup helper exes).
    exes.into_iter().find(|p| {
        let name = p
            .file_name()
            .map(|n| n.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default();
        (name.contains("runescape") || name.contains("rs3"))
            && !name.starts_with("unins")
            && !name.contains("setup")
    })
}

fn matches_name(p: &Path, name: &str) -> bool {
    p.file_name()
        .map(|n| n.to_string_lossy().eq_ignore_ascii_case(name))
        .unwrap_or(false)
}

/// Acquire the Jagex launcher for the CURRENT host OS at `out`.
///
/// Every platform downloads from Jagex and unpacks: Windows from the Inno Setup
/// installer, macOS from the `.dmg`, Linux from the Debian package. Callers pass
/// the exact file they want written, so the launcher's own data dir and a
/// `data/client/<os>/` slot in a source tree are both reachable.
pub async fn acquire_host_launcher(client: &reqwest::Client, out: &Path) -> Result<()> {
    #[cfg(target_os = "windows")]
    {
        return acquire_windows_launcher(client, out).await;
    }
    #[cfg(target_os = "macos")]
    {
        return acquire_macos_launcher(client, out).await;
    }
    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    {
        return acquire_linux_launcher(client, out).await;
    }
}

/// Acquire the **Linux** Jagex launcher (`rs3linux`) into `out` from the Debian
/// package — the same flow the live-mode client update takes, reused here so a
/// data dir that never ran that update (custom mode, a fresh install) can still
/// get a launcher.
#[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
async fn acquire_linux_launcher(client: &reqwest::Client, out: &Path) -> Result<()> {
    use super::{download_deb, fetch_package_info, verify_hash};

    let info = fetch_package_info(client).await?;
    let deb = download_deb(client, &info.filename).await?;
    if !verify_hash(&deb, &info.sha256) {
        return Err(anyhow!("Hash verification failed for {}", info.filename));
    }
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("Failed to create {}", parent.display()))?;
    }
    crate::game::deb::extract_rs3_binary(deb, out)?;
    log::info!("Installed Linux launcher to {}", out.display());
    Ok(())
}

} // mod launcher_acq

#[cfg(test)]
mod tests {
    use super::*;

    const TWO_STANZAS: &str = "\
Package: some-other-package
Filename: pool/non-free/o/other/other_1.0_amd64.deb
Size: 111
SHA256: aaaa

Package: runescape-launcher
Filename: pool/non-free/r/runescape-launcher/runescape-launcher_2.2.12_amd64.deb
Size: 3600532
SHA256: bbbb
";

    #[test]
    fn picks_fields_from_the_matching_stanza_only() {
        let info = parse_package_info(TWO_STANZAS, "runescape-launcher").unwrap();
        assert!(info.filename.contains("runescape-launcher_2.2.12"));
        assert_eq!(info.sha256, "bbbb");
    }

    #[test]
    fn reads_a_final_stanza_with_no_trailing_blank_line() {
        let text = "Package: runescape-launcher\nFilename: f.deb\nSHA256: cccc";
        let info = parse_package_info(text, "runescape-launcher").unwrap();
        assert_eq!((info.filename.as_str(), info.sha256.as_str()), ("f.deb", "cccc"));
    }

    #[test]
    fn errors_when_the_package_is_absent() {
        assert!(parse_package_info(TWO_STANZAS, "not-here").is_err());
    }
}

