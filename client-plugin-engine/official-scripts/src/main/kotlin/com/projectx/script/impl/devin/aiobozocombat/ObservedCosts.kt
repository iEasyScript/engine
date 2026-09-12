package com.projectx.script.impl.devin.aiobozocombat

/**
 * Adrenaline costs learned by watching the bar, preferred over the cache's declared value.
 *
 * `combatv2_ability_adrenaline_req` is exact for basics and thresholds - Assault and Flurry spend
 * their declared 25% every time - but not for ultimates: Berserk declares 100% and spends 80%, while
 * Meteor Strike and Overpower declare 60% and spend 40%. A flat 20-point reduction fits all three,
 * which points at something in the player's setup rather than a stale param.
 *
 * Learning it rather than hardcoding the offset keeps the lookahead correct for a player who does not
 * have whatever grants that reduction.
 */
object ObservedCosts {
    private val costs = HashMap<Int, Double>()

    @Synchronized
    fun record(structId: Int, declared: Double, observed: Double) {
        if (observed <= 0.0) return
        val previous = costs.put(structId, observed)
        if (previous == null && kotlin.math.abs(observed - declared) >= REPORTABLE_DRIFT) {
            println(
                "[BozoCap] COSTFIX struct=$structId declared=%.0f%% observed=%.0f%% - using observed"
                    .format(declared, observed)
            )
        }
    }

    @Synchronized
    fun costOf(structId: Int, declared: Double): Double = costs[structId] ?: declared

    private const val REPORTABLE_DRIFT = 1.0
}
