#include "../projectx_platform.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// Ahead of windows.h deliberately: winsock2.h defines _WINSOCKAPI_, which stops windows.h pulling in
// the Winsock 1.1 header that would then collide with it. ws2tcpip.h carries the IPv6 definitions.
#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.h>

#include <dbghelp.h>
#include <fcntl.h>
#include <funchook.h>
#include <io.h>
#include <sys/stat.h>
#include <shlobj.h>

using namespace projectx::forensics;

namespace projectx::platform {

const char *home_dir() {
    static char resolved[MAX_PATH] = {0};
    if (resolved[0]) return resolved;

    PWSTR wide = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Profile, 0, nullptr, &wide))) {
        WideCharToMultiByte(CP_UTF8, 0, wide, -1, resolved, sizeof(resolved), nullptr, nullptr);
        CoTaskMemFree(wide);
        if (resolved[0]) return resolved;
    }

    const char *profile = std::getenv("USERPROFILE");
    if (profile && profile[0]) {
        std::snprintf(resolved, sizeof(resolved), "%s", profile);
        return resolved;
    }
    std::snprintf(resolved, sizeof(resolved), "C:\\Temp");
    return resolved;
}

const char *module_directory() {
    static char resolved[MAX_PATH] = {0};
    if (resolved[0]) return resolved;
    HMODULE self = nullptr;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           reinterpret_cast<LPCSTR>(&module_directory), &self) && self) {
        GetModuleFileNameA(self, resolved, sizeof(resolved));
        char *slash = std::strrchr(resolved, '\\');
        char *fwd = std::strrchr(resolved, '/');
        if (fwd > slash) slash = fwd;
        if (slash) *slash = '\0';
    }
    return resolved;
}

const char *temp_dir() {
    static char resolved[MAX_PATH] = {0};
    if (!resolved[0]) {
        DWORD n = GetTempPathA(sizeof(resolved), resolved);
        if (n == 0 || n >= sizeof(resolved)) std::snprintf(resolved, sizeof(resolved), "C:\\Temp");
        // GetTempPathA leaves a trailing separator; the callers append their own.
        size_t len = std::strlen(resolved);
        if (len > 0 && (resolved[len - 1] == '\\' || resolved[len - 1] == '/')) resolved[len - 1] = 0;
    }
    return resolved;
}

uint64_t current_thread_id() { return (uint64_t)GetCurrentThreadId(); }

uintptr_t main_module_base() { return (uintptr_t)GetModuleHandleW(nullptr); }

void jvm_library_path(char *out, size_t out_size, const char *java_home) {
    std::snprintf(out, out_size, "%s\\bin\\server\\jvm.dll", java_home);
}

static bool holds_jvm(const char *java_home) {
    char probe[MAX_PATH];
    jvm_library_path(probe, sizeof(probe), java_home);
    return GetFileAttributesA(probe) != INVALID_FILE_ATTRIBUTES;
}

/// First digit run in a JDK directory name — "jdk-25.0.1" and "jdk-21" score 25 and 21, so the
/// newest install wins a root that holds several. Legacy "jre1.8.0_401" scores 1 and loses.
static int version_score(const char *name) {
    for (const char *c = name; *c; ++c)
        if (*c >= '0' && *c <= '9') return std::atoi(c);
    return 0;
}

