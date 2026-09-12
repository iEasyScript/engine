package com.projectx.script.impl.mel

import com.projectx.game.interfaces.IFSlot
import org.projectx.core.game.combat.Effect
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.impl.trent.SpiritAttractionPotion

@ScriptDescription(
    name = "Deep Sea Fishing",
    version = "1.0.0",
    author = "Mel",
    description = "Deep Sea Fishing",
)
class DeepSeaFishing : StateMachineScript<DeepSeaFishing>(), ConfigurableScript {

    companion object {
        const val FISHING_NOTE_ID = 42286
        const val MESSAGE_IN_BOTTLE_ID = 42282

        val SPECIAL_ITEMS = mapOf(
            42283 to "Barrel of bait",
            42284 to "Broken fishing rod",
            42285 to "Tangled fishbowl"
        )

        const val NPC_SEARCH_RADIUS = 40
        const val OBJECT_SEARCH_RADIUS = 50
        const val FRENZY_XP_TIMEOUT_MS = 300L
        const val DEBUFF_CHECK_COOLDOWN_MS = 5000L
        const val DEBUFF_WAIT_AFTER_SWITCH_MS = 65000L
        const val DEBUFF_SPOT_TIMEOUT_MS = 45_000L
        const val MAX_NO_SPOT_ATTEMPTS = 50
        const val MAX_BANK_FAILURES = 3

        const val MESSAGE_DIALOG_ID = 1186
        const val BOOST_DIALOG_ID = 751
        const val SPECIAL_ITEM_DIALOG_ID = 847
    }

    val selectedFish = EnumConfigItem(
        name = "Fish Type",
        description = "Choose which fish to catch in Deep Sea",
        enumValues = DeepSeaFishType.entries.toTypedArray(),
        initialValue = DeepSeaFishType.SAILFISH
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = false
    )

    val currentFishType: DeepSeaFishType get() = selectedFish.value

    var consecutiveNoSpotAttempts = 0
    var consecutiveBankFailures = 0
    var lastDebuffCheckTime = 0L
    var lastFishingInteractionTime = 0L

    private val debuffedSpots = mutableMapOf<Int, Long>()
    var currentFishingSpotIndex: Int? = null

    override fun onStart() {
        super.onStart()
        addParallelScript(SpiritAttractionPotion())
    }

    override fun getStartState() = DeepSeaMainState()

    fun debug(message: String) {
        if (enableDebug.value) println("[DeepSea] $message")
    }

    fun hasSpecialItem() = SPECIAL_ITEMS.keys.any { inventory.hasItem(it) }

    fun markSpotAsDebuffed(serverIndex: Int) {
        debuffedSpots[serverIndex] = System.currentTimeMillis()
        debug("Marked fishing spot (serverIndex=$serverIndex) as debuffed, will retry in ${DEBUFF_SPOT_TIMEOUT_MS/1000}s")
    }

    fun isSpotAvailable(serverIndex: Int): Boolean {
        val markedTime = debuffedSpots[serverIndex] ?: return true
        if (System.currentTimeMillis() - markedTime > DEBUFF_SPOT_TIMEOUT_MS) {
            debuffedSpots.remove(serverIndex)
            return true
        }
        return false
    }

    enum class DeepSeaFishType(val displayName: String) {
        MINNOW_SHOAL("Minnow Shoal"),
        SWARM("Swarm"),
        GREEN_BLUBBER_JELLYFISH("Green Blubber Jellyfish"),
        BLUE_BLUBBER_JELLYFISH("Blue Blubber Jellyfish"),
        SAILFISH("Sailfish"),
        FISHING_FRENZY("Fishing Frenzy");

        override fun toString() = displayName

        val isJellyfish: Boolean
            get() = this == GREEN_BLUBBER_JELLYFISH || this == BLUE_BLUBBER_JELLYFISH
    }
}

class DeepSeaMainState : State<DeepSeaFishing>() {
    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        if (consecutiveBankFailures >= DeepSeaFishing.MAX_BANK_FAILURES) {
            println("ERROR: Failed to bank ${DeepSeaFishing.MAX_BANK_FAILURES} times in a row. Stopping script.")
            stop()
            return null
        }

        return when {
            inventory.hasItem(DeepSeaFishing.MESSAGE_IN_BOTTLE_ID) -> MessageInBottleState()
            hasSpecialItem() -> InteractSpecialItemsState()
            inventory.hasItem(DeepSeaFishing.FISHING_NOTE_ID) -> RedeemFishingNotesState()
            inventory.isFull -> BankingState()
            else -> FishingState()
        }
    }

    override suspend fun DeepSeaFishing.stateLoop() { }
}

class RedeemFishingNotesState : State<DeepSeaFishing>() {
    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        return if (!inventory.hasItem(DeepSeaFishing.FISHING_NOTE_ID)) DeepSeaMainState() else null
    }

    override suspend fun DeepSeaFishing.stateLoop() {
        if (!inventory.clickItem(DeepSeaFishing.FISHING_NOTE_ID, 1)) return

        waitThenDelayUntil(800, 3000) { interfaces.isOpen(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID) }

        if (interfaces.isOpen(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID)) {
            delay(300, 200)
            IFSlot(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID, 22).dialogueContinue()
            delay(600, 400)
        }
    }
}

