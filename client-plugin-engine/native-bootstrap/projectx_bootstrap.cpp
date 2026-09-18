#include "projectx_platform.h"

#include <jni.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace projectx::platform;

FILE *log_file = nullptr;
JavaVM *jvm = nullptr;

typedef jint (*JNI_CreateJavaVM_t)(JavaVM **, void **, void *);

// ============================================================================
// Crash forensics: breadcrumb ring buffer.
//
// Breadcrumbs are written from Kotlin via JNI. The ring is fixed-size static
// memory in this module's data segment so a fault handler can read it without
// touching the heap or taking a lock.
// ============================================================================

namespace projectx::forensics {

struct Breadcrumb {
    std::atomic<uint64_t> seq;   // sequence # when written (0 = empty slot)
    uint64_t tid;
    int64_t addr;
    char tag[96];
    char pad[8];                 // pad to 128 bytes for cache-line cleanliness
};

static constexpr size_t BREADCRUMB_COUNT = 64;
static volatile Breadcrumb breadcrumbs[BREADCRUMB_COUNT];
static std::atomic<uint64_t> breadcrumb_seq{1};   // start at 1; 0 means empty

void log(const char *format, ...) {
    va_list args;
    va_start(args, format);
    std::vfprintf(log_file, format, args);
    std::fflush(log_file);
    va_end(args);
}

void write_str(int fd, const char *text) { raw_write(fd, text, std::strlen(text)); }

void write_dec(int fd, uint64_t value) {
    char buf[24];
    int i = 23;
    buf[i] = 0;
    if (value == 0) { buf[--i] = '0'; write_str(fd, &buf[i]); return; }
    while (value > 0 && i > 0) { buf[--i] = (char)('0' + (value % 10)); value /= 10; }
    write_str(fd, &buf[i]);
}

void write_hex(int fd, uint64_t value) {
    static const char hex[] = "0123456789abcdef";
    char buf[20];
    int i = 19;
    buf[i] = 0;
    if (value == 0) { buf[--i] = '0'; write_str(fd, &buf[i]); return; }
    while (value > 0 && i > 0) { buf[--i] = hex[value & 0xf]; value >>= 4; }
    write_str(fd, &buf[i]);
}

void dump_breadcrumbs(int fd) {
    write_str(fd, "\n\n=== Last breadcrumbs (newest first) ===\n");

    uint64_t newest = breadcrumb_seq.load(std::memory_order_relaxed);
    uint64_t cutoff = newest > BREADCRUMB_COUNT ? newest - BREADCRUMB_COUNT : 1;
    for (uint64_t s = newest - 1; s + 1 > cutoff; s--) {
        volatile Breadcrumb &b = breadcrumbs[s % BREADCRUMB_COUNT];
        if (b.seq.load(std::memory_order_relaxed) != s) {
            // Slot got overwritten or was never written.
            if (s == 0) break;
            continue;
        }
        write_str(fd, "[seq=");
        write_dec(fd, s);
        write_str(fd, " tid=");
        write_dec(fd, b.tid);
        write_str(fd, "] ");
        write_str(fd, (const char *)b.tag);
        if (b.addr != 0) {
            write_str(fd, " addr=0x");
            write_hex(fd, (uint64_t)b.addr);
        }
        write_str(fd, "\n");
        if (s == 0) break;
    }
}

} // namespace projectx::forensics

using projectx::forensics::breadcrumbs;
using projectx::forensics::breadcrumb_seq;
using projectx::forensics::BREADCRUMB_COUNT;

