use anyhow::{anyhow, Context, Result};
use std::io::{Cursor, Read, Write};
use std::path::Path;
use tar::Archive as TarArchive;
use xz2::read::XzDecoder;

const GAME_BINARY_PATH: &str = "./usr/share/games/runescape-launcher/runescape";

/// Pull the `data.tar.xz` member out of a .deb (an `ar` archive).
pub fn data_tar_xz(deb_bytes: &[u8]) -> Result<Vec<u8>> {
    let mut archive = ar::Archive::new(Cursor::new(deb_bytes));

    while let Some(entry) = archive.next_entry() {
        let mut entry = entry.context("Failed to read ar entry")?;
        let name = std::str::from_utf8(entry.header().identifier())
            .context("Invalid ar entry name")?
            .to_string();

        if name.starts_with("data.tar.xz") {
            let mut buf = Vec::new();
            entry
                .read_to_end(&mut buf)
                .context("Failed to read data.tar.xz from .deb")?;
            return Ok(buf);
        }
    }

    Err(anyhow!("No data.tar.xz found in .deb file"))
}

/// Extract the RS3 binary from a .deb file (ar > data.tar.xz > game binary).
///
/// Accepts any `AsRef<[u8]>` (e.g. `bytes::Bytes`) so the multi-MB .deb body is
/// not copied into a fresh `Vec` just to be read immutably.
pub fn extract_rs3_binary(deb_bytes: impl AsRef<[u8]>, output: &Path) -> Result<()> {
    let xz_reader = XzDecoder::new(Cursor::new(data_tar_xz(deb_bytes.as_ref())?));
    let mut tar = TarArchive::new(xz_reader);

    for entry in tar.entries().context("Failed to read tar entries")? {
        let mut entry = entry.context("Failed to read tar entry")?;
        let path = entry
            .path()
            .context("Failed to read tar entry path")?
            .to_string_lossy()
            .to_string();

        if path == GAME_BINARY_PATH || path == &GAME_BINARY_PATH[2..] {
            let parent = output
                .parent()
                .context("Output path has no parent directory")?;
            std::fs::create_dir_all(parent)?;

            let mut buf = Vec::new();
            entry
                .read_to_end(&mut buf)
                .context("Failed to read game binary from tar")?;

            // Write to a temp file in the same directory, then atomically rename
            // over the target. Renaming replaces the directory entry without
            // touching the inode a still-running client holds open, so we avoid
            // both ETXTBSY and the truncated-binary window of write-in-place.
            let file_name = output
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| "rs3linux".to_string());
            let tmp = parent.join(format!(".{}.{}.tmp", file_name, std::process::id()));

            let write_result = (|| -> Result<()> {
                let mut f = std::fs::File::create(&tmp)
                    .context("Failed to create temp binary file")?;
                f.write_all(&buf).context("Failed to write game binary")?;
                f.sync_all().context("Failed to fsync game binary")?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    f.set_permissions(std::fs::Permissions::from_mode(0o755))
                        .context("Failed to set game binary permissions")?;
                }
                Ok(())
            })();

            if let Err(e) = write_result {
                let _ = std::fs::remove_file(&tmp);
                return Err(e);
            }

            std::fs::rename(&tmp, output)
                .context("Failed to rename temp binary over target")?;

            log::info!("Extracted RS3 binary to {}", output.display());
            return Ok(());
        }
    }

    Err(anyhow!(
        "Game binary '{}' not found in .deb archive",
        GAME_BINARY_PATH
    ))
}
