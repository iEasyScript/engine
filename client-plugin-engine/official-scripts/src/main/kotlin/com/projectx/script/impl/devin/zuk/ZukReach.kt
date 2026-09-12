package com.projectx.script.impl.devin.zuk

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.WorldCollision
import com.projectx.pathfinder.hasLineOfSight
import com.projectx.script.api.allNpcsWithinRange
import world.gregs.voidps.type.Tile

/**
 * Side-effect-free threat primitives shared by prayer, dodge and targeting. Each system derives its
 * OWN threat set from these — no system consumes another's list. Priority reasons in time-to-hurt,
 * not booleans. Degrade rules are permissive: missing collision reads as sighted/reachable.
 */
object ZukReach {

    const val TRASH_ATTACK_RANGE = 14
    const val UNREACHABLE = 1_000_000
    private const val MELEE_REACH = 1
    private const val BODY_FLOOD_RADIUS = 8

    /** One BFS per tick: reachable steps from the player with every encounter npc's body a blocker. */
    fun bodyFlood(playerTile: Tile): Map<Long, Int>? {
        val blocked = HashSet<Long>()
        for (npc in allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() && it.zukMinion() != null }) {
            runCatching {
                val t = npc.tile
                for (dx in 0 until npc.size) for (dy in 0 until npc.size) blocked += packTile(t.x + dx, t.y + dy)
            }
        }
        return ZukArena.pathDistances(playerTile, BODY_FLOOD_RADIUS, blocked)
    }

    fun sight(npc: NPC, playerTile: Tile): Boolean {
        val npcTile = npc.tile
        if (WorldCollision.getFlags(npcTile) == -1 || WorldCollision.getFlags(playerTile) == -1) return true
        return hasLineOfSight(playerTile, 1, npcTile, npc.size)
    }

    fun gap(playerTile: Tile, npc: NPC): Int = edgeDistance(playerTile, npc.tile, npc.size)

    fun hitsNow(minion: ZukMinion, npc: NPC, gap: Int, playerTile: Tile): Boolean = when {
        minion == ZukMinion.ZUK -> true
        minion == ZukMinion.AKEN -> sight(npc, playerTile)
        minion.role == MinionRole.JAD -> gap <= TRASH_ATTACK_RANGE && sight(npc, playerTile)
        minion == ZukMinion.KIH || minion == ZukMinion.SLAYER_DEBUFF -> gap <= MELEE_REACH
        minion.style == CombatStyle.MELEE -> gap <= MELEE_REACH
        minion.style == CombatStyle.RANGED || minion.style == CombatStyle.MAGIC -> gap <= TRASH_ATTACK_RANGE && sight(npc, playerTile)
        else -> false
    }

    /** Loop passes until this npc can hurt the player: 0 if already; a body-aware approach for melee;
     *  the raw gap as a proxy for a ranged threat not yet in position; [UNREACHABLE] when walled off. */
    fun timeToHurt(minion: ZukMinion, npc: NPC, gap: Int, playerTile: Tile, bodyFlood: Map<Long, Int>?): Int {
        if (hitsNow(minion, npc, gap, playerTile)) return 0
        val melee = minion == ZukMinion.KIH || minion == ZukMinion.SLAYER_DEBUFF || minion.style == CombatStyle.MELEE
        return if (melee) walkAround(npc, bodyFlood) else gap
    }

    private fun walkAround(npc: NPC, bodyFlood: Map<Long, Int>?): Int {
        if (bodyFlood == null) return 0
        val base = npc.tile
        var best = UNREACHABLE
        for (dx in -1..npc.size) for (dy in -1..npc.size) {
            if (dx in 0 until npc.size && dy in 0 until npc.size) continue
            bodyFlood[packTile(base.x + dx, base.y + dy)]?.let { if (it < best) best = it }
        }
        return best
    }

    private fun edgeDistance(from: Tile, target: Tile, size: Int): Int {
        val dx = maxOf(target.x - from.x, from.x - (target.x + size - 1), 0)
        val dy = maxOf(target.y - from.y, from.y - (target.y + size - 1), 0)
        return maxOf(dx, dy)
    }
}
