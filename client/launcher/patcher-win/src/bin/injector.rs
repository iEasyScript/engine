//! projectx_injector — cross-process DLL injector for rs2client.exe
//!
//! Usage:
//!   projectx_injector.exe <path-to-rs2client.exe> [args...]
//!
//! Flow (CreateRemoteThread + LoadLibraryW — the textbook safe injector):
//!   1. Locate projectx_patcher.dll on disk.
//!   2. CreateProcessW(rs2client.exe, ..., CREATE_SUSPENDED) — the main thread
//!      is created but suspended at the entry point.
//!   3. Resolve LoadLibraryW in *our* kernel32. On x64 Windows the kernel32
//!      base is fixed per session, so the same VA is valid in the target's
//!      address space — no need to enumerate the target's modules.
//!   4. VirtualAllocEx(MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE) in the target
//!      for the wide DLL path.
//!   5. WriteProcessMemory the UTF-16-encoded DLL path.
//!   6. CreateRemoteThread(target, lpStartAddress=LoadLibraryW, lpParameter=path).
//!   7. WaitForSingleObject on the remote thread; GetExitCodeThread — nonzero
//!      means LoadLibraryW returned a valid HMODULE (truncated to DWORD).
//!   8. VirtualFreeEx the path buffer.
//!   9. ResumeThread(main thread).
//!  10. CloseHandle on everything.
//!
//! Env vars (`PROJECTX_RSA_MODULUS`, `PROJECTX_JS5_RSA_MODULUS`, `PROJECTX_HTTP_PORT`)
//! are inherited automatically by CreateProcessW — we don't override the env
//! block, so the patcher DLL sees whatever the injector saw.

#![cfg(windows)]

use std::env;
use std::ffi::OsStr;
use std::iter::once;
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::ptr;

use anyhow::{anyhow, bail, Context, Result};
use projectx_patcher_common as common;
use windows_sys::Win32::Foundation::{CloseHandle, FALSE, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::Debug::WriteProcessMemory;
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress};
use windows_sys::Win32::System::Memory::{
    VirtualAllocEx, VirtualFreeEx, MEM_COMMIT, MEM_RELEASE, MEM_RESERVE, PAGE_READWRITE,
};
use windows_sys::Win32::System::Threading::{
    CreateProcessW, CreateRemoteThread, GetExitCodeThread, ResumeThread, WaitForSingleObject,
    CREATE_SUSPENDED, INFINITE, LPTHREAD_START_ROUTINE, PROCESS_INFORMATION, STARTUPINFOW,
};

const DLL_NAME: &str = "projectx_patcher.dll";

fn main() {
    if let Err(e) = run() {
        eprintln!("[projectx-injector] FATAL: {:#}", e);
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        print_usage(&args[0]);
        bail!("missing required <path-to-rs2client.exe> argument");
    }

    let exe_path = PathBuf::from(&args[1]);
    if !exe_path.is_file() {
        bail!("target executable does not exist: {}", exe_path.display());
    }
    let child_args: Vec<String> = args.iter().skip(2).cloned().collect();

    let dll_path = locate_dll().context("failed to locate projectx_patcher.dll")?;
    println!("[projectx-injector] DLL    : {}", dll_path.display());
    println!("[projectx-injector] target : {}", exe_path.display());
    if !child_args.is_empty() {
        println!("[projectx-injector] args   : {:?}", child_args);
    }

    // Preferred path: debug the whole tree so the client is patched at its entry
    // point. rs3windows spawns rs2client itself, and rs2client parses both RSA moduli
    // in a CRT static initializer — before main(). Injecting after it is already
    // running rewrites the .rdata strings but never the bignums the client verifies
    // with, so the JS5 master index fails its whirlpool check and the client dies with
    // an empty index table. LD_PRELOAD wins that race on Linux; this is the equivalent.
    match debug_and_patch_tree(&exe_path, &child_args) {
        Ok(()) => {
            println!("[projectx-injector] done — tree patched at entry point.");
            return Ok(());
        }
        Err(e) => eprintln!(
            "[projectx-injector] entry-point patching unavailable ({:#}); \
             falling back to post-start DLL injection.",
            e
        ),
    }

    let proc_info = spawn_suspended(&exe_path, &child_args)
        .context("CreateProcessW(CREATE_SUSPENDED) failed")?;
    println!(
        "[projectx-injector] spawned: pid={}, tid={}",
        proc_info.dwProcessId, proc_info.dwThreadId
    );

    // Drive the injection inside a guard so that, on any error path, we always
    // ResumeThread (so we don't leave a zombie suspended process around) and
    // CloseHandle on the OS objects.
    let inject_result = inject_dll(proc_info.hProcess, &dll_path);

    // Resume regardless of inject_result — even if injection failed, the user
    // probably wants the target to keep running (it'll just be un-patched).
    let resume_rc = unsafe { ResumeThread(proc_info.hThread) };
    if resume_rc == u32::MAX {
        eprintln!("[projectx-injector] WARNING: ResumeThread returned -1 (last error not checked)");
    }

    unsafe {
        CloseHandle(proc_info.hThread);
        CloseHandle(proc_info.hProcess);
    }

    inject_result.context("DLL injection failed")?;
    println!("[projectx-injector] done — target running with patcher loaded.");
    Ok(())
}

