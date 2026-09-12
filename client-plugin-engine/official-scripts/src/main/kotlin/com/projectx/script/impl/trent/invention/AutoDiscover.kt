package com.projectx.script.impl.trent.invention

import com.projectx.puzzle.invention.InventionDiscoveryModel
import com.projectx.puzzle.invention.OptimisationText
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestReachableObject
import com.projectx.script.impl.trent.invention.DiscoveryScreen.Stage
import com.projectx.util.gaussian

private const val WORKBENCH = "Inventor's workbench"
private const val DISCOVER_OPTION = "Discover"
private const val WORKBENCH_RANGE = 20
private const val NOTHING_LEFT = "No inventions"
private const val MAX_STALLS = 4

/** Cadence between clicks inside one panel - the rate a player picks icons at, not a wait for the server. */
private const val PICK_CADENCE_MS = 155
private const val PICK_JITTER_MS = 45

/**
 * Discovers every blueprint the Inventor's workbench currently offers.
 *
 * Each project runs the same three stages: narrow the ten candidate modules down to the five the
 * blueprint wants (a failed prototype strikes off the ones that were wrong and keeps the ones that
 * were right), lay those five on the track, then swap pairs until the server reports Perfect. The
 * swaps come from [InventionDiscoveryModel], which fits the server's additive scoring rather than
 * hunting blindly.
 *
 * Filling a panel is a burst of clicks at a player's picking speed; the server catches up afterwards
 * and every read waits on [DiscoveryScreen.serverSync] or on the state it expects, never on a timer.
 */
@ScriptDescription(
    name = "Auto Discover",
    version = "1.1.0",
    author = "Trent",
    description = "Works through every invention blueprint the workbench offers: picks a project, finds the five " +
        "correct modules by prototyping, optimises the track to Perfect, and discovers it.",
    category = ScriptCategory.INVENTION
)
class AutoDiscover : Script(), ConfigurableScript {

    val optimiseTrack = BooleanConfigItem(
        name = "Optimise the track",
        description = "Swap modules until the arrangement scores Perfect. Off discovers as soon as the five " +
            "modules are on the track, which is faster but gives up the bonus XP.",
        initialValue = true
    )

    val swapBudget = IntConfigItem(
        name = "Swap budget",
        description = "Stop optimising after this many swaps and discover on the best arrangement found so far.",
        initialValue = 40,
        min = 5,
        max = 200
    )

    private val model = InventionDiscoveryModel()
    private val unselectable = mutableSetOf<Int>()
    private var swaps = 0
    private var discovered = 0
    private var stalls = 0
    private var stage = Stage.CLOSED

    override fun onStart() {
        println("AutoDiscover started.")
    }

    override fun onStop() {
        println("AutoDiscover stopped after $discovered discoveries.")
    }

    override suspend fun loop() {
        val current = DiscoveryScreen.stage
        if (current != stage) {
            stage = current
            stalls = 0
        }
        if (DiscoveryScreen.dialogOpen) { dismissDialog(); return }
        when (current) {
            Stage.CLOSED -> openWorkbench()
            Stage.PICKING -> pickProject()
            Stage.PROTOTYPING -> prototype()
            Stage.ARRANGING -> arrange()
        }
    }

    private suspend fun openWorkbench() {
        if (!interactClosestReachableObject(WORKBENCH, DISCOVER_OPTION, WORKBENCH_RANGE)) {
            stall("no $WORKBENCH within $WORKBENCH_RANGE tiles")
            return
        }
        delayUntil(gaussian(9000L, 1800L)) { DiscoveryScreen.isOpen }
        if (!DiscoveryScreen.isOpen) stall("the workbench did not open")
    }

