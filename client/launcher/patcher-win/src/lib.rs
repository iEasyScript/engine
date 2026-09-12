//! Project X runtime patcher — Windows DLL (cross-process LoadLibrary injection target)
//!
//! Mirrors the Linux LD_PRELOAD patcher at `client/launcher/patcher/src/lib.rs`,
//! adapted for the Windows PE32+ builds. Patches are applied on
//! DLL_PROCESS_ATTACH when `PROJECTX_RSA_MODULUS` is set:
//!
//!   * `rs2client.exe`     — login RSA modulus, JS5 RSA modulus, live HTTP content port
//!   * `rs3windows.exe`    — download-verification RSA modulus, codebase URL regex
//!
//! Each pattern only occurs in the image that needs it, so a process patches
//! itself with no process sniffing. Patch targets are shared with the other two
//! platform patchers via `projectx-patcher-common`.
//!
//! **What is NOT patched here**: the ISAAC delta. MSVC inlines the step as
//! scattered immediates with no central data site, and the server-side contract
//! is unchanged, so no patcher action is required.
//!
//! With `PROJECTX_RSA_MODULUS` unset the patcher does nothing, so the DLL can stay
//! injected in live mode.

#![cfg(windows)]

use projectx_patcher_common as common;
use memchr::memmem::Finder;
use std::ffi::c_void;
use std::ptr;
use std::slice;

use windows_sys::Win32::Foundation::{BOOL, FALSE, HMODULE, TRUE};
use windows_sys::Win32::System::Diagnostics::Debug::OutputDebugStringW;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Memory::{
    VirtualProtect, PAGE_PROTECTION_FLAGS, PAGE_READONLY, PAGE_READWRITE,
};
use windows_sys::Win32::System::ProcessStatus::{GetModuleInformation, MODULEINFO};
use windows_sys::Win32::System::SystemServices::{
    DLL_PROCESS_ATTACH, DLL_PROCESS_DETACH, DLL_THREAD_ATTACH, DLL_THREAD_DETACH,
};
use windows_sys::Win32::System::Threading::GetCurrentProcess;

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
            debug_log(&format!(
                "All {} patch target(s) applied successfully.",
                self.attempted
            ));
            return;
        }
        debug_log("####################################################");
        debug_log(&format!(
            "#### PATCHING FAILED — {} of {} target(s) missed",
            self.failed.len(),
            self.attempted
        ));
        for target in &self.failed {
            debug_log(&format!("####   MISSED: {}", target));
        }
        debug_log("#### The client is NOT fully redirected and may");
        debug_log("#### connect to LIVE JAGEX SERVERS. Do not continue.");
        debug_log("####################################################");
    }
}

/// DLL entry point. Called by the loader on PROCESS_ATTACH (right after the
/// injected `LoadLibraryW` resolves the DLL) and PROCESS_DETACH. We do all
/// patching synchronously inside PROCESS_ATTACH so it completes before the
/// main thread is resumed by the injector.
#[no_mangle]
#[allow(non_snake_case, unused_variables)]
pub extern "system" fn DllMain(
    _dll_module: HMODULE,
    call_reason: u32,
    _reserved: *mut c_void,
) -> BOOL {
    match call_reason {
        DLL_PROCESS_ATTACH => {
            // Swallow any panic — DllMain MUST NOT propagate Rust unwinds across
            // the FFI boundary. The patcher logs errors via OutputDebugStringW
            // and returns TRUE either way: failing the DLL load would crash the
            // host process, which is worse than silently leaving the binary
            // un-patched (the user can then run in live mode anyway).
            let _ = std::panic::catch_unwind(|| apply_patches());
            TRUE
        }
        DLL_PROCESS_DETACH | DLL_THREAD_ATTACH | DLL_THREAD_DETACH => TRUE,
        _ => TRUE,
    }
}

