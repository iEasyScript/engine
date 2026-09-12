package com.projectx.script.impl.devin.solak

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.gameval.Gameval

object SolakCapture {

    private const val TAG = "[SolakCap]"
    private const val POSITION_INTERVAL_MS = 1_000L
    private const val SCAN_RANGE = 40

    private val TRACKED_VARBITS = listOf(
        "blight" to SolakIds.BLIGHT_STACKS,
        "blessing" to SolakIds.NATURES_BLESSING,
        "extraAction" to SolakIds.EXTRA_ACTION_ACTIVE,
        "barState" to SolakIds.HEALTHBAR_STATE,
        "barLp" to SolakIds.SECOND_BAR_LP,
        "team" to SolakIds.TEAM_SIZE,
        "prayerBook" to SolakIds.PRAYER_BOOK_ACTIVE,
        "prayerBookTime" to SolakIds.PRAYER_BOOK_TIME
    )

    private val TRACKED_EXTRAS = listOf(
        "merethiel" to SolakIds.MERETHIEL,
        "energyOrb" to SolakIds.ENERGY_ORB,
        "blightStorm" to SolakIds.BLIGHT_STORM
    )

    private var lastAnimation = Int.MIN_VALUE
    private var lastPositionAt = 0L
    private var lastPosition = ""
    private val varbitValues = HashMap<String, Int>()
    private val alive = HashMap<Int, String>()

    fun reset() {
        lastAnimation = Int.MIN_VALUE
        lastPositionAt = 0
        lastPosition = ""
        varbitValues.clear()
        alive.clear()
    }

    fun tick(state: SolakState?) {
        if (state == null) {
            if (alive.isNotEmpty() || lastAnimation != Int.MIN_VALUE) {
                log("ENCOUNTER ended")
                reset()
            }
            return
        }
        guarded("animation") { animation(state) }
        guarded("entities") { entities(state) }
        guarded("varbits") { varbits() }
        guarded("position") { position() }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        runCatching(block).onFailure { log("ERR $what: $it") }
    }

    private fun log(line: String) = println("$TAG $line")

    private fun animation(state: SolakState) {
        if (state.bossAnimation == lastAnimation) return
        lastAnimation = state.bossAnimation
        log(
            "ANIM ${Gameval.seqLabel(state.bossAnimation)} telegraph=${state.telegraph?.name ?: "-"} " +
                "window=${state.dpsWindow?.label ?: "-"}"
        )
    }

    private fun entities(state: SolakState) {
        val current = HashMap<Int, Pair<String, NPC>>()
        state.core?.let { current[it.serverIndex] = "core" to it }
        for (limb in state.limbs) current[limb.npc.serverIndex] = limb.limb.label to limb.npc
        for (root in state.rootlings) current[root.serverIndex] = "rootling" to root
        for (lasher in state.lashers) current[lasher.serverIndex] = "lasher" to lasher
        for (npc in extras()) {
            val label = TRACKED_EXTRAS.firstOrNull { npc.isType(it.second) }?.first ?: continue
            current[npc.serverIndex] = label to npc
        }
        for ((index, entry) in current) {
            if (index !in alive) log("SPAWN ${entry.first} idx=$index lp=${lifepoints(entry.second)}")
        }
        for ((index, label) in alive) {
            if (index !in current) log("GONE $label idx=$index")
        }
        alive.clear()
        for ((index, entry) in current) alive[index] = entry.first
    }

    private fun extras(): List<NPC> =
        allNpcsWithinRange(SCAN_RANGE) { npc -> npc.exists() && TRACKED_EXTRAS.any { npc.isType(it.second) } }

    private fun lifepoints(npc: NPC): Int = runCatching { npc.currentHealth }.getOrDefault(0)

    private fun varbits() {
        for ((label, id) in TRACKED_VARBITS) {
            val value = readVarbit(id) ?: continue
            if (varbitValues.put(label, value) != value) log("VARBIT $label=$value")
        }
    }

    private fun position() {
        val now = System.currentTimeMillis()
        if (now - lastPositionAt < POSITION_INTERVAL_MS) return
        lastPositionAt = now
        val tile = localPlayer.tile
        val rendered = "${tile.x},${tile.y},${tile.plane}"
        if (rendered == lastPosition) return
        lastPosition = rendered
        log("POS $rendered")
    }
}