extern "C" JNIEXPORT void JNICALL
breadcrumb_jni(JNIEnv *env, jclass /*cls*/, jstring tag, jlong addr) {
    uint64_t seq = breadcrumb_seq.fetch_add(1, std::memory_order_relaxed);
    volatile projectx::forensics::Breadcrumb &b = breadcrumbs[seq % BREADCRUMB_COUNT];
    b.tid = current_thread_id();
    b.addr = addr;
    const char *cstr = tag ? env->GetStringUTFChars(tag, nullptr) : "";
    if (cstr) {
        size_t n = std::strlen(cstr);
        if (n >= sizeof(b.tag)) n = sizeof(b.tag) - 1;
        std::memcpy((void *)b.tag, cstr, n);
        ((char *)b.tag)[n] = 0;
        if (tag) env->ReleaseStringUTFChars(tag, cstr);
    } else {
        ((char *)b.tag)[0] = 0;
    }
    // Publish seq last so a fault handler only ever sees a fully-written slot.
    b.seq.store(seq, std::memory_order_release);
}

static void register_forensics_natives(JNIEnv *env) {
    jclass cls = env->FindClass("com/projectx/diag/CrashForensics");
    if (!cls) {
        env->ExceptionClear();
        projectx::forensics::log("[FORENSICS] CrashForensics class not found; breadcrumbs disabled.\n");
        return;
    }
    static const JNINativeMethod methods[] = {
        { (char *)"breadcrumbNative", (char *)"(Ljava/lang/String;J)V", (void *)breadcrumb_jni },
    };
    if (env->RegisterNatives(cls, methods, 1) != 0) {
        projectx::forensics::log("[FORENSICS] RegisterNatives failed.\n");
        env->ExceptionClear();
        return;
    }
    env->NewGlobalRef(cls);
    projectx::forensics::log("[FORENSICS] breadcrumb JNI registered.\n");
}

// ============================================================================
// JDK resolution.
//
// The engine runs on a JVM started inside the game client, so the client — not the launcher — is
// where a missing or too-old JDK actually bites, and it bites quietly: the bootstrap is mapped by
// then, so every outside observer sees an injected client whose control socket simply never comes
// up. Everything below therefore validates a candidate before committing to it, and records in
// this log why each rejected one was rejected.
// ============================================================================

namespace projectx::platform {

/// `"25.0.1"` / `"25"` -> 25, `"1.8.0_402"` -> 8. A leading quote and spaces are tolerated so the
/// value can be handed straight over from a `release` line.
static int parse_feature_version(const char *raw) {
    while (*raw == ' ' || *raw == '"') ++raw;
    int major = std::atoi(raw);
    if (major != 1) return major;
    // Legacy 1.x scheme: the feature version is the second component.
    const char *dot = std::strchr(raw, '.');
    return dot ? std::atoi(dot + 1) : 1;
}

int jdk_feature_version(const char *java_home) {
    if (java_home == nullptr || java_home[0] == '\0') return -1;

    char probe[512];
    jvm_library_path(probe, sizeof(probe), java_home);
    if (std::FILE *jvm_lib = std::fopen(probe, "rb")) {
        std::fclose(jvm_lib);
    } else {
        return -1;
    }

    char release_path[512];
    std::snprintf(release_path, sizeof(release_path), "%s/release", java_home);
    std::FILE *release = std::fopen(release_path, "r");
    if (release == nullptr) return 0;

    static const char KEY[] = "JAVA_VERSION=";
    char line[256];
    int version = 0;
    while (std::fgets(line, sizeof(line), release)) {
        if (std::strncmp(line, KEY, sizeof(KEY) - 1) == 0) {
            version = parse_feature_version(line + sizeof(KEY) - 1);
            break;
        }
    }
    std::fclose(release);
    return version;
}

bool jdk_is_usable(const char *java_home) {
    int version = jdk_feature_version(java_home);
    return version == 0 || version >= MIN_JDK_FEATURE_VERSION;
}

} // namespace projectx::platform