fn apply_patches() {
    let modulus_hex = read_env("PROJECTX_RSA_MODULUS");
    let js5_modulus_hex = read_env("PROJECTX_JS5_RSA_MODULUS");
    let http_port = read_env("PROJECTX_HTTP_PORT");

    // Same semantics as the Linux patcher: presence without configuration is a
    // no-op so the DLL is safe to leave injected in live mode.
    let Some(modulus_hex) = modulus_hex else {
        debug_log("PROJECTX_RSA_MODULUS not set — patcher idle (live mode).");
        return;
    };

    debug_log("Patcher loaded, scanning main module image for patch targets...");

    let module = match get_main_module_range() {
        Some(m) => m,
        None => {
            debug_log("ERROR: failed to query main module range — aborting.");
            return;
        }
    };

    debug_log(&format!(
        "Main module image: base=0x{:x}, size={} bytes, end=0x{:x}",
        module.base,
        module.size,
        module.base + module.size
    ));

    // SAFETY: the main module image is mapped readable from `base` for `size`
    // bytes for the lifetime of the process. We only borrow this slice for the
    // duration of the pattern search; before any write we re-acquire pointers
    // and call VirtualProtect to make the relevant page writable.
    let image = unsafe { slice::from_raw_parts(module.base as *const u8, module.size) };

    let mut report = Report::default();

    // Each image carries exactly one of the two keys, so the image itself says
    // which binary this is — no process-name sniffing, and no chance of applying
    // a target's patches to the wrong executable.
    let is_client = Finder::new(common::LOGIN_MODULUS_PREFIX).find(image).is_some();
    let is_launcher = Finder::new(common::LAUNCHER_MODULUS_PREFIX)
        .find(image)
        .is_some();

    if is_client {
        debug_log("Target: rs2client.exe");
        report.record(
            "login RSA modulus",
            patch_modulus(
                image,
                module.base,
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
                    image,
                    module.base,
                    "JS5 RSA modulus",
                    common::JS5_MODULUS_PREFIX,
                    js5,
                    common::JS5_MODULUS_HEX_LEN,
                ),
            ),
            None => debug_log("PROJECTX_JS5_RSA_MODULUS not set — skipping JS5 patch."),
        }

        patch_http_port(image, module.base, http_port.as_deref(), &mut report);
    } else if is_launcher {
        debug_log("Target: rs3 launcher");
        report.record(
            "launcher download-verification RSA modulus",
            patch_modulus(
                image,
                module.base,
                "launcher download-verification RSA modulus",
                common::LAUNCHER_MODULUS_PREFIX,
                &modulus_hex,
                common::LAUNCHER_MODULUS_HEX_LEN,
            ),
        );
        report.record(
            "codebase URL regex",
            patch_sites(
                image,
                module.base,
                "codebase URL regex",
                common::CODEBASE_REGEX,
                &common::relaxed_codebase_regex(),
                0,
                false,
            ) == 1,
        );
    } else {
        debug_log("ERROR: image carries neither the client nor the launcher key — nothing patched.");
        return;
    }

    report.finish();
}

fn patch_modulus(
    image: &[u8],
    base: usize,
    label: &str,
    prefix: &[u8],
    modulus_hex: &str,
    hex_len: usize,
) -> bool {
    let Some(padded) = common::pad_modulus_hex(modulus_hex, hex_len) else {
        debug_log(&format!(
            "ERROR: {} replacement is longer than the {}-char field.",
            label, hex_len
        ));
        return false;
    };
    patch_sites(image, base, label, prefix, padded.as_bytes(), 0, false) == 1
}

fn patch_http_port(image: &[u8], base: usize, http_port: Option<&str>, report: &mut Report) {
    let Some(port_str) = http_port else {
        debug_log("PROJECTX_HTTP_PORT not set — skipping HTTP port patch.");
        return;
    };
    let Ok(port) = port_str.trim().parse::<u16>() else {
        debug_log(&format!(
            "ERROR: PROJECTX_HTTP_PORT='{}' is not a valid port.",
            port_str
        ));
        report.record("HTTP content port", false);
        return;
    };

    let immediate = common::port_immediate(port);
    let sites = common::find_port_sites(image);
    if sites.is_empty() {
        debug_log("ERROR: HTTP content port matched 0 sites.");
        report.record("HTTP content port", false);
        return;
    }

    let mut patched = 0usize;
    for site in &sites {
        let va = base + site.patch_offset();
        match write_with_unprotect(va, &immediate[..site.imm_width]) {
            Ok(()) => patched += 1,
            Err(e) => debug_log(&format!("    ERROR patching HTTP port at 0x{:x}: {}", va, e)),
        }
    }
    debug_log(&format!(
        "HTTP content port -> {}: patched {}/{} site(s)",
        port,
        patched,
        sites.len()
    ));
    report.record("HTTP content port", patched == sites.len());
}

/// Overwrite `replacement` at every (or only the first) match of `needle`.
/// Returns how many sites were successfully written.
fn patch_sites(
    image: &[u8],
    base: usize,
    label: &str,
    needle: &[u8],
    replacement: &[u8],
    offset_within_pattern: usize,
    patch_all_matches: bool,
) -> usize {
    let finder = Finder::new(needle);
    let mut matches: Vec<usize> = finder.find_iter(image).collect();

    if matches.is_empty() {
        debug_log(&format!("ERROR: {} pattern matched 0 sites.", label));
        return 0;
    }

    if !patch_all_matches {
        matches.truncate(1);
    }

    let mut patched = 0usize;
    for image_offset in &matches {
        let va = base + image_offset + offset_within_pattern;
        match write_with_unprotect(va, replacement) {
            Ok(()) => patched += 1,
            Err(e) => debug_log(&format!("    ERROR patching {} at 0x{:x}: {}", label, va, e)),
        }
    }
    debug_log(&format!(
        "{}: patched {}/{} site(s)",
        label,
        patched,
        matches.len()
    ));
    patched
}

