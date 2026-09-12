package com.projectx.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object InventoryTools {

    private val KNOWN_INV_IDS = listOf(
        93 to "backpack",
        94 to "equipment",
        95 to "bank",
        530 to "beast_of_burden",
        773 to "area_loot",
        623 to "coin_pouch",
        670 to "cosmetic_equipment",
        937 to "woodbox",
    )

    private val EQUIPMENT_SLOT_NAMES = listOf(
        "Head",       // 0
        "Cape",       // 1
        "Neck",       // 2
        "Quiver",     // 3 (arrow/ammo)
        "Weapon",     // 4
        "Body",       // 5
        "Shield",     // 6 (off-hand)
        "(reserved)", // 7
        "Legs",       // 8
        "(reserved)", // 9
        "Gloves",     // 10
        "Boots",      // 11
        "(reserved)", // 12
        "Ring",       // 13
        "Aura",       // 14
        "Pocket",     // 15
    )

    fun register(server: Server): Int {
        registerListInventories(server)
        registerGetInventory(server)
        registerGetEquipment(server)
        registerGetObjVar(server)
        return 4
    }

    private fun registerListInventories(server: Server) {
        server.addTool(
            name = "list_inventories",
            description = """
                Purpose: Probe every well-known inventory id (backpack/equipment/bank/etc.) and report which ones are currently loaded for the player, with size and free-slot count. Returns the heap address of each Inventory struct so the agent can drill in.
                || Returns: JSON envelope with count, items[]. Each item: inv_id, label, addr, addr_rel, exists, size, used_slots, free_slots, is_full, is_empty.
                || Inputs: `include_empty` (optional bool, default true) - when false, drops inventories whose `exists` is false. `verbose` (optional bool) - include additional debug fields.
                || Use cases: "Which inventories do I have open right now (bank/loot/etc.)?", "Is my backpack full?", "Get the addr of my equipment inventory to drill into the struct".
                || Related tools: get_inventory (per-inventory detail), get_equipment (named equipment slots), use_item (action layer).
                || Pitfalls: Requires LOGGED_IN. The list of known inv ids is hard-coded to the common ones; an unmapped inv id can still be queried directly via get_inventory. `exists` calls into the engine's getInventory native function - a null return means the inventory is not currently allocated for this player.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("include_empty") {
                        put("type", "boolean")
                        put("description", "If true (default), include inventories that don't currently exist for this player.")
                    }
                    putJsonObject("verbose") {
                        put("type", "boolean")
                        put("description", "Include extra debug fields per inventory.")
                    }
                },
            ),
        ) { request ->
            safeJsonCall("list_inventories") { warnings ->
                val args = request.arguments
                val includeEmpty = args?.get("include_empty")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val client = requireLoggedIn()
                val invs = client.inventoryManager
                var emitted = 0
                put("items", buildJsonArray {
                    for ((id, label) in KNOWN_INV_IDS) {
                        val exists = runCatching { invs.exists(id) }.getOrDefault(false)
                        if (!exists && !includeEmpty) continue
                        runCatching {
                            val obj = buildJsonObject {
                                put("inv_id", id)
                                put("label", label)
                                put("exists", exists)
                                if (exists) {
                                    val inv = invs[id]
                                    putAddr(inv.ptr.address())
                                    val size = inv.items.size.toInt()
                                    val free = inv.freeSlots
                                    put("size", size)
                                    put("used_slots", size - free)
                                    put("free_slots", free)
                                    put("is_full", inv.isFull)
                                    put("is_empty", inv.isEmpty)
                                } else {
                                    put("addr", JsonNull)
                                    put("addr_rel", JsonNull)
                                }
                            }
                            add(obj)
                            emitted++
                        }.onFailure { warnings.add("inv $id read failed: ${it.message}") }
                    }
                })
                put("count", emitted)
            }
        }
    }

    private fun registerGetInventory(server: Server) {
        server.addTool(
            name = "get_inventory",
            description = """
                Purpose: Dump every slot of an inventory by id, with per-slot item id, amount, resolved name, and the per-item ObjVarDomain heap address when present. The Inventory struct address is reported so the agent can drill in.
                || Returns: JSON envelope with: inv_id, addr (Inventory), interface_id, component_id, size, used_slots, free_slots, items[]. Each item entry: slot, item_id, amount, name, has_obj_var_domain, obj_var_domain_addr.
                || Inputs: `id` (required int) - the inventory id. `include_empty_slots` (optional bool, default false) - emit slots whose item_id is -1.
                || Use cases: "What's in my bank?", "Is the inventory slot 3 a dragon dagger?", "Find any item with ObjVarDomain charges so I can read them".
                || Related tools: list_inventories (which exist), get_obj_var (per-item ObjVarDomain query), use_item (action layer), get_content_type kind=item (ItemType cache definition).
                || Pitfalls: Requires LOGGED_IN. Throws engine_state when the inventory id is not currently allocated. The "size" reads the underlying items Vector size - empty slots have item_id = -1.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "Inventory id (e.g. 93=backpack, 94=equipment, 95=bank)")
                    }
                    putJsonObject("include_empty_slots") {
                        put("type", "boolean")
                        put("description", "If true, include slots with item_id == -1. Default false.")
                    }
                },
                required = listOf("id"),
            ),
        ) { request ->
            safeJsonCall("get_inventory") { warnings ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val id = args["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'id'")
                val includeEmpty = args["include_empty_slots"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val client = requireLoggedIn()
                if (!client.inventoryManager.exists(id)) throw EngineState("inventory $id not allocated for this player")
                val inv = client.inventoryManager[id]
                put("inv_id", id)
                putAddr(inv.ptr.address())
                put("interface_id", inv.interfaceId)
                put("component_id", inv.componentId)
                val size = inv.items.size.toInt()
                val free = inv.freeSlots
                put("size", size)
                put("used_slots", size - free)
                put("free_slots", free)

                put("items", buildJsonArray {
                    for (slot in 0 until size) {
                        runCatching {
                            val item = inv[slot]
                            if (item == null && !includeEmpty) return@runCatching
                            add(buildJsonObject {
                                put("slot", slot)
                                if (item == null) {
                                    put("item_id", -1)
                                    put("amount", 0)
                                } else {
                                    put("item_id", item.id)
                                    put("amount", item.amount)
                                    runCatching { put("name", item.name) }
                                    val ovd = item.varDomain
                                    put("has_obj_var_domain", ovd != null)
                                    if (ovd != null) {
                                        putPtrField("obj_var_domain", ovd.ptr.address())
                                    }
                                }
                            })
                        }.onFailure { warnings.add("slot $slot read failed: ${it.message}") }
                    }
                })
            }
        }
    }

    private fun registerGetEquipment(server: Server) {
        server.addTool(
            name = "get_equipment",
            description = """
                Purpose: Convenience wrapper over inventory id 94 (equipment) with each slot labeled by name (Head/Cape/Neck/Quiver/Weapon/Body/Shield/Legs/Gloves/Boots/Ring/Aura/Pocket).
                || Returns: JSON envelope with: addr (Inventory), size, items[]. Each item: slot, slot_name, item_id, amount, name, has_obj_var_domain, obj_var_domain_addr. Empty slots are emitted with item_id=-1 so the agent can see all 16 positions.
                || Inputs: none.
                || Use cases: "What am I wearing?", "Is my weapon a Drygore longsword?", "Check if I have a Quiver-slot ammo".
                || Related tools: get_inventory (raw), use_item (action layer to swap), get_content_type kind=item (cache definition).
                || Pitfalls: Requires LOGGED_IN. The slot order is the engine's; the names are derived from the conventional NXT equipment layout. Some slots are reserved/unused and show as "(reserved)".
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            safeJsonCall("get_equipment") { warnings ->
                val client = requireLoggedIn()
                if (!client.inventoryManager.exists(94)) throw EngineState("equipment inventory (94) not allocated")
                val inv = client.inventoryManager[94]
                putAddr(inv.ptr.address())
                val size = inv.items.size.toInt()
                put("size", size)

                put("items", buildJsonArray {
                    for (slot in 0 until size) {
                        runCatching {
                            val item = inv[slot]
                            val slotName = EQUIPMENT_SLOT_NAMES.getOrNull(slot) ?: "slot_$slot"
                            add(buildJsonObject {
                                put("slot", slot)
                                put("slot_name", slotName)
                                if (item == null) {
                                    put("item_id", -1)
                                    put("amount", 0)
                                } else {
                                    put("item_id", item.id)
                                    put("amount", item.amount)
                                    runCatching { put("name", item.name) }
                                    put("has_obj_var_domain", item.varDomain != null)
                                    if (item.varDomain != null) putPtrField("obj_var_domain", item.varDomain.ptr.address())
                                }
                            })
                        }.onFailure { warnings.add("equipment slot $slot read failed: ${it.message}") }
                    }
                })
            }
        }
    }

    private fun registerGetObjVar(server: Server) {
        server.addTool(
            name = "get_obj_var",
            description = """
                Purpose: Read a per-item ObjVarDomain entry (object-scoped var, e.g. weapon charges / item-specific state) by inventory id, slot, and var id. Returns the value plus the underlying hash-table node addr.
                || Returns: JSON envelope with: inv_id, slot, item_id, var_id, value (int), node_addr (nullable).
                || Inputs: `inv_id` (required int), `slot` (required int), `var_id` (required int), `as_varbit` (optional bool, default false) - if true, decode as a varbit on top of the underlying obj-var.
                || Use cases: "How many charges are on the weapon in my main-hand slot?", "Read a quest-tracking obj-var on an inventory item".
                || Related tools: get_inventory (find which slots have obj_var_domain), get_varbit (player-domain varbit decode).
                || Pitfalls: Requires LOGGED_IN. Many items have NO ObjVarDomain - `node_addr` will be null and value=0. The varbit decode path uses VarbitType.get(var_id) which may not match if var_id is not a registered varbit.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("inv_id") {
                        put("type", "integer")
                        put("description", "Inventory id")
                    }
                    putJsonObject("slot") {
                        put("type", "integer")
                        put("description", "Slot index")
                    }
                    putJsonObject("var_id") {
                        put("type", "integer")
                        put("description", "Var id (or varbit id if as_varbit=true)")
                    }
                    putJsonObject("as_varbit") {
                        put("type", "boolean")
                        put("description", "If true, decode as varbit. Default false (raw obj var).")
                    }
                },
                required = listOf("inv_id", "slot", "var_id"),
            ),
        ) { request ->
            safeJsonCall("get_obj_var") { _ ->
                val args = request.arguments ?: throw BadRequest("missing arguments")
                val invId = args["inv_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'inv_id'")
                val slot = args["slot"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'slot'")
                val varId = args["var_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BadRequest("missing/invalid 'var_id'")
                val asVarbit = args["as_varbit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val client = requireLoggedIn()
                if (!client.inventoryManager.exists(invId)) throw EngineState("inventory $invId not allocated")
                val inv = client.inventoryManager[invId]
                val item = inv[slot] ?: throw EngineState("slot $slot is empty in inventory $invId")
                put("inv_id", invId)
                put("slot", slot)
                put("item_id", item.id)
                put("var_id", varId)

                val ovd = item.varDomain
                if (ovd == null) {
                    put("value", 0)
                    putPtrField("node", 0L)
                    put("has_obj_var_domain", false)
                    return@safeJsonCall
                }
                put("has_obj_var_domain", true)
                putPtrField("obj_var_domain", ovd.ptr.address())
                val value = if (asVarbit) ovd.getVarBit(varId) else ovd.getVar(varId)
                put("value", value)
                put("as_varbit", asVarbit)
            }
        }
    }
}
