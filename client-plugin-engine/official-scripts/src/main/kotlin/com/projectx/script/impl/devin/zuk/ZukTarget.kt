package com.projectx.script.impl.devin.zuk

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.localPlayer

/**
 * Which specific npc to attack now. Reasons in time-to-hurt, not booleans: a threat-driven mechanic
 * that can no longer reach or see the player is demoted below whatever is actually hitting, while
 * progression kills (the igneous trio) hold their rank regardless of safespots. Owns no shared threat
 * list — every input is a [ZukReach] primitive. Keyed by [NPC.serverIndex] so twins never conflate.
 */
object ZukTargeting {

    var lastPick: String? = null
        private set

    @Volatile
    var lastBoard: String = ""
        private set

    private var lastIndex = -1

    fun reset() {
        lastIndex = -1
        lastPick = null
        resetChallenger()
    }

    fun resolve(kihDraining: Boolean, steps: Map<Long, Int>?, bodyFlood: Map<Long, Int>?): NPC? {
        val player = runCatching { localPlayer }.getOrNull() ?: return clearPick()
        val playerTile = runCatching { player.tile }.getOrNull() ?: return clearPick()
        val grounded = runCatching { ZukActions.groundedStacks() >= ZukIds.UNGROUND_AT_STACKS }.getOrDefault(false)

        val underAttack = ZukAttackMemory.underNpcAttack()
        val unrecognized = ArrayList<String>()
        val pool = ArrayList<Candidate>()
        for (npc in allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() }) {
            runCatching {
                val minion = npc.zukMinion() ?: run {
                    unrecognized += "${runCatching { npc.name() }.getOrDefault("?")}(${runCatching { npc.typeId }.getOrDefault(-1)})"
                    return@runCatching
                }
                if (minion.role == MinionRole.HAZARD) return@runCatching
                val gap = ZukReach.gap(playerTile, npc)
                val walk = footprintWalk(npc, steps) ?: gap
                val rank = mechanicRank(minion, kihDraining, grounded, walk)
                // Support minions (an idle HurKot) are never a default target until they arm a
                // mechanic; a Kih stays a plain trash target even beyond kill-on-sight range.
                if (rank == null && minion.role == MinionRole.MECHANIC && !minion.isKih()) return@runCatching
                val los = ZukReach.sight(npc, playerTile)
                val lockedRaw = runCatching { npc.interactingWith(player) }.getOrDefault(false)
                if (lockedRaw) ZukAttackMemory.stampLocked(npc.serverIndex)
                val locked = lockedRaw || ZukAttackMemory.recentlyLocked(npc.serverIndex)
                // Evidence beats model, for ranged/magic only: one that demonstrably attacked us
                // within plausible range is hitting us no matter what the sight ray claims this
                // tick. Melee never gets the override — walk anims stamp too, and adjacency is
                // already a perfect melee signal.
                val melee = minion == ZukMinion.KIH || minion == ZukMinion.SLAYER_DEBUFF || minion.style == CombatStyle.MELEE
                val proven = !melee && locked && gap <= ZukReach.TRASH_ATTACK_RANGE &&
                    ZukAttackMemory.recent(npc.serverIndex) && underAttack
                val hitting = ZukReach.hitsNow(minion, npc, gap, playerTile) || proven
                val timeToHurt = if (hitting) 0 else ZukReach.timeToHurt(minion, npc, gap, playerTile, bodyFlood)
                pool += Candidate(npc, npc.serverIndex, rank, tierOf(rank, hitting, timeToHurt, locked, underAttack), timeToHurt, walk, los, locked, hitting, proven, minion)
            }
        }
        lastBoard = pool.sortedWith(TARGET_ORDER).joinToString(" | ", postfix = " || unrec=${unrecognized.joinToString(",").ifEmpty { "0" }}") { c ->
            val name = runCatching { c.npc.name() }.getOrDefault("?")
            "$name#${c.serverIndex}[${c.minion}] t${c.tier} lock${b(c.locked)} hit${b(c.hitting)} prov${b(c.proven)} los${b(c.hasLos)} tth${c.timeToHurt} w${c.walk}"
        }
        if (pool.isEmpty()) return clearPick()

