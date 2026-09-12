#include "../projectx_platform.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <arpa/inet.h>
#include <dirent.h>
#include <dlfcn.h>
#include <limits.h>
#include <execinfo.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <funchook.h>
#include <pthread.h>
#include <pwd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>

using namespace projectx::forensics;

namespace projectx::platform {

const char *home_dir() {
    struct passwd *pw = getpwuid(getuid());
    if (pw && pw->pw_dir && pw->pw_dir[0]) return pw->pw_dir;
    const char *h = std::getenv("HOME");
    return h ? h : "/tmp";
}

const char *module_directory() {
    static char resolved[512] = {0};
    if (resolved[0]) return resolved;
    Dl_info info{};
    // Any address inside this library identifies it; the function's own is the obvious choice.
    if (dladdr(reinterpret_cast<void *>(&module_directory), &info) && info.dli_fname) {
        std::snprintf(resolved, sizeof(resolved), "%s", info.dli_fname);
        if (char *slash = std::strrchr(resolved, '/')) *slash = '\0';
    }
    return resolved;
}

const char *temp_dir() { return "/tmp"; }

uint64_t current_thread_id() { return (uint64_t)syscall(SYS_gettid); }

uintptr_t main_module_base() {
    char maps_path[256];
    std::snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", getpid());

    FILE *maps_file = std::fopen(maps_path, "r");
    if (!maps_file) {
        log("[ERROR] fopen for maps file failed\n");
        return 0;
    }

    unsigned long base_address = 0;
    char line[256];
    while (std::fgets(line, sizeof(line), maps_file)) {
        if (std::strstr(line, "r-xp") && !std::strstr(line, "vdso")) {
            std::sscanf(line, "%lx", &base_address);
            break;
        }
    }

    std::fclose(maps_file);
    return base_address;
}

void jvm_library_path(char *out, size_t out_size, const char *java_home) {
    std::snprintf(out, out_size, "%s/lib/server/libjvm.so", java_home);
}

static bool holds_jvm(const char *java_home) {
    char probe[512];
    jvm_library_path(probe, sizeof(probe), java_home);
    return access(probe, R_OK) == 0;
}

/// First digit run in a JDK directory name — "java-25-openjdk" and "jdk-21.0.2" score 25 and 21, so
/// the newest install wins a root that holds several. Legacy "java-1.8.0-*" scores 1 and loses.
static int version_score(const char *name) {
    for (const char *c = name; *c; ++c)
        if (*c >= '0' && *c <= '9') return std::atoi(c);
    return 0;
}

static void consider_java_root(const char *root, char *best, size_t best_size, int *best_score) {
    DIR *dir = opendir(root);
    if (!dir) return;
    while (dirent *entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        char candidate[512];
        std::snprintf(candidate, sizeof(candidate), "%s/%s", root, entry->d_name);
        int score = version_score(entry->d_name);
        if (score <= *best_score || !holds_jvm(candidate)) continue;
        std::snprintf(best, best_size, "%s", candidate);
        *best_score = score;
    }
    closedir(dir);
}

const char *discover_java_home() {
    static char resolved[512] = {0};
    static bool searched = false;
    if (searched) return resolved[0] ? resolved : nullptr;
    searched = true;

    const char *jdk_home = std::getenv("JDK_HOME");
    if (jdk_home && jdk_home[0] && holds_jvm(jdk_home)) {
        std::snprintf(resolved, sizeof(resolved), "%s", jdk_home);
        return resolved;
    }

    // The JDK the user actually runs outranks anything a version scan picks, so resolve `java` on
    // PATH through its symlinks first and walk up out of bin/.
    char launcher[PATH_MAX];
    if (realpath("/usr/bin/java", launcher)) {
        char *bin = std::strrchr(launcher, '/');
        char *home = bin ? (*bin = 0, std::strrchr(launcher, '/')) : nullptr;
        if (home) {
            *home = 0;
            if (holds_jvm(launcher)) {
                std::snprintf(resolved, sizeof(resolved), "%s", launcher);
                return resolved;
            }
        }
    }

    static const char *roots[] = {"/usr/lib/jvm", "/usr/lib64/jvm", "/opt/java",
                                  "/Library/Java/JavaVirtualMachines"};
    int best_score = 0;
    for (const char *root : roots) consider_java_root(root, resolved, sizeof(resolved), &best_score);
    return resolved[0] ? resolved : nullptr;
}

void *load_library(const char *path) { return dlopen(path, RTLD_NOW | RTLD_GLOBAL); }

void *library_symbol(void *handle, const char *name) {
    return dlsym(handle ? handle : RTLD_DEFAULT, name);
}

const char *load_error() { return dlerror(); }

char path_list_separator() { return ':'; }

bool start_detached_thread(void *(*entry)(void *), void *arg) {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, entry, arg) != 0) return false;
    pthread_detach(thread);
    return true;
}

