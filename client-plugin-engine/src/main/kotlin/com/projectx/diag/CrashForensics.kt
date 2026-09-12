package com.projectx.diag

/**
 * Drops breadcrumbs into the native ring buffer in libprojectxbootstrap.so. On a
 * SIGSEGV the pre-JVM signal handler dumps the last N breadcrumbs to
 * ~/.projectx/logs/last-crash.txt alongside fault address + register state.
 *
 * Native methods are registered via `RegisterNatives` from C++ after the JVM
 * initializes; there is no [System.loadLibrary] step.
 *
 * Call sites should be cheap and may run on any thread.
 */
object CrashForensics {
    /** Standard breadcrumb. Pass a short tag identifying the operation. */
    fun trace(tag: String) {
        try {
            breadcrumbNative(tag, 0L)
        } catch (_: Throwable) {
            // If native binding isn't loaded (very early init, tests, etc.), swallow.
        }
    }

    /** Breadcrumb with an address (e.g. the pointer about to be dereferenced). */
    fun trace(tag: String, addr: Long) {
        try {
            breadcrumbNative(tag, addr)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    private external fun breadcrumbNative(tag: String, addr: Long)
}
