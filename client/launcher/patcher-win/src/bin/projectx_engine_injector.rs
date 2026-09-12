//! projectx_engine_injector — injects the Project X engine into an already-running rs2client.exe.
//!
//! This is the Windows counterpart of the Linux GDB-`dlopen` path in
//! `client-plugin-engine/inject`, and it differs from `projectx_injector` in the
//! same crate: that one spawns the client suspended and injects before it runs,
//! whereas the engine attaches to a client that is already up and logged in.
//!
//! Usage:
//!   projectx_engine_injector.exe [--pid <pid>] [--dll <path>]
//!
//! With no arguments it finds the newest rs2client.exe that does not already
//! have the bootstrap mapped.
//!
//! Flow (CreateRemoteThread + LoadLibraryW):
//!   1. Resolve the target pid and the DLL path.
//!   2. Refuse if the bootstrap is already mapped — a second LoadLibrary would
//!      only bump the refcount, and the engine would look injected but be the
//!      previously loaded build.
//!   3. VirtualAllocEx a buffer for the UTF-16 DLL path, WriteProcessMemory it.
//!   4. CreateRemoteThread at LoadLibraryW with that buffer.
//!   5. Wait, read the exit code (the truncated HMODULE), free the buffer.
//!
//! Unlike the Linux path this plants no environment variables: the bootstrap
//! derives PROJECTX_HOME_DIR from its own module path, because Windows offers no
//! equivalent of GDB's `call setenv()` in a process we did not spawn.

#![cfg(windows)]

use std::env;
use std::ffi::OsStr;
use std::iter::once;
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::ptr;

use anyhow::{anyhow, bail, Context, Result};
use windows_sys::Win32::Foundation::{CloseHandle, FALSE, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::Debug::WriteProcessMemory;
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Module32FirstW, Module32NextW, Process32FirstW, Process32NextW,
    MODULEENTRY32W, PROCESSENTRY32W, TH32CS_SNAPMODULE, TH32CS_SNAPMODULE32, TH32CS_SNAPPROCESS,
};
use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress};
use windows_sys::Win32::System::Memory::{
    VirtualAllocEx, VirtualFreeEx, MEM_COMMIT, MEM_RELEASE, MEM_RESERVE, PAGE_READWRITE,
};
use windows_sys::Win32::System::Threading::{
    CreateRemoteThread, GetExitCodeThread, OpenProcess, WaitForSingleObject, INFINITE,
    LPTHREAD_START_ROUTINE, PROCESS_CREATE_THREAD, PROCESS_QUERY_INFORMATION, PROCESS_VM_OPERATION,
    PROCESS_VM_READ, PROCESS_VM_WRITE,
};

const DLL_NAME: &str = "projectxbootstrap.dll";
const TARGET_PROCESS: &str = "rs2client.exe";

fn main() {
    if let Err(e) = run() {
        eprintln!("[projectx-injector] FATAL: {:#}", e);
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    let mut pid: Option<u32> = None;
    let mut dll: Option<PathBuf> = None;

    let args: Vec<String> = env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--pid" => {
                i += 1;
                pid = Some(args.get(i).ok_or_else(|| anyhow!("--pid needs a value"))?.parse()?);
            }
            "--dll" => {
                i += 1;
                dll = Some(PathBuf::from(args.get(i).ok_or_else(|| anyhow!("--dll needs a value"))?));
            }
            "--help" | "-h" => {
                print_usage(&args[0]);
                return Ok(());
            }
            other => bail!("unknown argument: {other}"),
        }
        i += 1;
    }

    let dll = match dll {
        Some(p) => p,
        None => locate_dll().context("failed to locate the engine bootstrap DLL")?,
    };
    let dll = std::fs::canonicalize(&dll).unwrap_or(dll);
    if !dll.is_file() {
        bail!("bootstrap DLL does not exist: {}", dll.display());
    }

    let pid = match pid {
        Some(p) => p,
        None => find_target_pid()?,
    };

    println!("[projectx-injector] dll    : {}", dll.display());
    println!("[projectx-injector] target : pid {pid}");

    if is_already_injected(pid)? {
        // A second LoadLibraryW would only bump the refcount, leaving the previously loaded build
        // in place while looking like a successful inject.
        println!("[projectx-injector] {DLL_NAME} is already mapped in pid {pid}; nothing to do.");
        return Ok(());
    }

    inject(pid, &dll)?;
    println!("[projectx-injector] done — engine loaded into pid {pid}.");
    Ok(())
}

