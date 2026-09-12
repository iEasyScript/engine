//! Project X runtime patcher — macOS dylib (DYLD_INSERT_LIBRARIES preload target).
//!
//! Loaded before the host's `main()`, the constructor locates the main
//! executable's image, scans it for known Jagex patterns, and overwrites them to
//! redirect the client to the private server. It patches whichever binary it
//! finds itself in:
//!
//!   * `rs2client` — login RSA modulus, JS5 RSA modulus, live HTTP content port
//!   * `rs3mac`    — download-verification RSA modulus, codebase URL regex
//!
//! With `PROJECTX_RSA_MODULUS` unset it does nothing, so the dylib is safe to
//! leave inserted in live mode.

#![cfg(target_os = "macos")]

use projectx_patcher_common as common;
use memchr::memmem::Finder;
use std::env;
use std::ffi::CStr;
use std::os::raw::{c_char, c_void};
use std::ptr;

const PAGE_SIZE: usize = 4096;

// -- dyld + Mach-O FFI --------------------------------------------------------
//
// The main executable's mapped image comes from the dyld inspection API rather
// than /proc, which does not exist here. Image index 0 is always the main
// executable.

extern "C" {
    fn _dyld_image_count() -> u32;
    fn _dyld_get_image_header(image_index: u32) -> *const MachHeader64;
    fn _dyld_get_image_name(image_index: u32) -> *const c_char;
    fn _dyld_get_image_vmaddr_slide(image_index: u32) -> isize;
}

const MH_MAGIC_64: u32 = 0xfeed_facf;
const LC_SEGMENT_64: u32 = 0x19;
const VM_PROT_READ: i32 = 0x1;

#[repr(C)]
struct MachHeader64 {
    magic: u32,
    cputype: i32,
    cpusubtype: i32,
    filetype: u32,
    ncmds: u32,
    sizeofcmds: u32,
    flags: u32,
    reserved: u32,
}

#[repr(C)]
struct LoadCommand {
    cmd: u32,
    cmdsize: u32,
}

#[repr(C)]
struct SegmentCommand64 {
    cmd: u32,
    cmdsize: u32,
    segname: [u8; 16],
    vmaddr: u64,
    vmsize: u64,
    fileoff: u64,
    filesize: u64,
    maxprot: i32,
    initprot: i32,
    nsects: u32,
    flags: u32,
}

/// A mapped, readable segment of the main executable image.
struct Region {
    start: usize,
    end: usize,
}

/// Tracks whether every patch this process was supposed to apply actually
/// landed. An unpatched client silently connects to live Jagex, so the summary
/// has to be impossible to miss in the log.
#[derive(Default)]
struct Report {
    attempted: usize,
    failed: Vec<String>,
}

impl Report {
    fn record(&mut self, target: &str, ok: bool) {
        self.attempted += 1;
        if !ok {
            self.failed.push(target.to_string());
        }
    }

    fn finish(&self) {
        if self.failed.is_empty() {
            eprintln!(
                "[projectx-patcher-mac] All {} patch target(s) applied successfully.",
                self.attempted
            );
            return;
        }
        eprintln!("[projectx-patcher-mac] ####################################################");
        eprintln!(
            "[projectx-patcher-mac] #### PATCHING FAILED — {} of {} target(s) missed",
            self.failed.len(),
            self.attempted
        );
        for target in &self.failed {
            eprintln!("[projectx-patcher-mac] ####   MISSED: {}", target);
        }
        eprintln!("[projectx-patcher-mac] #### The client is NOT fully redirected and may");
        eprintln!("[projectx-patcher-mac] #### connect to LIVE JAGEX SERVERS. Do not continue.");
        eprintln!("[projectx-patcher-mac] ####################################################");
    }
}

