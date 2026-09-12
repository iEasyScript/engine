package com.projectx.quest.runtime

import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.WorldLocation
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import kotlin.math.abs

/**
 * Resolution of a quest target to live in-world entities.
 *
 * The match is purely typeId equality against either [Spec.typeId] (the
 * primary id from `Models.npcs[...]`/`Models.objects[...]`) or any of
 * [Spec.candidateTypeIds] (offline-computed list of NPC/Object types whose
 * cache `modelIds` overlap a raw `Model.new(N)` referenced in the lua source).
 *
 * No runtime cache lookups — every per-tick call here is integer equality on
 * already-materialized entities. The previous design (calling `NPCType.get` /
 * `ObjectType.get` per scene entity to scan its `modelIds`) consistently
 * crashed entering instances: the SQLite-backed cache plus walking entities
 * mid-scene-mutation wrote to recycled memory and the game's
 * `jag::ref_counter_base::DecRef` blew up on the corrupted refcount.
 *
 * Additional fixed-cost filters:
 *   - [Spec.atLocation] — only entities whose tile matches (optionally
 *     translated by the captured instance origin when [Spec.instance]).
 *   - [Spec.animated] — only entities currently playing a non-idle animation.
 */
object QuestTargetMatcher {
    data class Spec(
        val typeId: Int = -1,
        val candidateNpcTypeIds: List<Int> = emptyList(),
        val candidateObjectTypeIds: List<Int> = emptyList(),
        val displayName: String = "",
        val kind: String = "",
        val atLocation: WorldLocation? = null,
        val instance: Boolean = false,
        val animated: Boolean = false,
    )

    fun specFor(a: QuestAction.ModelHighlight): Spec = Spec(
        typeId = a.typeId,
        candidateNpcTypeIds = a.candidateNpcTypeIds,
        candidateObjectTypeIds = a.candidateObjectTypeIds,
        displayName = a.displayName,
        kind = a.kind,
        atLocation = a.atLocation,
        instance = a.instance,
    )

    fun specFor(c: QuestCondition.ModelVisible): Spec = Spec(
        typeId = c.typeId,
        candidateNpcTypeIds = c.candidateNpcTypeIds,
        candidateObjectTypeIds = c.candidateObjectTypeIds,
        displayName = c.displayName,
        kind = c.kind,
        atLocation = c.atLocation,
        instance = c.instance,
        animated = c.animated,
    )

    fun specFor(c: QuestCondition.ModelNotVisible): Spec = Spec(
        typeId = c.typeId,
        candidateNpcTypeIds = c.candidateNpcTypeIds,
        candidateObjectTypeIds = c.candidateObjectTypeIds,
        displayName = c.displayName,
        kind = c.kind,
        atLocation = c.atLocation,
        instance = c.instance,
    )

    fun specFor(c: QuestCondition.NpcNearTile): Spec = Spec(
        typeId = c.typeId,
        candidateNpcTypeIds = c.candidateNpcTypeIds,
        displayName = c.displayName,
        kind = "npc",
    )

    /** True if any NPC matching [spec] is within [distance] tiles (Chebyshev) of the tile. */
    fun anyNpcNearTile(spec: Spec, tileX: Int, tileY: Int, plane: Int, distance: Int): Boolean {
        val wanted = wantedNpcTypeIds(spec)
        if (wanted.isEmpty()) return false
        val npcManager = Bootstrap.client.npcManager
        for (hashCode in npcManager.indices) {
            if (hashCode <= 0) continue
            val ptr = npcManager[hashCode] ?: continue
            if (ptr.address() == 0L) continue
            val npc = NPC(ptr)
            if (!npc.exists()) continue
            val entityTypeId = runCatching { if (npc.typeId == -1) npc.id else npc.typeId }.getOrNull() ?: continue
            if (entityTypeId !in wanted) continue
            val t = runCatching { npc.tile }.getOrNull() ?: continue
            if (t.plane != plane) continue
            val dx = abs(t.x - tileX)
            val dy = abs(t.y - tileY)
            if (maxOf(dx, dy) <= distance) return true
        }
        return false
    }

    fun findFirst(action: QuestAction.ModelHighlight): Entity? = findFirst(specFor(action))
    fun findAll(action: QuestAction.ModelHighlight): List<Entity> = findAll(specFor(action))

    fun findFirst(spec: Spec): Entity? = findAll(spec).firstOrNull()

    fun findAll(spec: Spec): List<Entity> {
        if (spec.kind == "grounditem") return emptyList()
        val npcs = if (spec.kind == "object") emptyList() else collectNpcs(spec)
        val objs = if (spec.kind == "npc") emptyList() else collectObjects(spec)
        return (npcs + objs.map { it as Entity })
    }

    data class GroundItemMatch(val id: Int, val name: String, val tile: Tile)

    fun findGroundItems(action: QuestAction.ModelHighlight): List<GroundItemMatch> = findGroundItems(specFor(action))