static void consider_java_root(const char *root, char *best, size_t best_size, int *best_score) {
    char pattern[MAX_PATH];
    std::snprintf(pattern, sizeof(pattern), "%s\\*", root);
    WIN32_FIND_DATAA entry;
    HANDLE find = FindFirstFileA(pattern, &entry);
    if (find == INVALID_HANDLE_VALUE) return;
    do {
        if (!(entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (entry.cFileName[0] == '.') continue;
        char candidate[MAX_PATH];
        std::snprintf(candidate, sizeof(candidate), "%s\\%s", root, entry.cFileName);
        int score = version_score(entry.cFileName);
        if (score <= *best_score || !holds_jvm(candidate)) continue;
        std::snprintf(best, best_size, "%s", candidate);
        *best_score = score;
    } while (FindNextFileA(find, &entry));
    FindClose(find);
}

const char *discover_java_home() {
    static char resolved[MAX_PATH] = {0};
    static bool searched = false;
    if (searched) return resolved[0] ? resolved : nullptr;
    searched = true;

    const char *jdk_home = std::getenv("JDK_HOME");
    if (jdk_home && jdk_home[0] && holds_jvm(jdk_home)) {
        std::snprintf(resolved, sizeof(resolved), "%s", jdk_home);
        return resolved;
    }

    // The JDK the user actually runs outranks anything a version scan picks, so take java.exe off
    // PATH first and walk up out of bin\.
    char launcher[MAX_PATH];
    if (SearchPathA(nullptr, "java.exe", nullptr, sizeof(launcher), launcher, nullptr)) {
        char *bin = std::strrchr(launcher, '\\');
        char *home = bin ? (*bin = 0, std::strrchr(launcher, '\\')) : nullptr;
        if (home) {
            *home = 0;
            if (holds_jvm(launcher)) {
                std::snprintf(resolved, sizeof(resolved), "%s", launcher);
                return resolved;
            }
        }
    }

    static const char *bases[] = {"ProgramFiles", "ProgramW6432", "LOCALAPPDATA"};
    static const char *vendors[] = {"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto",
                                    "Programs\\Eclipse Adoptium"};
    int best_score = 0;
    for (const char *base : bases) {
        const char *prefix = std::getenv(base);
        if (!prefix || !prefix[0]) continue;
        for (const char *vendor : vendors) {
            char root[MAX_PATH];
            std::snprintf(root, sizeof(root), "%s\\%s", prefix, vendor);
            consider_java_root(root, resolved, sizeof(resolved), &best_score);
        }
    }
    return resolved[0] ? resolved : nullptr;
}

void *load_library(const char *path) { return (void *)LoadLibraryA(path); }

void *library_symbol(void *handle, const char *name) {
    if (handle) return (void *)GetProcAddress((HMODULE)handle, name);
    // A null handle means "search what is already loaded". The exit-path entry points the forensics
    // hooks want live in kernel32/ntdll, and the process image is checked first for completeness.
    static const wchar_t *modules[] = {nullptr, L"kernel32.dll", L"ntdll.dll", L"ucrtbase.dll"};
    for (const wchar_t *module : modules) {
        HMODULE h = GetModuleHandleW(module);
        if (!h) continue;
        if (FARPROC proc = GetProcAddress(h, name)) return (void *)proc;
    }
    return nullptr;
}

const char *load_error() {
    static char message[512];
    DWORD code = GetLastError();
    if (code == 0) return nullptr;
    DWORD written = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, code,
        MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT), message, sizeof(message) - 1, nullptr);
    if (written == 0) std::snprintf(message, sizeof(message), "error %lu", (unsigned long)code);
    return message;
}

char path_list_separator() { return ';'; }

namespace {
struct ThreadStart {
    void *(*entry)(void *);
    void *arg;
};

DWORD WINAPI thread_trampoline(LPVOID raw) {
    ThreadStart *start = (ThreadStart *)raw;
    ThreadStart copy = *start;
    delete start;
    copy.entry(copy.arg);
    return 0;
}
} // namespace

bool start_detached_thread(void *(*entry)(void *), void *arg) {
    auto *start = new ThreadStart{entry, arg};
    HANDLE thread = CreateThread(nullptr, 0, thread_trampoline, start, 0, nullptr);
    if (!thread) {
        delete start;
        return false;
    }
    CloseHandle(thread);
    return true;
}