/// A JDK home the launcher resolved, left in a file beside this library.
///
/// The launcher looks in more places than an in-process scan reasonably can — sdkman, asdf,
/// ~/.jdks, a JAVA_HOME exported from a shell rc file that a desktop session never sources — and on
/// Windows it has no way to plant an environment variable in a client it attaches to rather than
/// spawns. Writing the answer next to the library it is about to inject is how it hands that
/// resolution over.
static const char *java_home_hint() {
    using projectx::forensics::log;
    static char resolved[512] = {0};
    static bool read_once = false;
    if (read_once) return resolved[0] ? resolved : nullptr;
    read_once = true;

    const char *dir = module_directory();
    if (dir == nullptr || dir[0] == '\0') return nullptr;

    char hint_path[512];
    std::snprintf(hint_path, sizeof(hint_path), "%s/java-home.txt", dir);
    std::FILE *hint = std::fopen(hint_path, "r");
    if (hint == nullptr) return nullptr;

    char line[512] = {0};
    bool got = std::fgets(line, sizeof(line), hint) != nullptr;
    std::fclose(hint);
    if (!got) return nullptr;

    size_t len = std::strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r' || line[len - 1] == ' ' ||
                       line[len - 1] == '\t')) {
        line[--len] = '\0';
    }
    if (len == 0) return nullptr;

    log("Read a JDK hint from %s: %s\n", hint_path, line);
    std::snprintf(resolved, sizeof(resolved), "%s", line);
    return resolved;
}

/// Say why `java_home` was passed over, so this log names the JDK that is in the way rather than
/// only the fact that no JDK was used.
static void log_rejected_jdk(const char *source, const char *java_home) {
    using projectx::forensics::log;
    int version = jdk_feature_version(java_home);
    if (version < 0) {
        log("Ignoring %s (%s): no JVM library there.\n", source, java_home);
    } else {
        log("Ignoring %s (%s): it is Java %d and the engine needs %d or newer.\n", source, java_home,
            version, MIN_JDK_FEATURE_VERSION);
    }
}

/// The JDK the engine will run on, in descending order of how deliberate the choice is: an explicit
/// JAVA_HOME, an explicit JDK_HOME, the launcher's hint, then whatever the platform scan turns up.
///
/// Each source is validated rather than trusted. A JAVA_HOME left pointing at a JRE 8 or at a JDK
/// that has since been uninstalled used to end start-up right here — no fallback, and no trace of
/// it anywhere outside this file.
static const char *resolve_java_home() {
    using projectx::forensics::log;

    static const char *const ENV_VARS[] = {"JAVA_HOME", "JDK_HOME"};
    for (const char *name : ENV_VARS) {
        const char *value = std::getenv(name);
        if (value == nullptr || value[0] == '\0') continue;
        if (jdk_is_usable(value)) {
            log("Using the JDK from %s: %s\n", name, value);
            return value;
        }
        log_rejected_jdk(name, value);
    }

    if (const char *hinted = java_home_hint()) {
        if (jdk_is_usable(hinted)) return hinted;
        log_rejected_jdk("the launcher's java-home.txt", hinted);
    }

    if (const char *discovered = discover_java_home()) {
        log("Using the JDK discovered at %s\n", discovered);
        return discovered;
    }

    return nullptr;
}

