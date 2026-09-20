package com.projectx.supervisor;

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Permanent layer loaded once on the JVM system classpath (the native bootstrap calls
 * {@link #start(long, String)} instead of {@code Bootstrap.initialize}). It owns the engine's
 * {@link URLClassLoader} lifecycle and the per-pid control socket; uninject/reinject drop and
 * recreate the child loader so a rebuilt engine jar runs without recreating the JVM.
 */
public final class Supervisor {
    private static final Object LOCK = new Object();

    private static long baseAddr;
    private static String engineJarPath;

    private static volatile EngineHandle engine;
    private static volatile URLClassLoader engineLoader;
    private static volatile int reloads;
    private static volatile String lastError;
    private static ControlSocket control;

    private Supervisor() {}

    /**
     * Native entry point (replaces the direct call to {@code Bootstrap.initialize}). [engineHomeDir]
     * is {@code PROJECTX_HOME_DIR}; we resolve the engine shadow jar within it so the jar name is
     * not hardcoded in native.
     */
    public static void start(long baseAddr_, String engineHomeDir) {
        synchronized (LOCK) {
            baseAddr = baseAddr_;
            engineJarPath = resolveEngineJar(engineHomeDir);
            if (engineJarPath == null) {
                lastError = "no engine shadow jar found in " + engineHomeDir;
                System.err.println("[Supervisor] " + lastError);
                return;
            }
            try {
                loadEngine();
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                System.err.println("[Supervisor] initial loadEngine failed: " + t);
                t.printStackTrace();
            }
            try {
                control = new ControlSocket(Supervisor::command);
                control.start();
            } catch (Throwable t) {
                System.err.println("[Supervisor] control socket failed to start: " + t);
                t.printStackTrace();
            }
        }
    }

    private static void loadEngine() throws Exception {
        if (engine != null) return;
        URL[] urls = { new File(engineJarPath).toURI().toURL() };
        // Parent = the loader that defined Supervisor/EngineHandle (the system app loader). The
        // engine jar must NOT be on that classpath, or parent-first delegation would return stale
        // classes and a rebuilt jar would silently not take effect.
        URLClassLoader loader = new URLClassLoader("projectx-engine", urls, Supervisor.class.getClassLoader());
        Class<?> entry = Class.forName("com.projectx.game.bootstrap.EngineEntry", true, loader);
        EngineHandle handle = (EngineHandle) entry.getDeclaredConstructor().newInstance();
        handle.start(baseAddr);
        engine = handle;
        engineLoader = loader;
        lastError = null;
        System.out.println("[Supervisor] engine loaded (version=" + safeVersion() + ", reloads=" + reloads + ")");
    }

    private static void unloadEngine() {
        EngineHandle h = engine;
        URLClassLoader l = engineLoader;
        engine = null;
        engineLoader = null;
        if (h != null) {
            try { h.stop(); }
            catch (Throwable t) { System.err.println("[Supervisor] engine.stop() threw: " + t); t.printStackTrace(); }
        }
        if (l != null) {
            try { l.close(); } catch (Throwable ignored) {}
        }
        // Verify the child loader (and thus the old engine's classes/metaspace) can actually be
        // reclaimed. If this logs false repeatedly across reloads, something outside the loader still
        // pins it (a live upcall stub, JNI global ref, or engine-owned thread) — i.e. a metaspace leak.
        WeakReference<ClassLoader> ref = new WeakReference<>(l);
        h = null;
        l = null;
        boolean reclaimed = awaitClassloaderUnload(ref);
        System.out.println("[Supervisor] engine unloaded (classloader reclaimed=" + reclaimed + ")");
    }

    private static boolean awaitClassloaderUnload(WeakReference<ClassLoader> ref) {
        for (int i = 0; i < 4 && ref.get() != null; i++) {
            System.gc();
            try { Thread.sleep(50); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return ref.get() == null;
    }

    private static void reload() throws Exception {
        unloadEngine();
        loadEngine();
        reloads++;
    }

    /** Dispatch one control-socket command line; returns the single response line. */
    static String command(String raw) {
        synchronized (LOCK) {
            String cmd = raw.trim().toUpperCase();
            try {
                switch (cmd) {
                    case "STATUS": {
                        String state = engine != null ? "active" : (lastError != null ? "error" : "unloaded");
                        String resp = "OK " + state + " pid=" + pid() + " version=" + safeVersion() + " reloads=" + reloads;
                        return lastError != null ? resp + " error=" + sanitize(lastError) : resp;
                    }
                    case "PING":
                        return "OK pong";
                    case "UNINJECT":
                        unloadEngine();
                        return "OK unloaded";
                    case "INJECT":
                        if (engine == null) loadEngine();
                        return "OK active version=" + safeVersion();
                    case "REINJECT":
                        reload();
                        return "OK active version=" + safeVersion();
                    default:
                        return "ERR unknown command: " + sanitize(raw);
                }
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                t.printStackTrace();
                return "ERR " + sanitize(String.valueOf(t));
            }
        }
    }

    /** Find the engine shadow jar in {@code PROJECTX_HOME_DIR}: a `.jar` that isn't the supervisor. */
    private static String resolveEngineJar(String dir) {
        File[] jars = new File(dir).listFiles(f ->
            f.getName().endsWith(".jar") && !f.getName().startsWith("projectx-supervisor"));
        if (jars == null || jars.length == 0) return null;
        // Prefer the shadow (`-all.jar`) artifact, then the highest version in the filename.
        //
        // Size used to be the tie-break, which is only coincidentally the newest: the launcher removes a
        // superseded jar, but on Windows it cannot while an injected client still holds it open, so two
        // versions sit here and the older one must not win. Size falls back in for names with no version.
        File best = null;
        for (File f : jars) {
            if (f.getName().endsWith("-all.jar")) return f.getAbsolutePath();
            if (best == null) {
                best = f;
                continue;
            }
            int byVersion = compareVersions(jarVersion(f.getName()), jarVersion(best.getName()));
            if (byVersion > 0 || (byVersion == 0 && f.length() > best.length())) best = f;
        }
        return best.getAbsolutePath();
    }

    /** The numbers out of `projectx-engine-<version>.jar`, or empty when the name carries none. */
    private static int[] jarVersion(String name) {
        int dash = name.lastIndexOf('-');
        int dot = name.lastIndexOf(".jar");
        if (dash < 0 || dot <= dash) return new int[0];
        String[] parts = name.substring(dash + 1, dot).split("[.]");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return numbers;
    }

    /** Component-wise, so 1.0.34 beats 1.0.9 and a versionless name loses to any version. */
    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return Integer.compare(a.length, b.length);
    }

    private static String safeVersion() {
        EngineHandle h = engine;
        if (h == null) return "-";
        try { return h.version(); } catch (Throwable t) { return "?"; }
    }

    private static String sanitize(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static long pid() {
        return ProcessHandle.current().pid();
    }
}
