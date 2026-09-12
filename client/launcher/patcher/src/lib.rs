//! Project X runtime patcher — LD_PRELOAD shared library (Linux).
//!
//! Loaded before the client starts, this scans the process image for known Jagex
//! patterns and rewrites them so the client talks to the private server instead.
//! It patches whichever binary it finds itself in:
//!
//!   * `rs2client` — login RSA modulus, JS5 RSA modulus, live HTTP content port
//!   * `rs3linux`  — download-verification RSA modulus, codebase URL regex
//!
//! With `PROJECTX_RSA_MODULUS` unset it does nothing, so the same library can stay
//! preloaded in live mode.

use projectx_patcher_common as common;
use memchr::memmem::Finder;
use std::env;
use std::fs;
use std::ptr;

const PAGE_SIZE: usize = 4096;

/// A memory region with its original permission flags.
struct Region {
    start: usize,
    end: usize,
    prot: i32,
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
                "[projectx-patcher] All {} patch target(s) applied successfully.",
                self.attempted
            );
            return;
        }
        eprintln!("[projectx-patcher] ####################################################");
        eprintln!("[projectx-patcher] #### PATCHING FAILED — {} of {} target(s) missed",
            self.failed.len(), self.attempted);
        for target in &self.failed {
            eprintln!("[projectx-patcher] ####   MISSED: {}", target);
        }
        eprintln!("[projectx-patcher] #### The client is NOT fully redirected and may");
        eprintln!("[projectx-patcher] #### connect to LIVE JAGEX SERVERS. Do not continue.");
        eprintln!("[projectx-patcher] ####################################################");
    }
}

#[ctor::ctor]
fn patch_client() {
    let modulus_hex = env::var("PROJECTX_RSA_MODULUS").ok().filter(|v| !v.is_empty());
    let js5_modulus_hex = env::var("PROJECTX_JS5_RSA_MODULUS").ok().filter(|v| !v.is_empty());

    let Some(modulus_hex) = modulus_hex else {
        return;
    };

    let Ok(exe_path) = fs::read_link("/proc/self/exe") else {
        eprintln!("[projectx-patcher] ERROR: cannot resolve /proc/self/exe — no patches applied.");
        return;
    };

    if common::hex_to_bytes(&modulus_hex).is_none() {
        eprintln!("[projectx-patcher] ERROR: PROJECTX_RSA_MODULUS is not valid hex — no patches applied.");
        return;
    }

    let maps = match fs::read_to_string("/proc/self/maps") {
        Ok(m) => m,
        Err(e) => {
            eprintln!("[projectx-patcher] ERROR: Failed to read /proc/self/maps: {}", e);
            return;
        }
    };

    let regions = main_image_regions(&maps, &exe_path);
    let Some(target) = identify(&exe_path, &regions) else {
        // LD_PRELOAD rides into every descendant the client spawns (shells,
        // helper tools). Those are not patch targets and must not raise alarms.
        return;
    };

    eprintln!(
        "[projectx-patcher] Target: {} ({})",
        target.label(),
        exe_path.display()
    );
    for (i, r) in regions.iter().enumerate() {
        eprintln!(
            "[projectx-patcher]   region[{}]: 0x{:x}-0x{:x} ({} bytes, prot={})",
            i, r.start, r.end, r.end - r.start, r.prot
        );
    }

    let mut report = Report::default();
    match target {
        Target::Client => {
            allow_any_debugger_to_attach();
            patch_client_keys(&regions, &modulus_hex, js5_modulus_hex.as_deref(), &mut report);
            patch_http_port(&regions, &mut report);
        }
        // The client key patches must never run here: the launcher image has no
        // such key, so a wider search would match a copy of the prefix elsewhere
        // in the address space and the write would land in unrelated memory.
        Target::Launcher => patch_launcher(&regions, &modulus_hex, &mut report),
    }

    report.finish();
}

/// Yama ptrace_scope=1 refuses a non-ancestor tracer unless the tracee opts in, which is what
/// lets the engine injector gdb-attach to the client without root.
fn allow_any_debugger_to_attach() {
    let ret = unsafe { libc::prctl(libc::PR_SET_PTRACER, libc::PR_SET_PTRACER_ANY, 0, 0, 0) };
    if ret == 0 {
        eprintln!("[projectx-patcher] PR_SET_PTRACER_ANY: ok");
    } else {
        eprintln!(
            "[projectx-patcher] PR_SET_PTRACER_ANY: failed, errno={}",
            unsafe { *libc::__errno_location() }
        );
    }
}

#[derive(Clone, Copy)]
enum Target {
    Client,
    Launcher,
}

impl Target {
    fn label(self) -> &'static str {
        match self {
            Target::Client => "rs2client",
            Target::Launcher => "rs3 launcher",
        }
    }
}