void *initialize_projectx(void *base_address) {
    using projectx::forensics::log;

    JNIEnv *env;
    JavaVMInitArgs vm_args;
    JavaVMOption options[16];

    jclass supervisorClass = nullptr;
    jmethodID startMethod = nullptr;
    jstring engineHomeStr = nullptr;

    // PROJECTX_HOME_DIR is by definition the directory holding this library alongside the jars, so
    // fall back to deriving it. Only the POSIX injector can plant the variable in a process it did
    // not spawn (via a gdb `setenv` call); on Windows nothing sets it at all.
    const char *classpath_dir = std::getenv("PROJECTX_HOME_DIR");
    if (classpath_dir == nullptr || classpath_dir[0] == '\0') {
        classpath_dir = module_directory();
        log("PROJECTX_HOME_DIR unset; derived from the loaded module: %s\n", classpath_dir);
    }
    if (classpath_dir == nullptr || classpath_dir[0] == '\0') {
        log("Could not determine PROJECTX_HOME_DIR.\n");
        return nullptr;
    }

    const char *java_home = resolve_java_home();
    if (java_home == nullptr) {
        log("No JDK %d or newer could be located on this machine, so the engine cannot start. "
            "Install one and set JAVA_HOME to its home directory.\n",
            MIN_JDK_FEATURE_VERSION);
        return nullptr;
    }

    char libjvm[512];
    jvm_library_path(libjvm, sizeof(libjvm), java_home);

    log("Initializing Project X JVM bootstrap with classpath at %s\n", classpath_dir);

    // System classpath = the tiny pure-Java supervisor jar ONLY. The engine shadow jar must NOT be
    // here, or the supervisor's child URLClassLoader would parent-delegate to stale classes and a
    // rebuilt engine jar would never take effect on reinject. The supervisor resolves + loads the
    // engine shadow jar from PROJECTX_HOME_DIR itself.
    char classpath_option[2048];
    std::snprintf(classpath_option, sizeof(classpath_option),
                  "-Djava.class.path=%s/projectx-supervisor.jar", classpath_dir);

    char librarypath_option[512];
    std::snprintf(librarypath_option, sizeof(librarypath_option), "-Djava.library.path=%s", classpath_dir);

    char debug_option[128];
    int debug_port = find_available_port(5005);
    std::snprintf(debug_option, sizeof(debug_option),
                  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%d", debug_port);

    const char *home = home_dir();
    char error_file_option[512];
    std::snprintf(error_file_option, sizeof(error_file_option),
                  "-XX:ErrorFile=%s/.projectx/logs/hs_err_pid%%p.log", home);

    options[0].optionString = const_cast<char *>("--enable-native-access=ALL-UNNAMED");
    options[1].optionString = classpath_option;
    options[2].optionString = librarypath_option;
    options[3].optionString = const_cast<char *>("-verbose:jni");
    options[4].optionString = debug_option;
    options[5].optionString = error_file_option;
    // Lower JIT thresholds so render-hot paths (MeshProjection, SceneSnapshot) hit C2 compilation
    // in ~1-2 s instead of ~15 s after a toggle.
    options[6].optionString = const_cast<char *>("-XX:CompileThresholdScaling=0.2");
    // Force AWT headless so the texture loader's BufferedImage/Graphics2D never tries to open a
    // display; on an EGL/Wayland client that throws AWTError and poisons every subsequent frame.
    options[7].optionString = const_cast<char *>("-Djava.awt.headless=true");
    // The hs_err log alone captures nothing from threads outside the JVM's own, so request a core
    // too. Pair with an unlimited core rlimit, which the injector raises from outside.
    options[8].optionString = const_cast<char *>("-XX:+CreateCoredumpOnCrash");
    // Without this the JVM elides stack data on recurring throws after JIT warm-up, which makes
    // hs_err logs far less useful when a crash follows a chain of trapped exceptions.
    options[9].optionString = const_cast<char *>("-XX:-OmitStackTraceInFastThrow");

    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = 10;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_TRUE;

#ifndef _WIN32
    // Load libjsig BEFORE libjvm so its sigaction interposes the JVM's. Without this HotSpot
    // installs its own SIGSEGV/SIGBUS handlers and does NOT chain to ours on a fatal native fault,
    // so the crash forensics never run. Windows needs no equivalent: a vectored exception handler
    // already runs ahead of every SEH frame.
    char libjsig[512];
    std::snprintf(libjsig, sizeof(libjsig), "%s/lib/libjsig.so", java_home);
    if (load_library(libjsig))
        log("Loaded libjsig (JVM signal chaining enabled).\n");
    else
        log("WARN: libjsig not loaded (%s); crash chaining degraded.\n", load_error());
#endif

    log("Loading JVM library from %s\n", libjvm);

    void *handle = load_library(libjvm);
    if (!handle) {
        log("Failed to load the JVM library: %s\n", load_error());
        return nullptr;
    }
    load_error();

    JNI_CreateJavaVM_t JNI_CreateJavaVM =
        reinterpret_cast<JNI_CreateJavaVM_t>(library_symbol(handle, "JNI_CreateJavaVM"));
    if (!JNI_CreateJavaVM) {
        log("Failed to resolve JNI_CreateJavaVM: %s\n", load_error());
        return nullptr;
    }

    jint res = JNI_CreateJavaVM(&jvm, reinterpret_cast<void **>(&env), &vm_args);
    if (res != JNI_OK) {
        log("Failed to create JVM %d\n", res);
        return nullptr;
    }

    log("Successfully created JVM (%d). Finding Object class to verify success...\n", res);

    jclass objCls = env->FindClass("java/lang/Object");

    log("Successfully found Object class (%p). Registering forensics natives...\n",
        static_cast<void *>(objCls));

    register_forensics_natives(env);

    log("Finding Supervisor class...\n");

    supervisorClass = env->FindClass("com/projectx/supervisor/Supervisor");
    if (supervisorClass == nullptr) {
        log("Failed to find class com.projectx.supervisor.Supervisor\n");
        goto destroy;
    }

    log("Found Supervisor class (%p). Finding start method...\n", static_cast<void *>(supervisorClass));

    startMethod = env->GetStaticMethodID(supervisorClass, "start", "(JLjava/lang/String;)V");
    if (startMethod == nullptr) {
        log("Failed to find method start in class Supervisor\n");
        goto destroy;
    }

    log("Found start method (%p). Calling Supervisor.start with base %p, home %s\n",
        reinterpret_cast<void *>(startMethod), base_address, classpath_dir);

    engineHomeStr = env->NewStringUTF(classpath_dir);
    env->CallStaticVoidMethod(supervisorClass, startMethod,
                              reinterpret_cast<jlong>(base_address), engineHomeStr);
    if (env->ExceptionOccurred()) {
        log("Exception occurred calling Supervisor.start:\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    return nullptr;

destroy:
    jvm->DestroyJavaVM();
    return nullptr;
}

// Forward declarations for the overlay's platform hooks.
extern "C" void ProjectX_InitializeSDLHook();
extern "C" void ProjectX_CleanupSDLHook();

static char crash_log_path[512];
static char exit_log_path[512];

void projectx_bootstrap_start(int pid) {
    using projectx::forensics::log;

    char log_path[256];
    std::snprintf(log_path, sizeof(log_path), "%s/projectx_log_%d.txt", temp_dir(), pid);

    log_file = std::fopen(log_path, "a");
    if (log_file == nullptr) {
        log_file = stderr;
        std::printf("Failed to access logfile at %s\n", log_path);
    } else {
        char stream_path[256];
        std::snprintf(stream_path, sizeof(stream_path), "%s/projectx_stderr_%d.txt", temp_dir(), pid);
        redirect_stderr(stream_path);
        std::snprintf(stream_path, sizeof(stream_path), "%s/projectx_stdout_%d.txt", temp_dir(), pid);
        redirect_stdout(stream_path);
    }

    // The directory may not exist on first run; creating it is best-effort.
    const char *home = home_dir();
    char crash_dir[400];
    std::snprintf(crash_dir, sizeof(crash_dir), "%s/.projectx/logs", home);
    make_directory(crash_dir);
    std::snprintf(crash_log_path, sizeof(crash_log_path), "%s/last-crash.txt", crash_dir);
    std::snprintf(exit_log_path, sizeof(exit_log_path), "%s/last-exit.txt", crash_dir);

    // Installed BEFORE the JVM starts so HotSpot picks our handler up as its "previous" one.
    install_crash_handler(crash_log_path);
    log("[FORENSICS] crash handler installed; will write to %s\n", crash_log_path);

    // Catch non-fault process exits, which leave no fault artifacts at all.
    install_exit_hooks(exit_log_path);
    log("[FORENSICS] exit hooks installed; will write to %s\n", exit_log_path);

    ProjectX_InitializeSDLHook();

    uintptr_t base_address = main_module_base();
    log("Shared object loaded. Starting JVM into PID: %d at base address %p...\n",
        pid, reinterpret_cast<void *>(base_address));

    if (!start_detached_thread(initialize_projectx, reinterpret_cast<void *>(base_address))) {
        log("Failed to create thread\n");
    }
}

void projectx_bootstrap_stop() {
    projectx::forensics::log("Shared library unloaded!\n");
    ProjectX_CleanupSDLHook();
}