static bool bind_probe(int family, int port) {
    SOCKET sock = socket(family, SOCK_STREAM, 0);
    // A family the host does not support cannot be holding the port against us.
    if (sock == INVALID_SOCKET) return true;

    int result;
    if (family == AF_INET6) {
        // Dual-stack, matching how the JVM's agent binds `*`: an IPv6 socket left v6-only would not
        // collide with an IPv4 holder and the probe would call the port free.
        DWORD v6_only = 0;
        setsockopt(sock, IPPROTO_IPV6, IPV6_V6ONLY, (const char *)&v6_only, sizeof(v6_only));

        sockaddr_in6 addr{};
        addr.sin6_family = AF_INET6;
        addr.sin6_port = htons((u_short)port);
        addr.sin6_addr = in6addr_any;
        result = bind(sock, (sockaddr *)&addr, sizeof(addr));
    } else {
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons((u_short)port);
        addr.sin_addr.s_addr = INADDR_ANY;
        result = bind(sock, (sockaddr *)&addr, sizeof(addr));
    }

    closesocket(sock);
    return result == 0;
}

static bool is_port_available(int port) {
    // Winsock is already initialised by the client (it is a network game), but the bootstrap must
    // not depend on load order, and repeated WSAStartup calls are refcounted and harmless.
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;

    // The JVM's agent resolves `*` to an IPv6 socket, which Windows leaves v6-only by default. An
    // IPv4-only probe cannot see that listener, so it reports a port free that the agent then fails
    // to bind — and a failed agent aborts VM init from inside JNI_CreateJavaVM.
    bool available = bind_probe(AF_INET6, port) && bind_probe(AF_INET, port);

    WSACleanup();
    return available;
}

int find_available_port(int start_port) {
    for (int port = start_port; port < start_port + 100; port++) {
        if (is_port_available(port)) return port;
    }
    return start_port;
}

/// Points both the CRT stream and the process-wide std handle at `path`.
///
/// `freopen` alone only rebinds this module's CRT descriptor. The JVM backs `System.out` and
/// `System.err` with `GetStdHandle`, which in a GUI-subsystem process returns null — so every
/// Java-level write is discarded no matter what the CRT descriptor points at, and the engine's
/// startup diagnostics disappear. Rebinding the std handle too is what makes them observable.
static void redirect_stream(const char *path, FILE *stream, DWORD std_handle) {
    if (std::freopen(path, "a", stream) == nullptr) return;
    intptr_t osf = _get_osfhandle(_fileno(stream));
    if (osf != -1) SetStdHandle(std_handle, reinterpret_cast<HANDLE>(osf));
}

void redirect_stderr(const char *path) { redirect_stream(path, stderr, STD_ERROR_HANDLE); }

void redirect_stdout(const char *path) { redirect_stream(path, stdout, STD_OUTPUT_HANDLE); }

void make_directory(const char *path) { CreateDirectoryA(path, nullptr); }

int open_truncating(const char *path) {
    return _open(path, _O_WRONLY | _O_CREAT | _O_TRUNC | _O_BINARY, _S_IREAD | _S_IWRITE);
}

void close_descriptor(int fd) { _close(fd); }

void raw_write(int fd, const char *bytes, size_t len) {
    while (len > 0) {
        int written = _write(fd, bytes, (unsigned int)len);
        if (written <= 0) return;
        bytes += written;
        len -= (size_t)written;
    }
}

// ============================================================================
// Fatal-fault handling.
//
// A vectored exception handler runs BEFORE any SEH frame, including HotSpot's,
// so unlike the POSIX side there is nothing to chain into and no libjsig
// equivalent is needed — we simply decline to handle and let the JVM proceed.
// ============================================================================

static const char *g_crash_log_path = nullptr;
static const char *g_exit_log_path = nullptr;
static std::atomic<int> crash_handler_reentry{0};

static const char *exception_name(DWORD code) {
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:      return "EXCEPTION_ACCESS_VIOLATION";
        case EXCEPTION_DATATYPE_MISALIGNMENT: return "EXCEPTION_DATATYPE_MISALIGNMENT";
        case EXCEPTION_ILLEGAL_INSTRUCTION:   return "EXCEPTION_ILLEGAL_INSTRUCTION";
        case EXCEPTION_INT_DIVIDE_BY_ZERO:    return "EXCEPTION_INT_DIVIDE_BY_ZERO";
        case EXCEPTION_STACK_OVERFLOW:        return "EXCEPTION_STACK_OVERFLOW";
        case EXCEPTION_IN_PAGE_ERROR:         return "EXCEPTION_IN_PAGE_ERROR";
        case EXCEPTION_PRIV_INSTRUCTION:      return "EXCEPTION_PRIV_INSTRUCTION";
        default:                              return "other";
    }
}