    private suspend fun pickProject() {
        delayUntil(gaussian(4000L, 900L)) {
            DiscoveryScreen.availableProjects.isNotEmpty() ||
                DiscoveryScreen.projectNotice.contains(NOTHING_LEFT, ignoreCase = true)
        }

        // Hidden list entries are the ones the player can't start, but the whole list is still worth a
        // try if none of them reports itself as drawn - a rejected pick just lands in `unselectable`.
        val offered = DiscoveryScreen.availableProjects.ifEmpty { DiscoveryScreen.allProjects }
        val projects = offered.filter { it !in unselectable }
        if (projects.isEmpty()) {
            println("Nothing left to invent (${DiscoveryScreen.projectNotice.ifBlank { "empty project list" }}).")
            DiscoveryScreen.close()
            stop()
            return
        }

        // Bottom of the list is the highest level requirement, and so the most experience per discovery.
        val listSlot = projects.last()
        DiscoveryScreen.selectProject(listSlot)
        delayUntil(gaussian(5000L, 1100L)) { DiscoveryScreen.stage == Stage.PROTOTYPING }
        if (DiscoveryScreen.stage != Stage.PROTOTYPING) {
            unselectable += listSlot
            return
        }
        model.reset()
        swaps = 0
    }

    private suspend fun prototype() {
        val slots = DiscoveryScreen.partSlots
        if (slots.any { it == 0 }) {
            loadModules(slots)
            return
        }

        val usableBefore = DiscoveryScreen.usableParts.size
        val acknowledged = DiscoveryScreen.serverSync
        DiscoveryScreen.buildPrototype()
        delayUntil(gaussian(11000L, 2000L)) {
            DiscoveryScreen.stage == Stage.ARRANGING || DiscoveryScreen.usableParts.size != usableBefore
        }
        if (DiscoveryScreen.stage == Stage.PROTOTYPING && DiscoveryScreen.usableParts.size == usableBefore) {
            stall(if (DiscoveryScreen.serverSync == acknowledged) "the prototype went unacknowledged" else "the prototype produced no result")
            return
        }
        dismissWorkbenchOverlay()
    }

    /** Fills every empty prototype slot in one burst; the modules already there are known good. */
    private suspend fun loadModules(slots: IntArray) {
        val vacancies = slots.count { it == 0 }
        val alreadyLoaded = slots.filter { it != 0 }.map { it - 1 }.toSet()
        val candidates = DiscoveryScreen.usableParts.filter { it !in alreadyLoaded }.shuffled()
        if (candidates.size < vacancies) {
            stall("only ${candidates.size} modules left for $vacancies slots")
            return
        }
        for (part in candidates.take(vacancies)) {
            DiscoveryScreen.addPart(part)
            delay(PICK_CADENCE_MS, PICK_JITTER_MS)
        }
        delayUntil(gaussian(6000L, 1200L)) { DiscoveryScreen.partSlots.none { it == 0 } }
        if (DiscoveryScreen.partSlots.any { it == 0 }) stall("modules did not all reach the prototype")
    }

    private suspend fun dismissWorkbenchOverlay() {
        val acknowledged = DiscoveryScreen.serverSync
        DiscoveryScreen.dismissWorkbenchOverlay()
        delayUntil(gaussian(4000L, 800L)) { DiscoveryScreen.serverSync != acknowledged }
    }

    private suspend fun arrange() {
        if (!DiscoveryScreen.trackFull) {
            placeModules()
            return
        }

        // Mid-swap the server has written one slot and not yet the other, so the track briefly holds a
        // module twice. Settle before reading it, or a duplicate reads as "nothing left to try".
        val arrangement = DiscoveryScreen.track
        if (!InventionDiscoveryModel.isArrangement(arrangement)) return
        if (OptimisationText.parse(DiscoveryScreen.scoreText) == null) return

        val penalty = DiscoveryScreen.penalty
        model.record(arrangement, penalty)

        if (penalty == DiscoveryScreen.PERFECT || !optimiseTrack.value) {
            discover(penalty)
            return
        }

        val advice = model.advise(arrangement)
        if (swaps >= swapBudget.value || advice.swapFirst < 0) {
            if (returnToBest(arrangement)) discover(DiscoveryScreen.penalty)
            return
        }
        swap(advice.swapFirst, advice.swapSecond)
    }

