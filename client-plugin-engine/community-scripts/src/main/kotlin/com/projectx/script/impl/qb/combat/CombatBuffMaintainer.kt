package com.projectx.script.impl.qb.combat

import org.projectx.core.game.combat.Effect
import com.projectx.game.chat.MessageType
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.api.drinkOverload
import com.projectx.script.api.inventory
import com.projectx.script.api.healthPercent
import com.projectx.script.api.eatFood
import com.projectx.script.api.adrenaline
import com.projectx.script.api.castAbility
import org.projectx.core.game.combat.Ability
import com.projectx.script.impl.qb.combat.BuffSpec
import com.projectx.script.api.getDivineCharges
import com.projectx.script.StringConfigItem
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.script.api.areaLoot
import com.projectx.script.api.areaLootOpen
import com.projectx.game.interfaces.IFSlot
import world.gregs.voidps.type.Tile
import com.projectx.script.api.interactClosestNPC
import com.projectx.traversal.nodes.ChebychevNode

@ScriptDescription(
    name = "Combat Utility",
    version = "2.0.1",
    author = "Kudlo",
    description = "General Combat Utilities"
)
class CombatBuffMaintainer : Script(), ConfigurableScript {
     private val checkInventoryForBuffables = BooleanConfigItem(
        name = "Check Inventory for Buffables",
        description = "Automatically detect and apply supported buffs based on items in inventory.",
        initialValue = true
    )

    private val enableAutoEat = BooleanConfigItem(
        name = "Enable Auto-Eat",
        description = "Use Eat Food ability when HP is below threshold.",
        initialValue = true
    )

    private val autoEatThresholdPercent = IntConfigItem(
        name = "Auto-Eat Threshold (%)",
        description = "HP percentage below which to trigger Eat Food.",
        initialValue = 35,
        min = 1,
        max = 99
    )

    private val enableAutoWeaponSpecial = BooleanConfigItem(
        name = "Enable Auto Weapon Special",
        description = "Automatically activate the Weapon Special Attack from the action bar when available.",
        initialValue = false
    )

    private val minAdrenalineForSpec = IntConfigItem(
        name = "Min Adrenaline for Spec (%)",
        description = "Only cast weapon special when adrenaline is at or above this percent.",
        initialValue = 50,
        min = 0,
        max = 100
    )

    private val useSpecBelowHpPercent = IntConfigItem(
        name = "Use Spec Below HP (%)",
        description = "Only cast weapon special when your HP is at or below this percent (e.g., 75).",
        initialValue = 75,
        min = 1,
        max = 100
    )

    private val enableOverload = BooleanConfigItem(
        name = "Maintain Overload",
        description = "Automatically drink an overload when not active.",
        initialValue = false
    )

    private val enablePrayerRenewal = BooleanConfigItem(
        name = "Maintain Prayer Renewal",
        description = "Automatically drink a (Super) Prayer Renewal when not active.",
        initialValue = false
    )

    private val enablePowderOfPenance = BooleanConfigItem(
        name = "Maintain Powder of Penance",
        description = "Automatically activate Powder of Penance when not active.",
        initialValue = false
    )

    private val enableCharmingPotion = BooleanConfigItem(
        name = "Maintain Charming Potion",
        description = "Automatically drink a Charming potion when not active.",
        initialValue = false
    )

    private val enableAggressionPotion = BooleanConfigItem(
        name = "Maintain Aggression Potion",
        description = "Automatically drink an Aggression potion when not active.",
        initialValue = false
    )

    private val maintainDivineChargePack = BooleanConfigItem(
        name = "Maintain Divine Charge Pack",
        description = "Keep charge pack topped up using 'Add All' on Divine charges.",
        initialValue = false
    )

    private val minDivineChargeHours = IntConfigItem(
        name = "Min Divine Charge Hours",
        description = "Top up if estimated hours left is below this threshold.",
        initialValue = 1,
        min = 0,
        max = 24
    )