/// Decide what this process is. The file name is the fast path; if Jagex ever
/// renames a binary, the key each image uniquely carries settles it. Anything
/// else is an unrelated process that merely inherited LD_PRELOAD.
fn identify(exe_path: &std::path::Path, regions: &[Region]) -> Option<Target> {
    let name = exe_path.file_name()?.to_string_lossy().to_string();
    if name.contains("rs2client") {
        return Some(Target::Client);
    }
    if name.contains("rs3linux") {
        return Some(Target::Launcher);
    }
    if find_first(regions, &Finder::new(common::LOGIN_MODULUS_PREFIX)).is_some() {
        return Some(Target::Client);
    }
    if find_first(regions, &Finder::new(common::LAUNCHER_MODULUS_PREFIX)).is_some() {
        return Some(Target::Launcher);
    }
    None
}

fn patch_client_keys(
    regions: &[Region],
    modulus_hex: &str,
    js5_modulus_hex: Option<&str>,
    report: &mut Report,
) {
    report.record(
        "rs2client login RSA modulus",
        patch_modulus(
            regions,
            "rs2client login RSA modulus",
            common::LOGIN_MODULUS_PREFIX,
            modulus_hex,
            common::LOGIN_MODULUS_HEX_LEN,
        ),
    );

    let Some(js5_modulus_hex) = js5_modulus_hex else {
        eprintln!("[projectx-patcher] PROJECTX_JS5_RSA_MODULUS not set — skipping JS5 key patch");
        return;
    };
    report.record(
        "rs2client JS5 RSA modulus",
        patch_modulus(
            regions,
            "rs2client JS5 RSA modulus",
            common::JS5_MODULUS_PREFIX,
            js5_modulus_hex,
            common::JS5_MODULUS_HEX_LEN,
        ),
    );
}

fn patch_launcher(regions: &[Region], modulus_hex: &str, report: &mut Report) {
    report.record(
        "rs3linux download-verification RSA modulus",
        patch_modulus(
            regions,
            "rs3linux download-verification RSA modulus",
            common::LAUNCHER_MODULUS_PREFIX,
            modulus_hex,
            common::LAUNCHER_MODULUS_HEX_LEN,
        ),
    );

    let relaxed = common::relaxed_codebase_regex();
    let found = find_first(regions, &Finder::new(common::CODEBASE_REGEX));
    let ok = match found {
        Some((addr, prot)) => {
            eprintln!("[projectx-patcher] Found codebase URL regex");
            patch_memory(addr, &relaxed, prot)
        }
        None => {
            eprintln!("[projectx-patcher] ERROR: codebase URL regex pattern matched 0 sites");
            false
        }
    };
    report.record("rs3linux codebase URL regex", ok);
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
            "[projectx-patcher] ERROR: {} replacement is longer than the {}-char field",
            label, hex_len
        );
        return false;
    };

    let finder = Finder::new(prefix);
    let Some((addr, prot)) = find_first(regions, &finder) else {
        eprintln!("[projectx-patcher] ERROR: {} pattern matched 0 sites", label);
        return false;
    };

    eprintln!("[projectx-patcher] Found {}", label);
    if patch_memory(addr, replacement.as_bytes(), prot) {
        eprintln!("[projectx-patcher] Patched {} ({} hex chars)", label, hex_len);
        true
    } else {
        eprintln!("[projectx-patcher] ERROR: write failed for {}", label);
        false
    }
}

fn patch_http_port(regions: &[Region], report: &mut Report) {
    let Ok(port_str) = env::var("PROJECTX_HTTP_PORT") else {
        return;
    };
    let Ok(port) = port_str.trim().parse::<u16>() else {
        eprintln!("[projectx-patcher] ERROR: PROJECTX_HTTP_PORT='{}' is not a valid port", port_str);
        report.record("rs2client HTTP content port", false);
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
            let addr = region.start + site.patch_offset();
            if patch_memory(addr, &immediate[..site.imm_width], region.prot) {
                patched += 1;
            } else {
                eprintln!("[projectx-patcher] ERROR: write failed for HTTP content port site");
            }
        }
    }

    if found == 0 {
        eprintln!("[projectx-patcher] ERROR: HTTP content port matched 0 sites");
    } else {
        eprintln!(
            "[projectx-patcher] HTTP content port -> {}: patched {}/{} site(s)",
            port, patched, found
        );
    }
    report.record("rs2client HTTP content port", found > 0 && patched == found);
}

/// Readable regions backed by the main executable itself, matched on the exact
/// resolved path. Restricting every scan to this image is what keeps a write
/// from ever landing in an unrelated mapping.
fn main_image_regions(maps: &str, exe_path: &std::path::Path) -> Vec<Region> {
    let exe = exe_path.to_string_lossy();
    maps.lines()
        .filter(|line| mapped_path(line) == Some(exe.as_ref()))
        .filter_map(parse_map_line)
        .collect()
}

fn mapped_path(line: &str) -> Option<&str> {
    let path = line.split_whitespace().nth(5)?;
    path.starts_with('/').then_some(path)
}

