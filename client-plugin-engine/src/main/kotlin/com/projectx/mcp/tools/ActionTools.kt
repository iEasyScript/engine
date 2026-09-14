package com.projectx.mcp.tools

import com.projectx.game.input.Key
import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.cs2.CS2Executor
import com.projectx.game.input.action.ActionInput
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.KeyTiming
import com.projectx.script.api.Keystroke
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.interactClosestReachableObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object ActionTools {

    fun register(server: Server): Int {
        registerWalkTo(server)
        registerFindClosestNpc(server)
        registerInteractNpc(server)
        registerFindClosestObject(server)
        registerInteractObject(server)
        registerInteractGroundItem(server)
        registerClickComponent(server)
        registerContinueDialogue(server)
        registerKeyPress(server)
        registerTypeText(server)
        registerExecuteCs2Script(server)
        registerWaitForInterfaceOpen(server)
        registerWaitForVarbitChange(server)
        return 13
    }

    private fun registerTypeText(server: Server) {
        server.addTool(
            name = "type_text",
            description = """
                **WRITE OPERATION.** Purpose: Type text into whatever has keyboard focus (a chatbox search, an amount prompt) the way a physical keyboard delivers it: key-down, the translated character, a human hold, key-up, with shift for capitals and typing-speed gaps. Same path as the script API's `typeText`.
                || Returns: JSON envelope with: ok, typed, strokes, elapsed_ms.
                || Inputs: `text` (required string - letters, digits, space; `\n` presses Enter).
                || Use cases: "Type an item name into the Grand Exchange search", "Enter an amount and press Enter".
                || Pitfalls: Requires LOGGED_IN. Characters outside letters/digits/space/tab/newline/backspace are rejected before anything is pressed.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("text") { put("type", "string"); put("description", "Text to type") }
                },
                required = listOf("text"),
            ),
        ) { request ->
            safeJsonCallAsync("type_text") { _ ->
                val text = request.arguments?.get("text")?.jsonPrimitive?.content ?: throw BadRequest("missing 'text'")
                val strokes = text.map { Keystroke.of(it) ?: throw BadRequest("cannot type '$it'") }
                val started = System.currentTimeMillis()
                for ((index, stroke) in strokes.withIndex()) {
                    if (index > 0) Thread.sleep(KeyTiming.gapMillis().toLong())
                    if (stroke.shift) {
                        onGameTick { requireLoggedIn(); ActionInput.keyDown(Keystroke.SHIFT) }
                        Thread.sleep(KeyTiming.modifierLeadMillis().toLong())
                    }
                    onGameTick {
                        requireLoggedIn()
                        ActionInput.keyDown(stroke.key)
                        stroke.char?.let { ActionInput.keyChar(it) }
                    }
                    Thread.sleep(KeyTiming.holdMillis().toLong())
                    onGameTick { ActionInput.keyUp(stroke.key) }
                    if (stroke.shift) {
                        Thread.sleep(KeyTiming.modifierTrailMillis().toLong())
                        onGameTick { ActionInput.keyUp(Keystroke.SHIFT) }
                    }
                }
                put("ok", true)
                put("typed", text)
                put("strokes", strokes.size)
                put("elapsed_ms", System.currentTimeMillis() - started)
            }
        }
    }

    private fun registerWalkTo(server: Server) {
        server.addTool(
            name = "walk_to",
            description = """
                **WRITE OPERATION.** Purpose: Walk the local player to the given tile by routing through `script.api.walkTo` - inherits the engine's 40-tile range validation (silent no-op beyond that range, matching script behavior).
                || Returns: JSON envelope with: ok (true if walk fired), called ("walkTo(tile, minimap)"), validation (distance, range), tile, player_tile.
                || Inputs: `x` (required int), `y` (required int), `plane` (optional int, default local-player plane), `minimap` (optional bool, default false) - true if the click should be a minimap-style click rather than scene click.
                || Use cases: "Walk to (3222, 3219)", "Move to Lumbridge bank", "Path to the next waypoint in a script".
                || Related tools: get_player_position, get_run_state, find_closest_object (target-anchored walking), interact_object.
                || Pitfalls: Engine's walkTo silently returns false when |Δ| > 40 tiles; in that case `ok` is true but `validation.in_range` is false. Walking doesn't guarantee path - collisions/obstacles may divert the route. The action queues to the next tick like all DoActions.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") { put("type", "integer"); put("description", "Target tile X") }
                    putJsonObject("y") { put("type", "integer"); put("description", "Target tile Y") }
                    putJsonObject("plane") { put("type", "integer"); put("description", "Target plane (default local-player plane)") }
                    putJsonObject("minimap") { put("type", "boolean"); put("description", "Use minimap-style click (default false)") }
                },
                required = listOf("x", "y"),
            ),
        ) { request ->
            safeJsonCall("walk_to") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val x = args["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'x'")
                val y = args["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'y'")
                val minimap = args["minimap"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                requireLoggedIn()
                val planeArg = args["plane"]?.jsonPrimitive?.content?.toIntOrNull()
                val plane = planeArg ?: localPlayer.tile.plane
                val target = Tile.of(x, y, plane)
                val player = localPlayer.tile
                val inRange = player.withinDistance(target, 40)
                val ok = walkTo(target, minimap)
                put("called", "walkTo(${target.x},${target.y},${target.plane}, minimap=$minimap)")
                put("ok", ok)
                put("tile", buildJsonObject {
                    put("x", x); put("y", y); put("plane", plane)
                })
                put("player_tile", buildJsonObject {
                    put("x", player.x); put("y", player.y); put("plane", player.plane)
                })
                put("validation", buildJsonObject {
                    put("in_range_40", inRange)
                    put("distance", player.getDistance(target))
                })
            }
        }
    }

    private fun registerFindClosestNpc(server: Server) {
        server.addTool(
            name = "find_closest_npc",
            description = """
                Purpose: Locate the nearest NPC matching a name and/or id, optionally only considering reachable NPCs (pathfinder check). Read-only convenience that pairs with interact_npc.
                || Returns: JSON envelope with: ok, found (true/false), addr (NPC heap), server_index, id, type_id, name, tile, distance (from local player). When no match, returns found=false.
                || Inputs: `name` (optional string, case-insensitive substring on NPCType.name), `id` (optional int - typeId match), `range` (optional int, default 20), `reachable` (optional bool, default false).
                || Use cases: "Find Hans within 15 tiles", "Find any 'Cow' NPC reachable from here", "Locate npc id=1 before calling interact_npc".
                || Related tools: interact_npc, list_npcs, get_entity_info.
                || Pitfalls: Range is measured in tiles. `reachable=true` runs the pathfinder which can be slow on large scenes. Returns null tile when the player isn't logged in.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") { put("type", "string"); put("description", "Name substring match") }
                    putJsonObject("id") { put("type", "integer"); put("description", "NPCType id") }
                    putJsonObject("range") { put("type", "integer"); put("description", "Max tile radius (default 20)") }
                    putJsonObject("reachable") { put("type", "boolean"); put("description", "Filter to pathfinder-reachable NPCs (default false)") }
                },
            ),
        ) { request ->
            safeJsonCall("find_closest_npc") { _ ->
                val args = request.arguments
                val name = args?.get("name")?.jsonPrimitive?.content
                val id = args?.get("id")?.jsonPrimitive?.content?.toIntOrNull()
                val range = args?.get("range")?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                val reachable = args?.get("reachable")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                if (name == null && id == null) throw BadRequest("provide 'name' or 'id'")
                requireLoggedIn()
                val npc = findClosestNPC(range, reachable) { n ->
                    val matchName = name == null || n.name().contains(name, ignoreCase = true)
                    val matchId = id == null || (if (n.typeId == -1) n.id else n.typeId) == id
                    matchName && matchId
                }
                put("ok", true)
                if (npc == null) {
                    put("found", false)
                } else {
                    put("found", true)
                    putAddr(npc.ptr.address())
                    put("server_index", npc.serverIndex)
                    put("id", npc.id)
                    put("type_id", npc.typeId)
                    runCatching { put("name", npc.name()) }
                    val tile = npc.tile
                    put("tile", buildJsonObject { put("x", tile.x); put("y", tile.y); put("plane", tile.plane) })
                    runCatching { put("distance", localPlayer.tile.getDistance(tile)) }
                }
            }
        }
    }

    private fun registerInteractNpc(server: Server) {
        server.addTool(
            name = "interact_npc",
            description = """
                **WRITE OPERATION.** Purpose: Fire a menu option on an NPC by server_index (preferred) or by name lookup, routing through NPC.interact(action). Inherits the engine's validations: exists() check, action index/name bounds, NPCType operation-name lookup. Never crafts raw DoActions.
                || Returns: JSON envelope with: ok (true if fire dispatched), called (function string), validation (exists, option_index_valid, op_name), addr (NPC), server_index, type_id, option_used.
                || Inputs: `server_index` (optional int, preferred when known), `name` (optional string), `id` (optional int - typeId), `option` (required - either string name like "Talk-to" OR int 0..5 - engine resolves names to indices via NPCType), `range` (optional int, default 20 - used when looking up by name/id).
                || Use cases: "Click 'Talk-to' on Hans", "Attack the closest goblin", "Trade NPC at server_index 42".
                || Related tools: find_closest_npc, get_entity_info, wait_for_interface_open (capture the dialog that opens), continue_dialogue.
                || Pitfalls: Requires LOGGED_IN. When option is a string, NPC.interact uses NPCType.getOpIdForName - names must match the cache exactly (case-sensitive). Action index >= MENU_OPS.size returns ok=false. NPC.exists() re-checks serverIndex against the manager; stale references fail cleanly.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("server_index") { put("type", "integer"); put("description", "NPC server_index (preferred)") }
                    putJsonObject("name") { put("type", "string"); put("description", "Name substring") }
                    putJsonObject("id") { put("type", "integer"); put("description", "NPCType id") }
                    putJsonObject("option") { put("type", "string"); put("description", "Option name (e.g. 'Talk-to') or index 0..5") }
                    putJsonObject("range") { put("type", "integer"); put("description", "Search range (default 20)") }
                },
                required = listOf("option"),
            ),
        ) { request ->
            safeJsonCall("interact_npc") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val sid = args["server_index"]?.jsonPrimitive?.content?.toIntOrNull()
                val name = args["name"]?.jsonPrimitive?.content
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull()
                val optionStr = args["option"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'option'")
                val range = args["range"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                requireLoggedIn()

                val npc = when {
                    sid != null -> {
                        val mgr = Bootstrap.client.npcManager
                        val seg = mgr[sid]
                        if (seg == null || seg.address() == 0L) throw EngineState("no NPC with server_index=$sid")
                        com.projectx.game.nxt.entity.npc.NPC(seg)
                    }
                    else -> findClosestNPC(range) { n ->
                        val matchName = name == null || n.name().contains(name, ignoreCase = true)
                        val matchId = id == null || (if (n.typeId == -1) n.id else n.typeId) == id
                        matchName && matchId
                    } ?: throw EngineState("no NPC matched name='$name' id=$id range=$range")
                }

                putAddr(npc.ptr.address())
                put("server_index", npc.serverIndex)
                put("type_id", npc.typeId)
                runCatching { put("name", npc.name()) }

                val asInt = optionStr.toIntOrNull()
                val ok = if (asInt != null) npc.interact(asInt) else npc.interact(optionStr)
                put("ok", ok)
                put("called", if (asInt != null) "NPC.interact($asInt)" else "NPC.interact(\"$optionStr\")")
                put("option_used", optionStr)
                put("validation", buildJsonObject {
                    put("exists", npc.exists())
                    val opName = if (asInt != null) runCatching { npc.getDef().getOp(asInt) }.getOrNull() else optionStr
                    put("op_name", opName ?: "(unknown)")
                })
            }
        }
    }

    private fun registerFindClosestObject(server: Server) {
        server.addTool(
            name = "find_closest_object",
            description = """
                Purpose: Locate the nearest scenery / location object matching a name and/or id, optionally restricting to a from-tile + radius. Read-only pair with interact_object.
                || Returns: JSON envelope with: ok, found, addr (SceneObject.memPointer), id, type_id, name, tile, distance.
                || Inputs: `name` (optional string substring on ObjectType.name), `id` (optional int - type id), `range` (optional int, default 20), `from_x`/`from_y`/`from_plane` (optional - center tile; defaults to local player).
                || Use cases: "Where's the closest bank booth?", "Find a tree within 15 tiles of (3222, 3219)", "Locate a 'Door' before clicking it".
                || Related tools: interact_object, list_locations.
                || Pitfalls: Requires LOGGED_IN. The engine's scan deduplicates by (shape, tile) so a complex object may appear once. Returns found=false on miss.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") { put("type", "string"); put("description", "Name substring") }
                    putJsonObject("id") { put("type", "integer"); put("description", "ObjectType id") }
                    putJsonObject("range") { put("type", "integer"); put("description", "Max tile radius (default 20)") }
                    putJsonObject("from_x") { put("type", "integer"); put("description", "Center tile X (default local player)") }
                    putJsonObject("from_y") { put("type", "integer"); put("description", "Center tile Y") }
                    putJsonObject("from_plane") { put("type", "integer"); put("description", "Center plane") }
                },
            ),
        ) { request ->
            safeJsonCall("find_closest_object") { _ ->
                val args = request.arguments
                val name = args?.get("name")?.jsonPrimitive?.content
                val id = args?.get("id")?.jsonPrimitive?.content?.toIntOrNull()
                val range = args?.get("range")?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                if (name == null && id == null) throw BadRequest("provide 'name' or 'id'")
                requireLoggedIn()
                val from = if (args.get("from_x") != null && args["from_y"] != null) {
                    Tile.of(
                        args["from_x"]!!.jsonPrimitive.content.toInt(),
                        args["from_y"]!!.jsonPrimitive.content.toInt(),
                        args["from_plane"]?.jsonPrimitive?.content?.toIntOrNull() ?: localPlayer.tile.plane,
                    )
                } else localPlayer.tile

                val obj = findClosestObjectToTile(from, range) { o ->
                    val matchName = name == null || o.name().contains(name, ignoreCase = true)
                    val matchId = id == null || o.visibleTypeId == id || o.id == id
                    matchName && matchId
                }
                put("ok", true)
                if (obj == null) {
                    put("found", false)
                } else {
                    put("found", true)
                    putAddr(obj.memPointer.address())
                    put("id", obj.id)
                    put("type_id", obj.visibleTypeId)
                    runCatching { put("name", obj.name()) }
                    val tile = obj.tile
                    put("tile", buildJsonObject { put("x", tile.x); put("y", tile.y); put("plane", tile.plane) })
                    put("distance", from.getDistance(tile))
                }
            }
        }
    }

    private fun registerInteractObject(server: Server) {
        server.addTool(
            name = "interact_object",
            description = """
                **WRITE OPERATION.** Purpose: Click a menu option on the nearest scenery/location object that matches a name or id. Routes through `script.api.interactClosestReachableObject` (which uses SceneObject.interact under the hood) - inherits engine's plane match, range check, op-name lookup, and reachability filter.
                || Returns: JSON envelope with: ok (true if a candidate was found AND the action fired), called, option, target_id, target_name, range.
                || Inputs: `option` (required string - option name like "Open"), one of `id` (int) OR `name` (string) - the object to target. `range` (optional int, default 20).
                || Use cases: "Open the closest door", "Mine the nearest copper rock", "Chop a tree".
                || Related tools: find_closest_object, list_locations, wait_for_interface_open (to capture what opens), get_local_player.
                || Pitfalls: Requires LOGGED_IN. The "reachable" variant runs the pathfinder; objects across walls or on other planes fail. If multiple objects match, the engine picks the closest - the agent must use find_closest_object first to disambiguate.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("option") { put("type", "string"); put("description", "Option name like 'Open'") }
                    putJsonObject("id") { put("type", "integer"); put("description", "ObjectType id") }
                    putJsonObject("name") { put("type", "string"); put("description", "Object name") }
                    putJsonObject("range") { put("type", "integer"); put("description", "Max range (default 20)") }
                },
                required = listOf("option"),
            ),
        ) { request ->
            safeJsonCall("interact_object") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val option = args["option"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'option'")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull()
                val name = args["name"]?.jsonPrimitive?.content
                val range = args["range"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                if (id == null && name == null) throw BadRequest("provide 'id' or 'name'")
                requireLoggedIn()

                val ok = when {
                    id != null -> interactClosestReachableObject(id, option, range)
                    else -> interactClosestReachableObject(name!!, option, range)
                }
                put("ok", ok)
                put("called", "interactClosestReachableObject(${if (id != null) "id=$id" else "name='$name'"}, '$option', $range)")
                put("option", option)
                put("target_id", id ?: -1)
                put("target_name", name ?: "")
                put("range", range)
            }
        }
    }

    private fun registerInteractGroundItem(server: Server) {
        server.addTool(
            name = "interact_ground_item",
            description = """
                **WRITE OPERATION.** Purpose: Click a menu option on a ground item stack. Routes through `GroundItem.interact(option)` which has the engine's built-in 25-tile range check.
                || Returns: JSON envelope with: ok, called, item_id, tile, option, distance.
                || Inputs: `id` (required int - item id), `option` (optional, default 0 - int 0..5 OR string option name like "Take"), `tile_x`/`tile_y`/`tile_plane` (optional - narrow to a specific tile if multiple stacks have this id).
                || Use cases: "Pick up the loot at my feet", "Take the nearest cabbage", "Examine a ground item".
                || Related tools: list_ground_items, get_content_type kind=item.
                || Pitfalls: Requires LOGGED_IN. Engine enforces 25-tile range - beyond that, ok=false. If multiple stacks contain the same item id, the first match is used. Inventory must have space for "Take" actions to succeed.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") { put("type", "integer"); put("description", "Item id") }
                    putJsonObject("option") { put("type", "string"); put("description", "Option name (e.g. 'Take') or index 0..5. Default '0' (primary).") }
                    putJsonObject("tile_x") { put("type", "integer"); put("description", "Disambiguating tile X (optional)") }
                    putJsonObject("tile_y") { put("type", "integer"); put("description", "Disambiguating tile Y (optional)") }
                    putJsonObject("tile_plane") { put("type", "integer"); put("description", "Disambiguating tile plane (optional)") }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("interact_ground_item") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val optionStr = args["option"]?.jsonPrimitive?.content ?: "0"
                val tileX = args["tile_x"]?.jsonPrimitive?.content?.toIntOrNull()
                val tileY = args["tile_y"]?.jsonPrimitive?.content?.toIntOrNull()
                val tilePlane = args["tile_plane"]?.jsonPrimitive?.content?.toIntOrNull()
                requireLoggedIn()

                val items = Bootstrap.client.itemStackList.allGroundItems
                val gi = items.firstOrNull { g ->
                    g.id == id &&
                        (tileX == null || g.tile.x == tileX) &&
                        (tileY == null || g.tile.y == tileY) &&
                        (tilePlane == null || g.tile.plane == tilePlane)
                } ?: throw EngineState("no ground item id=$id matching tile filter")

                val asInt = optionStr.toIntOrNull()
                val ok = if (asInt != null) gi.interact(asInt) else gi.interact(optionStr)
                put("ok", ok)
                put("called", if (asInt != null) "GroundItem.interact($asInt)" else "GroundItem.interact(\"$optionStr\")")
                put("item_id", id)
                put("option", optionStr)
                put("tile", buildJsonObject { put("x", gi.tile.x); put("y", gi.tile.y); put("plane", gi.tile.plane) })
                put("distance", localPlayer.tile.getDistance(gi.tile))
            }
        }
    }

    private fun registerClickComponent(server: Server) {
        server.addTool(
            name = "click_component",
            description = """
                **WRITE OPERATION.** Purpose: Click an interface component by (interface_id, component_id, slot_id) with an option number. Routes through `IFSlot.click(option)` which validates the component exists before firing.
                || Returns: JSON envelope with: ok, called, interface_id, component_id, slot_id, option.
                || Inputs: `interface_id` (required int), `component_id` (required int), `slot_id` (optional int, default -1), `option` (optional int, default 1 - 1..5 use COMPONENT opcode, 6+ uses COMPONENT_SIXPLUS).
                || Use cases: "Click the Continue button on dialog interface 1188 component 8", "Press the 'Withdraw 1' button on the bank", "Activate a HUD button".
                || Related tools: get_interface_component (find ids first), continue_dialogue (specialized for dialog continue), use_item.
                || Pitfalls: Requires LOGGED_IN. IFSlot.click returns false when `interfaces.getComponent` returns null - note this uses the visibility-filtered getter, so a structurally-present but flag-invisible component won't click. For dialog debugging, capture the component via get_interface_component first.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("interface_id") { put("type", "integer"); put("description", "Parent interface id") }
                    putJsonObject("component_id") { put("type", "integer"); put("description", "Component id") }
                    putJsonObject("slot_id") { put("type", "integer"); put("description", "Slot id (default -1)") }
                    putJsonObject("option") { put("type", "integer"); put("description", "Option number (1..N). Default 1.") }
                },
                required = listOf("interface_id", "component_id"),
            ),
        ) { request ->
            safeJsonCall("click_component") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val ifaceId = args["interface_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'interface_id'")
                val compId = args["component_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'component_id'")
                val slotId = args["slot_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                val option = args["option"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                requireLoggedIn()

                val slot = IFSlot(ifaceId, compId, slotId)
                val ok = slot.click(option)
                put("ok", ok)
                put("called", "IFSlot($ifaceId, $compId, $slotId).click($option)")
                put("interface_id", ifaceId)
                put("component_id", compId)
                put("slot_id", slotId)
                put("option", option)
            }
        }
    }

    private fun registerContinueDialogue(server: Server) {
        server.addTool(
            name = "continue_dialogue",
            description = """
                **WRITE OPERATION.** Purpose: Advance an NPC dialogue, either by selecting an option whose text contains a query (uses engine's `continueDialogueContaining` + dialogueOptions map) OR by directly invoking `IFSlot(interface_id, component_id).dialogueContinue()` for the escape-hatch path when the engine's dialog-detection list is missing the interface.
                || Returns: JSON envelope with: ok, called, mode ("by_text" | "by_component"), interface_id (when known), component_id (when known), matched_text (when by_text).
                || Inputs: `contains` (optional string) - engine looks up the option whose text contains this substring. OR pass `interface_id` + `component_id` directly to skip the engine's hardcoded list. At least one form is required.
                || Use cases: "Continue the dialog containing 'Lumbridge'", "Click the Continue button at interface 1188 component 8 even though engine doesn't recognize it as a dialog", "Select dialog option 2".
                || Related tools: list_open_interfaces (find unmapped dialog interfaces), get_interface_tree (inspect a dialog), wait_for_interface_open (capture dialog open events).
                || Pitfalls: `contains` only works when the dialog interface is in the engine's hardcoded set (1188, 720, etc.) AND `interfaces.isOpen` returns true for it. The direct `interface_id`+`component_id` form bypasses both - useful for debugging engine predicate gaps. Returns ok=false silently when no option matches.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("contains") { put("type", "string"); put("description", "Text the option must contain") }
                    putJsonObject("interface_id") { put("type", "integer"); put("description", "Direct: dialog interface id") }
                    putJsonObject("component_id") { put("type", "integer"); put("description", "Direct: continue-button component id") }
                },
            ),
        ) { request ->
            safeJsonCall("continue_dialogue") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val contains = args["contains"]?.jsonPrimitive?.content
                val ifaceId = args["interface_id"]?.jsonPrimitive?.content?.toIntOrNull()
                val compId = args["component_id"]?.jsonPrimitive?.content?.toIntOrNull()
                requireLoggedIn()

                if (ifaceId != null && compId != null) {
                    val slot = IFSlot(ifaceId, compId, -1)
                    val ok = slot.dialogueContinue()
                    put("ok", ok)
                    put("mode", "by_component")
                    put("called", "IFSlot($ifaceId, $compId).dialogueContinue()")
                    put("interface_id", ifaceId)
                    put("component_id", compId)
                } else if (contains != null) {
                    val options = dialogueOptions
                    val matchKey = options.keys.firstOrNull { it.contains(contains) }
                    val ok = continueDialogueContaining(contains)
                    put("ok", ok)
                    put("mode", "by_text")
                    put("called", "continueDialogueContaining(\"$contains\")")
                    put("matched_text", matchKey ?: "(no match)")
                    put("available_options", buildJsonArray { for (k in options.keys) add(JsonPrimitive(k)) })
                } else {
                    throw BadRequest("provide 'contains' OR ('interface_id' AND 'component_id')")
                }
            }
        }
    }

    private fun registerKeyPress(server: Server) {
        server.addTool(
            name = "key_press",
            description = """
                **WRITE OPERATION.** Purpose: Synthesize a keyboard key press (keyDown + brief delay + keyUp) by SDL keycode name. Wraps the engine's existing keyDown/keyUp hooks.
                || Returns: JSON envelope with: ok, key_name, key_code, called.
                || Inputs: `key` (required string - Key enum name, e.g. "F1", "RETURN", "ESCAPE"). `hold_ms` (optional int, default 30 - milliseconds between down and up).
                || Use cases: "Press F1 to switch action bar", "Tap Escape to close a menu", "Press Enter to submit".
                || Related tools: click_component (preferred for UI buttons), interact_object.
                || Pitfalls: Requires LOGGED_IN. Invalid key names throw bad_request with the full Key enum list. This bypasses engine-level safety (no range check) - it's the lowest-level input synthesis path; prefer click_component for UI interaction.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("key") { put("type", "string"); put("description", "Key name") }
                    putJsonObject("hold_ms") { put("type", "integer"); put("description", "Hold time in ms (default 30)") }
                },
                required = listOf("key"),
            ),
        ) { request ->
            safeJsonCallAsync("key_press") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val keyName = args["key"]?.jsonPrimitive?.content ?: throw BadRequest("missing 'key'")
                val holdMs = (args["hold_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30L).coerceIn(1L, 1000L)
                val key = try {
                    Key.valueOf(keyName.uppercase())
                } catch (t: Throwable) {
                    throw BadRequest("unknown key '$keyName'. Valid names: " + Key.entries.joinToString(", ") { it.name })
                }
                onGameTick {
                    requireLoggedIn()
                    ActionInput.keyDown(key.native)
                }
                Thread.sleep(holdMs)
                onGameTick {
                    ActionInput.keyUp(key.native)
                }
                put("ok", true)
                put("key_name", key.name)
                put("key_code", key.native)
                put("called", "keyDown($keyName) ; sleep ${holdMs}ms ; keyUp($keyName)")
            }
        }
    }

    private fun registerExecuteCs2Script(server: Server) {
        server.addTool(
            name = "execute_cs2_script",
            description = """
                **WRITE OPERATION.** Purpose: Queue a CS2 client script for execution on the next game tick via `CS2Executor.executeScript`. Up to 20 arguments are accepted (int / long / string; >6 are staged in a side buffer). Returns the post-enqueue queue depth and the captured ScriptRunner heap pointer.
                || Returns: JSON envelope with: ok, queued, script_id, args (echo of typed args), pending_count, script_runner_addr, called.
                || Inputs: `script_id` (required int). `args` (optional array of either bare primitives [numbers + strings] OR `{type:"int"|"long"|"string", value: ...}` typed objects to disambiguate int vs long).
                || Use cases: "Run quest script 75 with quest_id=4", "Execute a dialog continuation script", "Trigger a varbit update via its CS2 setter script".
                || Related tools: get_cs2_script (read the source first), cs2_executor_status (check if the executor is ready), get_varbit / get_varp (verify aftermath).
                || Return values: pass `want_result: true` to run synchronously and marshal back the values the script leaves on its operand stacks as `result.{ints,longs,strings}` (in push order). Without it, the run is fire-and-forget (queued).
                || Pitfalls: Requires CS2Executor.isReady() (ScriptRunner captured). Args > 20 are rejected. Strings are SSO-truncated at 22 bytes. Long-running scripts exceeding 1,000,000 steps are killed by the engine.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("script_id") { put("type", "integer"); put("description", "CS2 script id") }
                    putJsonObject("args") { put("type", "array"); put("description", "Up to 6 args. Each can be a bare int/string OR {type:'int'|'long'|'string', value: ...}.") }
                    putJsonObject("want_result") { put("type", "boolean"); put("description", "If true, run synchronously and marshal the values the script leaves on the int/long/string operand stacks back into the response.") }
                },
                required = listOf("script_id"),
            ),
        ) { request ->
            safeJsonCall("execute_cs2_script") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val scriptId = args["script_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'script_id'")
                val argArr = args["args"]?.jsonArray
                val coerced = mutableListOf<Any>()
                argArr?.forEachIndexed { idx, elem ->
                    val v: Any = when (elem) {
                        is JsonPrimitive -> {
                            when {
                                elem.isString -> elem.content
                                elem.longOrNull != null -> {
                                    val l = elem.long
                                    if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else l
                                }
                                else -> throw BadRequest("arg[$idx] must be int/long/string primitive")
                            }
                        }
                        is JsonObject -> {
                            val t = elem["type"]?.jsonPrimitive?.content ?: throw BadRequest("arg[$idx] missing 'type'")
                            val vv = elem["value"] ?: throw BadRequest("arg[$idx] missing 'value'")
                            when (t) {
                                "int" -> vv.jsonPrimitive.int
                                "long" -> vv.jsonPrimitive.long
                                "string" -> vv.jsonPrimitive.content
                                else -> throw BadRequest("unknown arg type '$t'")
                            }
                        }
                        else -> throw BadRequest("arg[$idx] must be primitive or {type,value}")
                    }
                    coerced.add(v)
                }
                if (coerced.size > 20) throw BadRequest("max 20 args; got ${coerced.size}")
                if (!CS2Executor.isReady()) throw EngineState("CS2Executor not ready - ScriptRunner pointer not yet captured")

                val wantResult = args["want_result"]?.jsonPrimitive?.booleanOrNull == true
                if (wantResult) {
                    val result = CS2Executor.executeScriptForResultSync(scriptId, coerced)
                        ?: throw EngineState("ScriptRunner not captured / no state recorded")
                    put("ok", true)
                    put("script_id", scriptId)
                    put("called", "CS2Executor.executeScriptForResultSync($scriptId, ${coerced.size} args)")
                    putJsonObject("result") {
                        put("ints", buildJsonArray { result.ints.forEach { add(JsonPrimitive(it)) } })
                        put("longs", buildJsonArray { result.longs.forEach { add(JsonPrimitive(it)) } })
                        put("strings", buildJsonArray { result.strings.forEach { add(JsonPrimitive(it)) } })
                    }
                    return@safeJsonCall
                }

                CS2Executor.executeScript(scriptId, *coerced.toTypedArray())
                put("ok", true)
                put("queued", true)
                put("script_id", scriptId)
                put("pending_count", CS2Executor.pendingCount())
                put("called", "CS2Executor.executeScript($scriptId, ${coerced.size} args)")
                put("args", buildJsonArray {
                    for (a in coerced) {
                        val tname = when (a) {
                            is Int -> "int"
                            is Long -> "long"
                            is String -> "string"
                            else -> a.javaClass.simpleName ?: "?"
                        }
                        add(buildJsonObject {
                            put("type", tname)
                            put("value", a.toString())
                        })
                    }
                })
            }
        }
    }

    private fun registerWaitForInterfaceOpen(server: Server) {
        server.addTool(
            name = "wait_for_interface_open",
            description = """
                Purpose: Poll the open interface set until a parent interface NOT in `exclude_ids` becomes resolvable, or the timeout elapses. The fundamental dialog-discovery primitive - does NOT call any engine `isDialogOpen` predicate. The agent captures the current open set via list_open_interfaces, performs an action, then waits for the newly-opened interface here.
                || Returns: JSON envelope with: ok, timed_out, elapsed_ms, opened (array of newly-opened parent_ids), current (full current set), exclude_ids (echo of input).
                || Inputs: `exclude_ids` (required int array - typically the parent_ids returned by a prior list_open_interfaces). `timeout_ms` (optional int, default 3000, hard cap 30000). `poll_interval_ms` (optional int, default 50, min 20).
                || Use cases: "After clicking Talk-to on Hans, wait up to 3s for the dialog interface to open and tell me what its id is", "Capture the post-action interface delta to find an unmapped dialog".
                || Related tools: list_open_interfaces (baseline), diff_open_interfaces (set-based diff), get_interface_tree (drill into the discovered interface).
                || Pitfalls: Requires LOGGED_IN throughout the wait. Releases the game tick between polls so the engine can run. The "opened" set is checked against `exclude_ids` only - if you missed a transient pre-existing interface in the baseline, it'll show up as opened.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("exclude_ids") { put("type", "array"); put("description", "Parent ids to exclude (the baseline open set)") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Max wait in ms (default 3000)") }
                    putJsonObject("poll_interval_ms") { put("type", "integer"); put("description", "Poll interval in ms (default 50)") }
                },
                required = listOf("exclude_ids"),
            ),
        ) { request ->
            safeJsonCallAsync("wait_for_interface_open") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val excludeArr = args["exclude_ids"]?.jsonArray ?: throw BadRequest("missing 'exclude_ids' array")
                val exclude = excludeArr.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }.toSet()
                val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3000L).coerceIn(50L, 30_000L)
                val pollMs = (args["poll_interval_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 50L).coerceAtLeast(20L)

                val start = System.currentTimeMillis()
                var opened: List<Int> = emptyList()
                var current: List<Int> = emptyList()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val snapshot = onGameTick { collectOpenIds() }
                    current = snapshot
                    opened = snapshot.filter { it !in exclude }
                    if (opened.isNotEmpty()) break
                    Thread.sleep(pollMs)
                }
                val elapsed = System.currentTimeMillis() - start
                put("ok", true)
                put("timed_out", opened.isEmpty())
                put("elapsed_ms", elapsed)
                put("opened", buildJsonArray { for (i in opened) add(JsonPrimitive(i)) })
                put("current", buildJsonArray { for (i in current.sorted()) add(JsonPrimitive(i)) })
                put("exclude_ids", buildJsonArray { for (i in exclude.sorted()) add(JsonPrimitive(i)) })
            }
        }
    }

    private fun collectOpenIds(): List<Int> {
        val client = Bootstrap.client
        val list = client.interfaceList
        val size = list.size.toInt()
        val ids = mutableListOf<Int>()
        for (i in 0 until size) {
            if (list.getRaw(i) != null) ids.add(i)
        }
        return ids
    }

    private fun registerWaitForVarbitChange(server: Server) {
        server.addTool(
            name = "wait_for_varbit_change",
            description = """
                Purpose: Poll a varbit (player domain by default) until its value changes from a known baseline or the timeout elapses. Returns the new value and the cycle it was observed.
                || Returns: JSON envelope with: ok, changed, timed_out, elapsed_ms, varbit_id, baseline, new_value, observed_at_cycle.
                || Inputs: `varbit_id` (required int), `baseline` (required int - the value at the start of the wait), `timeout_ms` (optional int, default 3000, hard cap 30000), `poll_interval_ms` (optional int, default 50).
                || Use cases: "Wait for the quest progress varbit to advance after I click an NPC option", "Detect a HUD-state varbit flip", "Confirm a varbit reset after using an item".
                || Related tools: get_varbit (current value), list_varbits_for_varp.
                || Pitfalls: Requires LOGGED_IN. PLAYER and CLIENT domains are supported; other domains poll the engine's varbit decoder and may always read 0. The baseline must be captured before the action - if you set baseline=current, the loop will time out immediately.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("varbit_id") { put("type", "integer"); put("description", "Varbit id") }
                    putJsonObject("baseline") { put("type", "integer"); put("description", "Value before the action") }
                    putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Max wait in ms (default 3000)") }
                    putJsonObject("poll_interval_ms") { put("type", "integer"); put("description", "Poll interval in ms (default 50)") }
                },
                required = listOf("varbit_id", "baseline"),
            ),
        ) { request ->
            safeJsonCallAsync("wait_for_varbit_change") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val varbitId = args["varbit_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'varbit_id'")
                val baseline = args["baseline"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'baseline'")
                val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3000L).coerceIn(50L, 30_000L)
                val pollMs = (args["poll_interval_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 50L).coerceAtLeast(20L)

                val start = System.currentTimeMillis()
                var newValue = baseline
                var observedCycle = 0
                var changed = false
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val read = onGameTick {
                        Bootstrap.client.playerVarDomain.getVarBit(varbitId) to Bootstrap.client.clientCycle
                    }
                    newValue = read.first
                    observedCycle = read.second
                    if (newValue != baseline) {
                        changed = true
                        break
                    }
                    Thread.sleep(pollMs)
                }
                val elapsed = System.currentTimeMillis() - start
                put("ok", true)
                put("changed", changed)
                put("timed_out", !changed)
                put("elapsed_ms", elapsed)
                put("varbit_id", varbitId)
                put("baseline", baseline)
                put("new_value", newValue)
                put("observed_at_cycle", observedCycle)
            }
        }
    }
}