    private val divineAddAllEvery2h = BooleanConfigItem(
        name = "Divine 'Add All' every 2h",
        description = "Every 2 hours, attempt to 'Add All' Divine charges (in addition to threshold logic).",
        initialValue = false
    )

    private val enableLootSpecific = BooleanConfigItem(
        name = "Loot Specific Drops",
        description = "Pick up configured valuable drops from the floor.",
        initialValue = false
    )

    private val lootNamesCsv = StringConfigItem(
        name = "Loot Item Names (CSV)",
        description = "Comma-separated item name parts to loot (case-insensitive).",
        initialValue = "Clue scroll,Hazelmere signet ring,Vecna skull"
    )

    private val lootUseChatTriggers = BooleanConfigItem(
        name = "Use Chat Triggers",
        description = "React to chat messages containing configured names to prioritize looting.",
        initialValue = true
    )

    private val lootScanRange = IntConfigItem(
        name = "Loot Scan Range",
        description = "Max tiles to scan for floor drops (<=25).",
        initialValue = 20,
        min = 1,
        max = 25
    )

    private val useAreaLootMenu = BooleanConfigItem(
        name = "Use Area Loot Menu",
        description = "When open, loot targets directly from the Area Loot UI (ID 773). Falls back to floor loot otherwise.",
        initialValue = true
    )

    private val enableAutoRebankLotD = BooleanConfigItem(
        name = "Auto-Rebank via LotD",
        description = "When out of applicable buff items, teleport to GE via Luck of the Dwarves and Load Last Preset.",
        initialValue = false
    )
    private val rebankCooldownMs = IntConfigItem(
        name = "Rebank Cooldown (ms)",
        description = "Minimum time between auto-rebanks.",
        initialValue = 60_000,
        min = 5_000,
        max = 600_000
    )

    private val lotdTeleportOption = IntConfigItem(
        name = "LotD Teleport Option Number",
        description = "Context menu option number to use on equipped Luck of the Dwarves (default 3).",
        initialValue = 3,
        min = 1,
        max = 8
    )

    private val rebankWhenNoBuffables = BooleanConfigItem(
        name = "Rebank When No Buffables",
        description = "Trigger rebank even if no immediate buff is needed, when no supported buff items remain in inventory.",
        initialValue = false
    )

    private val rebankWhenInventoryEmpty = BooleanConfigItem(
        name = "Rebank When Inventory Empty",
        description = "Trigger rebank when the inventory is empty and no supported buff items remain.",
        initialValue = false
    )

    private val checkIntervalMs = IntConfigItem(
        name = "Check Interval (ms)",
        description = "How often to check and reapply buffs.",
        initialValue = 1500,
        min = 250,
        max = 30000
    )

    private var lastCheck = 0L
    private val lastAppliedTimes = mutableMapOf<BuffSpec, Long>()
    private var lastDivineAddAllAt = 0L
    private var lastRebankAt = 0L
    private var chatLootHint: String? = null
    private var chatLootHintExpiresAt = 0L

