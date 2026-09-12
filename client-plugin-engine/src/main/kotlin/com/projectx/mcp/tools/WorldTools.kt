package com.projectx.mcp.tools

import world.gregs.voidps.type.Tile
import world.gregs.voidps.gameval.Gameval
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.spotAnimName
import com.projectx.game.nxt.ItemStackNode
import com.projectx.game.nxt.entity.GroundItem
import com.projectx.game.nxt.entity.ItemStack
import com.projectx.game.nxt.entity.Projectile
import com.projectx.game.nxt.entity.SpotAnim
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.foreign.MemorySegment

object WorldTools {

    fun register(server: Server): Int {
        registerListNpcs(server)
        registerListPlayers(server)
        registerListGroundItems(server)
        registerListLocations(server)
        registerListProjectiles(server)
        registerListSpotAnims(server)
        return 6
    }

    private fun localPlayerTile(): Tile? = try {
        Bootstrap.client.loggedInPlayer.self.tile
    } catch (_: Throwable) {
        null
    }

    private fun JsonObjectBuilder.putTile(tile: Tile) {
        put("tile", buildJsonObject {
            put("x", tile.x)
            put("y", tile.y)
            put("plane", tile.plane)
        })
    }

    private fun passesSpatialFilter(filters: ListFilters, tile: Tile, center: Tile?): Boolean {
        if (filters.plane != null && tile.plane != filters.plane) return false
        if (filters.withinZone && center != null) {
            return tile.plane == center.plane && tile.zoneX == center.zoneX && tile.zoneY == center.zoneY
        }
        if (filters.withinTiles != null && center != null) {
            return tile.withinDistance(center, filters.withinTiles)
        }
        return true
    }

