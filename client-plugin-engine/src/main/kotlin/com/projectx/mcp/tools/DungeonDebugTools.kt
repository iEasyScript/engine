package com.projectx.mcp.tools

import com.projectx.mcp.ScriptDebug
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * REMOVABLE - DUNGEONEERING-SPECIFIC. Thin relays for the debug snapshots the Dungeoneering
 * script (in the hot-reloaded official-scripts jar) publishes via [ScriptDebug]. Holds ZERO
 * compile deps on the dungeon code so that library can live in the module and hot-reload without
 * an engine rebuild. Delete this file + its `DungeonDebugTools.register` line in McpServer once the
 * Dungeoneering work is finished; nothing generic depends on it.
 */
object DungeonDebugTools {

    fun register(server: Server): Int {
        relay(
            server, "dungeon_state",
            "Purpose: One-shot snapshot of the dungeon sensing/context + player activity - the go-to for stuck detection (a GO target with an idle, non-moving, non-combat player over several polls = stuck). || Returns: envelope + helper/bot running, session/seed/calibration, current_cell, player {tile, room, moving, animating, in_combat, health_pct}, cell_count, boss_found, go_target, room_classes, door_classes, held_keys, examined_doors. || Inputs: none. || Related: dungeon_map, dungeon_targets, read_logs, restart_script.",
        )
        relay(
            server, "dungeon_map",
            "Purpose: Per-cell dump of the decoded dungeon map/connectivity graph for debugging criticality + door classification. || Returns: envelope + cells[] {gx, gy, openings, openings_known, start/boss/unknown, skill_door, key_door_obj, door_level, room_class, door_class, critical}. || Inputs: none. || Related: dungeon_state, dungeon_targets.",
        )
        relay(
            server, "dungeon_targets",
            "Purpose: The scanner's tracked world targets with their exact tiles + state - what the bot decides from. || Returns: envelope + go_target, skill_doors[], key_doors[], guardian_doors[], resources[], ground_keys[], slayer_npcs[]. || Inputs: none. || Related: dungeon_state, dungeon_map.",
        )
        return 3
    }

    private fun relay(server: Server, name: String, description: String) {
        server.addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        ) {
            safeJsonCall(name) { _ ->
                val provider = ScriptDebug.provider(name)
                if (provider == null) {
                    put("script_loaded", false)
                    return@safeJsonCall
                }
                this.provider()
            }
        }
    }
}
