use super::policy::{Gpu, GpuKind};
use std::fs;
use std::path::Path;

pub const PCI_DEVICES: &str = "/sys/bus/pci/devices";

const DISPLAY_CLASS: u32 = 0x03;

pub fn scan(root: &Path) -> Vec<Gpu> {
    let Ok(entries) = fs::read_dir(root) else {
        return Vec::new();
    };
    let mut gpus: Vec<Gpu> = entries
        .flatten()
        .filter_map(|entry| {
            let dir = entry.path();
            let read = |name: &str| fs::read_to_string(dir.join(name)).ok();
            let driver = fs::read_link(dir.join("driver"))
                .ok()
                .and_then(|target| target.file_name().map(|n| n.to_string_lossy().into_owned()));
            parse_device(
                &entry.file_name().to_string_lossy(),
                &read("class")?,
                &read("vendor")?,
                &read("device")?,
                driver,
                read("boot_vga").as_deref(),
            )
        })
        .collect();
    gpus.sort_by(|a, b| a.address.cmp(&b.address));
    gpus
}

pub fn parse_device(
    address: &str,
    class: &str,
    vendor: &str,
    device: &str,
    driver: Option<String>,
    boot_vga: Option<&str>,
) -> Option<Gpu> {
    if parse_hex(class)? >> 16 != DISPLAY_CLASS {
        return None;
    }
    Some(Gpu {
        address: address.to_string(),
        vendor: u16::try_from(parse_hex(vendor)?).ok()?,
        device: u16::try_from(parse_hex(device)?).ok()?,
        driver,
        boot_vga: boot_vga.is_some_and(|v| v.trim() == "1"),
        kind: GpuKind::Unknown,
    })
}

fn parse_hex(raw: &str) -> Option<u32> {
    let raw = raw.trim();
    u32::from_str_radix(raw.strip_prefix("0x").unwrap_or(raw), 16).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_vga_controller_is_parsed() {
        let gpu = parse_device(
            "0000:00:02.0",
            "0x030000\n",
            "0x8086\n",
            "0xa7a0\n",
            Some("i915".to_string()),
            Some("1\n"),
        )
        .unwrap();
        assert_eq!(gpu.vendor, 0x8086);
        assert_eq!(gpu.device, 0xa7a0);
        assert!(gpu.boot_vga);
        assert_eq!(gpu.kind, GpuKind::Unknown);
    }

    #[test]
    fn a_3d_controller_without_boot_vga_is_a_gpu() {
        let gpu = parse_device(
            "0000:01:00.0",
            "0x030200\n",
            "0x10de\n",
            "0x28e0\n",
            None,
            None,
        )
        .unwrap();
        assert!(!gpu.boot_vga);
        assert_eq!(gpu.driver, None);
    }

    #[test]
    fn other_device_classes_are_skipped() {
        assert_eq!(
            parse_device(
                "0000:00:1f.3",
                "0x040380\n",
                "0x8086\n",
                "0x51c8\n",
                None,
                None
            ),
            None
        );
    }

    #[test]
    fn scanning_a_missing_directory_finds_nothing() {
        assert!(scan(Path::new("/nonexistent/projectx/pci")).is_empty());
    }
}
