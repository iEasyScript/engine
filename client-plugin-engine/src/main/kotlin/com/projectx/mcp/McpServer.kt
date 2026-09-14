package com.projectx.mcp

import com.projectx.mcp.tools.EntityTools
import com.projectx.mcp.tools.PacketLogTools
import com.projectx.mcp.tools.ActionTools
import com.projectx.mcp.tools.ContentTools
import com.projectx.mcp.tools.Cs2Tools
import com.projectx.mcp.tools.ExploreTools
import com.projectx.mcp.tools.GameStateTools
import com.projectx.mcp.tools.GrandExchangeTools
import com.projectx.mcp.tools.InterfaceTools
import com.projectx.mcp.tools.DungeonDebugTools
import com.projectx.mcp.tools.InventoryTools
import com.projectx.mcp.tools.LogTools
import com.projectx.mcp.tools.MemoryTools
import com.projectx.mcp.tools.MetaTools
import com.projectx.mcp.tools.ScriptTools
import com.projectx.mcp.tools.PlayerTools
import com.projectx.mcp.tools.ProjectionTools
import com.projectx.mcp.tools.VarTools
import com.projectx.mcp.tools.WorldTools
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.InetSocketAddress
import java.net.ServerSocket

object McpServer {

    const val PORT = 7882

    sealed class State {
        data object Disabled : State() {
            override fun toString() = "Disabled"
        }

        data object Starting : State() {
            override fun toString() = "Starting..."
        }

        data class Listening(val port: Int, val toolCount: Int) : State() {
            override fun toString() = "Listening on 127.0.0.1:$port ($toolCount tools)"
        }

        data object Stopping : State() {
            override fun toString() = "Stopping..."
        }

        data class Error(val message: String) : State() {
            override fun toString() = "Error: $message"
        }
    }

    @Volatile
    var state: State = State.Disabled
        private set

    private val lock = Any()
    private var ktorEngine: EmbeddedServer<*, *>? = null
    private var serverThread: Thread? = null

    fun start(): Result<Unit> = synchronized(lock) {
        when (val s = state) {
            is State.Listening -> return Result.success(Unit)
            State.Starting, State.Stopping -> return Result.failure(IllegalStateException("transition in progress: $s"))
            else -> {}
        }
        state = State.Starting

        probeBind(PORT)?.let { e ->
            state = State.Error("port $PORT in use by another client")
            return Result.failure(e)
        }

        return try {
            val server = createServer()
            val toolCount = lastToolCount
            val engine = embeddedServer(CIO, host = "127.0.0.1", port = PORT) {
                mcp { server }
            }
            val t = Thread({
                try {
                    engine.start(wait = true)
                } catch (e: Throwable) {
                    println("[MCP] engine error: ${e.message}")
                }
            }, "mcp-debug-server")
            t.isDaemon = true
            t.start()

            ktorEngine = engine
            serverThread = t
            state = State.Listening(PORT, toolCount)
            println("[MCP] started on 127.0.0.1:$PORT")
            Result.success(Unit)
        } catch (e: Throwable) {
            state = State.Error("start failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun stop(): Result<Unit> = synchronized(lock) {
        when (state) {
            State.Disabled -> return Result.success(Unit)
            State.Starting, State.Stopping -> return Result.failure(IllegalStateException("transition in progress"))
            else -> {}
        }
        state = State.Stopping

        return try {
            ktorEngine?.stop(gracePeriodMillis = 1_000L, timeoutMillis = 5_000L)
            ktorEngine = null
            serverThread?.join(6_000L)
            serverThread = null
            state = State.Disabled
            println("[MCP] stopped, port $PORT released")
            Result.success(Unit)
        } catch (e: Throwable) {
            state = State.Error("stop failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun probeBind(port: Int): Throwable? = try {
        ServerSocket().use { ss ->
            ss.reuseAddress = false
            ss.bind(InetSocketAddress("127.0.0.1", port))
        }
        null
    } catch (e: Throwable) {
        e
    }

    @Volatile
    private var lastToolCount: Int = 0

    private fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "projectx-debug", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        var count = 0
        count += MemoryTools.register(server)
        count += ProjectionTools.register(server)
        count += EntityTools.register(server)
        count += GameStateTools.register(server)
        count += PacketLogTools.register(server)
        count += MetaTools.register(server)
        count += PlayerTools.register(server)
        count += WorldTools.register(server)
        count += InterfaceTools.register(server)
        count += VarTools.register(server)
        count += InventoryTools.register(server)
        count += GrandExchangeTools.register(server)
        count += ContentTools.register(server)
        count += Cs2Tools.register(server)
        count += ActionTools.register(server)
        count += ExploreTools.register(server)
        // Generic script-automation tooling - keep.
        count += ScriptTools.register(server)
        count += LogTools.register(server)
        // Dungeoneering-specific debug tooling - REMOVE this line (and DungeonDebugTools.kt) once the
        // Dungeoneering script work is finished; nothing generic depends on it.
        count += DungeonDebugTools.register(server)

        lastToolCount = count
        return server
    }
}
