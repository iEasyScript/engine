package com.projectx.script.impl.devin.zuk

import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.script.api.localPlayer
import com.projectx.script.api.projectiles
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.Effect

/**
 * Presses the defensive answer to a mechanic the game has already announced, so every trigger here is
 * an observed chat event rather than a prediction.
 *
 * Movement mechanics (Sear, Quake) are deliberately absent: their abilities are directional and a
 * blind Surge is as likely to end in an eruption as out of one.
 *
 * The Igneous Vengeance extra-action button is deliberately absent too — it stays a prompt only.
 * These stay coached, never automated.
 */
object ZukDefensive {

    private val handledAt = HashMap<ZukMessage, Long>()

    @Volatile
    private var firstHurKotProjectileAt = 0L

    fun reset() {
        handledAt.clear()
        firstHurKotProjectileAt = 0
    }

    /** Zuk-phase telegraph presses only — the wave-15 chain belongs to the wave-15 toggle. */
    fun respond(alert: ZukAlert) {
        if (clearGeothermalBleed(alert)) return
        if (negateEmpoweredMagic(alert)) return
        anticipateIgneousRain(alert)
    }

    /**
     * The bleed debuff is the trigger, not the announcement alone: the message precedes the hit by
     * a tick, and Freedom spent early is both wasted and unavailable for the bleed it exists to
     * clear. Both signals are required, because the effect domain can hold stale or garbage state
     * (the 17:45 run mishandled Freedom after the first Igneous Rain cycle): the telegraph must be
     * recent AND the debuff must read a plausible remaining duration — never the raw active bit.
     */
    private fun clearGeothermalBleed(alert: ZukAlert): Boolean {
        val announced = alert.sawRecently(ZukMessage.GEOTHERMAL_BURN, GEOTHERMAL_WINDOW_MS) ||
            alert.sawRecently(ZukMessage.QUAKE, QUAKE_BLEED_WINDOW_MS)
        if (!announced) return false
        val remaining = runCatching { Effect.GEOTHERMAL_BURN.timeRemaining }.getOrDefault(0L)
        if (remaining !in 1..GEOTHERMAL_MAX_MS) return false
        return press("freedom", listOf(ZukMessage.Abilities.FREEDOM))
    }

    private const val GEOTHERMAL_WINDOW_MS = 8_000L

    /** Quake's eruptions also stack the Geothermal bleed (wiki), so its window arms Freedom too. */
    private const val QUAKE_BLEED_WINDOW_MS = 12_000L
    private const val GEOTHERMAL_MAX_MS = 20_000L

    private fun negateEmpoweredMagic(alert: ZukAlert): Boolean {
        if (!claim(alert, ZukMessage.EMPOWERED_MAGIC, TELEGRAPH_WINDOW_MS)) return false
        return press("negate", ZukMessage.Abilities.NEGATE)
    }

    private fun anticipateIgneousRain(alert: ZukAlert): Boolean {
        if (!claim(alert, ZukMessage.IGNEOUS_RAIN, TELEGRAPH_WINDOW_MS)) return false
        return press("anticipate", listOf(ZukMessage.Abilities.ANTICIPATION))
    }

    /**
     * The four attacks arrive one at a time, 3.0s apart, and **attacks 1 and 4 are soft typeless** —
     * which is why the measured attempt failed on a TYPELESS hit. No prayer defends typeless, so
     * Devotion cannot answer those two and must never lead here; it covers only the magic/ranged
     * attacks 2 and 3. Barricade blocks everything including typeless, Resonance blocks the soft
     * typeless, so the order is Barricade → Resonance → Devotion.
     *
     * The handover is scheduled off the live remaining duration rather than waiting for immunity to
     * read as gone: an attack landing in the reaction gap fails the whole challenge, and at a 60ms
     * loop over a 600ms tick that gap is real.
     *
     * The caller (the single wave-15 toggle) guarantees the challenge shield is already worn before
     * this runs — a press before the swap either wastes the short-shield cover or is cancelled by
     * the swap that follows it.
     *
     * Barricade and nothing else, ever (user-confirmed): Resonance does not preserve the perfect
     * challenge — its non-Devoted 1 is damage — and pressing anything Barricade-shaped that is not
     * Barricade only spends an ability to fail anyway.
     */
    fun pressSurviveChain(action: ZukAction?): Boolean {
        if (action != ZukAction.SURVIVE) {
            firstHurKotProjectileAt = 0
            return false
        }
        if (!hurKotAttackClosing()) return false
        if (immunityRemainingMs() > HANDOVER_LEAD_MS) return false
        return press("survive", BARRICADE_ONLY)
    }