    fun findGroundItems(spec: Spec): List<GroundItemMatch> {
        if (spec.kind != "grounditem") return emptyList()
        if (spec.typeId < 0 && spec.displayName.isBlank()) return emptyList()
        val pTile = runCatching { localPlayer.tile }.getOrNull() ?: return emptyList()
        val targetTile = absoluteTargetTile(spec)
        val wantName = spec.displayName.trim()
        val matches = mutableListOf<Pair<GroundItemMatch, Int>>()
        for (gi in runCatching { groundItems }.getOrNull() ?: return emptyList()) {
            val t = runCatching { gi.tile }.getOrNull() ?: continue
            if (t.plane != pTile.plane) continue
            // typeId match is a pure memory read (no cache); name match resolves the cache item
            // type only when the spec has no id (the picker always captures an id).
            val name: String
            val matched: Boolean
            if (spec.typeId >= 0) {
                matched = gi.id == spec.typeId
                name = ""
            } else {
                val n = runCatching { gi.name }.getOrNull() ?: continue
                matched = n.contains(wantName, ignoreCase = true)
                name = n
            }
            if (!matched) continue
            if (targetTile != null && (t.x != targetTile.first || t.y != targetTile.second)) continue
            val dx = t.x - pTile.x
            val dy = t.y - pTile.y
            matches += GroundItemMatch(gi.id, name, t) to (dx * dx + dy * dy)
        }
        return matches.sortedBy { it.second }.map { it.first }
    }

    fun countAtLeast(spec: Spec, minCount: Int): Boolean =
        if (spec.kind == "grounditem") findGroundItems(spec).size >= minCount
        else findAll(spec).size >= minCount

    fun isPresent(spec: Spec): Boolean =
        if (spec.kind == "grounditem") findGroundItems(spec).isNotEmpty()
        else findAll(spec).isNotEmpty()

    private fun collectNpcs(spec: Spec): List<NPC> {
        val wanted = wantedNpcTypeIds(spec)
        if (wanted.isEmpty()) return emptyList()
        val pTile = runCatching { localPlayer.tile }.getOrNull() ?: return emptyList()
        val targetTile = absoluteTargetTile(spec)
        val matches = mutableListOf<Pair<NPC, Int>>()

        val npcManager = Bootstrap.client.npcManager
        for (hashCode in npcManager.indices) {
            if (hashCode <= 0) continue
            val ptr = npcManager[hashCode] ?: continue
            if (ptr.address() == 0L) continue
            val npc = NPC(ptr)
            if (!npc.exists()) continue
            val entityTypeId = runCatching { if (npc.typeId == -1) npc.id else npc.typeId }.getOrNull() ?: continue
            if (entityTypeId !in wanted) continue
            val t = runCatching { npc.tile }.getOrNull() ?: continue
            if (t.plane != pTile.plane) continue
            if (targetTile != null && (t.x != targetTile.first || t.y != targetTile.second)) continue
            if (spec.animated && !isAnimated(npc)) continue
            val dx = t.x - pTile.x
            val dy = t.y - pTile.y
            matches += npc to (dx * dx + dy * dy)
        }
        return matches.sortedBy { it.second }.map { it.first }
    }

    private fun collectObjects(spec: Spec): List<SceneObject> {
        val wanted = wantedObjectTypeIds(spec)
        if (wanted.isEmpty()) return emptyList()
        val pTile = runCatching { localPlayer.tile }.getOrNull() ?: return emptyList()
        val targetTile = absoluteTargetTile(spec)
        val matches = mutableListOf<Pair<SceneObject, Int>>()

        for (obj in getAllObjectsWithinRange(60)) {
            val entityTypeId = runCatching { obj.visibleTypeId }.getOrNull() ?: continue
            if (entityTypeId !in wanted) continue
            val t = runCatching { obj.tile }.getOrNull() ?: continue
            if (t.plane != pTile.plane) continue
            if (targetTile != null && (t.x != targetTile.first || t.y != targetTile.second)) continue
            val dx = t.x - pTile.x
            val dy = t.y - pTile.y
            matches += obj to (dx * dx + dy * dy)
        }
        return matches.sortedBy { it.second }.map { it.first }
    }

    private fun wantedNpcTypeIds(spec: Spec): Set<Int> {
        if (spec.typeId < 0 && spec.candidateNpcTypeIds.isEmpty()) return emptySet()
        val out = HashSet<Int>(spec.candidateNpcTypeIds.size + 1)
        if (spec.typeId >= 0) out += spec.typeId
        out += spec.candidateNpcTypeIds
        return out
    }

    private fun wantedObjectTypeIds(spec: Spec): Set<Int> {
        if (spec.typeId < 0 && spec.candidateObjectTypeIds.isEmpty()) return emptySet()
        val out = HashSet<Int>(spec.candidateObjectTypeIds.size + 1)
        if (spec.typeId >= 0) out += spec.typeId
        out += spec.candidateObjectTypeIds
        return out
    }

    private fun absoluteTargetTile(spec: Spec): Pair<Int, Int>? {
        val loc = spec.atLocation ?: return null
        val originX: Int; val originY: Int
        if (spec.instance) {
            val o = QuestInstanceTracker.origin ?: return null
            originX = o.x; originY = o.y
        } else {
            originX = 0; originY = 0
        }
        return (loc.x.toInt() + originX) to (loc.y.toInt() + originY)
    }

    private fun isAnimated(npc: NPC): Boolean = runCatching {
        npc.animationId.let { it >= 0 && it != 0xFFFF }
    }.getOrDefault(false)
}