fn print_usage(prog: &str) {
    eprintln!("Usage: {prog} [--pid <pid>] [--dll <path-to-{DLL_NAME}>]");
    eprintln!();
    eprintln!("With no --pid, the newest {TARGET_PROCESS} without the bootstrap mapped is chosen.");
    eprintln!("With no --dll, these are searched in order:");
    eprintln!("  1. %PROJECTX_HOME_DIR%\\{DLL_NAME}");
    eprintln!("  2. <injector_dir>\\{DLL_NAME}");
    eprintln!("  3. <cwd>\\client-plugin-engine\\build\\libs\\{DLL_NAME}");
}

/// Mirrors the Linux injector's search: the env var, then next to the injector, then the dev tree.
fn locate_dll() -> Result<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Ok(home) = env::var("PROJECTX_HOME_DIR") {
        if !home.is_empty() {
            candidates.push(Path::new(&home).join(DLL_NAME));
        }
    }
    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join(DLL_NAME));
        }
    }
    if let Ok(cwd) = env::current_dir() {
        candidates.push(
            cwd.join("client-plugin-engine").join("build").join("libs").join(DLL_NAME),
        );
    }

    candidates
        .iter()
        .find(|c| c.is_file())
        .cloned()
        .ok_or_else(|| {
            anyhow!(
                "{DLL_NAME} not found in any of:\n  {}",
                candidates.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join("\n  ")
            )
        })
}

/// Newest `rs2client.exe` that does not already have the bootstrap mapped.
fn find_target_pid() -> Result<u32> {
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
    if snapshot == INVALID_HANDLE_VALUE {
        bail!("CreateToolhelp32Snapshot failed: {}", std::io::Error::last_os_error());
    }

    let mut found: Vec<u32> = Vec::new();
    let mut entry: PROCESSENTRY32W = unsafe { std::mem::zeroed() };
    entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;

    let mut ok = unsafe { Process32FirstW(snapshot, &mut entry) };
    while ok != FALSE {
        let name = wide_to_string(&entry.szExeFile);
        if name.eq_ignore_ascii_case(TARGET_PROCESS) {
            found.push(entry.th32ProcessID);
        }
        ok = unsafe { Process32NextW(snapshot, &mut entry) };
    }
    unsafe { CloseHandle(snapshot) };

    if found.is_empty() {
        bail!("no {TARGET_PROCESS} process is running");
    }

    // Later pids are newer in practice; prefer one that is not already injected.
    found.sort_unstable();
    for pid in found.iter().rev() {
        if !is_already_injected(*pid).unwrap_or(false) {
            return Ok(*pid);
        }
    }
    bail!("every {TARGET_PROCESS} process already has {DLL_NAME} mapped")
}

fn is_already_injected(pid: u32) -> Result<bool> {
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid) };
    if snapshot == INVALID_HANDLE_VALUE {
        // A process we cannot enumerate (permissions, or it just exited) is treated as not injected;
        // the inject attempt itself will produce the real error.
        return Ok(false);
    }

    let mut entry: MODULEENTRY32W = unsafe { std::mem::zeroed() };
    entry.dwSize = size_of::<MODULEENTRY32W>() as u32;

    let mut mapped = false;
    let mut ok = unsafe { Module32FirstW(snapshot, &mut entry) };
    while ok != FALSE {
        if wide_to_string(&entry.szModule).eq_ignore_ascii_case(DLL_NAME) {
            mapped = true;
            break;
        }
        ok = unsafe { Module32NextW(snapshot, &mut entry) };
    }
    unsafe { CloseHandle(snapshot) };
    Ok(mapped)
}

fn inject(pid: u32, dll: &Path) -> Result<()> {
    let access = PROCESS_CREATE_THREAD
        | PROCESS_QUERY_INFORMATION
        | PROCESS_VM_OPERATION
        | PROCESS_VM_WRITE
        | PROCESS_VM_READ;
    let process: HANDLE = unsafe { OpenProcess(access, FALSE, pid) };
    if process.is_null() {
        bail!(
            "OpenProcess({pid}) failed: {}. Run the injector elevated, or as the same user that owns the client.",
            std::io::Error::last_os_error()
        );
    }

    let result = inject_into(process, dll);
    unsafe { CloseHandle(process) };
    result
}