#[ctor::ctor]
fn patch_client() {
    let modulus_hex = env::var("PROJECTX_RSA_MODULUS").ok().filter(|v| !v.is_empty());
    let js5_modulus_hex = env::var("PROJECTX_JS5_RSA_MODULUS")
        .ok()
        .filter(|v| !v.is_empty());

    let Some(modulus_hex) = modulus_hex else {
        return;
    };

    if common::hex_to_bytes(&modulus_hex).is_none() {
        eprintln!("[projectx-patcher-mac] ERROR: PROJECTX_RSA_MODULUS is not valid hex — no patches applied.");
        return;
    }

    let regions = main_image_regions();
    if regions.is_empty() {
        eprintln!("[projectx-patcher-mac] ERROR: could not resolve main executable image regions — aborting");
        return;
    }

    // Each image carries exactly one of the two keys, so the image itself says
    // which binary this is. Anything else merely inherited the insert variable.
    let is_client = find_first(&regions, &Finder::new(common::LOGIN_MODULUS_PREFIX)).is_some();
    let is_launcher = find_first(&regions, &Finder::new(common::LAUNCHER_MODULUS_PREFIX)).is_some();
    if !is_client && !is_launcher {
        return;
    }

    eprintln!(
        "[projectx-patcher-mac] Target: {}",
        if is_client { "rs2client" } else { "rs3 launcher" }
    );
    for (i, r) in regions.iter().enumerate() {
        eprintln!(
            "[projectx-patcher-mac]   region[{}]: 0x{:x}-0x{:x} ({} bytes)",
            i,
            r.start,
            r.end,
            r.end - r.start
        );
    }

    let mut report = Report::default();
    if is_client {
        report.record(
            "login RSA modulus",
            patch_modulus(
                &regions,
                "login RSA modulus",
                common::LOGIN_MODULUS_PREFIX,
                &modulus_hex,
                common::LOGIN_MODULUS_HEX_LEN,
            ),
        );
        match js5_modulus_hex.as_deref() {
            Some(js5) => report.record(
                "JS5 RSA modulus",
                patch_modulus(
                    &regions,
                    "JS5 RSA modulus",
                    common::JS5_MODULUS_PREFIX,
                    js5,
                    common::JS5_MODULUS_HEX_LEN,
                ),
            ),
            None => eprintln!("[projectx-patcher-mac] PROJECTX_JS5_RSA_MODULUS not set — skipping JS5 patch"),
        }
        patch_http_port(&regions, &mut report);
    } else {
        report.record(
            "launcher download-verification RSA modulus",
            patch_modulus(
                &regions,
                "launcher download-verification RSA modulus",
                common::LAUNCHER_MODULUS_PREFIX,
                &modulus_hex,
                common::LAUNCHER_MODULUS_HEX_LEN,
            ),
        );
        let relaxed = common::relaxed_codebase_regex();
        let ok = match find_first(&regions, &Finder::new(common::CODEBASE_REGEX)) {
            Some(addr) => patch_memory(addr, &relaxed),
            None => {
                eprintln!("[projectx-patcher-mac] ERROR: codebase URL regex matched 0 sites");
                false
            }
        };
        report.record("codebase URL regex", ok);
    }

    report.finish();
}

fn patch_modulus(
    regions: &[Region],
    label: &str,
    prefix: &[u8],
    modulus_hex: &str,
    hex_len: usize,
) -> bool {
    let Some(replacement) = common::pad_modulus_hex(modulus_hex, hex_len) else {
        eprintln!(
            "[projectx-patcher-mac] ERROR: {} replacement is longer than the {}-char field",
            label, hex_len
        );
        return false;
    };

    let Some(addr) = find_first(regions, &Finder::new(prefix)) else {
        eprintln!("[projectx-patcher-mac] ERROR: {} pattern matched 0 sites", label);
        return false;
    };

    if patch_memory(addr, replacement.as_bytes()) {
        eprintln!("[projectx-patcher-mac] Patched {} ({} hex chars)", label, hex_len);
        true
    } else {
        eprintln!("[projectx-patcher-mac] ERROR: write failed for {}", label);
        false
    }
}

fn patch_http_port(regions: &[Region], report: &mut Report) {
    let Ok(port_str) = env::var("PROJECTX_HTTP_PORT") else {
        return;
    };
    let Ok(port) = port_str.trim().parse::<u16>() else {
        eprintln!(
            "[projectx-patcher-mac] ERROR: PROJECTX_HTTP_PORT='{}' is not a valid port",
            port_str
        );
        report.record("HTTP content port", false);
        return;
    };

    let immediate = common::port_immediate(port);
    let mut found = 0usize;
    let mut patched = 0usize;

    for region in regions {
        let Some(slice) = region_slice(region) else {
            continue;
        };
        for site in common::find_port_sites(slice) {
            found += 1;
            if patch_memory(region.start + site.patch_offset(), &immediate[..site.imm_width]) {
                patched += 1;
            } else {
                eprintln!("[projectx-patcher-mac] ERROR: write failed for HTTP content port site");
            }
        }
    }

    if found == 0 {
        eprintln!("[projectx-patcher-mac] ERROR: HTTP content port matched 0 sites");
    } else {
        eprintln!(
            "[projectx-patcher-mac] HTTP content port -> {}: patched {}/{} site(s)",
            port, patched, found
        );
    }
    report.record("HTTP content port", found > 0 && patched == found);
}

