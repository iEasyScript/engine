package com.projectx.game.invention

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.timeLoggedInAt
import com.projectx.ui.UIState
import com.projectx.util.getUnitsPerHour
import world.gregs.voidps.gameval.Gameval

/**
 * Tracks the rate at which each Invention component is accumulating, read from the player's
 * `invent_material_*` varps (the material-storage counts the client keeps per component type).
 *
 * On login every material varp repopulates from the save at once, which would read as an enormous
 * one-tick gain; the [LOGIN_COOLDOWN_MS] window re-baselines without recording, mirroring the
 * augmented-XP tracker's guard in UpdateStat.
 */
object InventionComponentTracker {

    private const val MATERIAL_PREFIX = "invent_material_"
    private const val LOGIN_COOLDOWN_MS = 15_000L
    private const val SCAN_INTERVAL_MS = 500L

    data class Row(
        val varpId: Int,
        val name: String,
        val count: Int,
        val gained: Int,
        val perHour: Int
    )

    private class State(var lastCount: Int) {
        var firstGainMs: Long = 0L
        var gained: Long = 0
    }

    private val componentVarps: List<Pair<Int, String>> by lazy {
        Gameval.entries(Gameval.VAR_PLAYER)
            .filter { it.value.startsWith(MATERIAL_PREFIX) }
            .map { it.key to prettyName(it.value) }
            .sortedBy { it.second }
    }

    private val states = HashMap<Int, State>()
    private var lastScanMs = 0L

    @Volatile var rows: List<Row> = emptyList()
        private set

    val totalPerHour: Int get() = rows.sumOf { it.perHour }

    fun reset() {
        states.clear()
        rows = emptyList()
    }

    fun tick() {
        if (!UIState.inventionComponentTrackerEnabled.value) return
        try {
            val client = runCatching { Bootstrap.client }.getOrNull() ?: return
            if (runCatching { client.mainState }.getOrNull() != MainState.LOGGED_IN) return
            val now = System.currentTimeMillis()
            if (now - lastScanMs < SCAN_INTERVAL_MS) return
            lastScanMs = now

            val domain = client.playerVarDomain
            if (domain.ptr.address() == 0L) return

            val withinLoginCooldown = (now - timeLoggedInAt) < LOGIN_COOLDOWN_MS
            val nextRows = ArrayList<Row>(componentVarps.size)

            for ((varpId, name) in componentVarps) {
                val count = runCatching { domain.getVar(varpId) }.getOrDefault(0)
                val state = states.getOrPut(varpId) { State(count) }

                if (!withinLoginCooldown) {
                    val delta = count - state.lastCount
                    if (delta > 0) {
                        state.gained += delta
                        if (state.firstGainMs == 0L) state.firstGainMs = now
                    }
                }
                state.lastCount = count

                if (state.gained > 0) {
                    nextRows.add(
                        Row(
                            varpId = varpId,
                            name = name,
                            count = count,
                            gained = state.gained.toInt(),
                            perHour = getUnitsPerHour(state.gained.toInt(), state.firstGainMs)
                        )
                    )
                }
            }

            rows = nextRows.sortedByDescending { it.perHour }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun prettyName(gamevalName: String): String =
        gamevalName.removePrefix(MATERIAL_PREFIX)
            .split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
