package com.projectx.script.impl.trent.aiosmithing

import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.script.ConfigurableScript
import com.projectx.script.OptionsConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.Smithing
import com.projectx.script.api.UNFINISHED_SMITHING_ITEM
import com.projectx.script.api.bankOpen
import com.projectx.script.api.clickKey
import com.projectx.script.api.closeBank
import com.projectx.script.api.depositAllInventory
import com.projectx.script.api.getXp
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.openClosestBank
import com.projectx.script.api.smithBarsAvailable
import com.projectx.script.api.smithBatchSize
import com.projectx.script.api.smithMake
import com.projectx.script.api.smithSelectTier
import com.projectx.script.api.smithSetQuantity
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import world.gregs.voidps.cache.Cache
import com.projectx.util.formatElapsedTime
import com.projectx.util.getFormattedXpPerHour

// Heat-loss chat lines are server-side (not in the cache), and the burial forge worded it differently
// from the normal forge, so match on heat-loss wording generally and clear on any "full heat" line.
private val REHEAT_MESSAGES = setOf(
    "run out of heat", "cooled down", "low heat", "low on heat", "running low on heat", "lost heat",
)
private const val FULL_HEAT_MESSAGE = "full heat"
private const val FALLBACK_BATCH = 10

// "Out of metal" is decided by the game, not our cost math: if the forge slider offers nothing makeable
// this many tries in a row, give up. One bad slider build alone can never stop the script.
private const val MAX_MAKE_FAILURES = 3
private const val ESCAPE_KEY = 0x1B
private val TIERS = arrayOf("Base", "+1", "+2", "+3", "+4", "+5", "Burial")

enum class Phase(val label: String) {
    REHEATING("Reheating"), SMITHING("Smithing"), MAKING("Making base"),
    UPGRADING("Upgrading tier"), BANKING("Banking"), IDLE("Idle")
}

@ScriptDescription(
    name = "AIO Smithing",
    version = "2.0.0",
    author = "Trent",
    description = "Smiths the selected forge item up through its tiers (Base->+5/Burial) at the Artisans' Workshop, batching, reheating and banking automatically.",
    category = ScriptCategory.SMITHING
)
class AIOSmithing : Script(), ConfigurableScript {
    val targetTier = OptionsConfigItem(
        name = "Target tier",
        description = "Highest tier to finish at. The base item is whatever is selected in the forge interface.",
        options = TIERS,
        initialValue = "Base"
    )

    private var startTime = 0L
    private var startingXp = 0
    private var needsReheat = false
    private var makeFailures = 0

    // True while the step in flight produces a Burial item - burial work uses the Burial Anvil/Forge,
    // every lower tier uses the regular Anvil/Forge.
    private var currentBurial = false
    private val anvilName get() = if (currentBurial) "Burial Anvil" else "Anvil"
    private val forgeName get() = if (currentBurial) "Burial Forge" else "Forge"

    // Whether the currently-open forge interface was opened from the Burial Anvil. Lets openForge detect
    // when an already-open interface is the wrong anvil for the tier in flight and relocate.
    private var openedBurial = false

    var phase = Phase.IDLE
        private set
    var statusText = "Starting..."
        private set

    private fun targetNum() = when (targetTier.value) {
        "+1" -> 1; "+2" -> 2; "+3" -> 3; "+4" -> 4; "+5" -> 5; "Burial" -> BURIAL_TIER; else -> 0
    }

    private fun tierLabel(t: Int) = when (t) { 0 -> "Base"; BURIAL_TIER -> "Burial"; else -> "+$t" }