fn print_usage(prog: &str) {
    eprintln!("Usage: {} <path-to-rs2client.exe> [args...]", prog);
    eprintln!();
    eprintln!("Environment passed through to the patched process:");
    eprintln!("  PROJECTX_RSA_MODULUS       login RSA modulus hex (required to enable patching)");
    eprintln!("  PROJECTX_JS5_RSA_MODULUS   JS5 master-index RSA modulus hex");
    eprintln!("  PROJECTX_HTTP_PORT         HTTP JS5 content port (default 80)");
    eprintln!();
    eprintln!("DLL search order:");
    eprintln!("  1. <injector_dir>\\{}", DLL_NAME);
    eprintln!("  2. %APPDATA%\\ProjectX\\{}", DLL_NAME);
    eprintln!("  3. %LOCALAPPDATA%\\ProjectX\\{}", DLL_NAME);
}

/// Hard-coded DLL search path:
///   1. Same directory as the running injector .exe
///   2. %APPDATA%\ProjectX\projectx_patcher.dll
///   3. %LOCALAPPDATA%\ProjectX\projectx_patcher.dll
fn locate_dll() -> Result<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Ok(injector_exe) = env::current_exe() {
        if let Some(dir) = injector_exe.parent() {
            candidates.push(dir.join(DLL_NAME));
        }
    }
    if let Ok(appdata) = env::var("APPDATA") {
        candidates.push(PathBuf::from(appdata).join("ProjectX").join(DLL_NAME));
    }
    if let Ok(local) = env::var("LOCALAPPDATA") {
        candidates.push(PathBuf::from(local).join("ProjectX").join(DLL_NAME));
    }

    for cand in &candidates {
        if cand.is_file() {
            return Ok(cand.canonicalize().unwrap_or_else(|_| cand.clone()));
        }
    }
    Err(anyhow!(
        "{} not found in any of:\n  {}",
        DLL_NAME,
        candidates
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join("\n  ")
    ))
}

