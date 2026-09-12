package com.projectx.script.impl.devin.zuk

import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.varps

enum class WaveType { REGULAR, IGNEOUS, CHALLENGE, JAD, AKEN }

data class WaveSpawn(val minion: ZukMinion, val count: Int)

data class WaveInfo(val number: Int, val type: WaveType, val spawns: List<WaveSpawn>) {
    val totalSpawns: Int = spawns.sumOf { it.count }

    fun countOf(minion: ZukMinion): Int = spawns.firstOrNull { it.minion == minion }?.count ?: 0

    fun canContain(present: Map<ZukMinion, Int>): Boolean =
        present.all { (minion, count) -> countOf(minion) >= count }

    fun styleCounts(): Map<CombatStyle, Int> {
        val byStyle = HashMap<CombatStyle, Int>()
        for (spawn in spawns) spawn.minion.style?.let { byStyle.merge(it, spawn.count, Int::plus) }
        return byStyle
    }

    fun protectStyles(): List<CombatStyle> = styleCounts().rankedByThreat()

    fun threatStyle(): CombatStyle? = protectStyles().firstOrNull()

    /**
     * Even a perfect per-wave prayer blocks only ~78% of damage because several styles land at once,
     * so the second style is surfaced too rather than pretending one prayer covers the wave.
     */
    fun secondaryStyle(): CombatStyle? = protectStyles().getOrNull(1)
}

object ZukWaves {

    const val OVERLAY_RANGE = 30

    /**
     * The 17 TzekHaar Front waves. Compositions are transcribed from the RuneScape Wiki
     * (runescape.wiki/w/TzKal-Zuk/Strategies) — community-sourced reference data, not RE-derived —
     * and each monster is linked to its cross-verified [ZukMinion] id/style.
     */
    val WAVE_TABLE: List<WaveInfo> = listOf(
        WaveInfo(1, WaveType.REGULAR, listOf(w(ZukMinion.KIH, 2), w(ZukMinion.HUR, 5))),
        WaveInfo(2, WaveType.REGULAR, listOf(w(ZukMinion.KIH, 2), w(ZukMinion.HUR, 4), w(ZukMinion.XIL, 2), w(ZukMinion.YT_MEJKOT, 1))),
        WaveInfo(3, WaveType.REGULAR, listOf(w(ZukMinion.KIH, 1), w(ZukMinion.HUR, 2), w(ZukMinion.XIL, 2), w(ZukMinion.YT_MEJKOT, 4))),
        WaveInfo(4, WaveType.IGNEOUS, listOf(w(ZukMinion.IGNEOUS_HUR, 3), w(ZukMinion.HUR, 8))),
        WaveInfo(5, WaveType.CHALLENGE, listOf(w(ZukMinion.VOLATILE_HUR, 5))),
        WaveInfo(6, WaveType.JAD, listOf(w(ZukMinion.JAD, 1), w(ZukMinion.XIL, 5), w(ZukMinion.YT_MEJKOT, 2), w(ZukMinion.KIH, 2))),
        WaveInfo(7, WaveType.REGULAR, listOf(w(ZukMinion.KIH, 2), w(ZukMinion.MEJ, 1), w(ZukMinion.XIL, 2), w(ZukMinion.TOK_XIL, 2), w(ZukMinion.YT_MEJKOT, 1))),
        WaveInfo(8, WaveType.REGULAR, listOf(w(ZukMinion.YT_MEJKOT, 1), w(ZukMinion.TOK_XIL, 6), w(ZukMinion.XIL, 1), w(ZukMinion.MEJ, 2))),
        WaveInfo(9, WaveType.IGNEOUS, listOf(w(ZukMinion.IGNEOUS_XIL, 3), w(ZukMinion.XIL, 8))),
        WaveInfo(10, WaveType.CHALLENGE, listOf(w(ZukMinion.UNBREAKABLE_KET, 1))),
        WaveInfo(11, WaveType.JAD, listOf(w(ZukMinion.JAD, 2), w(ZukMinion.KIH, 2), w(ZukMinion.YT_MEJKOT, 1), w(ZukMinion.MEJ, 5))),
        WaveInfo(12, WaveType.REGULAR, listOf(w(ZukMinion.KET_ZEK, 2), w(ZukMinion.MEJ, 4), w(ZukMinion.TOK_XIL, 2), w(ZukMinion.KIH, 1))),
        WaveInfo(13, WaveType.REGULAR, listOf(w(ZukMinion.KET_ZEK, 4), w(ZukMinion.MEJ, 4), w(ZukMinion.TOK_XIL, 2))),
        WaveInfo(14, WaveType.IGNEOUS, listOf(w(ZukMinion.IGNEOUS_MEJ, 3), w(ZukMinion.MEJ, 8))),
        WaveInfo(15, WaveType.CHALLENGE, listOf(w(ZukMinion.FATAL_GENERIC, 1), w(ZukMinion.FATAL_RANGED, 1), w(ZukMinion.FATAL_MAGIC, 1))),
        WaveInfo(16, WaveType.JAD, listOf(w(ZukMinion.JAD, 3))),
        WaveInfo(17, WaveType.AKEN, listOf(w(ZukMinion.AKEN, 1), w(ZukMinion.TENTACLE_RANGED, 4), w(ZukMinion.TENTACLE_MAGIC, 4))),
    )

