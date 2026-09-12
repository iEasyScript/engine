package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.script.api.varps
import world.gregs.voidps.gameval.Gameval

/**
 * Resolves abilities that recast into a higher tier from the same bar slot.
 *
 * Dismember steps through Slaughter and Massacre on successive casts, each a distinct struct with its
 * own icon and name, all sharing one slot. The bar shows whichever tier is armed next, so a queue
 * that renders the base struct shows the wrong picture the moment the first tier is spent.
 *
 * Only the base struct sits on the bar, so polling the barred abilities alone sees a single cast for
 * the whole chain and the history strip shows one entry repainting itself. Each tier's own varc has
 * to be watched, and a tiered entry must take its icon from its own struct rather than from the bar
 * slot, which always renders whichever tier is armed next.
 *
 * `:core`'s `AbilityTransform` already handles this shape for Spectral Scythe but has no Dismember
 * entry — a `:core` gap this works around locally.
 */
object AbilityTiers {

    private class Tier(val base: Int, val recasts: List<Pair<Int, Int>>) {
        /** Cast order: base first, then each recast in turn, wrapping back to base. */
        val chain: List<Int> = listOf(base) + recasts.map { it.first }
    }

    private val tiers: List<Tier> by lazy {
        listOfNotNull(
            tier(
                "combatv2_ability_strength_dismember",
                "combatv2_ability_melee_dismember_recast_1" to "combatv2_ability_melee_dismember_recast_1_active",
                "combatv2_ability_melee_dismember_recast_2" to "combatv2_ability_melee_dismember_recast_2_active"
            ),
            tier(
                "combatv2_ability_necromancy_spectral_scythe",
                "combatv2_ability_necromancy_spectral_scythe_recast_1"
                    to "combatv2_ability_necromancy_spectral_scythe_recast_1_active",
                "combatv2_ability_necromancy_spectral_scythe_recast_2"
                    to "combatv2_ability_necromancy_spectral_scythe_recast_2_active"
            )
        )
    }

    private val byAnyStruct: Map<Int, Tier> by lazy {
        buildMap {
            for (t in tiers) {
                put(t.base, t)
                for ((struct, _) in t.recasts) put(struct, t)
            }
        }
    }

    /** The struct actually armed right now for [structId], or [structId] when it has no tiers. */
    fun resolve(structId: Int): Int {
        val tier = byAnyStruct[structId] ?: return structId
        for ((struct, activeVarp) in tier.recasts) {
            if (runCatching { varps.getVar(activeVarp) }.getOrDefault(0) == 1) return struct
        }
        return tier.base
    }

    /** Logs the tier varps whenever they move, so a wrong icon can be traced to the actual values. */
    fun logIfChanged() {
        for (tier in tiers) {
            val state = tier.recasts.joinToString(",") { (struct, varp) ->
                "$struct=${runCatching { varps.getVar(varp) }.getOrDefault(-99)}"
            }
            val key = tier.base
            if (lastLogged[key] == state) continue
            lastLogged[key] = state
            println("[BozoCap] TIER base=$key armed=${resolve(key)} varps[$state]")
        }
    }

    private val lastLogged = HashMap<Int, String>()

    /** Every struct in [structId]'s chain, so each tier's own cooldown varc can be watched. */
    fun chainOf(structId: Int): List<Int> = byAnyStruct[structId]?.chain ?: listOf(structId)

    /** All tier structs whose base appears in [bar], for adding to the polled set. */
    fun extraStructsFor(bar: Collection<Int>): List<Int> =
        bar.flatMap { chainOf(it) }.filter { it !in bar }.distinct()

    /** The bar-slot struct a tier belongs to, for looking up its keybind. */
    fun baseOf(structId: Int): Int = byAnyStruct[structId]?.base ?: structId

    private fun tier(baseName: String, vararg recasts: Pair<String, String>): Tier? {
        val base = Gameval.id(Gameval.STRUCT, baseName) ?: return null
        val resolved = recasts.mapNotNull { (structName, varpName) ->
            val struct = Gameval.id(Gameval.STRUCT, structName) ?: return@mapNotNull null
            val varp = Gameval.id(Gameval.VAR_PLAYER, varpName) ?: return@mapNotNull null
            struct to varp
        }
        return if (resolved.isEmpty()) null else Tier(base, resolved)
    }
}
