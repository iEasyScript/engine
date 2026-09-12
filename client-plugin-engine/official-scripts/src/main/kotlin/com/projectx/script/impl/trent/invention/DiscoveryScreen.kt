package com.projectx.script.impl.trent.invention

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.api.interfaces
import com.projectx.script.api.varps
import world.gregs.voidps.gameval.Gameval

private const val DISCOVERY = "invent_discovery"

private fun component(name: String): Int =
    Gameval.componentHash("$DISCOVERY:$name")?.and(0xFFFF) ?: error("Gameval: no $DISCOVERY:$name component")

private fun varbit(name: String): Int = Gameval.requireId(Gameval.VARBIT, name)

/**
 * The Inventor's workbench discovery screen, as the server sees it.
 *
 * Every panel on this screen is drawn by clientscripts off an optimistic client-side mirror, but the
 * server holds the real state in `invent_discovery_state_0/1` and pushes it back after each click —
 * so reading the varbits works even though a synthetic component click never runs those clientscripts.
 *
 * Lives in the script module rather than the engine deliberately: reading a live screen is where the
 * surprises are, and a script jar hot-reloads where the engine needs an uninject/reinject cycle.
 */
object DiscoveryScreen {

    /** Where the screen is in the discover-a-blueprint sequence. */
    enum class Stage { CLOSED, PICKING, PROTOTYPING, ARRANGING }

    const val TRACK_SLOTS = 5
    const val PART_COUNT = 10

    /** Track penalty: 0 is "Perfect", rising in steps of two to 12 for "Poor". */
    const val PERFECT = 0

    private const val CONFIRM_SLOT = 1
    private const val PROTOTYPING_MODE = 1
    private const val ARRANGING_MODE = 2

    private val INTERFACE_ID = Gameval.requireId(Gameval.INTERFACE, DISCOVERY)
    private val PROJECT_LIST = component("side_active_layer")
    private val PROJECT_NOTICE = component("side_draw_layer")
    private val PROJECT_TITLE = component("subject_title")
    private val SCORE_TEXT = component("track_score_text")
    private val PROTOTYPE_BUTTON = component("prototype_button")
    private val OVERLAY_BUTTON = component("overlay_button")
    private val DISCOVER_BUTTON = component("discover_button")
    private val DIALOG_BUTTONS = component("dialog_buttons")
    private val CLOSE_BUTTON = component("close_button_layer")
    private val PART_ICONS = (0 until PART_COUNT).map { component("part_icon_$it") }
    private val MODULE_ICONS = (0 until TRACK_SLOTS).map { component("blueprint_icon_layer_$it") }
    private val TRACK_ICONS = (0 until TRACK_SLOTS).map { component("track_icon_$it") }

    private val CONFIRM_MODAL = Gameval.requireId(Gameval.INTERFACE, "modal_confirm_overlay")
    private val MODE = varbit("invent_discovery_mode")
    private val SCORE = varbit("invent_discovery_score")
    private val REJECTED = varbit("invent_discovery_rejected")
    private val SELECTED = varbit("invent_discovery_selected")
    private val PART_SLOTS = (0 until TRACK_SLOTS).map { varbit("invent_discovery_slot_$it") }
    private val TRACK_SLOT_VARBITS = (0 until TRACK_SLOTS).map { varbit("invent_discovery_order_slot_$it") }
    private val BLUEPRINT = Gameval.requireId(Gameval.VAR_PLAYER, "invent_blueprint_selected")
    private val SYNC = Gameval.requireId(Gameval.VAR_PLAYER, "invent_synchronise")

    val isOpen: Boolean get() = runCatching { interfaces.isOpen(INTERFACE_ID) }.getOrDefault(false)

    val stage: Stage
        get() = when {
            !isOpen -> Stage.CLOSED
            bit(MODE) == PROTOTYPING_MODE -> Stage.PROTOTYPING
            bit(MODE) == ARRANGING_MODE -> Stage.ARRANGING
            else -> Stage.PICKING
        }

    /** Dbrow of the project being worked on, or -1 between projects. */
    val blueprint: Int get() = runCatching { varps.getVar(BLUEPRINT) }.getOrDefault(-1)

    val blueprintName: String get() = text(PROJECT_TITLE)

