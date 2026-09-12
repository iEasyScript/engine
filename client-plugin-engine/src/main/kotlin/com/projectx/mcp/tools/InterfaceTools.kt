package com.projectx.mcp.tools

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.interfaces.InterfaceComponent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object InterfaceTools {

    fun register(server: Server): Int {
        registerListOpenInterfaces(server)
        registerGetInterfaceComponent(server)
        registerGetInterfaceTree(server)
        registerDiffOpenInterfaces(server)
        registerFindComponents(server)
        return 5
    }

    private fun collectOpenParentIds(includeRaw: Boolean = true): List<Int> {
        val client = Bootstrap.client
        val list = client.interfaceList
        val size = list.size.toInt()
        val ids = mutableListOf<Int>()
        for (i in 0 until size) {
            val parent = if (includeRaw) list.getRaw(i) else list[i]
            if (parent != null) ids.add(i)
        }
        return ids
    }

    private fun registerListOpenInterfaces(server: Server) {
        server.addTool(
            name = "list_open_interfaces",
            description = """
                Purpose: Enumerate every parent interface currently resolvable in the client's InterfaceList. Reports the heap address of each parent and a quick summary so the agent can spot newly-opened dialogs or HUDs without trusting any engine predicate. THIS IS A RAW-STATE DEBUGGING PRIMITIVE - does not call isDialogOpen() or any predicate; an interface is included if the engine has loaded a parent struct for it, regardless of the FLAGS bit 0x20 visibility.
                || Returns: JSON envelope with count, total, items[]. Each item: parent_id, addr (InterfaceParent), addr_rel, visible (component-0 visibility from screen w/h), comp0_screen_rect (null when not laid out), component_0_addr.
                || Inputs: `verbose` (optional bool) - if true, also include comp0_text and comp0_item_id when readable. `include_invisible` (optional bool, default true) - when false, drops parents whose component 0 is not visible.
                || Use cases: "Take a snapshot of open interfaces before clicking Hans, then diff after to find the dialog interface", "List every HUD parent so I can match against the engine's known interface ids", "Find unmapped dialog interfaces by capturing what opens after Talk-to".
                || Related tools: diff_open_interfaces (compute opened/closed sets), get_interface_component (single component drill-down), get_interface_tree (recursive), wait_for_interface_open (action layer primitive).
                || Pitfalls: Requires LOGGED_IN. The InterfaceList size dynamically grows; iterating 0..size is bounded. Component-0 visibility is heuristic (component-0 may not even exist). Some HUD root parents always exist regardless of state.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("verbose") {
                        put("type", "boolean")
                        put("description", "If true, include comp0_text and comp0_item_id when readable")
                    }
                    putJsonObject("include_invisible") {
                        put("type", "boolean")
                        put("description", "If false, drop parents whose component 0 is not visible. Default true.")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("list_open_interfaces") { warnings ->
                val args = request.arguments
                val verbose = args?.get("verbose")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val includeInvisible = args?.get("include_invisible")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

                val client = requireLoggedIn()
                val list = client.interfaceList
                val size = list.size.toInt()
                put("interface_list_size", size)

                val rows = mutableListOf<JsonObjectBuilder.() -> Unit>()
                var total = 0
                for (i in 0 until size) {
                    runCatching {
                        val parent = list.getRaw(i) ?: return@runCatching
                        total++
                        val comp0 = parent[0]
                        val visible = comp0?.visible ?: false
                        if (!includeInvisible && !visible) return@runCatching
                        rows.add {
                            put("parent_id", i)
                            putAddr(parent.ptr.address())
                            put("visible", visible)
                            putPtrField("component_0", comp0?.ptr?.address() ?: 0L)
                            if (comp0 != null) {
                                runCatching {
                                    val w = comp0.screenWidth
                                    val h = comp0.screenHeight
                                    put("comp0_w", w)
                                    put("comp0_h", h)
                                }
                                val rect = comp0.screenRect
                                if (rect != null) {
                                    put("comp0_screen_rect", buildJsonObject {
                                        put("x", rect.x)
                                        put("y", rect.y)
                                        put("w", rect.width)
                                        put("h", rect.height)
                                    })
                                }
                                if (verbose) {
                                    runCatching { put("comp0_text", comp0.text) }
                                    runCatching { put("comp0_item_id", comp0.itemId) }
                                    runCatching { put("comp0_parent_layer_id", comp0.parentLayerId) }
                                }
                            }
                        }
                    }.onFailure { warnings.add("parent $i skip: ${it.message}") }
                }

                put("count", rows.size)
                put("total", total)
                put("items", buildJsonArray {
                    for (row in rows) {
                        add(buildJsonObject { row() })
                    }
                })
            }
        }
    }

    private fun JsonObjectBuilder.putComponentFields(c: InterfaceComponent, verbose: Boolean) {
        putAddr(c.ptr.address())
        put("interface_id", c.interfaceId)
        put("component_id", c.componentId)
        put("slot_id", c.slotId)
        put("parent_layer_id", c.parentLayerId)
        put("visible", c.visible)
        put("parent_rel_x", c.parentRelX)
        put("parent_rel_y", c.parentRelY)
        put("screen_width", c.screenWidth)
        put("screen_height", c.screenHeight)
        val rect = c.screenRect
        if (rect != null) {
            put("screen_rect", buildJsonObject {
                put("x", rect.x)
                put("y", rect.y)
                put("w", rect.width)
                put("h", rect.height)
            })
        }
        runCatching {
            val t = c.text
            if (t.isNotEmpty()) put("text", t)
        }
        runCatching { put("item_id", c.itemId) }
        runCatching { put("stack_size", c.stackSize) }
        runCatching { put("graphic_id", c.graphicId) }
        if (verbose) {
            runCatching { put("raw_width", c.rawWidth) }
            runCatching { put("raw_height", c.rawHeight) }
            runCatching { put("x_origin_offset", c.xOriginOffset) }
            runCatching { put("y_origin_offset", c.yOriginOffset) }
            runCatching {
                val p = c.parent
                putPtrField("parent_component", p?.ptr?.address() ?: 0L)
            }
        }
    }

    private fun registerGetInterfaceComponent(server: Server) {
        server.addTool(
            name = "get_interface_component",
            description = """
                Purpose: Read all known fields of a single InterfaceComponent at (interface_id, component_id), including its screen rect (if laid out this frame) and the heap address for further drilling.
                || Returns: JSON envelope with all standard component fields: addr, interface_id, component_id, slot_id, type, visible, parent_rel_x/y, screen_width/height, screen_rect (nullable), text, item_id, stack_size, graphic_id. With verbose=true: raw_width/height, x/y_origin_offset, x/y_origin_mode, x/y_anchor_mode, parent_component_addr.
                || Inputs: `interface_id` (required int), `component_id` (required int), `verbose` (optional bool).
                || Use cases: "What text is in component 11 of interface 1188?", "Is the Continue button laid out and clickable?", "Drill into a component to find an unknown field via read_struct_field".
                || Related tools: get_interface_tree (recursive walk), list_open_interfaces (find parent ids), click_component (action layer).
                || Pitfalls: Returns engine_state error if the parent or component is null. `visible` heuristic uses non-zero width/height. screen_rect can be null even for visible components if the layout pass hasn't run this frame.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("interface_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Parent interface id")
                    })
                    put("component_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Component id within the parent")
                    })
                    put("verbose", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include extra layout-mode fields and parent pointer")
                    })
                },
                required = listOf("interface_id", "component_id"),
            ),
        ) { request ->
            safeJsonCall("get_interface_component") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val ifaceId = args["interface_id"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing 'interface_id'")
                val compId = args["component_id"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing 'component_id'")
                val verbose = args["verbose"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val client = requireLoggedIn()
                val parent = client.interfaceList.getRaw(ifaceId)
                    ?: throw EngineState("no parent for interface_id=$ifaceId")
                putPtrField("parent", parent.ptr.address())
                val comp = parent[compId] ?: throw EngineState("no component_id=$compId on interface_id=$ifaceId")
                putComponentFields(comp, verbose)
            }
        }
    }

    private fun registerGetInterfaceTree(server: Server) {
        server.addTool(
            name = "get_interface_tree",
            description = """
                Purpose: Recursively dump the component tree of a parent interface, capped at `max_depth` (default 2). Each node carries its heap address and standard component fields. The children list at the leaf level is summarized via children_count + children_truncated when depth is exhausted.
                || Returns: JSON envelope with: parent_id, parent_addr, interface_list_size, root component object containing children[] (recursive). At max_depth, deeper children are reported as children_truncated=true with the count.
                || Inputs: `interface_id` (required int), `component_id` (optional int, default 0 - root), `max_depth` (optional int, default 2), `max_children_per_node` (optional int, default 25), `verbose` (optional bool).
                || Use cases: "Dump the chat dialog interface to find the Continue button id", "Walk a HUD to map all visible text", "Discover the structure of an unmapped dialog interface".
                || Related tools: get_interface_component, list_open_interfaces, find_components_by_text (Phase 1 future).
                || Pitfalls: Trees can be deep; the default cap is conservative. Calls to children[] may reallocate readers each invocation - large `max_depth` + `max_children_per_node` will be slow. Components that aren't laid out this frame have null screen_rect.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("interface_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Parent interface id")
                    })
                    put("component_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Root component id within the parent (default 0)")
                    })
                    put("max_depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max recursion depth (default 2)")
                    })
                    put("max_children_per_node", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max children to list per node (default 25); excess summarized as children_truncated")
                    })
                    put("verbose", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include all extra layout-mode fields per node")
                    })
                },
                required = listOf("interface_id"),
            ),
        ) { request ->
            safeJsonCall("get_interface_tree") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val ifaceId = args["interface_id"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BadRequest("missing 'interface_id'")
                val rootComp = args["component_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val maxDepth = (args["max_depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2).coerceIn(0, 6)
                val maxChildrenPerNode = (args["max_children_per_node"]?.jsonPrimitive?.content?.toIntOrNull() ?: 25).coerceIn(1, 200)
                val verbose = args["verbose"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val client = requireLoggedIn()
                val parent = client.interfaceList.getRaw(ifaceId)
                    ?: throw EngineState("no parent for interface_id=$ifaceId")

                // Components are a flat vector indexed by id (InterfaceParent at +0x8) - there is no
                // per-component child vector to recurse. Emit every component with its parent id;
                // the caller reconstructs nesting from parent_component_id. (max_depth /
                // max_children_per_node retained for arg compatibility but no longer meaningful.)
                put("parent_id", ifaceId)
                putPtrField("parent", parent.ptr.address())
                put("interface_list_size", client.interfaceList.size.toInt())
                val count = parent.size
                put("component_count", count)
                put("components", buildJsonArray {
                    for (cid in 0 until count) {
                        val c = parent[cid] ?: continue
                        add(buildJsonObject {
                            putComponentFields(c, verbose)
                            put("parent_component_id", c.parent?.componentId ?: -1)
                        })
                    }
                })
            }
        }
    }

    private fun registerFindComponents(server: Server) {
        server.addTool(
            name = "find_components",
            description = """
                Purpose: Scan every component of every open interface (or a specified parent) and return the ones matching predicate-style filters - text substring/regex, component type, visibility, has-text, item-id, graphic-id. Walks each parent's full component array (not just component 0's children), so it catches widgets stored as peer slots (e.g. interface 1186 component 3 holding NPC dialog text).
                || Returns: JSON envelope with count, total_scanned, items[]. Each item: interface_id, component_id, addr, addr_rel, type, visible, screen_rect (nullable), text, item_id, graphic_id.
                || Inputs: `text` (optional string - case-insensitive substring on component.text), `text_regex` (optional bool - interpret `text` as regex), `interface_id` (optional int - scan only this parent; omit to scan all open parents), `visible_only` (optional bool, default true - drop comp0-invisible parents and components with zero w/h), `has_text` (optional bool - require non-empty text), `type` (optional int - match component.type, e.g. 4 for button), `item_id` (optional int - match an exact item id, e.g. for inventory slots), `graphic_id` (optional int), `limit` (optional int, default 50, max 500), `include_invisible_parents` (optional bool, default false - when true, scan parents whose component 0 is not visibility-flagged).
                || Use cases: "Find every component whose text contains 'Fluffs hisses' across all open interfaces", "Locate the Continue button (type=4) inside the dialog interface 1186", "Search for any component holding item id 1511", "Find which interface has the text I see on screen".
                || Related tools: list_open_interfaces (parent overview), get_interface_component (single component drill-down), get_interface_tree (recursive), continue_dialogue (act on a discovered dialog component).
                || Pitfalls: Scans up to 1000 component slots per parent × every open parent (~80 parents × 1000 = ~80k slot probes worst case). With visible_only=true (default) it short-circuits invisible parents - usually only ~30 parents to scan. Components with empty text are skipped from the items list unless `has_text=false` is explicitly set. Regex compile errors return bad_request.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Case-insensitive substring on component.text. Omit to skip text filter.")
                    }
                    putJsonObject("text_regex") {
                        put("type", "boolean")
                        put("description", "Interpret 'text' as regex (case-insensitive). Default false.")
                    }
                    putJsonObject("interface_id") {
                        put("type", "integer")
                        put("description", "Scan only this parent. Omit to scan all open parents.")
                    }
                    putJsonObject("visible_only") {
                        put("type", "boolean")
                        put("description", "Drop components with zero w/h and parents whose component 0 is not visible. Default true.")
                    }
                    putJsonObject("has_text") {
                        put("type", "boolean")
                        put("description", "Require non-empty text. Default true when 'text' is provided, false otherwise.")
                    }
                    putJsonObject("parent_layer_id") {
                        put("type", "integer")
                        put("description", "Match the component id of the enclosing layer exactly.")
                    }
                    putJsonObject("item_id") {
                        put("type", "integer")
                        put("description", "Match component.item_id exactly.")
                    }
                    putJsonObject("graphic_id") {
                        put("type", "integer")
                        put("description", "Match component.graphic_id exactly.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max items returned (default 50, hard max 500).")
                    }
                    putJsonObject("include_invisible_parents") {
                        put("type", "boolean")
                        put("description", "Scan parents whose component 0 isn't visibility-flagged. Default false.")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("find_components") { warnings ->
                val args = request.arguments
                val text = args?.get("text")?.jsonPrimitive?.content
                val textRegex = args?.get("text_regex")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val ifaceFilter = args?.get("interface_id")?.jsonPrimitive?.content?.toIntOrNull()
                val visibleOnly = args?.get("visible_only")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val hasText = args?.get("has_text")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: (text != null)
                val parentLayerFilter = args?.get("parent_layer_id")?.jsonPrimitive?.content?.toIntOrNull()
                val itemIdFilter = args?.get("item_id")?.jsonPrimitive?.content?.toIntOrNull()
                val graphicIdFilter = args?.get("graphic_id")?.jsonPrimitive?.content?.toIntOrNull()
                val limit = (args?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val includeInvisibleParents = args?.get("include_invisible_parents")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val regex = if (text != null && textRegex) {
                    try {
                        Regex(text, RegexOption.IGNORE_CASE)
                    } catch (t: Throwable) {
                        throw BadRequest("regex compile error: ${t.message}")
                    }
                } else null

                val client = requireLoggedIn()
                val list = client.interfaceList
                val size = list.size.toInt()

                val parentIds = if (ifaceFilter != null) listOf(ifaceFilter) else (0 until size).toList()

                val hits = mutableListOf<JsonObject>()
                var scanned = 0
                var parentsScanned = 0
                outer@ for (pid in parentIds) {
                    val parent = runCatching { list.getRaw(pid) }.getOrNull() ?: continue
                    if (visibleOnly && !includeInvisibleParents) {
                        val comp0 = runCatching { parent[0] }.getOrNull()
                        if (comp0 == null || !comp0.visible) continue
                    }
                    parentsScanned++
                    for (comp in parent) {
                        scanned++
                        val compVisible = runCatching { comp.visible }.getOrDefault(false)
                        if (visibleOnly && !compVisible) continue

                        val compText = runCatching { comp.text }.getOrDefault("")
                        if (hasText && compText.isEmpty()) continue
                        if (text != null) {
                            val matched = if (regex != null) regex.containsMatchIn(compText)
                            else compText.contains(text, ignoreCase = true)
                            if (!matched) continue
                        }
                        if (parentLayerFilter != null && runCatching { comp.parentLayerId }.getOrDefault(Int.MIN_VALUE) != parentLayerFilter) continue
                        if (itemIdFilter != null && runCatching { comp.itemId }.getOrDefault(Int.MIN_VALUE) != itemIdFilter) continue
                        if (graphicIdFilter != null && runCatching { comp.graphicId }.getOrDefault(Int.MIN_VALUE) != graphicIdFilter) continue

                        hits.add(buildJsonObject {
                            put("interface_id", runCatching { comp.interfaceId }.getOrDefault(pid))
                            put("component_id", runCatching { comp.componentId }.getOrDefault(-1))
                            putAddr(comp.ptr.address())
                            runCatching { put("parent_layer_id", comp.parentLayerId) }
                            put("visible", compVisible)
                            val rect = runCatching { comp.screenRect }.getOrNull()
                            if (rect != null) {
                                put("screen_rect", buildJsonObject {
                                    put("x", rect.x); put("y", rect.y)
                                    put("w", rect.width); put("h", rect.height)
                                })
                            }
                            if (compText.isNotEmpty()) put("text", compText)
                            runCatching { put("item_id", comp.itemId) }
                            runCatching { put("graphic_id", comp.graphicId) }
                        })
                        if (hits.size >= limit) break@outer
                    }
                }

                put("count", hits.size)
                put("scanned", scanned)
                put("parents_scanned", parentsScanned)
                put("truncated", hits.size == limit)
                put("items", JsonArray(hits))
            }
        }
    }

    private fun registerDiffOpenInterfaces(server: Server) {
        server.addTool(
            name = "diff_open_interfaces",
            description = """
                Purpose: Compare a previous set of open parent interface ids against the live set right now, returning opened / closed / persisted. The fundamental debugging primitive for "what happened when I clicked X?" - the agent first calls list_open_interfaces to capture a baseline, performs an action, then calls this tool with the baseline ids to see what changed.
                || Returns: JSON envelope with `opened` (array of newly-opened parent ids), `closed` (ids that were in baseline but not now), `persisted` (ids in both), `current` (full current set).
                || Inputs: `before_ids` (required int array) - the parent ids that were open before. Pass the parent_ids from a prior list_open_interfaces response. Optional `include_invisible` (default true) controls whether to use raw or visibility-filtered current set.
                || Use cases: "After clicking Talk-to on Hans, what new interface opened? That's the dialog id." "Did any interface close as a side effect of this action?", "Verify a script step that should open and close a sequence of HUDs".
                || Related tools: list_open_interfaces (capture baseline), wait_for_interface_open (action layer that wraps polling+diff), get_interface_tree (drill into a newly-opened interface).
                || Pitfalls: Requires LOGGED_IN. Interface ids are recycled; very rare race where an id closes and reopens between snapshots looks like "persisted" not "opened+closed". Some HUD parents flicker (open/close every tick); they'll show as opened or closed depending on snapshot timing.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("before_ids", buildJsonObject {
                        put("type", "array")
                        put("description", "Array of parent_id ints from a prior list_open_interfaces call")
                    })
                    put("include_invisible", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true (default), use raw parent presence; if false, filter to component-0-visible parents")
                    })
                },
                required = listOf("before_ids"),
            ),
        ) { request ->
            safeJsonCall("diff_open_interfaces") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val beforeArr = args["before_ids"]?.jsonArray
                    ?: throw BadRequest("missing 'before_ids' array")
                val before = beforeArr.mapNotNull { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }.toSet()
                val includeInvisible = args["include_invisible"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

                val client = requireLoggedIn()
                val list = client.interfaceList
                val size = list.size.toInt()
                val current = mutableSetOf<Int>()
                for (i in 0 until size) {
                    val parent = list.getRaw(i) ?: continue
                    if (!includeInvisible) {
                        val comp0 = parent[0] ?: continue
                        if (!comp0.visible) continue
                    }
                    current.add(i)
                }

                val opened = (current - before).sorted()
                val closed = (before - current).sorted()
                val persisted = (before intersect current).sorted()

                put("opened", buildJsonArray { for (i in opened) add(JsonPrimitive(i)) })
                put("closed", buildJsonArray { for (i in closed) add(JsonPrimitive(i)) })
                put("persisted", buildJsonArray { for (i in persisted) add(JsonPrimitive(i)) })
                put("current", buildJsonArray { for (i in current.sorted()) add(JsonPrimitive(i)) })
                put("opened_count", opened.size)
                put("closed_count", closed.size)
            }
        }
    }
}