/// CreateProcessW(target, "target arg1 arg2 ...", CREATE_SUSPENDED). The
/// command line must be a mutable buffer per the Win32 contract.
fn spawn_suspended(exe: &Path, args: &[String]) -> Result<PROCESS_INFORMATION> {
    // Build a "C:\path with spaces\exe.exe" arg0 arg1 ... command line. argv[0]
    // gets quoted; subsequent args are passed through verbatim (the caller is
    // responsible for any escaping). rs2client is currently invoked with no
    // user-supplied args, so verbatim passthrough is sufficient.
    let mut cmdline = String::new();
    cmdline.push('"');
    cmdline.push_str(&exe.to_string_lossy());
    cmdline.push('"');
    for a in args {
        cmdline.push(' ');
        cmdline.push_str(a);
    }
    let mut cmdline_w = to_wide(&cmdline);

    let mut startup: STARTUPINFOW = unsafe { std::mem::zeroed() };
    startup.cb = size_of::<STARTUPINFOW>() as u32;
    let mut proc_info: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };

    // lpApplicationName=NULL forces CreateProcessW to parse the command line
    // (and resolve the exe via the quoted first token). This matches what
    // every standard-library spawner does and avoids a class of "wrong
    // executable" bugs when paths contain spaces.
    let ok = unsafe {
        CreateProcessW(
            ptr::null(),
            cmdline_w.as_mut_ptr(),
            ptr::null(),
            ptr::null(),
            FALSE,
            CREATE_SUSPENDED,
            ptr::null(),
            ptr::null(),
            &mut startup,
            &mut proc_info,
        )
    };
    if ok == FALSE {
        bail!(
            "CreateProcessW failed for {} (last_os_error={})",
            exe.display(),
            std::io::Error::last_os_error()
        );
    }
    Ok(proc_info)
}

/// Force the target process to call `LoadLibraryW(dll_path)` via
/// CreateRemoteThread. Returns Ok if the remote LoadLibraryW returned a
/// nonzero HMODULE (truncated to a DWORD by GetExitCodeThread).
fn inject_dll(h_process: HANDLE, dll_path: &Path) -> Result<()> {
    if h_process.is_null() || h_process == INVALID_HANDLE_VALUE {
        bail!("invalid target process handle");
    }

    // Resolve LoadLibraryW from kernel32 in *our* process. The Win32 loader
    // maps kernel32.dll at the same base in every process within a session
    // (x64 + KASLR-but-not-per-process), so the VA is portable across the
    // process boundary.
    let kernel32 = unsafe { GetModuleHandleW(to_wide("kernel32.dll").as_ptr()) };
    if kernel32.is_null() {
        bail!("GetModuleHandleW(\"kernel32.dll\") returned null");
    }
    let load_library_w = unsafe {
        GetProcAddress(
            kernel32,
            b"LoadLibraryW\0".as_ptr() as *const u8,
        )
    };
    let load_library_w = match load_library_w {
        Some(addr) => addr as usize,
        None => bail!("GetProcAddress(\"LoadLibraryW\") returned null"),
    };
    println!("[projectx-injector] LoadLibraryW @ 0x{:x}", load_library_w);

    // Write the DLL path (UTF-16, NUL-terminated) into the target.
    // `to_wide` takes `AsRef<OsStr>`. `Path` impls `AsRef<OsStr>` directly, so
    // we can skip the to_string_lossy round-trip (which also returns Cow, not OsStr).
    let dll_path_wide: Vec<u16> = to_wide(dll_path);
    let path_bytes_len = dll_path_wide.len() * size_of::<u16>();

    let remote_buf = unsafe {
        VirtualAllocEx(
            h_process,
            ptr::null(),
            path_bytes_len,
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        )
    };
    if remote_buf.is_null() {
        bail!(
            "VirtualAllocEx({} bytes) failed: {}",
            path_bytes_len,
            std::io::Error::last_os_error()
        );
    }

    let mut bytes_written: usize = 0;
    let write_ok = unsafe {
        WriteProcessMemory(
            h_process,
            remote_buf,
            dll_path_wide.as_ptr() as *const _,
            path_bytes_len,
            &mut bytes_written,
        )
    };
    if write_ok == FALSE || bytes_written != path_bytes_len {
        let err = std::io::Error::last_os_error();
        unsafe { VirtualFreeEx(h_process, remote_buf, 0, MEM_RELEASE) };
        bail!(
            "WriteProcessMemory wrote {}/{} bytes: {}",
            bytes_written,
            path_bytes_len,
            err
        );
    }

    // SAFETY: LoadLibraryW has the signature `HMODULE WINAPI LoadLibraryW(LPCWSTR)`,
    // which matches LPTHREAD_START_ROUTINE's `DWORD (*)(LPVOID)` for the calling
    // convention purposes the loader cares about (Win64 fastcall, single
    // pointer argument, integer return). This is the canonical injection
    // recipe used in every textbook DLL injector since Windows NT 4.
    let start_routine: LPTHREAD_START_ROUTINE = Some(unsafe {
        std::mem::transmute::<usize, unsafe extern "system" fn(*mut std::ffi::c_void) -> u32>(
            load_library_w,
        )
    });

    let h_thread = unsafe {
        CreateRemoteThread(
            h_process,
            ptr::null(),
            0,
            start_routine,
            remote_buf,
            0,
            ptr::null_mut(),
        )
    };
    if h_thread.is_null() {
        let err = std::io::Error::last_os_error();
        unsafe { VirtualFreeEx(h_process, remote_buf, 0, MEM_RELEASE) };
        bail!("CreateRemoteThread failed: {}", err);
    }

    // Wait for LoadLibraryW to return. The remote thread exits as soon as
    // LoadLibraryW does — that's the entire body of the thread.
    let wait_rc = unsafe { WaitForSingleObject(h_thread, INFINITE) };
    if wait_rc != 0 {
        // WAIT_OBJECT_0 = 0; anything else (WAIT_TIMEOUT/WAIT_FAILED) is a
        // bug, but we still try to read the exit code below.
        eprintln!(
            "[projectx-injector] WARNING: WaitForSingleObject returned 0x{:x} (expected 0)",
            wait_rc
        );
    }

    let mut exit_code: u32 = 0;
    let got_exit = unsafe { GetExitCodeThread(h_thread, &mut exit_code) };
    unsafe {
        CloseHandle(h_thread);
        VirtualFreeEx(h_process, remote_buf, 0, MEM_RELEASE);
    }

    if got_exit == FALSE {
        bail!(
            "GetExitCodeThread failed: {}",
            std::io::Error::last_os_error()
        );
    }
    // exit_code is the DWORD-truncated HMODULE returned by LoadLibraryW.
    // On x64 a real HMODULE is a pointer; the low 32 bits being nonzero is
    // the conventional signal of "load succeeded". A genuine failure
    // returns 0 (NULL HMODULE).
    if exit_code == 0 {
        bail!("remote LoadLibraryW returned NULL — DLL load failed inside target");
    }
    println!(
        "[projectx-injector] LoadLibraryW returned (low32) 0x{:x} — DLL loaded",
        exit_code
    );
    Ok(())
}