    /** Drops all the remaining modules onto the track in one burst, then waits for the track to fill. */
    private suspend fun placeModules() {
        val placed = DiscoveryScreen.track.filter { it != 0 }.map { it - 1 }.toSet()
        val remaining = (0 until DiscoveryScreen.TRACK_SLOTS).filter { it !in placed }.shuffled()
        for (module in remaining) {
            DiscoveryScreen.placeModule(module)
            delay(PICK_CADENCE_MS, PICK_JITTER_MS)
        }
        delayUntil(gaussian(6000L, 1200L)) { DiscoveryScreen.trackFull }
        if (!DiscoveryScreen.trackFull) stall("modules did not all reach the track")
    }

    /** Walks back to the best-scoring arrangement tried; true once we are standing on it. */
    private suspend fun returnToBest(arrangement: IntArray): Boolean {
        val best = model.bestArrangement ?: return true
        if (arrangement.contentEquals(best)) return true
        val (first, second) = model.swapToward(arrangement, best)
        if (first < 0) return true
        swap(first, second)
        return false
    }

    private suspend fun swap(first: Int, second: Int) {
        val stray = DiscoveryScreen.selectedTrackSlot
        if (stray >= 0 && stray != first && stray != second && !release(stray)) {
            stall("could not put back a held track module")
            return
        }

        val before = DiscoveryScreen.track
        val expected = before.copyOf().also {
            it[first] = before[second]
            it[second] = before[first]
        }
        val acknowledged = DiscoveryScreen.serverSync

        // Pick-up and swap go out at picking speed rather than a round trip apart: the server applies
        // clicks in order, so the pair reads the same whether it lands in one tick or two.
        val held = DiscoveryScreen.selectedTrackSlot
        if (held != first && held != second) {
            DiscoveryScreen.clickTrackSlot(first)
            delay(PICK_CADENCE_MS, PICK_JITTER_MS)
        }
        DiscoveryScreen.clickTrackSlot(if (held == second) first else second)

        // The swap's acknowledgement carries the new arrangement and its score in one batch, so once it
        // lands the penalty read back belongs to this arrangement and not the previous one.
        delayUntil(gaussian(4500L, 900L)) {
            DiscoveryScreen.serverSync != acknowledged && DiscoveryScreen.track.contentEquals(expected)
        }
        swaps++
        stalls = 0
    }

    /** Puts a module held from an interrupted swap back down, so the next pair starts from nothing held. */
    private suspend fun release(slot: Int): Boolean {
        val acknowledged = DiscoveryScreen.serverSync
        DiscoveryScreen.clickTrackSlot(slot)
        delayUntil(gaussian(2500L, 600L)) {
            DiscoveryScreen.serverSync != acknowledged && DiscoveryScreen.selectedTrackSlot < 0
        }
        return DiscoveryScreen.selectedTrackSlot < 0
    }

    private suspend fun discover(penalty: Int) {
        val name = DiscoveryScreen.blueprintName
        DiscoveryScreen.discover()
        delayUntil(gaussian(7000L, 1400L)) {
            DiscoveryScreen.dialogOpen || DiscoveryScreen.stage == Stage.PICKING
        }
        if (!DiscoveryScreen.dialogOpen && DiscoveryScreen.stage != Stage.PICKING) {
            stall("the Discover button did not take")
            return
        }
        discovered++
        stalls = 0
        println("Discovered $name after $swaps swaps (penalty $penalty).")
    }

    private suspend fun dismissDialog() {
        DiscoveryScreen.confirmDialog()
        delayUntil(gaussian(5000L, 1000L)) { !DiscoveryScreen.dialogOpen }
        if (DiscoveryScreen.dialogOpen) stall("a workbench dialog would not close")
    }

    private suspend fun stall(reason: String) {
        stalls++
        println("AutoDiscover: $reason (attempt $stalls/$MAX_STALLS).")
        if (stalls >= MAX_STALLS) {
            stop()
            return
        }
        delay(1400, 450)
    }
}