    private val BARRICADE_ONLY = listOf(ZukMessage.Abilities.BARRICADE)

    /** Drill-only entry: the wave-15 press without the projectile gate, for out-of-instance rehearsal. */
    fun pressSurviveNow(): Boolean = press("survive", BARRICADE_ONLY)

    /**
     * Tracks the HurKot's own projectile in, and presses when it is nearly on the player.
     *
     * Only [ZukIds.HURKOT_PROJECTILES] count. Zuk keeps his own projectiles in the air across the
     * challenge — `2263` was seen locked onto the player half a second *before* the minions even
     * spawned — so any "is something incoming" test starts the sequence on the wrong missile.
     *
     * Timing is tight at both ends and measured: the projectile flies 1.73s, and with Barricade at
     * 10,200ms against a 9.0s attack span the press has to land inside a ~1.2s window. Firing at
     * launch overshoots the far end by half a second; firing at two tiles out sits in the middle of
     * the window with margin on both sides. The fallback is anchored on the first *HurKot* projectile
     * rather than the clock, so it can never fire before the mechanic has actually started.
     */
    private fun hurKotAttackClosing(): Boolean = runCatching {
        val player = localPlayer
        val incoming = projectiles.filter { it.id in ZukIds.HURKOT_PROJECTILES && it.lockedOnto(player) }
        if (incoming.isEmpty()) return@runCatching false
        if (firstHurKotProjectileAt == 0L) firstHurKotProjectileAt = System.currentTimeMillis()
        incoming.any { it.tile.getDistance(player.tile) <= PRESS_RANGE } ||
            System.currentTimeMillis() - firstHurKotProjectileAt >= PRESS_FALLBACK_MS
    }.getOrDefault(false)

    /** Swapping a shield while Barricade is active cancels it, so callers must be able to check. */
    fun immunityUp(): Boolean = immunityRemainingMs() > 0

    /**
     * Fails closed on an unreadable effect, but clamps to plausibility: the effect domain returns
     * garbage for effects never cast this session — the 17:45 run read Devotion "active" with 4.4
     * days remaining from start to finish, which froze every immunity-gated action all run. Nothing
     * outside a real immunity's possible span counts as cover.
     */
    private fun immunityRemainingMs(): Long = runCatching {
        SURVIVE_EFFECTS.maxOf { effect -> effect.timeRemaining.takeIf { it in 1..MAX_IMMUNITY_MS } ?: 0L }
    }.getOrDefault(Long.MAX_VALUE)

    private const val MAX_IMMUNITY_MS = 30_000L

    private val SURVIVE_EFFECTS = listOf(Effect.BARRICADE, Effect.RESONANCE, Effect.DEVOTION)

    /**
     * One attempt per announcement, marked before the press: a mechanic that finds no answer on the
     * bar must not be retried every iteration for as long as its window is open.
     */
    private fun claim(alert: ZukAlert, message: ZukMessage, windowMs: Long): Boolean {
        val seenAt = alert.lastSeenAt(message) ?: return false
        if (!pending(message, seenAt, windowMs)) return false
        handledAt[message] = seenAt
        return true
    }

    private fun pending(message: ZukMessage, seenAt: Long, windowMs: Long): Boolean =
        handledAt[message] != seenAt && System.currentTimeMillis() - seenAt <= windowMs

    /** First name that is both barred and off cooldown wins, so the list reads as a preference order. */
    private fun press(gateKey: String, cacheNames: List<String>, cooldownMs: Long = ABILITY_MS): Boolean {
        val bar = runCatching { parseAllActionBarAbilities() }.getOrNull() ?: return false
        for (name in cacheNames) {
            val structId = AbilityRegistry.byCacheName(name)?.structId ?: continue
            val slot = bar.entries.firstOrNull { it.key.structId == structId && it.key.offCd() }?.value ?: continue
            if (!ZukInputGate.allow(gateKey, cooldownMs)) return false
            return runCatching { slot.click() }.getOrDefault(false)
        }
        return false
    }

    private const val ABILITY_MS = 3_000L

    /** One tick of lead, so the replacement lands on the tick the current cover lapses, not after it. */
    private const val HANDOVER_LEAD_MS = ZukInputGate.TICK_MS

    private const val PRESS_RANGE = 2

    /** Under the 1.73s flight, and only ever counted from a HurKot projectile actually being seen. */
    private const val PRESS_FALLBACK_MS = 900L

    /** The special lands a tick after it is announced, so a stale trigger is a wasted ability. */
    private const val TELEGRAPH_WINDOW_MS = 2_000L
}
