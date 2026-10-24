package com.projectx.pathfinder.navpath

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import world.gregs.voidps.type.Tile

internal class NavParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Reads a navpathService `/route` answer: an object with an `actions` array (or a bare array of actions). Macro
 * actions describe themselves through `metadata.steps[].db_row`, the planner's own database rows; the first row of the
 * action's kind gives its object, NPC or item, and the rows after it become the action's chain. A step kind with no
 * client action is kept as [NavStep.Unsupported] so the route can be refused whole rather than walked with a hole.
 */
internal object NavResultsParser {

    fun parse(text: String?): NavRoute {
        if (text.isNullOrBlank()) throw NavParseException("the route answer is blank")
        val root = try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw NavParseException("the route answer is not JSON: ${e.message}", e)
        }
        val (actions, envelope) = when {
            root.isJsonArray -> root.asJsonArray to null
            root.isJsonObject -> {
                val o = root.asJsonObject
                val found = o.get("found")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                val list = o.getAsJsonArrayOrNull("actions")
                if (list == null && found) throw NavParseException("the route answer has no actions")
                (list ?: JsonArray()) to o
            }
            else -> throw NavParseException("the route answer is neither an object nor an array")
        }
        val steps = ArrayList<NavStep>(actions.size())
        var enteredByTeleport = false
        actions.forEachIndexed { index, element ->
            val action = element.takeIf { it.isJsonObject }?.asJsonObject ?: throw NavParseException("action $index is not an object")
            if (index == 0 && str(obj(action, "metadata"), "reason") == "start_coordinate_not_found") enteredByTeleport = true
            steps += step(index, action)
        }
        return NavRoute(
            found = envelope?.get("found")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            cost = envelope?.get("cost")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0,
            lengthTiles = int(envelope, "length_tiles"),
            durationMs = int(envelope, "duration_ms"),
            steps = steps,
            enteredByTeleport = enteredByTeleport,
        )
    }

    private fun step(index: Int, o: JsonObject): NavStep {
        val type = str(o, "type") ?: throw NavParseException("action $index has no type")
        return when (type) {
            "move" -> NavStep.Move(tileOf(index, o.get("to"), "to") ?: moveFromMetadata(index, o))
            "surge" -> NavStep.Surge(tileOf(index, o.get("from"), "from"), required(index, o, "to"), int(o, "tiles_covered"))
            "dive" -> NavStep.Dive(tileOf(index, o.get("from"), "from"), required(index, o, "to"), int(o, "tiles_covered"))
            "tile_exact" -> NavStep.TileExact(required(index, o, "to"), bool(o, "minimap"))
            "fairy_ring" -> fairyRing(index, o)
            "lodestone" -> lodestone(index, o)
            "npc" -> npc(index, o)
            "ifslot" -> ifSlot(index, o)
            "object" -> loc(index, o)
            "door" -> door(index, o)
            "item_click", "item" -> itemClick(index, o)
            "poa_item" -> poaItem(index, o)
            "use_on" -> useOn(index, o)
            "teleport", "global_teleport" -> NavStep.Unsupported(type, "no client action for a generic teleport")
            else -> NavStep.Unsupported(type, "unknown step type")
        }
    }

    private fun moveFromMetadata(index: Int, o: JsonObject): Tile {
        val meta = obj(o, "metadata") ?: throw NavParseException("move $index has no destination")
        val x = int(meta, "x1") ?: throw NavParseException("move $index has no x1")
        val y = int(meta, "y1") ?: throw NavParseException("move $index has no y1")
        val plane = int(meta, "plane") ?: throw NavParseException("move $index has no plane")
        return Tile.of(x, y, plane)
    }

    private fun fairyRing(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val code = str(o, "code") ?: str(meta, "destination_code") ?: throw NavParseException("fairy ring $index has no code")
        val portable = bool(meta, "portable") == true
        val from = obj(o, "from") ?: throw NavParseException("fairy ring $index has no origin")
        val objectId = int(from, "object_id")
        if (objectId == null && !portable) throw NavParseException("fairy ring $index has no ring object")
        return NavStep.FairyRing(
            code = code.lowercase(),
            objectId = if (portable) -1 else objectId!!,
            action = str(o, "action") ?: str(meta, "action") ?: "",
            from = tileOf(index, from, "from"),
            to = required(index, o, "to"),
            portable = portable,
        )
    }

    private fun lodestone(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "lodestone")
        val teleport = obj(meta, "teleport")
        val teleportDb = row(teleport, "lodestone")
        val name = str(o, "value") ?: str(o, "lodestone") ?: str(o, "target_lodestone") ?: str(meta, "lodestone") ?: str(meta, "target_lodestone") ?:
            str(teleport, "lodestone") ?: str(teleport, "target_lodestone") ?: str(db, "lodestone") ?: str(teleportDb, "lodestone") ?:
            firstStepField(meta, "lodestone") ?: firstStepField(teleport, "lodestone")
            ?: throw NavParseException("lodestone $index names no lodestone")
        val chain = childChain(db) + childChain(teleportDb) + stepsChain(meta, setOf("lodestone")) + stepsChain(teleport, setOf("lodestone")) + topChain(o)
        return NavStep.Lodestone(name, pointOf(index, o, "from"), pointOf(index, o, "to"), chain)
    }

    private fun npc(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "npc")
        val id = int(o, "id") ?: int(meta, "npc_id") ?: int(db, "npc_id")
        val name = str(o, "name") ?: str(meta, "npc_name") ?: str(db, "npc_name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: throw NavParseException("npc $index has no action")
        if (id == null && name.isNullOrBlank()) throw NavParseException("npc $index names no NPC")
        return NavStep.Npc(
            id, name, action, int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius"),
            rect(db, "dest") ?: xyArea(obj(meta, "destination_area") ?: obj(o, "destination_area")),
            rect(db, "orig"),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("npc")) + topChain(o),
        )
    }

    private fun ifSlot(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "ifslot")
        fun need(key: String, metaKey: String) = int(o, key) ?: int(meta, metaKey) ?: int(db, metaKey)
            ?: throw NavParseException("ifslot $index has no $key")
        return NavStep.IFSlot(
            need("interface", "interface_id"), need("component", "component_id"), need("slot", "slot_id"), need("click_id", "click_id"),
            rect(db, "dest"), pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("ifslot")) + topChain(o),
        )
    }

    private fun loc(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "object")
        val id = int(o, "id") ?: int(meta, "object_id") ?: int(db, "object_id")
        val name = str(o, "name") ?: str(meta, "object_name") ?: str(db, "object_name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: throw NavParseException("object $index has no action")
        if (id == null && name.isNullOrBlank()) throw NavParseException("object $index names no object")
        return NavStep.Loc(
            id, name, action, int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius"),
            rect(db, "dest") ?: xyArea(obj(meta, "destination_area") ?: obj(o, "destination_area")),
            rect(db, "orig"),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("object")) + topChain(o),
        )
    }

    private fun door(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "door")
        val info = obj(o, "info")
        val direction = str(o, "direction") ?: str(meta, "door_direction") ?: str(db, "direction")
            ?: throw NavParseException("door $index has no direction")
        fun tile(infoKey: String, rowKey: String) =
            tileOf(index, info?.get(infoKey), infoKey) ?: tileOf(index, meta?.get(rowKey), rowKey) ?: tileOf(index, db?.get(rowKey), rowKey)
        return NavStep.Door(
            direction = direction.uppercase(),
            realIdOpen = int(info, "realIdOpen") ?: int(o, "realIdOpen") ?: int(meta, "real_id_open") ?: int(db, "real_id_open"),
            realIdClosed = int(info, "realIdClosed") ?: int(o, "realIdClosed") ?: int(meta, "real_id_closed") ?: int(db, "real_id_closed"),
            openAction = (str(info, "openAction") ?: str(o, "openAction") ?: str(db, "open_action") ?: str(meta, "action"))?.takeIf { it.isNotBlank() },
            tileInside = tile("tileInside", "tile_inside"),
            tileOutside = tile("tileOutside", "tile_outside"),
            from = pointOf(index, o, "from"),
            to = pointOf(index, o, "to"),
            chain = childChain(db) + stepsChain(meta, setOf("door")) + topChain(o),
        )
    }

    private fun itemClick(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val teleport = obj(meta, "teleport")
        val db = row(meta, "item") ?: row(teleport, "item")
        val id = int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val name = str(o, "name") ?: str(meta, "item_name") ?: str(meta, "name") ?: str(db, "item_name") ?: str(db, "name")
        val option = str(o, "option") ?: str(meta, "option") ?: str(db, "action")
        val optionNum = int(o, "option_num") ?: int(meta, "option_num") ?: int(db, "option_num")
        if (id == null && name.isNullOrBlank()) throw NavParseException("item $index names no item")
        if (option.isNullOrBlank() && optionNum == null) throw NavParseException("item $index has no option")
        return NavStep.ItemClick(
            id, name, option, optionNum,
            rect(db, "dest") ?: xyArea(obj(meta, "destination_area") ?: obj(o, "destination_area")),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("item", "item_click")) + stepsChain(teleport, setOf("item", "item_click")) + topChain(o),
        )
    }

    private fun poaItem(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val teleport = obj(meta, "teleport")
        val db = row(meta, "poa_item") ?: row(teleport, "poa_item")
        val id = int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val name = str(o, "name") ?: str(meta, "item_name") ?: str(meta, "name") ?: str(db, "item_name") ?: str(db, "name")
        val action = str(o, "action") ?: str(meta, "action") ?: str(db, "action") ?: throw NavParseException("poa_item $index has no action")
        if (id == null && name.isNullOrBlank()) throw NavParseException("poa_item $index names no item")
        return NavStep.POAItem(
            id, name, action,
            str(o, "action2") ?: str(o, "action_2") ?: str(meta, "action2") ?: str(db, "action2"),
            str(o, "action3") ?: str(o, "action_3") ?: str(meta, "action3") ?: str(db, "action3"),
            rect(db, "dest") ?: xyArea(obj(meta, "destination_area") ?: obj(o, "destination_area")),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("poa_item", "item", "item_click")) + stepsChain(teleport, setOf("poa_item", "item", "item_click")) + topChain(o),
        )
    }

    private fun useOn(index: Int, o: JsonObject): NavStep {
        val meta = obj(o, "metadata")
        val db = row(meta, "use_on")
        val itemId = int(o, "item_id") ?: int(o, "id") ?: int(meta, "item_id") ?: int(db, "item_id")
        val itemName = str(o, "item_name") ?: str(o, "name") ?: str(meta, "item_name") ?: str(db, "item_name")
        val objectId = int(o, "object_id") ?: int(meta, "object_id") ?: int(db, "object_id")
        val objectName = str(o, "object_name") ?: str(meta, "object_name") ?: str(db, "object_name")
        if (itemId == null && itemName.isNullOrBlank()) throw NavParseException("use_on $index names no item")
        if (objectId == null && objectName.isNullOrBlank()) throw NavParseException("use_on $index names no object")
        return NavStep.UseOn(
            itemId, itemName, objectId, objectName, int(o, "search_radius") ?: int(meta, "search_radius") ?: int(db, "search_radius"),
            rect(db, "dest") ?: xyArea(obj(meta, "destination_area") ?: obj(o, "destination_area")),
            rect(db, "orig"),
            pointOf(index, o, "from"), pointOf(index, o, "to"),
            childChain(db) + stepsChain(meta, setOf("use_on")) + topChain(o),
        )
    }

    /** The action's own database row: the first `metadata.steps` row of [kind] (or with an action), else the first row. */
    private fun row(meta: JsonObject?, kind: String): JsonObject? {
        obj(meta, "db_row")?.let { return it }
        val steps = meta?.getAsJsonArrayOrNull("steps") ?: return null
        var first: JsonObject? = null
        for (element in steps) {
            val step = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val db = obj(step, "db_row") ?: continue
            if (first == null) first = db
            if (str(step, "kind")?.lowercase() == kind || str(db, "action") != null) return db
        }
        return first
    }

    private fun firstStepField(meta: JsonObject?, key: String): String? =
        meta?.getAsJsonArrayOrNull("steps")?.firstOrNull { it.isJsonObject }?.asJsonObject?.let { str(it, key) }

    /** The `child_chain` a database row carries: interface clicks, and any other kind read like a step of its own. */
    private fun childChain(db: JsonObject?): List<NavStep> {
        val chain = db?.getAsJsonArrayOrNull("child_chain") ?: return emptyList()
        val out = ArrayList<NavStep>()
        chain.forEachIndexed { i, element ->
            val child = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val type = str(child, "type") ?: return@forEachIndexed
            val meta = obj(child, "meta")
            if (type.equals("ifslot", ignoreCase = true)) {
                val iface = int(meta, "interface_id") ?: return@forEachIndexed
                val component = int(meta, "component_id") ?: return@forEachIndexed
                val slot = int(meta, "slot_id") ?: return@forEachIndexed
                out += NavStep.IFSlot(iface, component, slot, int(meta, "click_id") ?: 0, null, null, null, emptyList())
            } else {
                val synthetic = JsonObject()
                synthetic.addProperty("type", type)
                if (meta != null) synthetic.add("metadata", meta)
                runCatching { step(i, synthetic) }.getOrNull()?.let(out::add)
            }
        }
        return out
    }

    /** The `metadata.steps` after the action's own kind, each read as a step from its database row. */
    private fun stepsChain(meta: JsonObject?, exclude: Set<String>): List<NavStep> {
        val steps = meta?.getAsJsonArrayOrNull("steps") ?: return emptyList()
        val out = ArrayList<NavStep>()
        steps.forEachIndexed { i, element ->
            val step = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val kind = (str(step, "kind") ?: str(step, "type"))?.lowercase() ?: return@forEachIndexed
            if (kind in exclude) return@forEachIndexed
            val synthetic = JsonObject()
            synthetic.addProperty("type", kind)
            val childMeta = JsonObject()
            obj(step, "db_row")?.let { childMeta.add("db_row", it) }
            step.getAsJsonArrayOrNull("steps")?.let { childMeta.add("steps", it) }
            if (childMeta.size() > 0) synthetic.add("metadata", childMeta)
            runCatching { step(i, synthetic) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun topChain(o: JsonObject): List<NavStep> {
        val chain = o.getAsJsonArrayOrNull("chain") ?: return emptyList()
        return chain.mapIndexedNotNull { i, element -> element.takeIf { it.isJsonObject }?.let { runCatching { step(i, it.asJsonObject) }.getOrNull() } }
    }

    private fun rect(db: JsonObject?, prefix: String): NavArea? {
        val minX = int(db, "${prefix}_min_x") ?: return null
        val minY = int(db, "${prefix}_min_y") ?: return null
        val maxX = int(db, "${prefix}_max_x") ?: return null
        val maxY = int(db, "${prefix}_max_y") ?: return null
        return NavArea(minX, minY, maxX, maxY, int(db, "${prefix}_plane"))
    }

    private fun xyArea(o: JsonObject?): NavArea? {
        o ?: return null
        val x1 = int(o, "x1") ?: return null
        val y1 = int(o, "y1") ?: return null
        val x2 = int(o, "x2") ?: return null
        val y2 = int(o, "y2") ?: return null
        return NavArea(x1, y1, x2, y2, null)
    }

    private fun required(index: Int, o: JsonObject, key: String): Tile =
        tileOf(index, o.get(key), key) ?: throw NavParseException("action $index has no '$key'")

    private fun pointOf(index: Int, o: JsonObject, key: String): Tile? = runCatching { tileOf(index, o.get(key), key) }.getOrNull()

    /** An `[x, y, plane]` array, or an area object's `min`. */
    private fun tileOf(index: Int, element: JsonElement?, field: String): Tile? {
        if (element == null || element.isJsonNull) return null
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.getAsJsonArrayOrNull("min") ?: return null
            else -> throw NavParseException("action $index: '$field' is not a tile")
        }
        if (array.size() != 3) throw NavParseException("action $index: '$field' needs [x, y, plane]")
        val values = array.map { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber }?.asInt ?: throw NavParseException("action $index: '$field' is not numeric") }
        return Tile.of(values[0], values[1], values[2])
    }

    private fun obj(o: JsonObject?, key: String): JsonObject? = o?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun str(o: JsonObject?, key: String): String? =
        o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun int(o: JsonObject?, key: String): Int? =
        o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.let { Math.round(it).toInt() }

    private fun bool(o: JsonObject?, key: String): Boolean? =
        o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