/// UTF-16 encode with a trailing NUL, the form every Win32 *W function wants.
fn to_wide<S: AsRef<OsStr>>(s: S) -> Vec<u16> {
    s.as_ref().encode_wide().chain(once(0u16)).collect()
}

// -- Entry-point patching via the debug loop -----------------------------------
//
// CREATE_PROCESS_DEBUG_EVENT is delivered before the new process executes a single
// instruction, so patches written there land ahead of the CRT static initializers
// that parse the RSA moduli into bignums. We debug the whole tree (DEBUG_PROCESS
// covers descendants) because the client we care about is spawned by rs3windows,
// not by us. Each process identifies itself by the key embedded in its own image,
// exactly as the DLL does in-process.

const DEBUG_PROCESS_FLAG: u32 = 0x0000_0001;
const CREATE_PROCESS_DEBUG_EVENT_CODE: u32 = 3;
const EXIT_PROCESS_DEBUG_EVENT_CODE: u32 = 5;
const EXCEPTION_DEBUG_EVENT_CODE: u32 = 1;
const DBG_CONTINUE_STATUS: i32 = 0x0001_0002u32 as i32;
const DBG_EXCEPTION_NOT_HANDLED_STATUS: i32 = 0x8001_0001u32 as i32;
const EXCEPTION_BREAKPOINT_CODE: u32 = 0x8000_0003;

/// How long to keep debugging while waiting for the client to appear. rs3windows
/// may download and verify a fresh client first, which is minutes on a cold install.
const CLIENT_WAIT: std::time::Duration = std::time::Duration::from_secs(300);

