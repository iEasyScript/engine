package com.projectx.quest.solver

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/**
 * Spirit of Summer emote-mimic puzzle (emote panel = interface [EMOTE_INTERFACE]).
 *
 * The "man" ([MAN_NPC]) performs an emote; the player must answer with one of the valid responses from
 * the wiki table, and must NOT repeat the previous response back-to-back. Each tick the solver reads the
 * man's current emote animation, picks a valid non-repeat response, and highlights that emote's button.
 * When it can't read the man's emote it highlights [THINK_EMOTE] — which makes the man repeat his emote.
 *
 * Solved when the "girl" is freed: she is a ringed NPC ([GIRL_NPC_RINGS]) that transforms through those
 * ids as rings clear, then becomes the ringless id ([GIRL_RINGLESS_NPC]). Advisory only — highlights,
 * never clicks.
 */
object SpiritOfSummerEmoteSolver : QuestStepSolver {
    override val id = "spirit-of-summer.emotes"

    private const val MAN_NPC = 7987
    private val GIRL_NPC_RINGS = intArrayOf(7980, 7979, 7978, 7977, 7976)
    private const val GIRL_RINGLESS_NPC = 7981
    private const val EMOTE_INTERFACE = 590
    private const val THINK_EMOTE = "Think"

    // Man emote -> animation seq id (read off NPC 7987). Bow and Curtsy share the "Bow" slot.
    private val MAN_EMOTE_ANIM = mapOf(
        "Yes" to 855, "No" to 856, "Bow" to 858, "Angry" to 859,
        "Wave" to 863, "Shrug" to 2113, "Cheer" to 862,
    )
    private val ANIM_TO_MAN_EMOTE = MAN_EMOTE_ANIM.entries.associate { (name, anim) -> anim to name }

    // Emote name -> slot id within the emote grid (interface 590, container component 11).
    private val EMOTE_SLOT = mapOf(
        "Yes" to 0, "No" to 1, "Bow" to 2, "Angry" to 3,
        "Wave" to 5, "Shrug" to 6, "Cheer" to 7, "Think" to 4,
    )

    // Man emote -> valid responses, in preference order (wiki table; "Bow/Curtsy" -> "Bow").
    private val RESPONSES = mapOf(
        "Yes" to listOf("No", "Angry", "Wave"),
        "No" to listOf("Angry", "Wave", "Cheer"),
        "Bow" to listOf("Yes", "No", "Angry", "Shrug"),
        "Angry" to listOf("Bow", "Shrug", "Cheer"),
        "Wave" to listOf("Angry", "Bow", "Shrug"),
        "Shrug" to listOf("Yes", "No", "Wave", "Cheer"),
        "Cheer" to listOf("Yes", "Bow", "Wave"),
    )

    // Cross-tick memory (touched only from the main-logic tick; see [QuestStepSolver]).
    private var lastResponse: String? = null     // our previous answer, so we never repeat back-to-back
    private var currentResponse: String? = null  // latched answer for the man's current emote
    private var wasEmoting = false
    private var sawRings = false

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        // She transforms through the ringed ids as rings clear, then into the ringless id once freed.
        if (GIRL_NPC_RINGS.any { npcWithId(it) != null }) sawRings = true
        if (sawRings && npcWithId(GIRL_RINGLESS_NPC) != null) {
            reset()
            return QuestStepSolver.Result(solved = true)
        }

        val manEmote = npcWithId(MAN_NPC)?.let { manEmoteOf(it) }
        if (manEmote != null && !wasEmoting) {    // rising edge: a fresh emote -> pick a fresh answer
            currentResponse = pickResponse(manEmote)?.also { lastResponse = it }
        }
        wasEmoting = manEmote != null

        // Latched answer carries us through the man's idle gap until his next emote; Think when unsure.
        val highlight = currentResponse ?: THINK_EMOTE
        return QuestStepSolver.Result(overlayActions = listOf(buttonHighlight(highlight)))
    }

    private fun manEmoteOf(man: NPC): String? =
        (ANIM_TO_MAN_EMOTE[man.animationId] ?: ANIM_TO_MAN_EMOTE[man.renderAnim])

    private fun pickResponse(manEmote: String): String? {
        val options = RESPONSES[manEmote] ?: return null
        return options.firstOrNull { it != lastResponse } ?: options.firstOrNull()
    }

    private fun buttonHighlight(emote: String) = QuestAction.InterfaceComponentHighlight(
        interfaceId = EMOTE_INTERFACE,
        componentId = 11,
        slotId = EMOTE_SLOT[emote] ?: EMOTE_SLOT.getValue(THINK_EMOTE),
        label = emote,
        color = ImGuiColors.CYAN,
    )

    private fun reset() {
        lastResponse = null
        currentResponse = null
        wasEmoting = false
        sawRings = false
    }

    private fun npcWithId(id: Int): NPC? {
        val manager = runCatching { Bootstrap.client.npcManager }.getOrNull() ?: return null
        for (index in manager.indices) {
            if (index <= 0) continue
            val ptr = manager[index] ?: continue
            if (ptr.address() == 0L) continue
            val npc = NPC(ptr)
            if (npc.exists() && npc.id == id) return npc
        }
        return null
    }
}
