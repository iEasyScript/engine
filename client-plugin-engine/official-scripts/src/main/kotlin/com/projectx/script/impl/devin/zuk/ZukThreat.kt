package com.projectx.script.impl.devin.zuk

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.Prayer
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.canUseProtectionPrayers
import com.projectx.script.api.combatTarget
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.localPlayer
import com.projectx.script.api.onCursesPrayers
import com.projectx.script.api.projectiles
import com.projectx.script.api.throwVulnBomb
import world.gregs.voidps.type.Tile

object ZukThreat {

    /**
     * The single most-imminent style to protect against right now.
     *
     * Projectiles win first: on Jad waves several TzekHaar-Jad can be up at once (2 on wave 11,
     * 3 on wave 16), and only the projectile actually locked onto us and closest to landing matters.
     * Zuk's own melee/ranged/magic tells are animation-based and resolve second, while he targets us.
     */
    private var lastTellStyle: CombatStyle? = null
    private var lastTellAt = 0L
    private var lastJadTellStyle: CombatStyle? = null
    private var lastJadTellAt = 0L

    fun reset() {
        lastTellStyle = null
        lastTellAt = 0
        lastJadTellStyle = null
        lastJadTellAt = 0
    }

    /**
     * A projectile already in the air beats everything — it is the one thing that is certainly about
     * to land. Zuk's own tells come next while he is the fight, and otherwise the room is scored live
     * by [ZukThreatModel] instead of falling back to a per-wave average, which never flicked because
     * it does not react to anything.
     */
    fun incomingStyle(assessment: ZukThreatModel.Assessment? = null): CombatStyle? {
        val player = localPlayer

        val projectileStyle = projectiles
            .filter { it.lockedOnto(player) && it.id in ZukIds.PROJECTILE_STYLE }
            .minByOrNull { it.tile.getDistance(player.tile) }
            ?.let { ZukIds.PROJECTILE_STYLE[it.id] }
        if (projectileStyle != null) return projectileStyle

        // An engaged Jad outranks the whole room: its style holds between tells, and the
        // add-driven dominant may never override it — 70-95% of max LP per unblocked hit.
        val jadUp = assessment?.jadEngaged == true
        jadTellStyle(jadUp)?.let { return it }
        zukTellStyle()?.let { return it }
        if (!jadUp) {
            // Zuk and Jad carry no fixed style, so an engaged room can still yield no dominant
            // style — the distance rule below must still resolve or the prayer disappears for the
            // whole boss fight.
            assessment?.takeUnless { it.nothingCanHitUs }?.dominant()?.let { return it }
        }
        return zukAutoAttackStyle()
    }

    /**
     * Zuk's auto-attack is melee inside four tiles and a magic fireball beyond it, so the prayer for
     * everything between telegraphed specials follows purely from how far away the player is.
     */
    private fun zukAutoAttackStyle(): CombatStyle? {
        val zuk = findClosestNPC(ZukIds.ZUK_SHOWDOWN)?.takeIf { it.exists() } ?: return null
        val gap = runCatching { edgeDistance(localPlayer.tile, zuk.tile, zuk.size) }.getOrNull() ?: return null
        return if (gap <= ZukIds.ZUK_MELEE_RANGE) CombatStyle.MELEE else CombatStyle.MAGIC
    }

    /** Range is measured to the footprint, not the south-west corner, or a size-5 boss reads as far away. */
    private fun edgeDistance(from: Tile, target: Tile, size: Int): Int {
        val dx = maxOf(target.x - from.x, from.x - (target.x + size - 1), 0)
        val dy = maxOf(target.y - from.y, from.y - (target.y + size - 1), 0)
        return maxOf(dx, dy)
    }

