package com.projectx.script.impl.devin.zuk

import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

/**
 * Dodge's OWN threat memory, kept apart from the prayer engagement list so gating one never corrupts
 * the other's exposure map. A melee threatens from adjacency; a ranged one from a lock plus an attack
 * animation seen within the memory window, exactly as the shipped dodge behaved.
 */
object ZukDodgeThreats {

    fun reset() = ZukAttackMemory.reset()

    fun of(playerTile: Tile): List<ZukArena.DodgeThreat> {
        val player = localPlayer
        val out = ArrayList<ZukArena.DodgeThreat>()
        for (npc in allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() }) {
            runCatching {
                val minion = npc.zukMinion() ?: return@runCatching
                if (minion.role == MinionRole.HAZARD) return@runCatching
                if (!npc.interactingWith(player)) return@runCatching
                val gap = edgeDistance(playerTile, npc.tile, npc.size)
                if (npc.animation != null) ZukAttackMemory.stamp(npc.serverIndex)
                val ranged = npc.combatStyle() != CombatStyle.MELEE
                val engaged = if (!ranged) gap <= MELEE_REACH else ZukAttackMemory.recent(npc.serverIndex)
                if (!engaged) return@runCatching
                out += ZukArena.DodgeThreat(npc.tile, npc.size, if (ranged) ZukReach.TRASH_ATTACK_RANGE else MELEE_REACH, ranged)
            }
        }
        return out
    }

    private fun edgeDistance(from: Tile, target: Tile, size: Int): Int {
        val dx = maxOf(target.x - from.x, from.x - (target.x + size - 1), 0)
        val dy = maxOf(target.y - from.y, from.y - (target.y + size - 1), 0)
        return maxOf(dx, dy)
    }

    private const val MELEE_REACH = 1
}

/**
 * Raw observation store: when each npc last animated while locked onto the player. Evidence, not
 * a threat list — an npc that demonstrably attacked outranks any model that claims it cannot.
 */
object ZukAttackMemory {

    private val lastAnimAt = HashMap<Int, Long>()
    private val lastHurtAt = HashMap<Int, Long>()
    private val lastLockedAt = HashMap<Int, Long>()

    @Volatile
    private var playerHitAt = 0L

    fun reset() {
        lastAnimAt.clear()
        lastHurtAt.clear()
        lastLockedAt.clear()
        playerHitAt = 0L
    }

    /** Ground truth that some npc is actually attacking the player: combat-typed damage arriving on
     *  us. Hazard/environment ticks (TYPELESS/UNKNOWN) never open this gate. */
    fun stampPlayerHit() {
        playerHitAt = System.currentTimeMillis()
    }

    fun underNpcAttack(): Boolean = System.currentTimeMillis() - playerHitAt <= ATTACK_MEMORY_MS

    /** An anim played while the npc is TAKING damage is a defend/flinch (the player's conjures and
     *  poison ticks flinch npcs anywhere on the map) — never attack evidence. */
    fun stamp(serverIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - (lastHurtAt[serverIndex] ?: 0L) <= FLINCH_WINDOW_MS) return
        lastAnimAt[serverIndex] = now
    }

    /** The hitsplat event can land a beat after the loop already read the flinch anim, so a hurt
     *  also scrubs any stamp from the same window — both sides of the race are covered. */
    fun stampHurt(serverIndex: Int) {
        val now = System.currentTimeMillis()
        lastHurtAt[serverIndex] = now
        if (now - (lastAnimAt[serverIndex] ?: 0L) <= FLINCH_WINDOW_MS) lastAnimAt.remove(serverIndex)
    }

    /** Npcs drop their interaction target between attack cycles; aggro is judged sticky. */
    fun stampLocked(serverIndex: Int) {
        lastLockedAt[serverIndex] = System.currentTimeMillis()
    }

    fun recentlyLocked(serverIndex: Int): Boolean =
        lastLockedAt[serverIndex]?.let { System.currentTimeMillis() - it <= LOCK_STICKY_MS } == true

    fun recent(serverIndex: Int): Boolean =
        lastAnimAt[serverIndex]?.let { System.currentTimeMillis() - it <= ATTACK_MEMORY_MS } == true

    private const val ATTACK_MEMORY_MS = 6_000L
    private const val FLINCH_WINDOW_MS = 600L
    private const val LOCK_STICKY_MS = 2_500L
}