#[derive(PartialEq, Clone, Copy, Debug)]
enum ImageKind {
    Client,
    Launcher,
}

/// Spawn the target under the debugger and patch every process in the tree as it is
/// created. Returns once the client has been patched, then detaches: an attached
/// debugger serializes thread and exception traffic and visibly slows rendering, so
/// it must not outlive the patch.
fn debug_and_patch_tree(exe: &Path, args: &[String]) -> Result<()> {
    use windows_sys::Win32::System::Diagnostics::Debug::{
        ContinueDebugEvent, DebugSetProcessKillOnExit, WaitForDebugEvent, DEBUG_EVENT,
    };

    if env::var("PROJECTX_RSA_MODULUS").ok().filter(|s| !s.is_empty()).is_none() {
        bail!("PROJECTX_RSA_MODULUS not set — nothing to patch");
    }

    let pi = spawn_with_flags(exe, args, DEBUG_PROCESS_FLAG)?;
    println!(
        "[projectx-injector] spawned under debugger: pid={}, tid={}",
        pi.dwProcessId, pi.dwThreadId
    );
    unsafe {
        DebugSetProcessKillOnExit(FALSE);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }

    let mut attached: Vec<u32> = Vec::new();
    let mut client_patched = false;
    let deadline = std::time::Instant::now() + CLIENT_WAIT;

    while std::time::Instant::now() < deadline {
        let mut ev: DEBUG_EVENT = unsafe { std::mem::zeroed() };
        if unsafe { WaitForDebugEvent(&mut ev, 1000) } == 0 {
            continue;
        }
        let mut status = DBG_CONTINUE_STATUS;
        let mut tree_gone = false;

        match ev.dwDebugEventCode {
            CREATE_PROCESS_DEBUG_EVENT_CODE => {
                if !attached.contains(&ev.dwProcessId) {
                    attached.push(ev.dwProcessId);
                }
                let info = unsafe { ev.u.CreateProcessInfo };
                match patch_process_image(info.hProcess, info.lpBaseOfImage as usize) {
                    Ok(Some(ImageKind::Client)) => {
                        println!(
                            "[projectx-injector] patched rs2client (pid {}) at its entry point",
                            ev.dwProcessId
                        );
                        client_patched = true;
                    }
                    Ok(Some(ImageKind::Launcher)) => println!(
                        "[projectx-injector] patched rs3 launcher (pid {}) at its entry point",
                        ev.dwProcessId
                    ),
                    Ok(None) => {}
                    Err(e) => eprintln!(
                        "[projectx-injector] WARNING: could not patch pid {}: {:#}",
                        ev.dwProcessId, e
                    ),
                }
                unsafe { CloseHandle(info.hFile) };
            }
            EXIT_PROCESS_DEBUG_EVENT_CODE => {
                attached.retain(|p| *p != ev.dwProcessId);
                tree_gone = attached.is_empty();
            }
            EXCEPTION_DEBUG_EVENT_CODE => {
                // The initial breakpoint is ours to swallow; anything else belongs to
                // the process, so hand it back rather than eating a real fault.
                let code = unsafe { ev.u.Exception.ExceptionRecord.ExceptionCode };
                if code as u32 != EXCEPTION_BREAKPOINT_CODE {
                    status = DBG_EXCEPTION_NOT_HANDLED_STATUS;
                }
            }
            _ => {}
        }

        unsafe { ContinueDebugEvent(ev.dwProcessId, ev.dwThreadId, status) };

        if client_patched {
            detach_all(&attached);
            println!("[projectx-injector] detached — client runs undebugged.");
            return Ok(());
        }
        if tree_gone {
            bail!("target tree exited before a client appeared");
        }
    }

    detach_all(&attached);
    bail!("no client process appeared within {}s", CLIENT_WAIT.as_secs())
}

fn detach_all(pids: &[u32]) {
    use windows_sys::Win32::System::Diagnostics::Debug::DebugActiveProcessStop;
    for pid in pids {
        unsafe { DebugActiveProcessStop(*pid) };
    }
}