static bool is_port_available(int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = INADDR_ANY;

    int result = bind(sock, (struct sockaddr *)&addr, sizeof(addr));
    close(sock);
    return result == 0;
}

int find_available_port(int start_port) {
    for (int port = start_port; port < start_port + 100; port++) {
        if (is_port_available(port)) return port;
    }
    return start_port;
}

void redirect_stderr(const char *path) { std::freopen(path, "a", stderr); }

void redirect_stdout(const char *path) { std::freopen(path, "a", stdout); }

void make_directory(const char *path) { mkdir(path, 0755); }

int open_truncating(const char *path) {
    return open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
}

void close_descriptor(int fd) { close(fd); }

void raw_write(int fd, const char *bytes, size_t len) {
    while (len > 0) {
        ssize_t written = write(fd, bytes, len);
        if (written < 0) return;
        bytes += written;
        len -= written;
    }
}

// ============================================================================
// Fatal-fault handling.
//
// The handler is installed BEFORE the JVM starts so HotSpot picks it up as its
// "previous" handler and chains to us on a fatal fault. libjsig (loaded by the
// core before libjvm) is what makes that chaining happen.
// ============================================================================

static const char *g_crash_log_path = nullptr;
static const char *g_exit_log_path = nullptr;

static struct sigaction prev_sigsegv, prev_sigbus, prev_sigabrt, prev_sigfpe;
static struct sigaction prev_sigill, prev_sigterm, prev_sighup, prev_sigquit;
static std::atomic<int> crash_handler_reentry{0};
static char crash_altstack[256 * 1024];

static struct sigaction *previous_handler(int sig) {
    switch (sig) {
        case SIGSEGV: return &prev_sigsegv;
        case SIGBUS:  return &prev_sigbus;
        case SIGABRT: return &prev_sigabrt;
        case SIGFPE:  return &prev_sigfpe;
        case SIGILL:  return &prev_sigill;
        case SIGTERM: return &prev_sigterm;
        case SIGHUP:  return &prev_sighup;
        case SIGQUIT: return &prev_sigquit;
        default:      return nullptr;
    }
}

static void chain_to_previous(struct sigaction *prev, int sig, siginfo_t *info, void *ctx) {
    if (!prev) return;
    if (prev->sa_flags & SA_SIGINFO) {
        if (prev->sa_sigaction) prev->sa_sigaction(sig, info, ctx);
    } else if (prev->sa_handler && prev->sa_handler != SIG_DFL && prev->sa_handler != SIG_IGN) {
        prev->sa_handler(sig);
    }
}

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGABRT: return "SIGABRT";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTERM: return "SIGTERM (external — launcher / parent process / kill -15)";
        case SIGHUP:  return "SIGHUP (controlling tty closed / external)";
        case SIGQUIT: return "SIGQUIT (external Ctrl-\\ / kill -3)";
        default:      return "other";
    }
}

static void crash_signal_handler(int sig, siginfo_t *info, void *ctx) {
    struct sigaction *prev = previous_handler(sig);

    // A recursive fault inside the handler must not loop.
    if (crash_handler_reentry.fetch_add(1) > 0) {
        chain_to_previous(prev, sig, info, ctx);
        return;
    }

    int fd = open_truncating(g_crash_log_path);
    if (fd >= 0) {
        write_str(fd, "=== PROJECT X CRASH FORENSICS ===\nsignal: ");
        write_dec(fd, (uint64_t)sig);
        write_str(fd, " (");
        write_str(fd, signal_name(sig));
        write_str(fd, ")\nfault_addr: 0x");
        write_hex(fd, (uint64_t)(uintptr_t)info->si_addr);
        write_str(fd, "\nsi_code: ");
        write_dec(fd, (uint64_t)info->si_code);
        write_str(fd, "\ntid: ");
        write_dec(fd, (uint64_t)syscall(SYS_gettid));
        ucontext_t *uc = (ucontext_t *)ctx;
        write_str(fd, "\nip: 0x");
        write_hex(fd, (uint64_t)uc->uc_mcontext.gregs[REG_RIP]);
        write_str(fd, "\nrsp: 0x");
        write_hex(fd, (uint64_t)uc->uc_mcontext.gregs[REG_RSP]);

        dump_breadcrumbs(fd);

        // Risky LAST: backtrace() walks frames and ELF headers and can re-fault on a corrupted
        // stack. Everything above is already flushed by unbuffered writes before we attempt it.
        write_str(fd, "\n=== Native backtrace (may be truncated if it refaults) ===\n");
        void *bt[80];
        int btn = backtrace(bt, 80);
        backtrace_symbols_fd(bt, btn, fd);

        write_str(fd, "=== END ===\n");
        close_descriptor(fd);
    }

    chain_to_previous(prev, sig, info, ctx);

    // No previous handler: restore the default and re-raise so the process dies properly.
    signal(sig, SIG_DFL);
    raise(sig);
}