    override suspend fun loop() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < checkIntervalMs.value) return
        lastCheck = now

        if (enableAutoEat.value && healthPercent < autoEatThresholdPercent.value) {
            if (Ability.EAT_FOOD.offCdIgnoreGCD && eatFood()) {
                delayUntil(800) { healthPercent >= autoEatThresholdPercent.value || !Ability.EAT_FOOD.offCdIgnoreGCD }
            }
        }

        if (enableAutoWeaponSpecial.value) {
            val hpOk = healthPercent <= useSpecBelowHpPercent.value
            val adrenOk = adrenaline * 100.0 >= minAdrenalineForSpec.value
            if (hpOk && adrenOk && Ability.WEAPON_SPECIAL_ATTACK.offCdIgnoreGCD) {
                if (castAbility(Ability.WEAPON_SPECIAL_ATTACK)) {
                    delayUntil(1200) { !Ability.WEAPON_SPECIAL_ATTACK.offCdIgnoreGCD }
                }
            }
        }

        lootSpecificDrops()

        if (maintainDivineChargePack.value) {
            val hoursLeft = getDivineCharges
            val shouldTopUpByHours = hoursLeft < minDivineChargeHours.value
            val shouldTopUpByInterval = divineAddAllEvery2h.value && (now - lastDivineAddAllAt) >= (2 * 60 * 60 * 1000)
            if (shouldTopUpByHours || shouldTopUpByInterval) {
                val pattern = Regex("(?i).*divine\\s*charge.*")
                val didAddAll =
                    inventory.clickItem(pattern, "Add All") ||
                    inventory.clickItem(pattern, "Add-all")
                if (didAddAll) {
                    lastDivineAddAllAt = now
                }
            }
        }

        if (enableAutoRebankLotD.value && (now - lastRebankAt) >= rebankCooldownMs.value) {
            val needAny = BuffSpec.values().any { it.shouldApply() }
            val haveAny = BuffSpec.values().any { it.isAvailable() }
            val noBuffables = !haveAny
            val inventoryEmpty = !inventory.any { true }

            val shouldRebank =
                (needAny && noBuffables) ||
                (rebankWhenNoBuffables.value && noBuffables) ||
                (rebankWhenInventoryEmpty.value && inventoryEmpty && noBuffables)

            if (shouldRebank) {
                if (rebankViaLotDFromEquip()) {
                    lastRebankAt = now
                    delay(1200, 400)
                }
            }
        }

        if (checkInventoryForBuffables.value) {
            val candidates = BuffSpec.values()
                .filter { it.isAvailable() && it.shouldApply() }
                .sortedByDescending { it.priority }

            for (buff in candidates) {
                val lastApplied = lastAppliedTimes[buff] ?: 0L
                if (now - lastApplied < buff.minReapplyIntervalMs) continue
                if (buff.use(this)) {
                    lastAppliedTimes[buff] = now
                    delayUntil(1600) { buff.waitCondition.invoke() }
                }
            }

            return
        }

        if (enableOverload.value && drinkOverload()) {
            delayUntil(1500) { Effect.OVERLOADED.active }
        }

        if (enablePrayerRenewal.value && Effect.PRAYER_RENEW.notActive && inventory.clickItem(Regex(".*prayer.*renewal.*", RegexOption.IGNORE_CASE), 1)) {
            delayUntil(1500) { Effect.PRAYER_RENEW.active }
        }

        if (enablePowderOfPenance.value && Effect.POWDER_OF_PENANCE.notActive && inventory.clickItem(Regex(".*powder of penance.*", RegexOption.IGNORE_CASE), 1)) {
            delayUntil(1200) { Effect.POWDER_OF_PENANCE.active }
        }

        if (enableCharmingPotion.value && Effect.CHARMING_POTION.notActive && inventory.clickItem(Regex(".*charming.*potion.*", RegexOption.IGNORE_CASE), 1)) {
            delayUntil(1200) { Effect.CHARMING_POTION.active }
        }

        if (enableAggressionPotion.value && Effect.AGGRESSION_POTION.notActive && Effect.AGGRESSION.notActive && inventory.clickItem(Regex(".*aggression.*", RegexOption.IGNORE_CASE), 1)) {
            delayUntil(1500) { Effect.AGGRESSION_POTION.active || Effect.AGGRESSION.active }
        }
    }

    private fun shouldRebankForBuffs(): Boolean {
        val needAny = BuffSpec.values().any { it.shouldApply() }
        val haveAny = BuffSpec.values().any { it.isAvailable() }
        return needAny && !haveAny
    }

    private suspend fun teleportToGEUsingLotDFromEquip(): Boolean {
        val lotdSlot = IFSlot(1464, 15, 12)
        val clicked = lotdSlot.click(lotdTeleportOption.value)
        if (!clicked) return false
        waitThenDelayUntil(1200, 15000) { localPlayer.isAniMoving || localPlayer.isAnimating }
        waitThenDelayUntil(600, 20000) { !localPlayer.isAniMoving && !localPlayer.isMoving }
        return true
    }

    private suspend fun walkGEWithChebyshev(): Boolean {
        val waypoints: List<Tile> = listOf(
            Tile.of(3162, 3464, 0),
            Tile.of(3163, 3484, 0)
        )
        val node = ChebychevNode(localPlayer.tile, waypoints, reached = { localPlayer.tile.getDistance(waypoints.last()) < 6 })
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20000 && !node.reached(this)) {
            val cont = node.process(this)
            if (!cont) break
            delay(50)
        }
        return node.reached(this)
    }

    private suspend fun rebankViaLotDFromEquip(): Boolean {
        if (!teleportToGEUsingLotDFromEquip()) return false

        if (!walkGEWithChebyshev()) return false

        val didLoad = interactClosestNPC("Banker", "Load Last Preset from", 20)
        if (didLoad) {
            waitThenDelayUntil(1200, 15000) { !localPlayer.isMoving }
            return true
        }
        return false
    }

    private fun currentLootTargets(): List<String> {
        val base = lootNamesCsv.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val useHint = lootUseChatTriggers.value && chatLootHint != null && System.currentTimeMillis() <= chatLootHintExpiresAt
        return if (useHint) listOf(chatLootHint!!) else base
    }

    private suspend fun lootFromAreaLootMenu(targets: List<String>): Boolean {
        if (!areaLootOpen) return false
        if (inventory.isFull) return false

        val patterns = targets.map { name ->
            Regex("(?i).*" + Regex.escape(name) + ".*")
        }

        try {
            for (rx in patterns) {
                val present = try { areaLoot.getItem(rx) != null } catch (_: Throwable) { false }
                if (!present) continue

                val clicked = try { areaLoot.clickItem(rx, 1) } catch (_: Throwable) { false }
                if (clicked) {
                    delayUntil(800) { !areaLootOpen || areaLoot.getItem(rx) == null || inventory.isFull }
                    return true
                }
            }
        } catch (_: Throwable) { }

        return false
    }

    private suspend fun lootSpecificDrops() {
        if (!enableLootSpecific.value) return

        val targets = currentLootTargets()
        if (targets.isEmpty()) return

        if (useAreaLootMenu.value) {
            val handled = lootFromAreaLootMenu(targets)
            if (handled) return
        }

        // Accessing groundItems may throw if the native list mutates. Guard it.
        val items = try {
            groundItems.toList()
        } catch (t: Throwable) {
            return
        }

        val candidates = items.mapNotNull { gi ->
            try {
                val dist = localPlayer.tile.getDistance(gi.tile)
                if (
                    gi.tile.plane == localPlayer.tile.plane &&
                    dist <= lootScanRange.value &&
                    targets.any { t -> gi.name.contains(t, ignoreCase = true) }
                ) {
                    dist to gi
                } else null
            } catch (_: Throwable) {
                null
            }
        }.sortedBy { it.first }

        for ((_, gi) in candidates) {
            try {
                if (gi.interact("Take")) {
                    delayWhile(400) { localPlayer.isMoving }
                    return
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (!enableLootSpecific.value || !lootUseChatTriggers.value) return
        val chat = event as? Chat ?: return
        val type = chat.messageType
        if (type !in setOf(MessageType.GAME, MessageType.FILTERABLE, MessageType.GLOBAL_ANNOUNCEMENT)) return

        val names = lootNamesCsv.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val hit = names.firstOrNull { chat.message.contains(it, ignoreCase = true) }
        if (hit != null) {
            chatLootHint = hit
            chatLootHintExpiresAt = System.currentTimeMillis() + 15_000
        }
    }
}
