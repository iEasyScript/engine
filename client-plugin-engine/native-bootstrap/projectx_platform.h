#pragma once

#include <cstddef>
#include <cstdint>

#if defined(__GNUC__) || defined(__clang__)
#define PROJECTX_PRINTF_FORMAT(fmt, first) __attribute__((format(printf, fmt, first)))
#else
#define PROJECTX_PRINTF_FORMAT(fmt, first)
#endif

// Host services the bootstrap needs that differ between the POSIX and Windows builds of the client.
// Everything else in the bootstrap — the breadcrumb ring, the JNI handshake, the JVM option set and
// the Supervisor call — is platform-neutral and lives in projectx_bootstrap.cpp.

namespace projectx::platform {

/// Real user home, ignoring $HOME: the launcher overrides it to its own data dir, which is where
/// crash logs were silently landing.
const char *home_dir();

/// Directory containing this bootstrap library. PROJECTX_HOME_DIR is defined to be exactly that, so
/// deriving it here removes the need for the injector to plant an environment variable in a process
/// it did not spawn — which POSIX can do via a gdb `setenv` call but Windows cannot.
const char *module_directory();

/// Directory for scratch logs the launcher and crash tooling look in.
const char *temp_dir();

/// Load address of the client's own main module.
uintptr_t main_module_base();

/// Current OS thread id, for correlating breadcrumbs with a faulting thread.
uint64_t current_thread_id();

/// Absolute path of the JVM shared library inside a JDK home.
void jvm_library_path(char *out, size_t out_size, const char *java_home);

/// Highest-versioned JDK home on the machine that actually contains a JVM library, or nullptr.
/// The Windows injector attaches to an already-running client and so cannot plant JAVA_HOME in it
/// the way the POSIX injector does through a gdb `setenv` call — the bootstrap has to find a JDK
/// itself. Only ever consulted when JAVA_HOME is unset, so an explicit setting always wins.
const char *discover_java_home();

/// Loads a shared library into the current process. Returns nullptr on failure.
void *load_library(const char *path);

/// Resolves an exported symbol; `handle` may be null to search already-loaded modules.
void *library_symbol(void *handle, const char *name);

/// Last dynamic-loader error, or nullptr when none is pending.
const char *load_error();

/// Separator between entries of a classpath or library path.
char path_list_separator();

/// Runs `entry(arg)` on a new detached thread. Returns false if the thread could not be started.
bool start_detached_thread(void *(*entry)(void *), void *arg);

/// First free TCP port at or after `start_port`, falling back to `start_port` if none is free.
int find_available_port(int start_port);

/// Redirects the process's standard error to `path`, so JVM diagnostics reach a known file.
void redirect_stderr(const char *path);

/// Redirects the process's standard output to `path`. A Windows GUI-subsystem process is given no
/// console, so its stdout descriptor is invalid and every `System.out` write the engine makes fails
/// silently — taking the supervisor's startup diagnostics with it.
void redirect_stdout(const char *path);

/// Creates a directory, ignoring "already exists".
void make_directory(const char *path);

/// Signal-safe write of `len` bytes to an already-open file descriptor/handle.
void raw_write(int fd, const char *bytes, size_t len);

/// Opens `path` for truncating write, returning a descriptor usable with raw_write, or -1.
int open_truncating(const char *path);

void close_descriptor(int fd);

/// Installs the fatal-fault handler. `crash_log_path` must outlive the process.
void install_crash_handler(const char *crash_log_path);

/// Installs hooks on the process-exit paths that bypass fault handling.
void install_exit_hooks(const char *exit_log_path);

} // namespace projectx::platform

// Provided by the platform-neutral core and called back from the platform fault/exit handlers, which
// own the signal- or SEH-specific machinery but share this reporting.
namespace projectx::forensics {

/// Appends the breadcrumb trail, newest first. Touches only static memory, so it is safe from a
/// fault handler and cannot itself fault.
void dump_breadcrumbs(int fd);

void log(const char *format, ...) PROJECTX_PRINTF_FORMAT(1, 2);

void write_dec(int fd, uint64_t value);
void write_hex(int fd, uint64_t value);
void write_str(int fd, const char *text);

} // namespace projectx::forensics