    /**
     * Counter the server bumps once per acknowledged click, in the same update batch as the state that
     * click produced. Waiting for it to move is how a caller knows the screen it is about to read is the
     * answer to its own action rather than the previous one.
     */
    val serverSync: Int get() = runCatching { varps.getVar(SYNC) }.getOrDefault(0)

    /** List slots the player can start right now; the rest of the list is built but left hidden. */
    val availableProjects: List<Int>
        get() = projectSlots { runCatching { it.visible }.getOrDefault(false) }

    /** Every list slot the screen built, hidden ones included. */
    val allProjects: List<Int> get() = projectSlots { true }

    /** The list's own message when it has nothing to offer, e.g. "No inventions". */
    val projectNotice: String
        get() = children(PROJECT_NOTICE).map { runCatching { it.text }.getOrDefault("") }
            .firstOrNull { it.isNotBlank() } ?: ""

    /** Module chosen for each prototype slot as a 1-based part number, 0 where the slot is empty. */
    val partSlots: IntArray get() = IntArray(TRACK_SLOTS) { bit(PART_SLOTS[it]) }

    /** Parts the workbench still accepts; a failed prototype strikes off every part it rejected. */
    val usableParts: List<Int>
        get() = (0 until PART_COUNT).filter { bit(REJECTED) shr it and 1 == 0 }

    /** Module in each track slot as a 1-based prototype-slot number, 0 where the slot is empty. */
    val track: IntArray get() = IntArray(TRACK_SLOTS) { bit(TRACK_SLOT_VARBITS[it]) }

    val trackFull: Boolean get() = track.none { it == 0 }

    val penalty: Int get() = bit(SCORE)

    /** Track slot waiting for a partner to swap with, or -1 when nothing is picked up. */
    val selectedTrackSlot: Int get() = bit(SELECTED) - 1

    val scoreText: String get() = text(SCORE_TEXT)

    /**
     * The server leaves `invent_discovery_current_dialog` set long after the screen is gone, so the
     * confirm modal itself is the only trustworthy signal that a dialog is waiting on the player.
     */
    val dialogOpen: Boolean
        get() = isOpen && runCatching { interfaces.isOpen(CONFIRM_MODAL) }.getOrDefault(false)

    fun selectProject(listSlot: Int) = IFSlot(INTERFACE_ID, PROJECT_LIST, listSlot).click()

    fun addPart(part: Int) = IFSlot(INTERFACE_ID, PART_ICONS[part]).click()

    fun buildPrototype() = IFSlot(INTERFACE_ID, PROTOTYPE_BUTTON).click()

    fun dismissWorkbenchOverlay() = IFSlot(INTERFACE_ID, OVERLAY_BUTTON).click()

    fun placeModule(module: Int) = IFSlot(INTERFACE_ID, MODULE_ICONS[module]).click()

    fun clickTrackSlot(slot: Int) = IFSlot(INTERFACE_ID, TRACK_ICONS[slot]).click()

    fun discover() = IFSlot(INTERFACE_ID, DISCOVER_BUTTON).click()

    fun confirmDialog() = IFSlot(INTERFACE_ID, DIALOG_BUTTONS, CONFIRM_SLOT).click()

    fun close() = IFSlot(INTERFACE_ID, CLOSE_BUTTON).click()

    /**
     * List slots in the order the player sees them, top first. The entries are built in enum order but
     * laid out sorted by level requirement, so their row position is the only thing that says which one
     * is at the bottom of the list.
     */
    private fun projectSlots(predicate: (InterfaceComponent) -> Boolean) =
        children(PROJECT_LIST).filter(predicate)
            .map { runCatching { it.slotId }.getOrDefault(-1) to runCatching { it.parentRelY }.getOrDefault(0) }
            .filter { it.first >= 0 }
            .sortedBy { it.second }
            .map { it.first }

    private fun bit(id: Int) = runCatching { varps.getVarBit(id) }.getOrDefault(0)

    private fun text(componentId: Int) =
        runCatching { interfaces.getComponent(INTERFACE_ID, componentId)?.text }.getOrNull() ?: ""

    private fun children(componentId: Int) =
        runCatching { interfaces.getComponent(INTERFACE_ID, componentId)?.slotChildren }.getOrNull().orEmpty()
}