        val best = pool.minWith(TARGET_ORDER)
        val previous = pool.firstOrNull { it.serverIndex == lastIndex }
        val pick = selectPick(best, previous)
        lastIndex = pick.serverIndex
        lastPick = "${pick.npc.name()}#${pick.serverIndex} tier=${pick.tier} rank=${pick.rank} tth=${pick.timeToHurt} walk=${pick.walk} los=${pick.hasLos}"
        return pick.npc
    }

    private var challengerIndex = -1
    private var challengerStreak = 0

    /**
     * Every switch needs the challenger to keep winning across consecutive passes — a better tier
     * for a short streak, a clearly-closer same-tier rival for a long one. Only the incumbent
     * vanishing from the pool switches instantly. Noise in any single input can therefore move the
     * pick for at most one pass of evaluation, never the outline.
     */
    private fun selectPick(best: Candidate, previous: Candidate?): Candidate {
        if (previous == null) { resetChallenger(); return best }
        if (best.serverIndex == previous.serverIndex) { resetChallenger(); return previous }
        val tierWin = best.tier < previous.tier ||
            (best.tier == previous.tier && rankOf(best) < rankOf(previous))
        val distanceWin = best.tier == previous.tier && rankOf(best) == rankOf(previous) &&
            best.walk + STICKY_SLACK < previous.walk
        if (!tierWin && !distanceWin) { resetChallenger(); return previous }
        challengerStreak = if (best.serverIndex == challengerIndex) challengerStreak + 1 else 1
        challengerIndex = best.serverIndex
        if (challengerStreak < (if (tierWin) TIER_SWITCH_PASSES else DISTANCE_SWITCH_PASSES)) return previous
        resetChallenger()
        return best
    }

    private fun resetChallenger() {
        challengerIndex = -1
        challengerStreak = 0
    }

    private fun clearPick(): NPC? {
        lastIndex = -1
        lastPick = null
        resetChallenger()
        return null
    }

    private class Candidate(
        val npc: NPC,
        val serverIndex: Int,
        val rank: Int?,
        val tier: Int,
        val timeToHurt: Int,
        val walk: Int,
        val hasLos: Boolean,
        val locked: Boolean,
        val hitting: Boolean,
        val proven: Boolean,
        val minion: ZukMinion
    )

    private fun b(v: Boolean) = if (v) 1 else 0

    private fun rankOf(c: Candidate): Int = c.rank ?: FILLER_RANK

    /**
     * Progression mechanic (0) → threat-driven mechanic that can hurt now or within [IMMINENT] steps
     * (1) → non-mechanic actively hitting us (2) → neutralised mechanic (3) → everything else (4, a
     * meleer still walking around included). Tier 2 additionally requires combat damage actually
     * arriving on the player — with only hazard ticks landing, nothing is "hitting us" no matter
     * what the per-npc signals claim.
     */
    private fun tierOf(rank: Int?, hitsNow: Boolean, timeToHurt: Int, locked: Boolean, underAttack: Boolean): Int = when {
        rank != null && rank in NON_DEMOTABLE_RANKS -> 0
        rank != null && rank < STANDING_RANK && (hitsNow || timeToHurt <= IMMINENT) -> 1
        rank == null && locked && hitsNow && underAttack -> 2
        rank != null -> 3
        else -> 4
    }

    /** Per-tier ordering: T0 by walk; T1 rank then time; T2 time then walk; T3/T4 player-sight then walk. */
    private val TARGET_ORDER = Comparator<Candidate> { a, b ->
        var c = a.tier.compareTo(b.tier)
        if (c == 0) c = when (a.tier) {
            0 -> a.walk.compareTo(b.walk)
            1 -> compareValuesBy(a, b, { rankOf(it) }, { it.timeToHurt })
            2 -> compareValuesBy(a, b, { it.timeToHurt }, { if (it.hasLos) 0 else 1 }, { it.walk })
            else -> compareValuesBy(a, b, { if (it.hasLos) 0 else 1 }, { it.walk })
        }
        if (c == 0) c = a.serverIndex.compareTo(b.serverIndex)
        c
    }

    private fun mechanicRank(minion: ZukMinion, kihDraining: Boolean, grounded: Boolean, walk: Int): Int? = when (minion) {
        ZukMinion.FATAL_GENERIC, ZukMinion.FATAL_RANGED, ZukMinion.FATAL_MAGIC -> 1
        ZukMinion.VOLATILE_HUR -> 2
        ZukMinion.UNBREAKABLE_KET -> 3
        ZukMinion.IGNEOUS_HUR -> 4
        ZukMinion.IGNEOUS_XIL -> 5
        ZukMinion.IGNEOUS_MEJ -> 6
        // A Kih is a kill-on-sight target BEFORE it reaches the player — waiting for the drain
        // message means it already arrived. Urgent while draining; a standing priority within
        // practical reach that never outranks whatever is actually attacking the player; across
        // the room it is just another far target.
        ZukMinion.KIH, ZukMinion.SLAYER_DEBUFF -> when {
            kihDraining -> 7
            walk <= STANDING_RANGE -> STANDING_RANK
            else -> null
        }
        ZukMinion.TOK_XIL -> if (grounded) 8 else null
        else -> null
    }

    private fun ZukMinion.isKih(): Boolean = this == ZukMinion.KIH || this == ZukMinion.SLAYER_DEBUFF

    /** Walk-steps to the nearest reachable tile of the npc's footprint over the body-blind flood,
     *  stable against the anchor corner shifting on movement. */
    private fun footprintWalk(npc: NPC, steps: Map<Long, Int>?): Int? {
        if (steps == null) return null
        val base = npc.tile
        var best = Int.MAX_VALUE
        for (dx in 0 until npc.size) for (dy in 0 until npc.size) {
            steps[packTile(base.x + dx, base.y + dy)]?.let { if (it < best) best = it }
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    private val NON_DEMOTABLE_RANKS = setOf(4, 5, 6)
    private const val STANDING_RANK = 9
    private const val STANDING_RANGE = 8
    private const val FILLER_RANK = 100
    private const val STICKY_SLACK = 1
    private const val TIER_SWITCH_PASSES = 5
    private const val DISTANCE_SWITCH_PASSES = 15
    private const val IMMINENT = 3
}
