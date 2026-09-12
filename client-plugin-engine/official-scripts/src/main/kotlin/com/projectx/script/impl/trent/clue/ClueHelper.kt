package com.projectx.script.impl.trent.clue

import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.util.gaussian

private const val AUTO_SOLVE_INTERVAL_MS = 520L
private const val IDLE_GRACE_MS = 4_000L

/**
 * Treasure Trails helper: senses whichever clue step is on screen, solves it, and draws the answer over
 * the puzzle. Every module is advisory by default - [autoSolve] is the single switch that lets one of
 * them click, and it is off unless the operator turns it on.
 *
 * Clicks are paced here rather than inside a module so no module can spam an interface: at most one
 * action per randomised interval, and only ever the single next action.
 */
@ScriptDescription(
    name = "Clue Helper",
    version = "1.0",
    author = "trent",
    description = "Highlights and optionally solves Treasure Trails puzzles and steps.",
    category = ScriptCategory.OTHER,
)
class ClueHelper : Script(), ConfigurableScript {

    val autoSolve = BooleanConfigItem(
        "Auto-solve puzzles",
        "Let the helper click the solution instead of only drawing it.",
        false,
    )

    private val modulesSection = ConfigSection("Steps")
    val slidePuzzles = BooleanConfigItem("Puzzle box", "Sliding-tile puzzle boxes.", true)
    val celticKnots = BooleanConfigItem("Celtic knots", "Rune-ring rotation knots.", true)
    val towers = BooleanConfigItem("Towers", "Skyscrapers grid puzzles.", true)
    val compass = BooleanConfigItem("Compass", "Marks the compass clue's dig tile in the world.", true)
    val scans = BooleanConfigItem("Scans", "Marks the scan region's candidate dig spots around you.", true)
    val lockboxes = BooleanConfigItem("Lockboxes", "Press counts for the combat-style lockbox grid.", true)

    private val modules: List<Pair<BooleanConfigItem, ClueModule>> = listOf(
        slidePuzzles to SlidePuzzleModule(),
        celticKnots to CelticKnotModule(),
        towers to TowersModule(),
        compass to CompassModule(),
        scans to ScanModule(),
        lockboxes to LockboxModule(),
    )

    private var nextActionAt = 0L
    private val idleSince = HashMap<ClueModule, Long>()

    /**
     * A step's interface drops out of layout for a frame or two all the time - scene loads, resizes,
     * the panel being rebuilt. Resetting a module the instant it reports inactive threw away whatever
     * it had latched every time that happened, which read as the overlay flickering on and off. A
     * module is only torn down once it has been gone for [IDLE_GRACE_MS] without interruption.
     */
    override suspend fun loop() {
        val now = System.currentTimeMillis()
        val live = ArrayList<ClueModule>(modules.size)
        for ((toggle, module) in modules) {
            if (toggle.value && module.active()) {
                idleSince.remove(module)
                live.add(module)
                continue
            }
            val since = idleSince.getOrPut(module) { now }
            if (now - since >= IDLE_GRACE_MS) module.reset()
        }
        live.forEach { it.update() }
        if (live.isEmpty()) {
            nextActionAt = 0L
            return
        }
        if (autoSolve.value) fireNextAction(live)
    }

    private fun fireNextAction(live: List<ClueModule>) {
        val now = System.currentTimeMillis()
        if (now < nextActionAt) return
        val action = live.firstNotNullOfOrNull { it.nextAction() } ?: return
        if (!action.fire()) return
        println("[ClueHelper] ${action.description}")
        nextActionAt = now + gaussian(AUTO_SOLVE_INTERVAL_MS, AUTO_SOLVE_INTERVAL_MS / 3)
    }

    override fun render() {
        backgroundDrawList {
            for ((toggle, module) in modules) {
                if (!toggle.value) continue
                try {
                    module.render(this)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
