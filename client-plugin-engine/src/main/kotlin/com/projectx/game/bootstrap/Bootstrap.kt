package com.projectx.game.bootstrap

import com.projectx.BuildInfo
import com.projectx.game.combat.CombatBridge
import com.projectx.game.hooks.HookManager
import com.projectx.game.input.InputRecorder
import com.projectx.game.memory.Funchook
import com.projectx.game.memory.NativeAccess
import com.projectx.util.Configuration
import com.projectx.game.net.PacketLogger
import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.game.net.packetlog.PacketUploadRunner
import com.projectx.game.nxt.Client
import com.projectx.game.nxt.GamevalCoverage
import com.projectx.game.nxt.OffsetCoverage
import com.projectx.markers.TileMarkerStore
import com.projectx.mcp.McpServer
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.solver.registerExampleSolvers
import com.projectx.script.ScriptExecutor
import com.projectx.ui.backend.native.StringAllocator
import com.projectx.util.EngineLog
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.VarBitType
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object Bootstrap {
    val lock = Any()
    lateinit var client: Client
    private var clientAttached = false

    /** Set during [shutdown] so the hot hooks become pure passthroughs before the hooks are removed. */
    @Volatile
    var stopping = false

    /**
     * Resolve the live NXT client cache dir. `RS_CACHE_DIR` (exported by the projectx launcher per
     * server mode) is authoritative; otherwise probe the known launcher locations and pick the first
     * that actually holds a cache. The injected engine runs inside rs2client whose HOME the launcher
     * redirects to its data dir, so the cache lands at `$HOME/Jagex/RuneScape`.
     */
    private fun resolveCacheDir(): Path {
        System.getenv("RS_CACHE_DIR")?.let {
            println("[Cache] cache dir = $it (RS_CACHE_DIR)")
            return Paths.get(it)
        }
        val home = System.getProperty("user.home")
        val candidates = buildList {
            // The Windows client caches under the machine-wide ProgramData root, and no HOME
            // redirect reaches it - the launcher only rewrites HOME on unix.
            System.getenv("PROGRAMDATA")?.let { add("$it/Jagex/RuneScape") }
            add("$home/Jagex/RuneScape")
            add("$home/.local/share/project-x-launcher/Jagex/RuneScape")
        }
        val chosen = candidates.firstOrNull { dir ->
            Files.isDirectory(Paths.get(dir)) && (0..255).any { Files.exists(Paths.get(dir, "js5-$it.jcache")) }
        } ?: candidates.first()
        println("[Cache] cache dir = $chosen (probed; RS_CACHE_DIR unset)")
        return Paths.get(chosen)
    }

    @JvmStatic
    fun initialize(baseAddr: Long) {
        synchronized(lock) {
            // Attach first: the offset table is chosen from the client image, and the first offset
            // anything reads fixes that choice for the whole engine load.
            println("Initializing native access at base address 0x${baseAddr.toString(16)}")
            NativeAccess.init(MemorySegment.ofAddress(baseAddr).reinterpret(0x2000000L))

            QuestLibrary.warm()

            ScriptExecutor.loadScripts()
            println("[ok] loadScripts() completed. Found: ${ScriptExecutor.scripts.size} scripts")

            // READ-ONLY: this is the live NXT client's own cache, open in another process. Writing it
            // (even the journal_mode=WAL pragma) corrupts the indices the client has open and forces a
            // re-download next launch. The server, which owns its cache, opens it read-write.
            Cache.init(resolveCacheDir(), readOnly = true)
            VarBitType.loadBaseVarMap()
            GamevalCoverage.report()
            OffsetCoverage.reportCurrentPlatform()

            client = Client.getClient(NativeAccess.BASE_ADDR.reinterpret(0x2000000L))
            println("Attached to base client address: 0x${client.ptr.address().toString(16)}")
            println("Game state: ${client.mainState}")
            println("Logged In Player: 0x${client.loggedInPlayer.ptr.address().toString(16)}")
            println("Player Var Domain: 0x${client.playerVarDomain.ptr.address().toString(16)}")

            CombatBridge.bind()

            PacketLogger.init()
            if (Configuration.config.packetLogEnabled) PacketRecorder.requestStart()
            if (Configuration.config.packetLogUploadEnabled) PacketUploadRunner.start()

            println("Parsing and applying hooks...")
            HookManager.parseAndApplyHooks(NativeAccess.BASE_ADDR)

            registerExampleSolvers()

            println("Project X successfully initialized - build ${BuildInfo.VERSION}. (MCP server disabled - enable from Settings tab)")
        }
    }

    /**
     * Full engine teardown so this classloader can be dropped and a rebuilt jar reloaded. ORDERING
     * IS CRASH-SENSITIVE: stop the game from calling engine code FIRST (hooks), quiesce, THEN tear
     * down native ImGui and stop threads. Doing ImGui/thread teardown while hooks are still live
     * lets the render/main-logic thread execute dead upcall stubs -> SIGSEGV.
     */
    @JvmStatic
    fun shutdown() {
        stopping = true
        // 1. Let the hot hooks (main-logic + render) become passthrough across a few frames.
        runCatching { Thread.sleep(120) }
        // 2. Uninstall every funchook hook and put back every swapped function pointer - the game's
        //    functions run unhooked from here on.
        runCatching { Funchook.uninstall() }
        runCatching { HookManager.restoreSlotHooks() }
        // 3. Barrier on the lock (drain an in-flight main-logic hook) + let the patch settle.
        synchronized(lock) {}
        runCatching { Thread.sleep(50) }
        // 4. Free the funchook instance. (Native ImGui + GL textures are deliberately LEFT resident:
        //    the game's GL context is unchanged across a reload, and a fresh engine reuses the
        //    existing ImGui context - ProjectX_ImGui_Init is idempotent. Destroying + recreating the
        //    GL backend here broke texture (re)creation on the reloaded engine, so we don't.)
        runCatching { Funchook.destroy(MemorySegment.NULL) }
        // 5. Stop engine-owned threads / resources (order not crash-sensitive once hooks are off).
        //    The recorder closes here rather than earlier: past the lock barrier with hooks uninstalled, the
        //    game thread can no longer reach its database, so this thread has it exclusively and can flush the
        //    queued tail and stamp the session end instead of abandoning both.
        runCatching { InputRecorder.shutdown() }
        // ⛔ The capture agent is NOT stopped here. It is a separate process whose whole job is to
        // outlive this one, and a hot reload is not a reason to give up on an unsent session.
        runCatching { PacketRecorder.shutdown() }
        runCatching { McpServer.stop() }
        runCatching { StringAllocator.shutdown() }
        runCatching { TileMarkerStore.stopFlusher() }
        runCatching { ScriptExecutor.stopAll() }
        runCatching { ScriptExecutor.stopInternalTasks() }
        runCatching { PacketLogger.close() }
        // 6. LAST: free every per-engine-load native allocation (hook upcall stubs, funchook slots,
        //    UI/DoAction buffers). Done only after hooks are off AND every engine thread/script is
        //    stopped, so nothing can read/write or allocate from the arena while it's being closed.
        //    Releasing the upcall stubs drops the MethodHandles bound to the hook methods, letting
        //    this classloader's metaspace be reclaimed (verified by the supervisor's WeakReference).
        runCatching { NativeAccess.teardown() }
        println("Project X engine torn down (was build ${runCatching { BuildInfo.VERSION }.getOrDefault("?")})")
        // 7. Detach the log redirect last, so everything above still reaches this load's log file.
        runCatching { EngineLog.close() }
    }
}