    private fun registerListNpcs(server: Server) {
        server.addTool(
            name = "list_npcs",
            description = """
                Purpose: Enumerate every NPC currently loaded in the client's NPC manager hash table, returning the heap address of each entity plus identifying fields. Entry-point tool for any "what NPCs are near me / what is X doing" question. Names resolved via NPCType cache.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: addr, addr_rel, server_index, id, type_id, name, tile {x, y, plane}, animation_id, current_hp, max_hp, is_moving, is_interacting, interaction_sid. With verbose=true also: combat_target, graph_node_addr, render_model_addr, hidden_menuop_flags.
                || Inputs: Standard list filters (id, name, within_tiles, within_zone, plane, limit, offset, fields, verbose, sort). `id` matches NPCType.id (typeId fallback to id). `name` is case-insensitive substring on the resolved NPCType name. `within_tiles` is from the local player tile.
                || Use cases: "What guards are within 10 tiles of me?", "Find any NPC named 'shop'", "Show me NPCs I'm currently fighting", "Get the addr of NPC sid=42 so I can drill into its struct".
                || Related tools: get_entity_info (per-NPC deep dive), find_closest_npc (action layer), interact_npc (action layer), get_content_type kind=npc id=N (NPCType cache definition), list_players (sibling tool).
                || Pitfalls: Requires LOGGED_IN. NPCs whose hash slot is empty are skipped silently. typeId == -1 means "use id for cache lookup" (transformation fallback). The manager is keyed by server_index, not by ordinal - do not assume contiguous numbering. Some NPCs may have null name strings during scene loads.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema {
                putJsonObject("animation_id") {
                    put("type", "integer")
                    put("description", "Match NPCs whose current animationId equals this (e.g. 803). Use -2 to match 'animating at all' (any anim != -1).")
                }
                putJsonObject("is_moving") {
                    put("type", "boolean")
                    put("description", "Filter to NPCs whose isMoving == this value.")
                }
                putJsonObject("is_interacting") {
                    put("type", "boolean")
                    put("description", "Filter to NPCs whose isInteracting == this value.")
                }
                putJsonObject("interaction_sid") {
                    put("type", "integer")
                    put("description", "Match NPCs whose interactionSid equals this. Use the local player's server_index to find who's targeting you.")
                }
                putJsonObject("hp_min") {
                    put("type", "integer")
                    put("description", "Match NPCs with currentHealth >= this.")
                }
                putJsonObject("hp_max") {
                    put("type", "integer")
                    put("description", "Match NPCs with currentHealth <= this.")
                }
                putJsonObject("hp_below_pct") {
                    put("type", "integer")
                    put("description", "Match NPCs whose currentHealth / maxHealth * 100 is below this percent. Skips NPCs with maxHealth <= 0.")
                }
                putJsonObject("combat_target") {
                    put("type", "boolean")
                    put("description", "If true, only the NPC that is currently the local player's combat target.")
                }
            },
        ) { request ->
            safeJsonCall("list_npcs") { warnings ->
                val filters = parseListFilters(request.arguments)
                val args = request.arguments
                val animFilter = args?.get("animation_id")?.jsonPrimitive?.content?.toIntOrNull()
                val isMovingFilter = args?.get("is_moving")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val isInteractingFilter = args?.get("is_interacting")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val interactionSidFilter = args?.get("interaction_sid")?.jsonPrimitive?.content?.toIntOrNull()
                val hpMin = args?.get("hp_min")?.jsonPrimitive?.content?.toIntOrNull()
                val hpMax = args?.get("hp_max")?.jsonPrimitive?.content?.toIntOrNull()
                val hpBelowPct = args?.get("hp_below_pct")?.jsonPrimitive?.content?.toIntOrNull()
                val combatTargetOnly = args?.get("combat_target")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val client = requireLoggedIn()
                val center = localPlayerTile()

                val seq = client.npcManager.asSequence().mapNotNull { seg ->
                    if (seg.address() == 0L) return@mapNotNull null
                    runCatching { NPC(seg) }.getOrNull()
                }

                val matched = mutableListOf<NPC>()
                var total = 0
                for (npc in seq) {
                    runCatching {
                        if (!npc.exists()) return@runCatching
                        total++
                        if (filters.id != null) {
                            val typeId = if (npc.typeId == -1) npc.id else npc.typeId
                            if (typeId != filters.id) return@runCatching
                        }
                        if (filters.name != null) {
                            val n = runCatching { npc.name() }.getOrDefault("")
                            if (!n.contains(filters.name, ignoreCase = true)) return@runCatching
                        }
                        val tile = npc.tile
                        if (!passesSpatialFilter(filters, tile, center)) return@runCatching

                        if (animFilter != null) {
                            val anim = runCatching { npc.animationId }.getOrDefault(-1)
                            if (animFilter == -2) {
                                if (anim == -1) return@runCatching
                            } else if (anim != animFilter) return@runCatching
                        }
                        if (isMovingFilter != null && runCatching { npc.isMoving }.getOrDefault(false) != isMovingFilter) return@runCatching
                        if (isInteractingFilter != null && runCatching { npc.isInteracting }.getOrDefault(false) != isInteractingFilter) return@runCatching
                        if (interactionSidFilter != null && runCatching { npc.interactionSid }.getOrDefault(-1) != interactionSidFilter) return@runCatching
                        if (hpMin != null && runCatching { npc.currentHealth }.getOrDefault(Int.MIN_VALUE) < hpMin) return@runCatching
                        if (hpMax != null && runCatching { npc.currentHealth }.getOrDefault(Int.MAX_VALUE) > hpMax) return@runCatching
                        if (hpBelowPct != null) {
                            val cur = runCatching { npc.currentHealth }.getOrDefault(-1)
                            val max = runCatching { npc.maxHealth }.getOrDefault(0)
                            if (max <= 0) return@runCatching
                            if (cur * 100 >= max * hpBelowPct) return@runCatching
                        }
                        if (combatTargetOnly && !runCatching { npc.isCombatTarget }.getOrDefault(false)) return@runCatching

                        matched.add(npc)
                    }.onFailure { warnings.add("npc skip: ${it.message}") }
                }

                val sorted = when (filters.sort) {
                    "distance" -> if (center != null) matched.sortedBy { it.tile.getDistance(center) } else matched
                    "id" -> matched.sortedBy { it.id }
                    "name" -> matched.sortedBy { runCatching { it.name() }.getOrDefault("") }
                    else -> matched.sortedBy { it.ptr.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", total)
                put("truncated", sorted.size > window.size + filters.offset)

                put("items", buildJsonArray {
                    for (npc in window) {
                        add(buildJsonObject {
                            putAddr(npc.ptr.address())
                            put("server_index", npc.serverIndex)
                            put("id", npc.id)
                            put("type_id", npc.typeId)
                            runCatching { put("name", npc.name()) }
                            runCatching { putTile(npc.tile) }
                            runCatching {
                                val a = npc.animationId
                                put("animation_id", a)
                                put("animation_name", Gameval.seq(a) ?: "")
                            }
                            runCatching {
                                put("current_hp", npc.currentHealth)
                                put("max_hp", npc.maxHealth)
                            }
                            runCatching { put("is_moving", npc.isMoving) }
                            runCatching { put("is_interacting", npc.isInteracting) }
                            runCatching { put("interaction_sid", npc.interactionSid) }
                            if (filters.verbose) {
                                runCatching { put("is_combat_target", npc.isCombatTarget) }
                                runCatching { putPtrField("graph_node", npc.graphNode.ptr.address()) }
                                runCatching { put("hidden_menuop_flags", npc.hiddenMenuOpFlags) }
                                runCatching {
                                    val ra = npc.renderAnim
                                    put("render_anim", ra)
                                    put("render_anim_name", Gameval.seq(ra) ?: "")
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun registerListPlayers(server: Server) {
        server.addTool(
            name = "list_players",
            description = """
                Purpose: Enumerate every player currently loaded in the client's player manager vector, returning the heap address and identifying fields per player. Includes the local player by default.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: addr, addr_rel, server_index, name, tile {x, y, plane}, animation_id, is_moving, is_interacting, interaction_sid. With verbose=true also: graph_node_addr, headbars_count.
                || Inputs: Standard list filters. `name` matches the EastlString name field. `within_tiles` from the local player. Player at vector index 0 is always empty and skipped.
                || Use cases: "Who's nearby?", "Find the player named 'Foo'", "Are any players fighting me?".
                || Related tools: list_npcs (sibling), get_local_player (local-player snapshot), get_entity_info (deep dive - note: currently NPC-only; this is read with read_memory if needed).
                || Pitfalls: Requires LOGGED_IN. Players who have just deregistered may show with stale names; check is_moving and target_index to gauge liveness. The vector is sparse.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_players") { warnings ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val center = localPlayerTile()

                val matched = mutableListOf<Player>()
                var total = 0
                for (seg in client.playerManager) {
                    if (seg.address() == 0L || seg == MemorySegment.NULL) continue
                    runCatching {
                        val p = Player(seg)
                        total++
                        if (filters.name != null) {
                            val n = runCatching { p.name }.getOrDefault("")
                            if (!n.contains(filters.name, ignoreCase = true)) return@runCatching
                        }
                        val tile = p.tile
                        if (!passesSpatialFilter(filters, tile, center)) return@runCatching
                        matched.add(p)
                    }.onFailure { warnings.add("player skip: ${it.message}") }
                }

                val sorted = when (filters.sort) {
                    "distance" -> if (center != null) matched.sortedBy { it.tile.getDistance(center) } else matched
                    "name" -> matched.sortedBy { runCatching { it.name }.getOrDefault("") }
                    else -> matched.sortedBy { it.ptr.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", total)
                put("truncated", sorted.size > window.size + filters.offset)

                put("items", buildJsonArray {
                    for (p in window) {
                        add(buildJsonObject {
                            putAddr(p.ptr.address())
                            runCatching { put("server_index", p.serverIndex) }
                            runCatching { put("name", p.name) }
                            runCatching { putTile(p.tile) }
                            runCatching {
                                val a = p.animationId
                                put("animation_id", a)
                                put("animation_name", Gameval.seq(a) ?: "")
                            }
                            runCatching { put("is_moving", p.isMoving) }
                            runCatching { put("is_interacting", p.isInteracting) }
                            runCatching { put("interaction_sid", p.interactionSid) }
                            if (filters.verbose) {
                                runCatching { putPtrField("graph_node", p.graphNode.ptr.address()) }
                                runCatching { put("headbars_count", p.headbars.size) }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun registerListGroundItems(server: Server) {
        server.addTool(
            name = "list_ground_items",
            description = """
                Purpose: Enumerate every ground item stack in the client's ItemStackList red-black tree. Each stack node holds one or more GroundItem entries (item_id + amount). Reports the heap address of each ItemStackNode and ItemStack.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: node_addr (the RB-tree node), stack_addr (ItemStack), tile {x, y, plane}, items[] (per-slot {item_id, amount, name}).
                || Inputs: Standard list filters. `id` filters by item_id (any ground item in the stack matching). `name` matches ItemType.name (resolved via cache).
                || Use cases: "What's on the floor at my tile?", "Is the rare drop I'm waiting for visible?", "Are there any prized loot items in the 8x8 zone?".
                || Related tools: get_content_type kind=item id=N (ItemType cache definition), interact_ground_item (action layer to pick up).
                || Pitfalls: Requires LOGGED_IN. Items the player can't see (private drops belonging to others) are not in this list. The RB-tree iteration is read-only; safe to enumerate during a tick.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_ground_items") { warnings ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val center = localPlayerTile()

                data class StackRow(val node: ItemStackNode, val stack: ItemStack, val items: List<GroundItem>)

                val rows = mutableListOf<StackRow>()
                var total = 0
                for (nodeSeg in client.itemStackList) {
                    runCatching {
                        val node = ItemStackNode(nodeSeg)
                        val stack = node.itemStack
                        val groundItems = stack.groundItems
                        total++
                        val tile = node.tile
                        if (!passesSpatialFilter(filters, tile, center)) return@runCatching
                        if (filters.id != null && groundItems.none { gi -> gi.id == filters.id }) return@runCatching
                        if (filters.name != null) {
                            val needle = filters.name
                            val anyMatch = groundItems.any { gi ->
                                runCatching { gi.name.contains(needle, ignoreCase = true) }.getOrDefault(false)
                            }
                            if (!anyMatch) return@runCatching
                        }
                        rows.add(StackRow(node, stack, groundItems))
                    }.onFailure { warnings.add("ground item skip: ${it.message}") }
                }

                val sorted = when (filters.sort) {
                    "distance" -> if (center != null) rows.sortedBy { it.node.tile.getDistance(center) } else rows
                    else -> rows.sortedBy { it.node.ptr.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", total)
                put("truncated", sorted.size > window.size + filters.offset)

                put("items", buildJsonArray {
                    for (row in window) {
                        add(buildJsonObject {
                            putPtrField("node", row.node.ptr.address())
                            putPtrField("stack", row.stack.ptr.address())
                            putTile(row.node.tile)
                            put("items", buildJsonArray {
                                for (gi in row.items) {
                                    add(buildJsonObject {
                                        put("item_id", gi.id)
                                        put("item_name", Gameval.obj(gi.id) ?: "")
                                        put("amount", gi.amount)
                                        runCatching { put("name", gi.name) }
                                    })
                                }
                            })
                        })
                    }
                })
            }
        }
    }

    private fun registerListLocations(server: Server) {
        server.addTool(
            name = "list_locations",
            description = """
                Purpose: Enumerate scenery / location objects (interactables and decorations) currently in the loaded scene around the local player. Uses SceneManager.getAllObjectsWithinRange; hidden/replaced loc sections (e.g. a chopped tree under its stump) and multi-locs whose keying varbit/varp transforms them to the hidden slot are filtered out at the source, and exact-pointer duplicates removed.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: addr (SceneObject memPointer), id (base loc, the multi-loc itself for transforming locs), type_id (id after multi-loc transform resolution), tile {x, y, plane}, shape (ObjectShape name), rotation, name (from ObjectType cache, resolved through the transform).
                || Inputs: Standard list filters. `id` filters by content id, matching either the base id or the transform-resolved id. `name` matches resolved ObjectType.name. `within_tiles` controls the scan radius (default 20 if neither within_tiles nor within_zone supplied - explicitly passing within_tiles=N changes that). Without LOGGED_IN you get an empty list.
                || Use cases: "What's the closest tree to me?", "Are there any banks within 12 tiles?", "Find all 'Door' objects on this plane".
                || Related tools: get_content_type kind=obj id=N (ObjectType / LocType cache), find_closest_object (action layer), interact_object (action layer).
                || Pitfalls: Requires LOGGED_IN. SceneObjects are an interface (SceneObject) covering Location and CombinedLocationSection - the memPointer is the underlying struct address. Hidden/replaced sections are dropped at the source; only exact-pointer duplicates are de-duped (distinct objects on the same tile are all kept). Default radius is 20 if no filter is given.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_locations") { warnings ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val center = localPlayerTile() ?: throw EngineState("no local player tile")
                val range = filters.withinTiles ?: 20

                val all: List<SceneObject> = try {
                    client.sceneManager.getAllObjectsWithinRange(center, range)
                } catch (t: Throwable) {
                    throw EngineState("scene scan failed: ${t.message}")
                }

                val filtered = all.filter { obj ->
                    runCatching {
                        if (filters.id != null && obj.visibleTypeId != filters.id && obj.id != filters.id) return@filter false
                        if (filters.name != null) {
                            val n = runCatching { obj.name() }.getOrDefault("")
                            if (!n.contains(filters.name, ignoreCase = true)) return@filter false
                        }
                        if (!passesSpatialFilter(filters, obj.tile, center)) return@filter false
                        true
                    }.getOrElse {
                        warnings.add("location skip: ${it.message}")
                        false
                    }
                }

                val sorted = when (filters.sort) {
                    "distance" -> filtered.sortedBy { it.tile.getDistance(center) }
                    "id" -> filtered.sortedBy { it.id }
                    "name" -> filtered.sortedBy { runCatching { it.name() }.getOrDefault("") }
                    else -> filtered.sortedBy { it.memPointer.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", all.size)
                put("truncated", sorted.size > window.size + filters.offset)
                put("scan_range", range)

                put("items", buildJsonArray {
                    for (obj in window) {
                        add(buildJsonObject {
                            putAddr(obj.memPointer.address())
                            put("id", obj.id)
                            put("name_gameval", Gameval.loc(obj.id) ?: "")
                            put("type_id", obj.visibleTypeId)
                            put("type_name", Gameval.loc(obj.visibleTypeId) ?: "")
                            runCatching { put("name", obj.name()) }
                            putTile(obj.tile)
                            runCatching { put("shape", obj.shape.name) }
                            runCatching { put("rotation", obj.rotation.toInt()) }
                            if (filters.verbose) {
                                runCatching {
                                    val gn = obj.graphNode
                                    putPtrField("graph_node", gn?.ptr?.address() ?: 0L)
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun registerListProjectiles(server: Server) {
        server.addTool(
            name = "list_projectiles",
            description = """
                Purpose: Enumerate every active projectile in the client's ProjectileList (EastlFixedPool). Useful for combat scripts and visual-effect debugging.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: addr, id, locked_to_server_index (-1 if free), tile {x, y, plane}.
                || Inputs: Standard list filters. `id` filters by projectile id. `within_tiles` from the local player.
                || Use cases: "Is there an incoming attack on me?", "What spell graphic is flying at the boss?".
                || Related tools: list_spot_anims (graphic effects), get_local_player.
                || Pitfalls: Requires LOGGED_IN. Projectiles are short-lived (often single-tick), so consecutive calls may return different sets. There is no cache type reader for ProjAnimType yet (Phase 7 deferred).
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_projectiles") { warnings ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val center = localPlayerTile()

                val matched = mutableListOf<Projectile>()
                var total = 0
                for (p in client.projectileList) {
                    runCatching {
                        total++
                        if (filters.id != null && p.id != filters.id) return@runCatching
                        val tile = p.tile
                        if (!passesSpatialFilter(filters, tile, center)) return@runCatching
                        matched.add(p)
                    }.onFailure { warnings.add("projectile skip: ${it.message}") }
                }

                val sorted = when (filters.sort) {
                    "distance" -> if (center != null) matched.sortedBy { it.tile.getDistance(center) } else matched
                    "id" -> matched.sortedBy { it.id }
                    else -> matched.sortedBy { it.ptr.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", total)
                put("truncated", sorted.size > window.size + filters.offset)

                put("items", buildJsonArray {
                    for (p in window) {
                        add(buildJsonObject {
                            putAddr(p.ptr.address())
                            put("id", p.id)
                            put("spotanim_name", spotAnimName(p.id) ?: "")
                            put("locked_to_server_index", p.lockedToServerIndex)
                            runCatching { putTile(p.tile) }
                        })
                    }
                })
            }
        }
    }

    private fun registerListSpotAnims(server: Server) {
        server.addTool(
            name = "list_spot_anims",
            description = """
                Purpose: Enumerate every active SpotAnim (graphic effect) in the SpotAnimManager linked list. Includes animations attached to tiles, NPCs, and players.
                || Returns: JSON envelope with count, total, truncated, items[]. Each item: addr, id, tile {x, y, plane}, created_clientcycle, cycles_alive.
                || Inputs: Standard list filters. `id` filters by spotanim id. `within_tiles` from the local player.
                || Use cases: "Is the AoE warning effect on the ground?", "Did my ability fire its graphic?", "What graphics are around me right now?".
                || Related tools: list_projectiles (projectile graphics), get_local_player.
                || Pitfalls: Requires LOGGED_IN. There is no SpotAnimType cache reader yet (Phase 7 deferred) - names cannot be resolved client-side. cycles_alive uses clientCycle - createdClientcycle; large negative values indicate counter wrap or unset.
            """.trimIndent().replace("\n", " "),
            inputSchema = listSchema(),
        ) { request ->
            safeJsonCall("list_spot_anims") { warnings ->
                val filters = parseListFilters(request.arguments)
                val client = requireLoggedIn()
                val center = localPlayerTile()

                val matched = mutableListOf<SpotAnim>()
                var total = 0
                for (seg in client.spotAnimManager) {
                    runCatching {
                        val sa = SpotAnim(seg)
                        total++
                        if (filters.id != null && sa.id != filters.id) return@runCatching
                        val tile = sa.tile
                        if (!passesSpatialFilter(filters, tile, center)) return@runCatching
                        matched.add(sa)
                    }.onFailure { warnings.add("spotanim skip: ${it.message}") }
                }

                val sorted = when (filters.sort) {
                    "distance" -> if (center != null) matched.sortedBy { it.tile.getDistance(center) } else matched
                    "id" -> matched.sortedBy { it.id }
                    else -> matched.sortedBy { it.ptr.address() }
                }
                val window = sorted.drop(filters.offset).take(filters.limit)

                put("count", window.size)
                put("total", total)
                put("truncated", sorted.size > window.size + filters.offset)

                put("items", buildJsonArray {
                    for (sa in window) {
                        add(buildJsonObject {
                            putAddr(sa.ptr.address())
                            put("id", sa.id)
                            put("spotanim_name", spotAnimName(sa.id) ?: "")
                            runCatching { putTile(sa.tile) }
                            put("created_clientcycle", sa.createdClientcycle)
                            put("cycles_alive", sa.cyclesAlive)
                        })
                    }
                })
            }
        }
    }
}