/// Only genuinely fatal faults are reported. The JVM raises benign exceptions constantly (implicit
/// null checks, safepoint polls, and 0x406D1388 thread-naming), and reporting those would both spam
/// the log and destroy the value of last-crash.txt.
static bool is_fatal(DWORD code) {
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:
        case EXCEPTION_ILLEGAL_INSTRUCTION:
        case EXCEPTION_INT_DIVIDE_BY_ZERO:
        case EXCEPTION_STACK_OVERFLOW:
        case EXCEPTION_IN_PAGE_ERROR:
        case EXCEPTION_PRIV_INSTRUCTION:
            return true;
        default:
            return false;
    }
}

static void write_native_backtrace(int fd, CONTEXT *context) {
    write_str(fd, "\n=== Native backtrace ===\n");

    HANDLE process = GetCurrentProcess();
    STACKFRAME64 frame{};
    frame.AddrPC.Offset = context->Rip;
    frame.AddrPC.Mode = AddrModeFlat;
    frame.AddrFrame.Offset = context->Rbp;
    frame.AddrFrame.Mode = AddrModeFlat;
    frame.AddrStack.Offset = context->Rsp;
    frame.AddrStack.Mode = AddrModeFlat;

    for (int depth = 0; depth < 80; ++depth) {
        if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, process, GetCurrentThread(), &frame, context,
                         nullptr, SymFunctionTableAccess64, SymGetModuleBase64, nullptr)) {
            break;
        }
        if (frame.AddrPC.Offset == 0) break;

        // Module-relative, matching how the offset tables and the Ghidra database address code.
        HMODULE module = nullptr;
        GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCSTR)frame.AddrPC.Offset, &module);
        char module_name[MAX_PATH] = "?";
        if (module) GetModuleFileNameA(module, module_name, sizeof(module_name));

        write_str(fd, "  ");
        write_str(fd, module_name);
        write_str(fd, "+0x");
        write_hex(fd, module ? frame.AddrPC.Offset - (uint64_t)module : frame.AddrPC.Offset);
        write_str(fd, "\n");
    }
}

static LONG CALLBACK crash_exception_handler(EXCEPTION_POINTERS *info) {
    DWORD code = info->ExceptionRecord->ExceptionCode;
    if (!is_fatal(code)) return EXCEPTION_CONTINUE_SEARCH;

    if (crash_handler_reentry.fetch_add(1) > 0) return EXCEPTION_CONTINUE_SEARCH;

    int fd = open_truncating(g_crash_log_path);
    if (fd >= 0) {
        write_str(fd, "=== PROJECT X CRASH FORENSICS ===\nexception: 0x");
        write_hex(fd, (uint64_t)code);
        write_str(fd, " (");
        write_str(fd, exception_name(code));
        write_str(fd, ")\nfault_addr: 0x");
        write_hex(fd, (uint64_t)(uintptr_t)info->ExceptionRecord->ExceptionAddress);
        if (code == EXCEPTION_ACCESS_VIOLATION && info->ExceptionRecord->NumberParameters >= 2) {
            write_str(fd, "\naccess: ");
            write_str(fd, info->ExceptionRecord->ExceptionInformation[0] ? "write" : "read");
            write_str(fd, " of 0x");
            write_hex(fd, (uint64_t)info->ExceptionRecord->ExceptionInformation[1]);
        }
        write_str(fd, "\ntid: ");
        write_dec(fd, (uint64_t)GetCurrentThreadId());
        write_str(fd, "\nip: 0x");
        write_hex(fd, (uint64_t)info->ContextRecord->Rip);
        write_str(fd, "\nrsp: 0x");
        write_hex(fd, (uint64_t)info->ContextRecord->Rsp);

        dump_breadcrumbs(fd);

        // StackWalk64 mutates the context it walks, so hand it a copy.
        CONTEXT walk_context = *info->ContextRecord;
        write_native_backtrace(fd, &walk_context);

        write_str(fd, "=== END ===\n");
        close_descriptor(fd);
    }

    // Let the JVM's own handler run: it writes hs_err and terminates the process properly.
    return EXCEPTION_CONTINUE_SEARCH;
}