    private val hasUnfinished get() = inventory.hasItem(UNFINISHED_SMITHING_ITEM)

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.SMITHING)
    }

    override suspend fun loop() {
        val base = Smithing.baseObjectId
        if (base <= 0) {
            statusText = "Select an item in the forge to start."
            delay(2500)
            return
        }
        when {
            needsReheat -> reheat()
            hasUnfinished -> smith()                 // in-progress family work - finish it before anything else
            else -> route(base, familyChain(base, targetNum()))
        }
    }

    /** No unfinished items: decide from finished backpack contents whether to upgrade, bank, or batch bases. */
    private suspend fun route(base: Int, chain: List<Int>) {
        val counts = chain.map { inventory.count(it) }
        val targetIdx = chain.lastIndex
        val lowestBelow = (0 until targetIdx).firstOrNull { counts[it] > 0 }
        when {
            lowestBelow != null -> upgrade(chain, lowestBelow, counts[lowestBelow])
            counts[targetIdx] > 0 -> bank()          // everything reached the target (Burial never lands here)
            else -> makeBase(base, chain)
        }
    }

    private suspend fun makeBase(base: Int, chain: List<Int>) {
        phase = Phase.MAKING
        currentBurial = false
        if (!openForge()) return
        if (!smithSelectTier(0)) { statusText = "Base tier unavailable for ${Smithing.selectedName}."; delay(2000); return }
        val barItem = chainBarItem(chain)
        val batch = smithBatchSize(barItem, chainCumulativeBars(chain)).let { if (it > 0) it else FALLBACK_BATCH }
        val made = smithSetQuantity(batch)
        if (made <= 0) { outOfMetal(barItem) { stop() }; return }
        makeFailures = 0
        statusText = "Making $made base ${Cache.obj(base)?.name ?: "?"}"
        if (!smithMake()) { statusText = "Begin failed."; delay(2000); return }
        delayUntil(6000) { !Smithing.isOpen || hasUnfinished }
    }

    private suspend fun upgrade(chain: List<Int>, fromIdx: Int, count: Int) {
        phase = Phase.UPGRADING
        val produce = chain[fromIdx + 1]
        currentBurial = itemIsBurial(produce)
        val tier = makeTierOf(produce)
        if (!openForge()) return
        if (!smithSelectTier(tier)) { statusText = "Tier ${tierLabel(tier)} unavailable."; delay(2000); return }
        // Best-effort clamp to what the bars look like they cover, but always ask for >=1 so the game -
        // not our cost math - is the final judge of how many are makeable.
        val barItem = chainBarItem(chain)
        val stepBars = stepBarCost(produce)
        val affordable = if (stepBars > 0) smithBarsAvailable(barItem) / stepBars else count
        val want = count.coerceAtMost(affordable).coerceAtLeast(1)
        val made = smithSetQuantity(want)
        if (made <= 0) { outOfMetal(barItem) { bank() }; return }
        makeFailures = 0
        statusText = "Upgrading $made -> ${Cache.obj(produce)?.name ?: "?"}"
        if (!smithMake()) { statusText = "Begin failed."; delay(2000); return }
        delayUntil(6000) { !Smithing.isOpen || hasUnfinished }
    }

    /** The forge offered nothing makeable. Tolerate a few bad builds, then run [onEmpty] (stop / bank). */
    private suspend fun outOfMetal(barItem: Int, onEmpty: suspend () -> Unit) {
        if (++makeFailures < MAX_MAKE_FAILURES) {
            statusText = "Nothing makeable yet (try $makeFailures/$MAX_MAKE_FAILURES)."
            delay(1500)
            return
        }
        makeFailures = 0
        val metal = barItem.let { if (it > 0) Cache.obj(it)?.name ?: "?" else "metal" }
        statusText = "Out of $metal."
        onEmpty()
    }

    private suspend fun smith() {
        phase = Phase.SMITHING
        statusText = "Smithing"
        if (interactClosestObject(anvilName, "Smith")) delayUntil(180000) { needsReheat || !hasUnfinished }
        else { statusText = "No $anvilName nearby."; delay(2000) }
    }

    private suspend fun reheat() {
        phase = Phase.REHEATING
        statusText = "Reheating"
        if (interactClosestObject(forgeName, "Heat")) {
            delayUntil(10000) { !needsReheat }
            needsReheat = false   // optimistic: a fresh low-heat line re-arms it if that wasn't enough
        } else { statusText = "No $forgeName nearby."; delay(2000) }
    }

    private suspend fun openForge(): Boolean {
        if (Smithing.isOpen && openedBurial == currentBurial) return true
        if (Smithing.isOpen) {
            // Open at the wrong anvil for this tier (e.g. the normal anvil while we need Burial) - the
            // server would reject the make ("You lack the requirements..."). Close so we can relocate.
            clickKey(ESCAPE_KEY)
            delayUntil(2000) { !Smithing.isOpen }
        }
        statusText = "Opening $anvilName"
        if (interactClosestObject(anvilName, "Open smithing interface")) {
            delayUntil(5000) { Smithing.isOpen }
            if (Smithing.isOpen) openedBurial = currentBurial
        } else { statusText = "No $anvilName nearby."; delay(2000) }
        return Smithing.isOpen
    }

    private suspend fun bank() {
        phase = Phase.BANKING
        statusText = "Banking finished items"
        if (!bankOpen) {
            if (openClosestBank()) delayUntil(5000) { bankOpen } else delay(1500)
            return
        }
        depositAllInventory()
        delayUntil(3000) { inventory.isEmpty || !hasUnfinished && inventory.isEmpty }
        closeBank()
    }

    override fun onEvent(event: Event) {
        if (event !is Chat || event.messageType != MessageType.UNFILTERABLE) return
        val message = event.message.lowercase()
        when {
            FULL_HEAT_MESSAGE in message -> needsReheat = false
            REHEAT_MESSAGES.any { it in message } -> needsReheat = true
        }
    }

    override fun render() {
        ImGuiDsl.window("AIO Smithing") {
            image(graphicTexture(GraphicIds.SMITHING), 32f, 32f)
            val base = Smithing.baseObjectId
            text("Target: ${if (base > 0) Cache.obj(base)?.name ?: "?" else "-"} -> ${tierLabel(targetNum())}")
            text("Phase: ${phase.label}")
            text("Status: $statusText")
            if (base > 0) {
                val chain = familyChain(base, targetNum())
                val barItem = chainBarItem(chain)
                if (barItem > 0) {
                    val cumulative = chainCumulativeBars(chain)
                    text("Bars: ${smithBarsAvailable(barItem)} ${Cache.obj(barItem)?.name ?: "?"}  ($cumulative/item)")
                    text("Batch: ${smithBatchSize(barItem, cumulative)} items")
                }
            }
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.SMITHING), startTime)}")
            xpProgressBar(Skill.SMITHING)
        }
    }
}
