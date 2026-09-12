package com.projectx.script.impl.devin.aiobozocombat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/**
 * Identifies params the engine's named accessors read wrongly, by probing one ability whose values
 * are already known from the cache dump and looking for whichever param actually holds them.
 *
 * Self-calibrating rather than hardcoded because a named accessor pointed at the wrong param returns
 * 0 rather than failing, which is exactly the failure this has to survive - and it is indistinguishable
 * from the ability genuinely having no value there. Ambiguous matches are logged, never silently
 * resolved.
 */
object ParamProbe {
    private const val PROBE_STRUCT = 14707
    private const val KNOWN_ICON_GRAPHIC = 14264
    private const val KNOWN_ACTIVE_TICKS = 33

    val iconParam: Int by lazy { probe("icon graphic", KNOWN_ICON_GRAPHIC) }
    val activeTicksParam: Int by lazy { probe("active ticks", KNOWN_ACTIVE_TICKS) }

    fun iconGraphic(structId: Int): Int = read(structId, iconParam)

    fun activeTicks(structId: Int): Int = read(structId, activeTicksParam)

    private fun read(structId: Int, param: Int): Int {
        if (param == -1) return 0
        return runCatching { Cache.struct(structId)?.getIntValue(param, 0) ?: 0 }.getOrDefault(0)
    }

    private fun probe(label: String, value: Int): Int {
        val values = runCatching { Cache.struct(PROBE_STRUCT)?.getValues() }.getOrNull()
        if (values.isNullOrEmpty()) {
            println("[BozoCap] PROBE $label FAILED - struct $PROBE_STRUCT has no params")
            return -1
        }
        val matches = values.entries.filter { it.value == value }.map { it.key }
        for (match in matches) {
            println("[BozoCap] PROBE $label candidate param=$match (${Gameval.param(match) ?: "unnamed"}) value=$value")
        }
        return when {
            matches.isEmpty() -> {
                println("[BozoCap] PROBE $label FAILED - no param on struct $PROBE_STRUCT holds $value")
                -1
            }
            matches.size > 1 -> {
                println("[BozoCap] PROBE $label AMBIGUOUS - ${matches.size} candidates, using ${matches.first()}")
                matches.first()
            }
            else -> matches.first()
        }
    }
}