    /**
     * HM igneous waves summon one of EACH igneous plus six mixed adds per energy cycle (wiki §16;
     * wave 4 verified against the 20:42 capture — waves 9/14 assumed symmetric until captured).
     * Every other captured HM wave (1–3, 5) matched the NM table exactly.
     */
    /** Capture-verified in HM: 1–3/5 identical to NM, 4 forked and seen; 9/14 assumed from 4. */
    val HM_VERIFIED_WAVES = setOf(1, 2, 3, 4, 5, 9, 14)

    private val HM_IGNEOUS_WAVES: Map<Int, WaveInfo> = listOf(4, 9, 14).associateWith { n ->
        WaveInfo(
            n, WaveType.IGNEOUS,
            listOf(
                w(ZukMinion.IGNEOUS_HUR, 1), w(ZukMinion.IGNEOUS_XIL, 1), w(ZukMinion.IGNEOUS_MEJ, 1),
                w(ZukMinion.HUR, 1), w(ZukMinion.XIL, 1), w(ZukMinion.MEJ, 1),
                w(ZukMinion.TOK_XIL, 1), w(ZukMinion.KET_ZEK, 1), w(ZukMinion.YT_MEJKOT, 1)
            )
        )
    }

    fun hardMode(): Boolean =
        runCatching { varps.getVarBit(ZukIds.ENCOUNTER_MODE_VARBIT) == ZukIds.MODE_HARD }.getOrDefault(false)

    private fun effectiveTable(): List<WaveInfo> =
        if (hardMode()) WAVE_TABLE.map { HM_IGNEOUS_WAVES[it.number] ?: it } else WAVE_TABLE

    private fun waveInfo(number: Int): WaveInfo? = effectiveTable().getOrNull(number - 1)

    private var detectedWave: Int? = null

    fun reset() {
        detectedWave = null
    }

    /**
     * Latches onto a wave rather than re-matching every tick: killing a wave down shrinks its live
     * composition, which would otherwise start matching an earlier, smaller wave. The latch only
     * gives way once the survivors no longer fit it — i.e. the next wave has spawned.
     */
    fun refresh() {
        varbitWaveNumber()?.let {
            detectedWave = it
            return
        }
        if (runCatching { ZukActions.inZukPhase() }.getOrDefault(false)) {
            detectedWave = null
            return
        }
        val present = livePresence()
        if (present.isEmpty()) return
        if (waveInfo(detectedWave ?: 0)?.canContain(present) == true) return
        detectedWave = bestMatch(present)?.number ?: detectedWave
    }

    /**
     * The wave index runs one past the final wave for the Zuk fight, where inference would otherwise
     * latch onto an igneous wave the moment Igneous Rain spawns its minions.
     */
    fun currentWaveNumber(): Int? {
        if (runCatching { ZukActions.inZukPhase() }.getOrDefault(false)) return null
        return varbitWaveNumber() ?: detectedWave
    }

    private fun varbitWaveNumber(): Int? {
        if (varps.getVarBit(ZukIds.ENCOUNTER_MODE_VARBIT) == 0) return null
        val wave = varps.getVarBit(ZukIds.WAVE_INDEX_VARBIT) + 1
        return if (wave in 1..ZukIds.TOTAL_WAVES) wave else null
    }

    /** Summons are excluded: they are not wave members, so counting them matches no wave at all. */
    private fun livePresence(range: Int = OVERLAY_RANGE): Map<ZukMinion, Int> =
        allNpcsWithinRange(range) { it.exists() }
            .mapNotNull { it.zukMinion() }
            .filter { it != ZukMinion.MEJ_DISCIPLE && it.countsTowardWave() }
            .groupingBy { it }
            .eachCount()

    private fun bestMatch(present: Map<ZukMinion, Int>): WaveInfo? =
        effectiveTable().filter { it.canContain(present) }.minByOrNull { it.totalSpawns }

    fun current(): WaveInfo? = currentWaveNumber()?.let { waveInfo(it) }

    /** The next wave, or null once we are on the final wave (Zuk himself follows wave 17). */
    fun next(): WaveInfo? {
        val n = currentWaveNumber() ?: return null
        return if (n >= ZukIds.TOTAL_WAVES) null else waveInfo(n + 1)
    }

    /** Style to protect from now: the known wave's lean, else derived from NPCs actually present. */
    fun dominantStyle(): CombatStyle? = current()?.threatStyle() ?: liveDominantStyle()

    private fun liveDominantStyle(range: Int = OVERLAY_RANGE): CombatStyle? =
        allNpcsWithinRange(range) { it.isOverlayTarget() }
            .mapNotNull { it.combatStyle() }
            .groupingBy { it }
            .eachCount()
            .rankedByThreat()
            .firstOrNull()
}

private fun w(minion: ZukMinion, count: Int) = WaveSpawn(minion, count)

/**
 * Melee is ranked like any other style. Deprioritising it assumed safespotting answered it, which
 * measured worse against the damage actually taken (71.5% vs 73.4% blocked) once no safespot exists.
 */
private fun Map<CombatStyle, Int>.rankedByThreat(): List<CombatStyle> =
    entries.filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<CombatStyle, Int>> { it.value }.thenBy { it.key.ordinal })
        .map { it.key }
