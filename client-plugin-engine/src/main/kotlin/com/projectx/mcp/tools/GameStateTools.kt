package com.projectx.mcp.tools

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.mcp.tools.MemoryTools.safeCall
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject

object GameStateTools {

    fun register(server: Server): Int {
        server.addTool(
            name = "get_game_state",
            description = "Get a snapshot of current game state: mainState, clientCycle, player info, " +
                "viewport, NPC/player counts, projection matrix availability, process base address.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
            )
        ) { _ ->
            safeCall {
                val client = Bootstrap.client
                val result = StringBuilder()

                result.appendLine("=== Game State Snapshot ===")
                result.appendLine()

                val baseAddr = NativeAccess.BASE_ADDR.address()
                result.appendLine("Process base address: 0x${baseAddr.toString(16)}")

                val mainState = client.mainState
                val stateName = when (mainState) {
                    MainState.INITIALIZING -> "INITIALIZING"
                    MainState.LOGIN_SCREEN -> "LOGIN_SCREEN"
                    MainState.LOBBY_SCREEN -> "LOBBY_SCREEN"
                    MainState.ACCOUNT_CREATION -> "ACCOUNT_CREATION"
                    MainState.LOGGED_IN -> "LOGGED_IN"
                    MainState.ATTEMPTING_TO_REESTABLISH_NOTIFICATION -> "ATTEMPTING_TO_REESTABLISH"
                    MainState.RECONNECTING_TO_SERVER -> "RECONNECTING"
                    MainState.LOADING_NOTIFICATION -> "LOADING_NOTIFICATION"
                    else -> "UNKNOWN"
                }
                result.appendLine("Main State: $mainState ($stateName)")
                result.appendLine("Client Cycle: ${client.clientCycle}")
                result.appendLine()

                try {
                    val lp = client.loggedInPlayer
                    val playerName = lp.getPlayerName() ?: "(null)"
                    result.appendLine("Player Name: $playerName")
                    result.appendLine("Player Index: ${lp.serverIndex}")
                    result.appendLine("Player Rights: ${lp.playerRights}")

                    if (mainState == MainState.LOGGED_IN) {
                        val self = lp.self
                        val tile = self.tile
                        result.appendLine("Player Tile: (${tile.x}, ${tile.y}, plane=${tile.plane})")
                        val scene = self.graphNode.scene
                        result.appendLine("Player World-Fine: (${scene.x}, ${scene.y}, ${scene.z})")
                    }
                } catch (e: Throwable) {
                    result.appendLine("Player Info: ERROR (${e.message})")
                }

                result.appendLine()

                try {
                    val varcs = client.clientVarDomain
                    val viewX = varcs.getVar(3005)
                    val viewY = varcs.getVar(3006)
                    val viewW = varcs.getVar(3001)
                    val viewH = varcs.getVar(3002)
                    result.appendLine("Viewport: offset=($viewX, $viewY) size=(${viewW}x${viewH})")
                } catch (e: Throwable) {
                    result.appendLine("Viewport: ERROR (${e.message})")
                }

                try {
                    val world = client.sceneManager.currentWorld
                    if (world != null) {
                        val pm = world.projectionMatrix
                        result.appendLine("Projection Matrix: available (${pm.size} elements)")
                    } else {
                        result.appendLine("Projection Matrix: unavailable (no current world)")
                    }
                } catch (e: Throwable) {
                    result.appendLine("Projection Matrix: ERROR (${e.message})")
                }

                result.appendLine()

                try {
                    val mgr = client.npcManager
                    var npcCount = 0
                    mgr.indices.forEach { if (it > 0) npcCount++ }
                    result.appendLine("NPCs (local indices): $npcCount")
                } catch (e: Throwable) {
                    result.appendLine("NPC Count: ERROR (${e.message})")
                }

                try {
                    val pmgr = client.playerManager
                    var playerCount = 0
                    for (seg in pmgr) {
                        if (seg.address() != 0L) playerCount++
                    }
                    result.appendLine("Players: $playerCount")
                } catch (e: Throwable) {
                    result.appendLine("Player Count: ERROR (${e.message})")
                }

                result.toString()
            }
        }
        return 1
    }
}
