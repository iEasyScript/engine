package com.projectx.script.impl.trent.dungeoneering

import com.projectx.mcp.ScriptDebug
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.healthPercent
import com.projectx.script.api.inCombat
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.localPlayer
import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import world.gregs.voidps.type.Tile

/**
 * Publishes the dungeon sensing/context snapshots the engine's `dungeon_*` MCP relays read. Kept in
 * the module (not the engine) so the whole dungeon library hot-reloads without an engine rebuild —
 * the engine only holds the generic [ScriptDebug] registry. Idempotent; [install] is called from
 * both scripts' onStart, and re-registering on a reload just replaces the provider.
 */
object DungeonDebug {

    fun install() {
        ScriptDebug.register("dungeon_state", ::state)
        ScriptDebug.register("dungeon_map", ::map)
        ScriptDebug.register("dungeon_targets", ::targets)
    }

    private fun state(b: JsonObjectBuilder) = with(b) {
        put("helper_running", ScriptExecutor.isScriptRunning(DungeoneeringAssistScript::class.java))
        put("bot_running", ScriptExecutor.isScriptRunningByName("DungeoneeringBotScript"))
        put("in_instance", inInstancedArea)

        runCatching {
            putJsonObject("player") {
                val t = localPlayer.tile
                putJsonObject("tile") { put("x", t.x); put("y", t.y); put("plane", t.plane) }
                put("room_x", t.x / 16); put("room_y", t.y / 16)
                put("moving", localPlayer.isMoving)
                put("animating", localPlayer.isAnimating)
                put("in_combat", inCombat)
                put("health_pct", healthPercent)
            }
        }

        val session = DungeonContext.session
        if (session == null) {
            put("session", false)
            return@with
        }
        put("session", true)
        put("seed", session.seed)
        put("calibration_valid", session.calibration.valid)
        session.currentCell?.let { putJsonObject("current_cell") { put("gx", it.first); put("gy", it.second) } }

        val map = session.map
        put("cell_count", map.all.size)
        put("boss_found", map.bossFound)
        map.goTarget?.let { putJsonObject("go_target") { put("gx", it.gx); put("gy", it.gy); put("reason", it.reason) } }
        putJsonObject("room_classes") {
            map.all.groupingBy { it.roomClass.name }.eachCount().forEach { (k, v) -> put(k, v) }
        }
        putJsonObject("door_classes") {
            map.all.filter { it.doorClass.name != "NONE" }.groupingBy { it.doorClass.name }.eachCount().forEach { (k, v) -> put(k, v) }
        }
        put("held_keys", MapIcons.heldKeyObjs().size)
        putJsonArray("held_key_names") { MapIcons.heldKeyNames().forEach { add(JsonPrimitive(it)) } }
        put("examined_doors", session.doors.entries().size)
    }

    private fun map(b: JsonObjectBuilder) = with(b) {
        val map = DungeonContext.session?.map
        if (map == null) {
            put("session", false)
            putJsonArray("cells") { }
            return@with
        }
        putJsonArray("debug_children") { map.debugChildren.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("cells") {
            for (c in map.all.sortedWith(compareBy({ it.gy }, { it.gx }))) {
                addJsonObject {
                    put("gx", c.gx); put("gy", c.gy)
                    put("openings", c.openings); put("openings_known", c.openingsKnown)
                    if (c.start) put("start", true)
                    if (c.boss) put("boss", true)
                    if (c.unknownRoom) put("unknown", true)
                    c.skillDoorSkill?.let { put("skill_door", it.name) }
                    if (c.keyDoorObjId > 0) put("key_door_obj", c.keyDoorObjId)
                    if (c.doorLevel > 0) put("door_level", c.doorLevel)
                    put("room_class", c.roomClass.name)
                    if (c.doorClass.name != "NONE") put("door_class", c.doorClass.name)
                    if (c.onCriticalPath) put("critical", true)
                }
            }
        }
    }

    private fun targets(b: JsonObjectBuilder) = with(b) {
        val session = DungeonContext.session
        val scanner = DungeonContext.scanner
        session?.map?.goTarget?.let { putJsonObject("go_target") { put("gx", it.gx); put("gy", it.gy); put("reason", it.reason) } }

        putJsonArray("skill_doors") {
            scanner.skillDoors.forEach { d ->
                addJsonObject {
                    put("name", d.name); tile(d.tile); put("skill", d.skill.name)
                    val req = session?.doors?.requirement(d.key)
                    req?.level?.let { put("req_level", it) }
                    req?.skill?.let { put("req_skill", it.name) }
                }
            }
        }
        putJsonArray("key_doors") {
            scanner.keyDoors.forEach { d -> addJsonObject { put("name", d.name); tile(d.tile); put("openable", d.openable) } }
        }
        putJsonArray("guardian_doors") {
            scanner.guardianDoors.forEach { d -> addJsonObject { tile(d.tile); put("monsters_alive", d.monstersAlive) } }
        }
        putJsonArray("resources") {
            scanner.resources.forEach { r ->
                addJsonObject {
                    put("name", r.name); tile(r.tile); put("skill", r.skill.name)
                    put("level", r.level); put("attainable", r.attainable); put("critical_tier", r.criticalTier)
                }
            }
        }
        putJsonArray("ground_keys") {
            scanner.groundKeys.forEach { k -> addJsonObject { put("name", k.name); tile(k.tile) } }
        }
        putJsonArray("slayer_npcs") {
            scanner.slayerNpcs.forEach { n -> addJsonObject { put("name", n.name); tile(n.tile); put("level", n.level) } }
        }
    }

    private fun JsonObjectBuilder.tile(t: Tile) {
        putJsonObject("tile") { put("x", t.x); put("y", t.y); put("plane", t.plane) }
    }
}
