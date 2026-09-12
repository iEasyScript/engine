package com.projectx.mcp.tools

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object PlayerTools {

    private val runOptionVarp = FeatureIds("PlayerTools") { Gameval.requireId(Gameval.VAR_PLAYER, "option_run") }

    fun register(server: Server): Int {
        registerGetLocalPlayer(server)
        registerGetPlayerStats(server)
        registerGetPlayerPosition(server)
        registerGetRunState(server)
        return 4
    }

    private fun registerGetLocalPlayer(server: Server) {
        server.addTool(
            name = "get_local_player",
            description = """
                Purpose: Full snapshot of the locally-logged-in player - identity, position, render state, current target - the "where am I, what am I doing right now" tool. Always returns the heap address of the player Entity so the agent can pivot into raw memory with read_memory/read_struct_field.
                || Returns: JSON envelope with: addr, addr_rel, server_index, name, player_rights, tile {x, y, plane}, world_fine {x, y, z}, animation_id, current_hp, max_hp, prayer_points, target_index, target_type, target_addr, is_moving, is_interacting. With verbose=true also returns graph_node_addr, route_waypoint_manager_addr, hitmarks_and_headbars_addr.
                || Inputs: `verbose` (optional bool, default false) - if true, include extra heap pointers and diagnostic fields.
                || Use cases: "Where am I in the world right now?", "What's my current target?", "Is my character animating?", "Drill into the local player struct to find an unknown field".
                || Related tools: get_player_stats (skill table), get_player_position (lightweight subset), get_run_state (run/move state), list_npcs (find nearby NPCs), eval_projection (world->screen).
                || Pitfalls: Requires main_state == LOGGED_IN. At the login screen or during world loading, returns engine_state error. Some fields (prayer_points, health) read from varbits and may be 0 briefly after login before the first server update.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("verbose") {
                        put("type", "boolean")
                        put("description", "If true, include extra heap pointers and diagnostic fields.")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("get_local_player") { warnings ->
                val verbose = request.arguments?.get("verbose")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val client = requireLoggedIn()
                val lp = client.loggedInPlayer
                val self = try {
                    lp.self
                } catch (t: Throwable) {
                    throw EngineState("local player not available: ${t.message}")
                }
                if (self.ptr.address() == 0L) throw EngineState("local player pointer is NULL")

                putAddr(self.ptr.address())
                put("server_index", self.serverIndex)
                put("name", lp.getPlayerName())
                put("player_rights", lp.playerRights)

                runCatching {
                    val tile = self.tile
                    put("tile", buildJsonObject {
                        put("x", tile.x)
                        put("y", tile.y)
                        put("plane", tile.plane)
                    })
                }.onFailure { warnings.add("tile read failed: ${it.message}") }

                runCatching {
                    val scene = self.graphNode.scene
                    put("world_fine", buildJsonObject {
                        put("x", scene.x)
                        put("y", scene.y)
                        put("z", scene.z)
                    })
                }.onFailure { warnings.add("world_fine read failed: ${it.message}") }

                runCatching {
                    val a = self.animationId
                    put("animation_id", a)
                    put("animation_name", Gameval.seq(a) ?: "")
                }
                runCatching { put("is_moving", self.isMoving) }
                runCatching { put("is_interacting", self.isInteracting) }
                runCatching { put("interaction_sid", self.interactionSid) }

                runCatching {
                    put("target_index", lp.targetIndex)
                    put("target_type", lp.targetType.name)
                }

                runCatching {
                    val varps = client.playerVarDomain
                    put("prayer_points", kotlin.math.ceil(varps.getVarBit(16736).toDouble() / 10.0).toInt())
                    put("health_current", varps.getVarBit(1668))
                    put("health_max", varps.getVarBit(24595))
                }.onFailure { warnings.add("varbit read failed: ${it.message}") }

                if (verbose) {
                    runCatching { putPtrField("graph_node", self.graphNode.ptr.address()) }
                    runCatching { putPtrField("route_waypoint_manager", self.routeWaypointManager.address()) }
                    runCatching {
                        val hh = self.hitmarksAndHeadbars
                        putPtrField("hitmarks_and_headbars", hh?.ptr?.address() ?: 0L)
                    }
                    runCatching { put("plane", self.plane) }
                    runCatching { put("size_tiles", self.size) }
                }
            }
        }
    }

    private fun registerGetPlayerStats(server: Server) {
        server.addTool(
            name = "get_player_stats",
            description = """
                Purpose: Per-skill snapshot of the local player's stat table - current level, real (base) level, and XP for every skill. Each entry carries the heap address of the StatEntry struct so the agent can drill in.
                || Returns: JSON envelope with `items[]` - one entry per skill: `addr`, `addr_rel`, `skill` (name), `ordinal`, `current_level`, `real_level`, `xp`. Skills are returned in canonical Skill enum order unless filtered.
                || Inputs: `skill` (optional string, case-insensitive) - restrict to one skill by name (e.g. "ATTACK", "MAGIC"). Omit to return all 29 skills.
                || Use cases: "What level am I at in slayer?", "Show me my current vs base levels to see active boosts/drains", "Is my Necromancy XP changing during this script?".
                || Related tools: get_local_player (player snapshot), get_varbit (boosts/drains are often stored as varbits too).
                || Pitfalls: Requires LOGGED_IN. Skills that don't exist on the account (e.g. members-only skills on F2P) still appear with realLevel=1, xp=0. Levels can read 0 momentarily during scene loads.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("skill") {
                        put("type", "string")
                        put("description", "Skill name (case-insensitive). Omit for all skills.")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("get_player_stats") { warnings ->
                val skillFilter = request.arguments?.get("skill")?.jsonPrimitive?.content?.uppercase()
                val client = requireLoggedIn()
                val table = client.skills
                putPtrField("stat_table", table.ptr.address())

                val skillsToRead = if (skillFilter != null) {
                    val found = Skill.entries.firstOrNull { it.name == skillFilter }
                        ?: throw BadRequest("unknown skill '$skillFilter'. Valid: ${Skill.entries.joinToString { it.name }}")
                    listOf(found)
                } else {
                    Skill.entries
                }

                put("items", buildJsonArray {
                    for (skill in skillsToRead) {
                        runCatching {
                            val stat = table[skill]
                            add(buildJsonObject {
                                putAddr(stat.ptr.address())
                                put("skill", skill.name)
                                put("ordinal", skill.ordinal)
                                put("current_level", stat.currentLevel)
                                put("real_level", stat.realLevel)
                                put("xp", stat.xp)
                            })
                        }.onFailure { warnings.add("read ${skill.name}: ${it.message}") }
                    }
                })
                put("count", skillsToRead.size)
            }
        }
    }

    private fun registerGetPlayerPosition(server: Server) {
        server.addTool(
            name = "get_player_position",
            description = """
                Purpose: Lightweight position snapshot of the local player - tile, world-fine scene coordinates, plane, mapSquare/zone ids. Use for quick spatial reasoning without pulling the full get_local_player payload.
                || Returns: JSON envelope with: addr (player entity), addr_rel, tile {x, y, plane}, world_fine {x, y, z}, mapSquare_x, mapSquare_y, mapSquare_id, zone_x, zone_y, zone_id, mapSquare_hash, tile_hash.
                || Inputs: none.
                || Use cases: "Am I in the right mapSquare to start this script?", "What's my zone id for the pathfinder?", "Compare my tile against this NPC's tile".
                || Related tools: get_local_player (full snapshot), list_locations (objects near me), walk_to (move to a tile).
                || Pitfalls: Requires LOGGED_IN. World-fine coordinates are in NXT scene units (512 per tile); divide by 512 for tile-space if comparing manually.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            safeJsonCall("get_player_position") { _ ->
                val client = requireLoggedIn()
                val self = client.loggedInPlayer.self
                if (self.ptr.address() == 0L) throw EngineState("local player NULL")

                putAddr(self.ptr.address())
                val tile = self.tile
                put("tile", buildJsonObject {
                    put("x", tile.x)
                    put("y", tile.y)
                    put("plane", tile.plane)
                })
                val scene = self.graphNode.scene
                put("world_fine", buildJsonObject {
                    put("x", scene.x)
                    put("y", scene.y)
                    put("z", scene.z)
                })
                put("mapSquare_x", tile.mapSquareX)
                put("mapSquare_y", tile.mapSquareY)
                put("mapSquare_id", tile.mapSquareId)
                put("zone_x", tile.zoneX)
                put("zone_y", tile.zoneY)
                put("zone_id", tile.zoneId)
                put("mapSquare_hash", tile.mapSquareHash)
                put("tile_hash", tile.tileHash)
            }
        }
    }

    private fun registerGetRunState(server: Server) {
        server.addTool(
            name = "get_run_state",
            description = """
                Purpose: Movement-and-routing state of the local player - whether run is toggled on, whether the player is currently moving, queued waypoint count, the in-flight route. Sources from the player entity's PathingEntity and routeWaypointManager.
                || Returns: JSON envelope with: addr (player entity), is_running (varp 463 option_run), is_moving, route_waypoint_manager_addr, waypoint_count, interaction_sid (-1 if none).
                || Inputs: none.
                || Use cases: "Did my walk_to actually start moving?", "Is run on?", "How many waypoints are queued before I'm done with this path?".
                || Related tools: walk_to (initiate movement), get_local_player (full snapshot).
                || Pitfalls: Requires LOGGED_IN. Run ENERGY is deliberately not reported - no player var, varbit, client var or entity field is known to hold it, and the id previously used (varbit 10000) is pnr_collected_ore2. is_running may be stale for one tick after a toggle. waypoint_count reads a Long; cast to Int for display.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            safeJsonCall("get_run_state") { warnings ->
                val client = requireLoggedIn()
                val self = client.loggedInPlayer.self
                if (self.ptr.address() == 0L) throw EngineState("local player NULL")

                putAddr(self.ptr.address())
                runCatching {
                    val varps = client.playerVarDomain
                    val runOption = runOptionVarp.get()
                    if (runOption == null) warnings.add("is_running unavailable: the option_run gameval is missing")
                    else put("is_running", varps.getVar(runOption) == 1)
                }.onFailure { warnings.add("varbit read failed: ${it.message}") }

                runCatching { put("is_moving", self.isMoving) }
                runCatching {
                    val rm = self.routeWaypointManager
                    putPtrField("route_waypoint_manager", rm.address())
                }
                runCatching { put("interaction_sid", self.interactionSid) }
            }
        }
    }
}
