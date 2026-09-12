use anyhow::{anyhow, Context, Result};
use sha2::{Digest, Sha256};
use std::ffi::CString;
use std::io::Write;
use std::os::raw::{c_char, c_int, c_void};
use std::path::{Path, PathBuf};
use tar::{Archive as TarArchive, EntryType};
use xz2::read::XzDecoder;

use super::deb::data_tar_xz;

const PREFIX_DIR: &str = "native-deps";

/// A distro package whose shared libraries the NXT client needs but which modern
/// hosts no longer ship. Pinned by URL + SHA-256 so a compromised or rebuilt
/// mirror cannot substitute a different binary.
struct LegacyPackage {
    label: &'static str,
    sonames: &'static [&'static str],
    url: &'static str,
    sha256: &'static str,
}

/// `libssl1.1` is Debian bullseye-security's final 1.1.1w build; `libgtk2.0-0`
/// is bookworm's. Both are ELF-forward-compatible with newer glibc, and GTK2's
/// own dependencies (glib, pango, cairo, gdk-pixbuf, atk) are still present on
/// any host that ships GTK3, so only the GTK2 libraries themselves are needed.
const LEGACY_PACKAGES: &[LegacyPackage] = &[
    LegacyPackage {
        label: "openssl 1.1",
        sonames: &["libssl.so.1.1", "libcrypto.so.1.1"],
        url: "https://security.debian.org/debian-security/pool/updates/main/o/openssl/libssl1.1_1.1.1w-0+deb11u8_amd64.deb",
        sha256: "dcc68a543de6cb955a57077b66dcdb15f61d1e31e072f2c6cc4082c37da1b00d",
    },
    LegacyPackage {
        label: "gtk2",
        sonames: &["libgtk-x11-2.0.so.0", "libgdk-x11-2.0.so.0"],
        url: "http://deb.debian.org/debian/pool/main/g/gtk%2B2.0/libgtk2.0-0_2.24.33-2+deb12u1_amd64.deb",
        sha256: "d1e9a26a5961748f220c5989c89516fead2a5054b80914f94c71c3fee6fdebe3",
    },
];

extern "C" {
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlclose(handle: *mut c_void) -> c_int;
}

const RTLD_LAZY: c_int = 1;
const RTLD_LOCAL: c_int = 0;

pub fn prefix(data_dir: &Path) -> PathBuf {
    data_dir.join(PREFIX_DIR)
}

/// The prefix, but only once it actually holds libraries — so a host that never
/// needed provisioning does not get an empty directory on its library path.
pub fn provisioned_prefix(data_dir: &Path) -> Option<PathBuf> {
    let dir = prefix(data_dir);
    let has_libs = std::fs::read_dir(&dir)
        .map(|mut entries| entries.any(|e| e.is_ok()))
        .unwrap_or(false);
    has_libs.then_some(dir)
}

/// Ask the real dynamic linker whether a soname resolves against the host's
/// search path. Cheaper and far more accurate than guessing at library
/// directories or parsing `ldconfig` output.
fn host_provides(soname: &str) -> bool {
    let Ok(name) = CString::new(soname) else {
        return false;
    };
    unsafe {
        let handle = dlopen(name.as_ptr(), RTLD_LAZY | RTLD_LOCAL);
        if handle.is_null() {
            false
        } else {
            dlclose(handle);
            true
        }
    }
}

fn already_staged(dir: &Path, sonames: &[&str]) -> bool {
    sonames.iter().all(|s| dir.join(s).exists())
}