class MessageInBottleState : State<DeepSeaFishing>() {
    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        return if (!inventory.hasItem(DeepSeaFishing.MESSAGE_IN_BOTTLE_ID)) DeepSeaMainState() else null
    }

    override suspend fun DeepSeaFishing.stateLoop() {
        if (!inventory.clickItem(DeepSeaFishing.MESSAGE_IN_BOTTLE_ID, 1)) return

        waitThenDelayUntil(1200, 5000) { interfaces.isOpen(DeepSeaFishing.MESSAGE_DIALOG_ID) }

        if (!interfaces.isOpen(DeepSeaFishing.MESSAGE_DIALOG_ID)) return

        delay(300, 200)
        val messageSlot = IFSlot(DeepSeaFishing.MESSAGE_DIALOG_ID, 8)
        if (!messageSlot.dialogueContinue()) {
            DoActionOpcode.DIALOGUE.fire(0, -1, messageSlot.hash)
        }

        waitThenDelayUntil(600, 3000) { !interfaces.isOpen(DeepSeaFishing.MESSAGE_DIALOG_ID) }
        waitThenDelayUntil(600, 5000) { interfaces.isOpen(DeepSeaFishing.BOOST_DIALOG_ID) }

        if (interfaces.isOpen(DeepSeaFishing.BOOST_DIALOG_ID)) {
            debug("Selecting boost option")
            delay(300, 200)
            val boostSlot = IFSlot(DeepSeaFishing.BOOST_DIALOG_ID, 50)
            if (!boostSlot.dialogueContinue()) {
                DoActionOpcode.DIALOGUE.fire(0, -1, boostSlot.hash)
            }
            delay(800, 400)
        }
    }
}

class BankingState : State<DeepSeaFishing>() {
    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        return if (!inventory.isFull && !inventory.hasItem(DeepSeaFishing.FISHING_NOTE_ID)) DeepSeaMainState() else null
    }

    override suspend fun DeepSeaFishing.stateLoop() {
        if (localPlayer.isAniMoving) return

        if (interactClosestObject("Deposit all fish", DeepSeaFishing.OBJECT_SEARCH_RADIUS)) {
            waitThenDelayUntil(1200, 15000) { !inventory.isFull }
            consecutiveBankFailures = 0
            return
        }

        if (interactClosestObject("Bank boat", "Use", DeepSeaFishing.OBJECT_SEARCH_RADIUS) ||
            interactClosestObject("Magical net", "Bank", DeepSeaFishing.OBJECT_SEARCH_RADIUS)) {
            waitThenDelayUntil(1200, 5000) { bankOpen }
            if (bankOpen) {
                depositAllInventory()
                delay(500, 300)
                consecutiveBankFailures = 0
                return
            }
        }

        debug("Failed to find banking option")
        consecutiveBankFailures++
        delay(1500, 500)
    }
}