/// Identify a freshly created process from its own image and apply the patches it
/// needs, cross-process. Mirrors the DLL's in-process `apply_patches`.
fn patch_process_image(process: HANDLE, base: usize) -> Result<Option<ImageKind>> {
    let size = image_size(process, base).context("reading PE headers")?;
    let image = read_image(process, base, size).context("reading module image")?;

    let kind = if find(&image, common::LOGIN_MODULUS_PREFIX).is_some() {
        ImageKind::Client
    } else if find(&image, common::LAUNCHER_MODULUS_PREFIX).is_some() {
        ImageKind::Launcher
    } else {
        return Ok(None);
    };

    let modulus = env::var("PROJECTX_RSA_MODULUS").unwrap_or_default();
    match kind {
        ImageKind::Client => {
            patch_modulus_at(
                process,
                base,
                &image,
                "login RSA modulus",
                common::LOGIN_MODULUS_PREFIX,
                &modulus,
                common::LOGIN_MODULUS_HEX_LEN,
            )?;
            if let Ok(js5) = env::var("PROJECTX_JS5_RSA_MODULUS") {
                if !js5.is_empty() {
                    patch_modulus_at(
                        process,
                        base,
                        &image,
                        "JS5 RSA modulus",
                        common::JS5_MODULUS_PREFIX,
                        &js5,
                        common::JS5_MODULUS_HEX_LEN,
                    )?;
                }
            }
            // Without this the client resolves the JS5-over-HTTP content URL to port
            // 80 and blocks forever on "loading application resources".
            if let Ok(raw) = env::var("PROJECTX_HTTP_PORT") {
                if let Ok(port) = raw.trim().parse::<u16>() {
                    let immediate = common::port_immediate(port);
                    let sites = common::find_port_sites(&image);
                    if sites.is_empty() {
                        bail!("HTTP content port matched 0 sites");
                    }
                    for site in &sites {
                        write_at(
                            process,
                            base + site.patch_offset(),
                            &immediate[..site.imm_width],
                        )?;
                    }
                }
            }
        }
        ImageKind::Launcher => {
            patch_modulus_at(
                process,
                base,
                &image,
                "launcher RSA modulus",
                common::LAUNCHER_MODULUS_PREFIX,
                &modulus,
                common::LAUNCHER_MODULUS_HEX_LEN,
            )?;
            if let Some(off) = find(&image, common::CODEBASE_REGEX) {
                write_at(process, base + off, &common::relaxed_codebase_regex())?;
            }
        }
    }
    Ok(Some(kind))
}

fn patch_modulus_at(
    process: HANDLE,
    base: usize,
    image: &[u8],
    label: &str,
    prefix: &[u8],
    modulus_hex: &str,
    hex_len: usize,
) -> Result<()> {
    let padded = common::pad_modulus_hex(modulus_hex, hex_len)
        .ok_or_else(|| anyhow!("{} replacement longer than the {}-char field", label, hex_len))?;
    let offset = find(image, prefix).ok_or_else(|| anyhow!("{} matched 0 sites", label))?;
    write_at(process, base + offset, padded.as_bytes())
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    memchr::memmem::find(haystack, needle)
}

fn image_size(process: HANDLE, base: usize) -> Result<usize> {
    let mut head = [0u8; 0x200];
    read_at(process, base, &mut head)?;
    let pe = u32::from_le_bytes(head[0x3c..0x40].try_into().unwrap()) as usize;
    if pe + 0x54 > head.len() || &head[pe..pe + 4] != b"PE\0\0" {
        bail!("not a PE image at {:#x}", base);
    }
    Ok(u32::from_le_bytes(head[pe + 0x50..pe + 0x54].try_into().unwrap()) as usize)
}

/// Read the mapped image. Pages not committed yet are skipped rather than failing
/// the whole read — the pattern search tolerates holes.
fn read_image(process: HANDLE, base: usize, size: usize) -> Result<Vec<u8>> {
    let mut buf = vec![0u8; size];
    if read_at(process, base, &mut buf).is_ok() {
        return Ok(buf);
    }
    const CHUNK: usize = 0x10000;
    let mut any = false;
    let mut off = 0;
    while off < size {
        let end = (off + CHUNK).min(size);
        if read_at(process, base + off, &mut buf[off..end]).is_ok() {
            any = true;
        }
        off = end;
    }
    if any {
        Ok(buf)
    } else {
        bail!("could not read any of the target image")
    }
}