/// Parse a single line from /proc/self/maps, keeping only readable regions.
fn parse_map_line(line: &str) -> Option<Region> {
    let mut parts = line.split_whitespace();
    let addr_range = parts.next()?;
    let perms = parts.next()?;

    if !perms.starts_with('r') {
        return None;
    }

    let mut addr_parts = addr_range.split('-');
    let start = usize::from_str_radix(addr_parts.next()?, 16).ok()?;
    let end = usize::from_str_radix(addr_parts.next()?, 16).ok()?;

    if end <= start || (end - start) < common::LOGIN_MODULUS_PREFIX.len() {
        return None;
    }

    let perm_bytes = perms.as_bytes();
    let mut prot = 0i32;
    if perm_bytes.len() >= 3 {
        if perm_bytes[0] == b'r' {
            prot |= libc::PROT_READ;
        }
        if perm_bytes[1] == b'w' {
            prot |= libc::PROT_WRITE;
        }
        if perm_bytes[2] == b'x' {
            prot |= libc::PROT_EXEC;
        }
    }

    Some(Region { start, end, prot })
}

fn region_slice(region: &Region) -> Option<&'static [u8]> {
    let len = region.end.checked_sub(region.start)?;
    if len == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(region.start as *const u8, len) })
}

fn find_first(regions: &[Region], finder: &Finder) -> Option<(usize, i32)> {
    regions.iter().find_map(|region| {
        let slice = region_slice(region)?;
        finder.find(slice).map(|i| (region.start + i, region.prot))
    })
}

/// Make the containing pages writable, write, verify the bytes are really there,
/// then restore the original flags.
fn patch_memory(addr: usize, new_bytes: &[u8], original_prot: i32) -> bool {
    let page_start = addr & !(PAGE_SIZE - 1);
    let page_end = (addr + new_bytes.len() + PAGE_SIZE - 1) & !(PAGE_SIZE - 1);
    let total_len = page_end - page_start;

    unsafe {
        let ret = libc::mprotect(
            page_start as *mut libc::c_void,
            total_len,
            original_prot | libc::PROT_WRITE,
        );
        if ret != 0 {
            eprintln!(
                "[projectx-patcher] mprotect(+WRITE) failed for 0x{:x}..0x{:x}: errno={}",
                page_start,
                page_start + total_len,
                *libc::__errno_location()
            );
            return false;
        }

        ptr::copy_nonoverlapping(new_bytes.as_ptr(), addr as *mut u8, new_bytes.len());
        let written = std::slice::from_raw_parts(addr as *const u8, new_bytes.len());
        let verified = written == new_bytes;

        if libc::mprotect(page_start as *mut libc::c_void, total_len, original_prot) != 0 {
            eprintln!(
                "[projectx-patcher] WARNING: mprotect(restore) failed for 0x{:x}: errno={}",
                page_start,
                *libc::__errno_location()
            );
        }

        if !verified {
            eprintln!("[projectx-patcher] ERROR: write did not take effect at 0x{:x}", addr);
        }
        verified
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_map_line_reads_range_and_permissions() {
        let line = "7f1234000000-7f1234001000 r--p 00000000 08:01 12345 /path/to/rs3linux";
        let region = parse_map_line(line).unwrap();
        assert_eq!(region.start, 0x7f1234000000);
        assert_eq!(region.end, 0x7f1234001000);
        assert_eq!(region.prot, libc::PROT_READ);

        let line = "7f1234000000-7f1234001000 r-xp 00000000 08:01 12345 /path/to/rs3linux";
        assert_eq!(
            parse_map_line(line).unwrap().prot,
            libc::PROT_READ | libc::PROT_EXEC
        );
    }

    #[test]
    fn parse_map_line_rejects_unreadable_regions() {
        let line = "7f1234000000-7f1234001000 --xp 00000000 08:01 12345 /path/to/rs3linux";
        assert!(parse_map_line(line).is_none());
    }

    #[test]
    fn main_image_regions_match_the_exact_exe_path_only() {
        let maps = "\
7f0000000000-7f0000001000 r-xp 00000000 08:01 1 /opt/rs2client
7f0000002000-7f0000003000 r-xp 00000000 08:01 2 /lib/libc.so.6
7f0000004000-7f0000005000 r--p 00000000 08:01 3 /opt/rs2client
7f0000006000-7f0000007000 r--p 00000000 08:01 4 /opt/other/rs2client
";
        let regions = main_image_regions(maps, std::path::Path::new("/opt/rs2client"));
        assert_eq!(regions.len(), 2);
        assert!(main_image_regions(maps, std::path::Path::new("/opt/rs3linux")).is_empty());
    }

    #[test]
    fn report_flags_every_missed_target() {
        let mut report = Report::default();
        report.record("a", true);
        report.record("b", false);
        assert_eq!(report.attempted, 2);
        assert_eq!(report.failed, vec!["b".to_string()]);
    }
}