    /**
     * Jads have no fixed style — their prayer must come from the attack animation, latched like
     * Zuk's tells. Any Jad in range counts: on multi-Jad waves the freshest attack wins, which is
     * also the one whose hit is inbound. While a Jad stays engaged the last tell holds
     * indefinitely rather than expiring — until 16201/16202 are mapped, the last observed style
     * is a better answer than anything the rest of the room suggests.
     */
    private fun jadTellStyle(jadUp: Boolean): CombatStyle? {
        for (npc in allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() && it.zukMinion()?.role == MinionRole.JAD }) {
            val anim = runCatching { npc.animation?.id }.getOrNull()
            ZukIds.JAD_ANIM_STYLE[anim]?.let {
                lastJadTellStyle = it
                lastJadTellAt = System.currentTimeMillis()
            }
        }
        val expired = System.currentTimeMillis() - lastJadTellAt > ZukIds.JAD_TELL_HOLD_MS
        if (expired && !jadUp) lastJadTellStyle = null
        return lastJadTellStyle
    }

    /**
     * Zuk's attack animation only plays for a moment but the damage lands up to ~1.5s later, so the
     * tell is latched briefly rather than read live — otherwise the prompt clears before the hit.
     */
    private fun zukTellStyle(): CombatStyle? {
        val zuk = findClosestNPC(ZukIds.ZUK_SHOWDOWN)
        val anim = zuk?.let { runCatching { it.animation?.id }.getOrNull() }
        ZukIds.ZUK_ANIM_STYLE[anim]?.let {
            lastTellStyle = it
            lastTellAt = System.currentTimeMillis()
        }
        if (System.currentTimeMillis() - lastTellAt > ZukIds.ZUK_TELL_HOLD_MS) lastTellStyle = null
        return lastTellStyle
    }

    fun zukPresent(): Boolean = runCatching { findClosestNPC(ZukIds.ZUK_SHOWDOWN)?.exists() }.getOrNull() == true
}

/**
 * Flick the protection prayer for [style] (deflect variant on curses).
 *
 * The rate gate is taken before clicking, not after succeeding: a click that fails leaves the prayer
 * inactive, so an unthrottled retry would fire again on the very next iteration. It never waits for
 * the switch to register either — blocking the loop stalls every other automation, and the wave-15
 * press window is only ~1.2s wide; the gate already paces any retry.
 */
fun flickProtection(style: CombatStyle) {
    if (!canUseProtectionPrayers) return
    val prayer = style.protectionPrayer(onCursesPrayers)
    if (prayer.active) return
    if (!ZukInputGate.allow("prayer:${prayer.name}", ZukInputGate.PRAYER_MS)) return
    prayer.click()
}

/**
 * With the room verifiably unable to hit us, protection buys nothing: Soul Split on curses turns
 * the same prayer drain into healing; on the standard book the active protection simply comes off.
 * Overheads are exclusive, so the next [flickProtection] click restores cover the moment a threat
 * returns.
 */
fun restWhenSafe() {
    if (!canUseProtectionPrayers) return
    if (runCatching { onCursesPrayers }.getOrDefault(false)) {
        if (runCatching { Prayer.SOUL_SPLIT.active }.getOrDefault(true)) return
        if (!ZukInputGate.allow("prayer:${Prayer.SOUL_SPLIT.name}", ZukInputGate.PRAYER_MS)) return
        Prayer.SOUL_SPLIT.click()
    } else {
        val active = PROTECTION_PRAYERS.firstOrNull { runCatching { it.active }.getOrDefault(false) } ?: return
        if (!ZukInputGate.allow("prayer:off:${active.name}", ZukInputGate.PRAYER_MS)) return
        active.click()
    }
}

private val PROTECTION_PRAYERS =
    listOf(Prayer.PROTECT_MELEE, Prayer.PROTECT_RANGED, Prayer.PROTECT_MAGIC, Prayer.PROTECT_NECROMANCY)

/**
 * The bomb lands on whatever the player is currently attacking, not on the npc we would like it to
 * hit — so it may only be thrown while the player's own combat target is the worthy one. The 17:45
 * run bombed trash all wave because a mere worthy-npc-nearby test gated a click that aims at
 * [combatTarget]. The throttle stays, since [throwVulnBomb] itself re-clicks while on cooldown.
 */
fun tryVulnBomb(): Boolean {
    val target = combatTarget ?: return false
    if (!runCatching { target.exists() && target.worthVulnBomb() }.getOrDefault(false)) return false
    if (!ZukInputGate.allow("vulnBomb", ZukInputGate.VULN_BOMB_MS)) return false
    return throwVulnBomb()
}