fn read_at(process: HANDLE, addr: usize, out: &mut [u8]) -> Result<()> {
    use windows_sys::Win32::System::Diagnostics::Debug::ReadProcessMemory;
    let mut got: usize = 0;
    let ok = unsafe {
        ReadProcessMemory(
            process,
            addr as *const _,
            out.as_mut_ptr() as *mut _,
            out.len(),
            &mut got,
        )
    };
    if ok == FALSE || got != out.len() {
        bail!("ReadProcessMemory({:#x}, {}) failed", addr, out.len());
    }
    Ok(())
}

/// Write through the page protection and restore it, so .text stays executable and
/// .rdata stays read-only for the rest of the process's life.
fn write_at(process: HANDLE, addr: usize, bytes: &[u8]) -> Result<()> {
    use windows_sys::Win32::System::Memory::{VirtualProtectEx, PAGE_EXECUTE_READWRITE};

    let mut old: u32 = 0;
    let unprotected = unsafe {
        VirtualProtectEx(
            process,
            addr as *const _,
            bytes.len(),
            PAGE_EXECUTE_READWRITE,
            &mut old,
        )
    };
    if unprotected == FALSE {
        bail!("VirtualProtectEx({:#x}) failed", addr);
    }

    let mut wrote: usize = 0;
    let ok = unsafe {
        WriteProcessMemory(
            process,
            addr as *const _,
            bytes.as_ptr() as *const _,
            bytes.len(),
            &mut wrote,
        )
    };

    let mut scratch: u32 = 0;
    unsafe { VirtualProtectEx(process, addr as *const _, bytes.len(), old, &mut scratch) };

    if ok == FALSE || wrote != bytes.len() {
        bail!("WriteProcessMemory({:#x}, {}) failed", addr, bytes.len());
    }
    Ok(())
}

/// CreateProcessW with caller-supplied creation flags, sharing command-line
/// construction with [`spawn_suspended`].
fn spawn_with_flags(exe: &Path, args: &[String], flags: u32) -> Result<PROCESS_INFORMATION> {
    let mut cmdline = String::new();
    cmdline.push('"');
    cmdline.push_str(&exe.to_string_lossy());
    cmdline.push('"');
    for a in args {
        cmdline.push(' ');
        cmdline.push_str(a);
    }
    let mut cmdline_w = to_wide(&cmdline);

    let mut startup: STARTUPINFOW = unsafe { std::mem::zeroed() };
    startup.cb = size_of::<STARTUPINFOW>() as u32;
    let mut proc_info: PROCESS_INFORMATION = unsafe { std::mem::zeroed() };

    let ok = unsafe {
        CreateProcessW(
            ptr::null(),
            cmdline_w.as_mut_ptr(),
            ptr::null(),
            ptr::null(),
            FALSE,
            flags,
            ptr::null(),
            ptr::null(),
            &startup,
            &mut proc_info,
        )
    };
    if ok == FALSE {
        bail!(
            "CreateProcessW(flags={:#x}) failed for {}",
            flags,
            exe.display()
        );
    }
    Ok(proc_info)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn to_wide_terminates_with_nul() {
        let w = to_wide("ok");
        assert_eq!(w.last(), Some(&0u16));
        assert_eq!(w.len(), 3);
    }

    #[test]
    fn locate_dll_fails_gracefully_when_absent() {
        // We can't reliably remove every candidate location, but we can at
        // least exercise the error formatting path.
        let result = locate_dll();
        // Either OK (the dll was deployed) or Err containing every candidate.
        if let Err(e) = result {
            let msg = format!("{}", e);
            assert!(msg.contains(DLL_NAME), "error message should name the DLL");
        }
    }
}
