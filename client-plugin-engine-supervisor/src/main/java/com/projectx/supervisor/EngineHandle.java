package com.projectx.supervisor;

/**
 * The ONLY type that crosses the supervisor↔engine classloader boundary. It lives in the shared
 * parent loader (system classpath), so the {@code (EngineHandle)} cast in {@link Supervisor}
 * succeeds even though the implementing class {@code com.projectx.game.bootstrap.EngineEntry} and
 * everything it touches are defined by the disposable child {@code URLClassLoader}.
 *
 * <p>All methods use primitives / java.lang types only — NO Kotlin or engine types may appear here,
 * or the parent loader would need them on its classpath and the reload isolation would break.
 */
public interface EngineHandle {
    /** Initialise the engine (installs hooks, overlay, scripts). Was {@code Bootstrap.initialize}. */
    void start(long baseAddr);

    /** Fully tear the engine down so its classloader can be dropped (uninstall hooks, stop threads). */
    void stop();

    /** Identifies the loaded engine build; changes every rebuild (verification signal). */
    String version();
}