void install_crash_handler(const char *crash_log_path) {
    g_crash_log_path = crash_log_path;

    // Dedicated signal stack so a stack-overflow crash can still be dumped.
    stack_t ss{};
    ss.ss_sp = crash_altstack;
    ss.ss_size = sizeof(crash_altstack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    // Pre-warm backtrace() so the first in-handler call doesn't lazily dlopen libgcc_s.
    void *warm[4];
    (void)backtrace(warm, 4);

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &prev_sigsegv);
    sigaction(SIGBUS, &sa, &prev_sigbus);
    // SIGABRT covers the JVM's os::abort() path; SIGFPE/SIGILL the rarer faults. Without these,
    // hard crashes that bypass the SEGV path leave no last-crash.txt at all.
    sigaction(SIGABRT, &sa, &prev_sigabrt);
    sigaction(SIGFPE, &sa, &prev_sigfpe);
    sigaction(SIGILL, &sa, &prev_sigill);
    // Termination signals aren't crashes, but the launcher sends them when it kills the client, so
    // capturing them distinguishes "killed externally" from "JVM crashed internally".
    sigaction(SIGTERM, &sa, &prev_sigterm);
    sigaction(SIGHUP, &sa, &prev_sighup);
    sigaction(SIGQUIT, &sa, &prev_sigquit);
}

// ============================================================================
// Exit-path forensics. A crash that leaves no signal artifacts is an exit(),
// which signals cannot catch — so the libc exit functions are hooked instead.
// ============================================================================

static std::atomic<int> exit_forensics_done{0};

typedef void (*exit_fn_t)(int);
static exit_fn_t real_exit = nullptr;
static exit_fn_t real__exit = nullptr;
static exit_fn_t real__Exit = nullptr;

static void write_exit_forensics(const char *via, int code) {
    if (exit_forensics_done.fetch_add(1) > 0) return;
    int fd = open_truncating(g_exit_log_path);
    if (fd < 0) return;
    write_str(fd, "=== PROJECT X EXIT FORENSICS ===\nvia: ");
    write_str(fd, via);
    write_str(fd, "\nexit_code: ");
    write_dec(fd, (uint64_t)(unsigned)code);
    write_str(fd, "\ntid: ");
    write_dec(fd, (uint64_t)syscall(SYS_gettid));
    dump_breadcrumbs(fd);
    write_str(fd, "\n=== Native backtrace ===\n");
    void *bt[80];
    int btn = backtrace(bt, 80);
    backtrace_symbols_fd(bt, btn, fd);
    write_str(fd, "=== END ===\n");
    close_descriptor(fd);
}

static void hook_exit(int code)  { write_exit_forensics("exit()", code);  real_exit(code);  __builtin_unreachable(); }
static void hook__exit(int code) { write_exit_forensics("_exit()", code); real__exit(code); __builtin_unreachable(); }
static void hook__Exit(int code) { write_exit_forensics("_Exit()", code); real__Exit(code); __builtin_unreachable(); }

static void install_one_exit_hook(const char *name, void *replacement, exit_fn_t *saved) {
    void *target = library_symbol(nullptr, name);
    if (!target) { log("[FORENSICS] exit hook: %s not found\n", name); return; }
    funchook_t *fh = funchook_create();
    if (!fh) return;
    void *tgt = target;
    if (funchook_prepare(fh, &tgt, replacement) != FUNCHOOK_ERROR_SUCCESS) { funchook_destroy(fh); return; }
    *saved = (exit_fn_t)tgt;
    if (funchook_install(fh, 0) != FUNCHOOK_ERROR_SUCCESS) { funchook_destroy(fh); return; }
    log("[FORENSICS] hooked %s for exit forensics.\n", name);
}

void install_exit_hooks(const char *exit_log_path) {
    g_exit_log_path = exit_log_path;
    install_one_exit_hook("exit",  (void *)hook_exit,  &real_exit);
    install_one_exit_hook("_exit", (void *)hook__exit, &real__exit);
    install_one_exit_hook("_Exit", (void *)hook__Exit, &real__Exit);
}

} // namespace projectx::platform

// ============================================================================
// Entry points. The library is injected by dlopen, so the ELF constructor runs
// as soon as it is mapped.
// ============================================================================

void projectx_bootstrap_start(int pid);
void projectx_bootstrap_stop();

extern "C" __attribute__((constructor)) void on_load() {
    projectx_bootstrap_start(getpid());
}

extern "C" __attribute__((destructor)) void on_unload() {
    projectx_bootstrap_stop();
}