fn inject_into(process: HANDLE, dll: &Path) -> Result<()> {
    // kernel32 is mapped at the same base in every process of a session, so its LoadLibraryW address
    // is valid across the process boundary without enumerating the target's modules.
    let kernel32 = unsafe { GetModuleHandleW(to_wide("kernel32.dll").as_ptr()) };
    if kernel32.is_null() {
        bail!("GetModuleHandleW(\"kernel32.dll\") returned null");
    }
    let load_library = unsafe { GetProcAddress(kernel32, b"LoadLibraryW\0".as_ptr()) }
        .ok_or_else(|| anyhow!("GetProcAddress(\"LoadLibraryW\") returned null"))?;

    let path_wide = to_wide(dll);
    let path_bytes = path_wide.len() * size_of::<u16>();

    let remote = unsafe {
        VirtualAllocEx(process, ptr::null(), path_bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE)
    };
    if remote.is_null() {
        bail!("VirtualAllocEx({path_bytes}) failed: {}", std::io::Error::last_os_error());
    }

    let mut written: usize = 0;
    let wrote = unsafe {
        WriteProcessMemory(process, remote, path_wide.as_ptr().cast(), path_bytes, &mut written)
    };
    if wrote == FALSE || written != path_bytes {
        let err = std::io::Error::last_os_error();
        unsafe { VirtualFreeEx(process, remote, 0, MEM_RELEASE) };
        bail!("WriteProcessMemory wrote {written}/{path_bytes} bytes: {err}");
    }

    // SAFETY: LoadLibraryW is `HMODULE WINAPI (LPCWSTR)`, which matches LPTHREAD_START_ROUTINE's
    // `DWORD (*)(LPVOID)` for calling-convention purposes on Win64 — one pointer argument, integer
    // return. This is the standard injection recipe.
    let start: LPTHREAD_START_ROUTINE = Some(unsafe {
        std::mem::transmute::<_, unsafe extern "system" fn(*mut std::ffi::c_void) -> u32>(load_library)
    });

    let thread = unsafe {
        CreateRemoteThread(process, ptr::null(), 0, start, remote, 0, ptr::null_mut())
    };
    if thread.is_null() {
        let err = std::io::Error::last_os_error();
        unsafe { VirtualFreeEx(process, remote, 0, MEM_RELEASE) };
        bail!("CreateRemoteThread failed: {err}");
    }

    // The remote thread's whole body is LoadLibraryW, so it exits as soon as the DLL is loaded.
    // DllMain hands off to its own thread immediately, so this does not wait for the JVM.
    unsafe { WaitForSingleObject(thread, INFINITE) };

    let mut exit_code: u32 = 0;
    let got = unsafe { GetExitCodeThread(thread, &mut exit_code) };
    unsafe {
        CloseHandle(thread);
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    }

    if got == FALSE {
        bail!("GetExitCodeThread failed: {}", std::io::Error::last_os_error());
    }
    if exit_code == 0 {
        bail!(
            "remote LoadLibraryW returned NULL — the DLL failed to load inside the target. \
             The usual cause is a missing dependency next to {}; check that the JVM is reachable.",
            dll.display()
        );
    }
    println!("[projectx-injector] LoadLibraryW returned (low32) 0x{exit_code:x}");
    Ok(())
}

fn to_wide<S: AsRef<OsStr>>(s: S) -> Vec<u16> {
    s.as_ref().encode_wide().chain(once(0u16)).collect()
}

fn wide_to_string(buf: &[u16]) -> String {
    let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..len])
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows_sys::Win32::Foundation::MAX_PATH;

    #[test]
    fn wide_roundtrip_is_nul_terminated() {
        let w = to_wide("rs2client.exe");
        assert_eq!(w.last(), Some(&0u16));
        assert_eq!(wide_to_string(&w), "rs2client.exe");
    }

    #[test]
    fn wide_to_string_stops_at_nul() {
        let buf: Vec<u16> = "abc\0def".encode_utf16().collect();
        assert_eq!(wide_to_string(&buf), "abc");
    }

    #[test]
    fn max_path_buffer_without_nul_is_fully_consumed() {
        let buf = vec![b'x' as u16; MAX_PATH as usize];
        assert_eq!(wide_to_string(&buf).len(), MAX_PATH as usize);
    }
}