class FishingState : State<DeepSeaFishing>() {
    private var animationDelay = 0

    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        return when {
            inventory.hasItem(DeepSeaFishing.FISHING_NOTE_ID) -> RedeemFishingNotesState()
            inventory.hasItem(DeepSeaFishing.MESSAGE_IN_BOTTLE_ID) -> MessageInBottleState()
            hasSpecialItem() -> InteractSpecialItemsState()
            inventory.isFull -> {
                consecutiveNoSpotAttempts = 0
                BankingState()
            }
            else -> null
        }
    }

    override suspend fun DeepSeaFishing.stateLoop() {
        if (localPlayer.isAniMoving) {
            handleActiveFishing()
            return
        }

        animationDelay++
        if (animationDelay < 2) {
            delay(600, 200)
            return
        }

        animationDelay = 0
        val fishingSpot = findFishingSpot()

        if (fishingSpot == null) {
            handleNoSpotFound()
            return
        }

        consecutiveNoSpotAttempts = 0

        if (fishingSpot.interact("Net") || fishingSpot.interact("Catch") || fishingSpot.interact(0)) {
            debug("Fishing at ${fishingSpot.name()} (serverIndex=${fishingSpot.serverIndex})")
            currentFishingSpotIndex = fishingSpot.serverIndex

            if (currentFishType == DeepSeaFishing.DeepSeaFishType.FISHING_FRENZY) {
                lastFishingInteractionTime = System.currentTimeMillis()
            }

            waitThenDelayUntil(1200, 3000) { localPlayer.isAnimating }
        }
    }

    private suspend fun DeepSeaFishing.handleActiveFishing() {
        if (currentFishType == DeepSeaFishing.DeepSeaFishType.FISHING_FRENZY) {
            val timeSinceInteraction = System.currentTimeMillis() - lastFishingInteractionTime
            if (timeSinceInteraction > DeepSeaFishing.FRENZY_XP_TIMEOUT_MS && timeSinceLastXpDrop > DeepSeaFishing.FRENZY_XP_TIMEOUT_MS) {
                animationDelay = 0
                delay(400, 200)
                return
            }
        }

        val timeSinceLastDebuffCheck = System.currentTimeMillis() - lastDebuffCheckTime
        if (currentFishType.isJellyfish &&
            Effect.DEEP_SEA_FISHING.active &&
            timeSinceLastDebuffCheck > DeepSeaFishing.DEBUFF_CHECK_COOLDOWN_MS) {

            debug("Debuff detected on serverIndex=$currentFishingSpotIndex, switching to new spot")

            currentFishingSpotIndex?.let { spotIndex ->
                markSpotAsDebuffed(spotIndex)
            }

            lastDebuffCheckTime = System.currentTimeMillis() + (DeepSeaFishing.DEBUFF_WAIT_AFTER_SWITCH_MS - DeepSeaFishing.DEBUFF_CHECK_COOLDOWN_MS)
            currentFishingSpotIndex = null
            animationDelay = 0

            val newSpot = findFishingSpot()
            if (newSpot != null) {
                debug("Switching to new spot: ${newSpot.name()} (serverIndex=${newSpot.serverIndex})")
                debug("Waiting ${DeepSeaFishing.DEBUFF_WAIT_AFTER_SWITCH_MS/1000}s for debuff to clear before checking again")
                if (newSpot.interact("Net") || newSpot.interact("Catch") || newSpot.interact(0)) {
                    currentFishingSpotIndex = newSpot.serverIndex
                    waitThenDelayUntil(1200, 3000) { localPlayer.isAnimating }
                }
            } else {
                debug("No alternative spot found, will retry on next loop")
                delay(600, 300)
            }
            return
        }

        delay(1000, 400)
    }

    private suspend fun DeepSeaFishing.handleNoSpotFound() {
        consecutiveNoSpotAttempts++

        if (consecutiveNoSpotAttempts >= DeepSeaFishing.MAX_NO_SPOT_ATTEMPTS) {
            println("ERROR: Could not find fishing spot after $consecutiveNoSpotAttempts attempts. Stopping script.")
            stop()
            return
        }

        if (consecutiveNoSpotAttempts % 10 == 0) {
            debug("Searching for ${currentFishType.displayName} spot (Attempt: $consecutiveNoSpotAttempts)")
        }

        delay(1500, 500)
    }

    private fun DeepSeaFishing.findFishingSpot() = when (currentFishType) {
        DeepSeaFishing.DeepSeaFishType.MINNOW_SHOAL ->
            findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                it.name().equals("Minnow shoal", ignoreCase = true) && isSpotAvailable(it.serverIndex)
            }

        DeepSeaFishing.DeepSeaFishType.SWARM ->
            findClosestNPC(30) {
                it.name().equals("Swarm", ignoreCase = true) && isSpotAvailable(it.serverIndex)
            }

        DeepSeaFishing.DeepSeaFishType.GREEN_BLUBBER_JELLYFISH ->
            findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                it.name().contains("Green blubber jellyfish", ignoreCase = true) &&
                        !it.name().contains("electrifying", ignoreCase = true) &&
                        isSpotAvailable(it.serverIndex)
            }

        DeepSeaFishing.DeepSeaFishType.BLUE_BLUBBER_JELLYFISH ->
            findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                it.name().contains("Blue blubber jellyfish", ignoreCase = true) &&
                        !it.name().contains("electrifying", ignoreCase = true) &&
                        isSpotAvailable(it.serverIndex)
            }

        DeepSeaFishing.DeepSeaFishType.SAILFISH ->
            findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                it.name().equals("Swift sailfish", ignoreCase = true) && isSpotAvailable(it.serverIndex)
            }
                ?: findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                    it.name().equals("Sailfish", ignoreCase = true) && isSpotAvailable(it.serverIndex)
                }

        DeepSeaFishing.DeepSeaFishType.FISHING_FRENZY ->
            findClosestNPC(DeepSeaFishing.NPC_SEARCH_RADIUS) {
                it.name().let { name ->
                    name.equals("Fish", ignoreCase = true) ||
                            name.equals("Calm fish", ignoreCase = true) ||
                            name.equals("Frenzy fish", ignoreCase = true)
                } && isSpotAvailable(it.serverIndex)
            }
    }
}

class InteractSpecialItemsState : State<DeepSeaFishing>() {
    override suspend fun DeepSeaFishing.checkNext(): State<DeepSeaFishing>? {
        return if (!hasSpecialItem()) DeepSeaMainState() else null
    }

    override suspend fun DeepSeaFishing.stateLoop() {
        for ((itemId, itemName) in DeepSeaFishing.SPECIAL_ITEMS) {
            if (!inventory.hasItem(itemId)) continue

            if (inventory.clickItem(itemId, "Interact") || inventory.clickItem(itemId, 0)) {
                waitThenDelayUntil(800, 3000) { interfaces.isOpen(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID) }

                if (interfaces.isOpen(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID)) {
                    debug("Redeeming $itemName")
                    IFSlot(DeepSeaFishing.SPECIAL_ITEM_DIALOG_ID, 22).dialogueContinue()
                    delay(600, 400)
                }
                return
            }
        }

        delay(600, 400)
    }
}