/// Write `bytes` at virtual address `addr` after temporarily making the
/// containing pages writable via VirtualProtect, then restore the original
/// protection. Returns the OS error message on failure.
fn write_with_unprotect(addr: usize, bytes: &[u8]) -> Result<(), &'static str> {
    let len = bytes.len();
    if len == 0 {
        return Ok(());
    }

    let mut old_protect: PAGE_PROTECTION_FLAGS = 0;
    let unprotect_ok = unsafe {
        VirtualProtect(
            addr as *const c_void,
            len,
            PAGE_READWRITE,
            &mut old_protect as *mut PAGE_PROTECTION_FLAGS,
        )
    };
    if unprotect_ok == FALSE {
        return Err("VirtualProtect(PAGE_READWRITE) failed");
    }

    let verified = unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), addr as *mut u8, len);
        slice::from_raw_parts(addr as *const u8, len) == bytes
    };

    // Restore the original protection. If the page was previously
    // PAGE_EXECUTE_READ (typical for .text), we put it back exactly. For
    // .rdata (PAGE_READONLY) we likewise restore the original flag. We do
    // NOT fall back to PAGE_EXECUTE_READWRITE — leaving pages permanently
    // writable would weaken DEP for the rest of the process.
    let restore_flag = if old_protect == 0 { PAGE_READONLY } else { old_protect };
    let mut scratch: PAGE_PROTECTION_FLAGS = 0;
    let restore_ok = unsafe {
        VirtualProtect(
            addr as *const c_void,
            len,
            restore_flag,
            &mut scratch as *mut PAGE_PROTECTION_FLAGS,
        )
    };
    if restore_ok == FALSE {
        // The write itself succeeded; not strictly fatal. Log via the caller.
        // We bias toward leaving the patch in place rather than failing.
        debug_log(&format!(
            "  WARNING: VirtualProtect(restore=0x{:x}) failed at 0x{:x}; patch is applied but protection not restored.",
            restore_flag, addr
        ));
    }

    if !verified {
        return Err("write did not take effect");
    }

    Ok(())
}

struct ModuleRange {
    base: usize,
    size: usize,
}

/// Resolve the main module's image base and image size.
///
/// `GetModuleHandleW(NULL)` returns the HMODULE of the executable that started
/// the current process (rs2client.exe — into which our DLL was just injected).
/// `GetModuleInformation` then gives us the linear range of the loaded image
/// so we can scan the whole thing as one contiguous slice.
fn get_main_module_range() -> Option<ModuleRange> {
    let h_module = unsafe { GetModuleHandleW(ptr::null()) };
    if h_module.is_null() {
        debug_log("ERROR: GetModuleHandleW(NULL) returned null.");
        return None;
    }

    let mut info: MODULEINFO = unsafe { std::mem::zeroed() };
    let h_process = unsafe { GetCurrentProcess() };
    let info_size = std::mem::size_of::<MODULEINFO>() as u32;

    let ok =
        unsafe { GetModuleInformation(h_process, h_module, &mut info, info_size) };
    if ok == FALSE {
        debug_log("ERROR: GetModuleInformation failed on main module.");
        return None;
    }

    Some(ModuleRange {
        base: info.lpBaseOfDll as usize,
        size: info.SizeOfImage as usize,
    })
}

fn read_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|s| !s.is_empty())
}

// -- Logging via OutputDebugStringW + %TEMP%\projectx-patcher.log ---------------
//
// rs2client.exe is a Windows GUI subsystem binary — it has no inherited
// console, so stdout/stderr writes go nowhere visible. We tee every log line
// to two sinks:
//   1. OutputDebugStringW — visible to any attached debugger or DebugView
//      (the standard way to observe DLL-injected code).
//   2. `%TEMP%\projectx-patcher.log` — a file sink so the host can inspect
//      patcher behavior after the fact without DebugView running. Truncated
//      once per process at the first debug_log() call.
//
// Each line is prefixed with the patcher tag and the host PID so multiple
// injected processes don't get confused if they ever share the log file.

static LOG_INIT: std::sync::Once = std::sync::Once::new();

fn log_file_path() -> std::path::PathBuf {
    std::env::temp_dir().join("projectx-patcher.log")
}

fn write_log_file(line: &str) {
    LOG_INIT.call_once(|| {
        let _ = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(log_file_path());
    });
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .append(true)
        .create(true)
        .open(log_file_path())
    {
        let _ = f.write_all(line.as_bytes());
    }
}

fn debug_log(msg: &str) {
    let pid = std::process::id();
    let line = format!("[projectx-patcher pid={}] {}\n", pid, msg);
    let wide: Vec<u16> = line.encode_utf16().chain(std::iter::once(0u16)).collect();
    unsafe {
        OutputDebugStringW(wide.as_ptr());
    }
    write_log_file(&line);
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

    /// Encode-utf16 sanity check — the `debug_log` path encodes via the same
    /// primitive, so any drift in standard-library UTF-16 semantics would
    /// silently break our OutputDebugStringW logging.
    #[test]
    fn utf16_encode_includes_all_chars() {
        let s = "hello";
        let wide: Vec<u16> = s.encode_utf16().collect();
        assert_eq!(wide, vec![b'h' as u16, b'e' as u16, b'l' as u16, b'l' as u16, b'o' as u16]);
    }
}
