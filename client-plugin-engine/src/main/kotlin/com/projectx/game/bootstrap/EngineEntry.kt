package com.projectx.game.bootstrap

import com.projectx.BuildInfo
import com.projectx.supervisor.EngineHandle

/**
 * The engine's implementation of the supervisor's [EngineHandle] — the single class the disposable
 * `URLClassLoader` instantiates per (re)inject. [start] initialises the engine; [stop] fully tears
 * it down so this classloader can be dropped and a rebuilt jar loaded fresh.
 *
 * [EngineHandle] is resolved from the shared parent loader (it is excluded from the engine shadow
 * jar), so this cross-loader implements + cast works.
 */
class EngineEntry : EngineHandle {
    override fun start(baseAddr: Long) = Bootstrap.initialize(baseAddr)

    override fun stop() = Bootstrap.shutdown()

    override fun version(): String = BuildInfo.VERSION
}