// -- Main image discovery -----------------------------------------------------

/// Readable, mapped `LC_SEGMENT_64` ranges of the MAIN executable image. The RSA
/// hex strings and the port literal all live inside `__TEXT`.
fn main_image_regions() -> Vec<Region> {
    let mut regions = Vec::new();
    if unsafe { _dyld_image_count() } == 0 {
        return regions;
    }

    let header = unsafe { _dyld_get_image_header(0) };
    if header.is_null() {
        return regions;
    }
    let slide = unsafe { _dyld_get_image_vmaddr_slide(0) };
    let name = unsafe {
        let np = _dyld_get_image_name(0);
        if np.is_null() {
            "<unknown>".to_string()
        } else {
            CStr::from_ptr(np).to_string_lossy().to_string()
        }
    };

    let hdr = unsafe { &*header };
    if hdr.magic != MH_MAGIC_64 {
        eprintln!(
            "[projectx-patcher-mac] main image '{}' is not a 64-bit Mach-O — aborting region scan",
            name
        );
        return regions;
    }

    let mut cmd_ptr = unsafe { (header as *const u8).add(std::mem::size_of::<MachHeader64>()) };
    for _ in 0..hdr.ncmds {
        let lc = unsafe { &*(cmd_ptr as *const LoadCommand) };
        if lc.cmdsize == 0 {
            break;
        }
        if lc.cmd == LC_SEGMENT_64 {
            let seg = unsafe { &*(cmd_ptr as *const SegmentCommand64) };
            if (seg.initprot & VM_PROT_READ) != 0 && seg.vmsize > 0 {
                let start = (seg.vmaddr as isize + slide) as usize;
                let end = start.wrapping_add(seg.vmsize as usize);
                if end > start {
                    regions.push(Region { start, end });
                }
            }
        }
        cmd_ptr = unsafe { cmd_ptr.add(lc.cmdsize as usize) };
    }

    regions
}

// -- Scan helpers -------------------------------------------------------------

fn region_slice(region: &Region) -> Option<&'static [u8]> {
    let len = region.end.checked_sub(region.start)?;
    if len == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(region.start as *const u8, len) })
}

fn find_first(regions: &[Region], finder: &Finder) -> Option<usize> {
    regions.iter().find_map(|region| {
        let slice = region_slice(region)?;
        finder.find(slice).map(|i| region.start + i)
    })
}

// -- Patch primitive ----------------------------------------------------------

/// Patch memory at `addr`, making the containing pages writable, verifying the
/// bytes really landed, then restoring R+X.
///
/// `__const` is mapped r-- rather than r-x; restoring READ|EXEC there is
/// harmless since dyld does not hand back the original protection cheaply.
fn patch_memory(addr: usize, new_bytes: &[u8]) -> bool {
    let page_start = addr & !(PAGE_SIZE - 1);
    let page_end = (addr + new_bytes.len() + PAGE_SIZE - 1) & !(PAGE_SIZE - 1);
    let total_len = page_end - page_start;

    unsafe {
        let ret = libc::mprotect(
            page_start as *mut c_void,
            total_len,
            libc::PROT_READ | libc::PROT_WRITE | libc::PROT_EXEC,
        );
        if ret != 0 {
            eprintln!(
                "[projectx-patcher-mac] mprotect(+WRITE) failed for 0x{:x}..0x{:x}: errno={}",
                page_start,
                page_start + total_len,
                *libc::__error()
            );
            return false;
        }

        ptr::copy_nonoverlapping(new_bytes.as_ptr(), addr as *mut u8, new_bytes.len());
        let written = std::slice::from_raw_parts(addr as *const u8, new_bytes.len());
        let verified = written == new_bytes;

        if libc::mprotect(
            page_start as *mut c_void,
            total_len,
            libc::PROT_READ | libc::PROT_EXEC,
        ) != 0
        {
            eprintln!(
                "[projectx-patcher-mac] WARNING: mprotect(restore) failed for 0x{:x}: errno={}",
                page_start,
                *libc::__error()
            );
        }

        if !verified {
            eprintln!(
                "[projectx-patcher-mac] ERROR: write did not take effect at 0x{:x}",
                addr
            );
        }
        verified
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_flags_every_missed_target() {
        let mut report = Report::default();
        report.record("a", true);
        report.record("b", false);
        assert_eq!(report.attempted, 2);
        assert_eq!(report.failed, vec!["b".to_string()]);
    }
}