void install_crash_handler(const char *crash_log_path) {
    g_crash_log_path = crash_log_path;
    SymSetOptions(SYMOPT_DEFERRED_LOADS | SYMOPT_UNDNAME);
    SymInitialize(GetCurrentProcess(), nullptr, TRUE);
    // First in the chain, so a fault is recorded before any SEH frame can swallow it.
    AddVectoredExceptionHandler(1, crash_exception_handler);
}

// ============================================================================
// Exit-path forensics: the exits that bypass exception handling entirely.
// ============================================================================

static std::atomic<int> exit_forensics_done{0};

typedef void(WINAPI *exit_process_fn)(UINT);
typedef BOOL(WINAPI *terminate_process_fn)(HANDLE, UINT);
static exit_process_fn real_exit_process = nullptr;
static terminate_process_fn real_terminate_process = nullptr;

static void write_exit_forensics(const char *via, uint64_t code) {
    if (exit_forensics_done.fetch_add(1) > 0) return;
    int fd = open_truncating(g_exit_log_path);
    if (fd < 0) return;
    write_str(fd, "=== PROJECT X EXIT FORENSICS ===\nvia: ");
    write_str(fd, via);
    write_str(fd, "\nexit_code: ");
    write_dec(fd, code);
    write_str(fd, "\ntid: ");
    write_dec(fd, (uint64_t)GetCurrentThreadId());
    dump_breadcrumbs(fd);
    write_str(fd, "=== END ===\n");
    close_descriptor(fd);
}

static void WINAPI hook_exit_process(UINT code) {
    write_exit_forensics("ExitProcess()", code);
    real_exit_process(code);
}

static BOOL WINAPI hook_terminate_process(HANDLE process, UINT code) {
    // Only our own termination is interesting; the client terminates child processes routinely.
    if (process == GetCurrentProcess()) write_exit_forensics("TerminateProcess(self)", code);
    return real_terminate_process(process, code);
}

static void install_one_exit_hook(const char *name, void *replacement, void **saved) {
    void *target = library_symbol(nullptr, name);
    if (!target) { log("[FORENSICS] exit hook: %s not found\n", name); return; }
    funchook_t *fh = funchook_create();
    if (!fh) return;
    void *tgt = target;
    if (funchook_prepare(fh, &tgt, replacement) != FUNCHOOK_ERROR_SUCCESS) { funchook_destroy(fh); return; }
    *saved = tgt;
    if (funchook_install(fh, 0) != FUNCHOOK_ERROR_SUCCESS) { funchook_destroy(fh); return; }
    log("[FORENSICS] hooked %s for exit forensics.\n", name);
}

void install_exit_hooks(const char *exit_log_path) {
    g_exit_log_path = exit_log_path;
    install_one_exit_hook("ExitProcess", (void *)hook_exit_process, (void **)&real_exit_process);
    install_one_exit_hook("TerminateProcess", (void *)hook_terminate_process, (void **)&real_terminate_process);
}

} // namespace projectx::platform

// ============================================================================
// Entry point. The injector uses CreateRemoteThread(LoadLibraryW), so DllMain
// runs on the loader lock — the real work is moved straight off it.
// ============================================================================

void projectx_bootstrap_start(int pid);
void projectx_bootstrap_stop();

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID /*reserved*/) {
    switch (reason) {
        case DLL_PROCESS_ATTACH:
            DisableThreadLibraryCalls(instance);
            projectx_bootstrap_start((int)GetCurrentProcessId());
            break;
        case DLL_PROCESS_DETACH:
            projectx_bootstrap_stop();
            break;
        default:
            break;
    }
    return TRUE;
}