/// Download, verify and unpack every legacy library the host is missing into a
/// launcher-owned prefix. Nothing is installed system-wide: the libraries are
/// only ever reachable through the `LD_LIBRARY_PATH` the launcher sets on the
/// client it spawns, which matters because OpenSSL 1.1 is end-of-life and must
/// not become the system default for anything else.
pub async fn ensure(client: &reqwest::Client, data_dir: &Path) -> Result<()> {
    let dir = prefix(data_dir);

    for package in LEGACY_PACKAGES {
        if already_staged(&dir, package.sonames) {
            continue;
        }
        if package.sonames.iter().all(|s| host_provides(s)) {
            log::info!("{} present on host; not provisioning", package.label);
            continue;
        }

        log::info!("{} missing; provisioning from {}", package.label, package.url);
        let bytes = client
            .get(package.url)
            .send()
            .await
            .with_context(|| format!("Failed to request {}", package.url))?
            .error_for_status()
            .with_context(|| format!("Bad status for {}", package.url))?
            .bytes()
            .await
            .with_context(|| format!("Failed to download {}", package.url))?;

        let digest = hex(&Sha256::digest(&bytes));
        if digest != package.sha256 {
            return Err(anyhow!(
                "{} checksum mismatch: expected {}, got {}",
                package.label,
                package.sha256,
                digest
            ));
        }

        let staging_dir = dir.clone();
        let sonames = package.sonames;
        let label = package.label;
        tokio::task::spawn_blocking(move || stage_libraries(&bytes, &staging_dir, sonames))
            .await?
            .with_context(|| format!("Failed to unpack {}", label))?;

        log::info!("{} staged into {}", package.label, dir.display());
    }

    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn matches_soname(file_name: &str, sonames: &[&str]) -> bool {
    sonames.iter().any(|s| file_name.starts_with(s))
}

/// Flatten the matching libraries out of the package's data tarball. Versioned
/// files are written directly; the sonames themselves are usually symlinks to
/// them, so those are recreated pointing at the basename within this prefix.
fn stage_libraries(deb_bytes: &[u8], dir: &Path, sonames: &[&str]) -> Result<()> {
    std::fs::create_dir_all(dir).context("Failed to create native-deps prefix")?;

    let mut tar = TarArchive::new(XzDecoder::new(std::io::Cursor::new(data_tar_xz(
        deb_bytes,
    )?)));
    let mut links: Vec<(String, String)> = Vec::new();
    let mut staged = 0usize;

    for entry in tar.entries().context("Failed to read tar entries")? {
        let mut entry = entry.context("Failed to read tar entry")?;
        let path = entry.path().context("Failed to read tar entry path")?;
        let Some(file_name) = path.file_name().map(|n| n.to_string_lossy().to_string()) else {
            continue;
        };
        if !matches_soname(&file_name, sonames) {
            continue;
        }

        match entry.header().entry_type() {
            EntryType::Symlink => {
                if let Some(target) = entry.link_name().ok().flatten() {
                    if let Some(base) = target.file_name() {
                        links.push((file_name, base.to_string_lossy().to_string()));
                    }
                }
            }
            EntryType::Regular => {
                write_library(&mut entry, &dir.join(&file_name))?;
                staged += 1;
            }
            _ => {}
        }
    }

    for (link, target) in links {
        let link_path = dir.join(&link);
        if link_path.exists() {
            continue;
        }
        if dir.join(&target).exists() {
            std::os::unix::fs::symlink(&target, &link_path)
                .with_context(|| format!("Failed to link {} -> {}", link, target))?;
        }
    }

    if staged == 0 {
        return Err(anyhow!("No matching libraries found in package"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_provides_agrees_with_the_loader() {
        assert!(host_provides("libc.so.6"));
        assert!(!host_provides("libdefinitely-not-a-real-library.so.99"));
    }

    #[test]
    fn provisioned_prefix_requires_contents() {
        let base = std::env::temp_dir().join(format!("nativedeps-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        assert!(provisioned_prefix(&base).is_none());

        std::fs::create_dir_all(prefix(&base)).unwrap();
        assert!(provisioned_prefix(&base).is_none());

        std::fs::write(prefix(&base).join("libssl.so.1.1"), b"x").unwrap();
        assert_eq!(provisioned_prefix(&base), Some(prefix(&base)));

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn soname_match_covers_versioned_files() {
        let sonames = &["libgtk-x11-2.0.so.0"];
        assert!(matches_soname("libgtk-x11-2.0.so.0", sonames));
        assert!(matches_soname("libgtk-x11-2.0.so.0.2400.33", sonames));
        assert!(!matches_soname("libgtk-3.so.0", sonames));
    }
}

/// Write via temp file + rename so a client already running against this prefix
/// keeps its mapped inode instead of hitting a truncated library.
fn write_library(entry: &mut impl std::io::Read, output: &Path) -> Result<()> {
    let parent = output.parent().context("Library path has no parent")?;
    let file_name = output
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    let tmp = parent.join(format!(".{}.{}.tmp", file_name, std::process::id()));

    let result = (|| -> Result<()> {
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf).context("Failed to read library")?;
        let mut f = std::fs::File::create(&tmp).context("Failed to create temp library")?;
        f.write_all(&buf).context("Failed to write library")?;
        f.sync_all().context("Failed to fsync library")?;
        use std::os::unix::fs::PermissionsExt;
        f.set_permissions(std::fs::Permissions::from_mode(0o755))
            .context("Failed to set library permissions")?;
        Ok(())
    })();

    if let Err(e) = result {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }

    std::fs::rename(&tmp, output).context("Failed to rename temp library over target")
}