/**
 * Live threat assessment across every npc in the arena, rather than a per-wave average.
 *
 * Deliberately built from signals that are trustworthy in an instance. The collision map is empty
 * inside the encounter — `hasLineOfSight` is false for 100% of samples, including at distance 0 — so
 * nothing here consults it. Melee reach is plain footprint geometry, and for ranged and magic the
 * honest test of whether something can hit you is whether it has recently *tried*.
 */
object ZukThreatModel {

    class Engagement(
        val npc: NPC,
        val style: CombatStyle?,
        val weight: Int,
        val gap: Int
    )

    class Assessment(
        val engagements: List<Engagement>,
        val byStyle: Map<CombatStyle, Int>,
        val jadEngaged: Boolean
    ) {
        val totalWeight: Int get() = byStyle.values.sum()

        /** Nothing in the room is landing anything, so prayer buys nothing and health is worth more. */
        val nothingCanHitUs: Boolean get() = engagements.isEmpty()

        fun dominant(): CombatStyle? = byStyle.maxByOrNull { it.value }?.key

        fun secondary(): CombatStyle? = byStyle.entries.sortedByDescending { it.value }
            .drop(1).firstOrNull()?.key

        fun describe(): String = engagements.joinToString {
            "${it.npc.name()}#${it.npc.typeId}:${it.style}w${it.weight}g${it.gap}"
        }
    }

    fun assess(): Assessment {
        val player = localPlayer
        val playerTile = player.tile

        val engagements = ArrayList<Engagement>()
        for (npc in allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() }) {
            runCatching {
                // The player's own familiar "interacts" with its owner while following (id 31142
                // read as an attacker for the whole 17:45 run, pinning nothingCanHitUs false), so
                // only recognised encounter npcs can count as threats.
                val minion = npc.zukMinion() ?: return@runCatching
                if (minion.role == MinionRole.HAZARD) return@runCatching
                val lockedRaw = npc.interactingWith(player)
                if (lockedRaw) ZukAttackMemory.stampLocked(npc.serverIndex)
                if (!lockedRaw && !ZukAttackMemory.recentlyLocked(npc.serverIndex)) return@runCatching
                val gap = edgeDistance(playerTile, npc.tile, npc.size)
                if (npc.animation != null) ZukAttackMemory.stamp(npc.serverIndex)
                // Locked AND able to land now: a ranged threat pre-arms the prayer from range + sight
                // before its first shot; melee counts only from adjacency (no melee pre-arm, per design).
                // Evidence beats model, ranged/magic only: one that demonstrably attacked within
                // plausible range stays engaged no matter what the sight ray claims this tick.
                val melee = minion == ZukMinion.KIH || minion == ZukMinion.SLAYER_DEBUFF || minion.style == CombatStyle.MELEE
                val proven = !melee && gap <= ZukReach.TRASH_ATTACK_RANGE &&
                    ZukAttackMemory.recent(npc.serverIndex) && ZukAttackMemory.underNpcAttack()
                if (!ZukReach.hitsNow(minion, npc, gap, playerTile) && !proven) return@runCatching
                engagements += Engagement(npc, npc.combatStyle(), weightOf(npc), gap)
            }
        }

        val byStyle = HashMap<CombatStyle, Int>()
        for (e in engagements) e.style?.let { byStyle.merge(it, e.weight, Int::plus) }

        return Assessment(
            engagements = engagements,
            byStyle = byStyle,
            jadEngaged = engagements.any { it.npc.zukMinion()?.role == MinionRole.JAD }
        )
    }

    /**
     * Footprint area, so a size-5 boss outweighs a size-1 add twenty-five to one. That ratio is the
     * point: one large ranger has to beat two small melees, and counting bodies gets that backwards.
     */
    private fun weightOf(npc: NPC): Int {
        val size = npc.size.coerceAtLeast(1)
        val role = when (npc.zukMinion()?.role) {
            MinionRole.ZUK, MinionRole.JAD, MinionRole.AKEN -> BOSS_MULTIPLIER
            else -> 1
        }
        return size * size * role
    }

    private fun edgeDistance(from: Tile, target: Tile, size: Int): Int {
        val dx = maxOf(target.x - from.x, from.x - (target.x + size - 1), 0)
        val dy = maxOf(target.y - from.y, from.y - (target.y + size - 1), 0)
        return maxOf(dx, dy)
    }

    private const val BOSS_MULTIPLIER = 4
}
