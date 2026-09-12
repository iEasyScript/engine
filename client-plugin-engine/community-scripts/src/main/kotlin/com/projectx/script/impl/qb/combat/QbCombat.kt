package com.projectx.script.impl.qb.combat

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import org.projectx.core.game.combat.Ability
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.hasLineOfSight
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.util.formatElapsedTime
import com.projectx.util.getFormattedXpPerHour
import java.util.concurrent.atomic.AtomicInteger

@ScriptDescription(
    name = "Combat_QB",
    version = "1.0.0",
    author = "Billy",
    description = "Combat script with GUI, food/potion/loot parallel scripts"
)
class QbCombat : StateMachineScript<QbCombat>(), ConfigurableScript {
    val eatFoodEnabled = BooleanConfigItem(
        name = "Eat Food",
        description = "Automatically eat food when health is low",
        initialValue = true
    )
    val drinkPotionEnabled = BooleanConfigItem(
        name = "Drink Potion",
        description = "Automatically drink potion when stats are low",
        initialValue = true
    )
    val lootEnabled = BooleanConfigItem(
        name = "Loot Items",
        description = "Automatically loot items after kill",
        initialValue = true
    )
    val checkLos = BooleanConfigItem(
        name = "Check Line of Sight",
        description = "Checks line of sight when getting a new target",
        initialValue = false
    )
    val attackRange = IntConfigItem(
        name = "Attack Range",
        description = "Range to attack NPCs from",
        initialValue = 5
    )
    val buryBones = BooleanConfigItem(
        name = "Bury Bones",
        description = "Automatically bury bones from inventory if enabled",
        initialValue = false
    )
    val anchorEnabled = BooleanConfigItem(
        name = "Anchor Position",
        description = "Enable anchor starting position. Player will stay within range of anchor tile.",
        initialValue = false
    )
    val anchorRange = IntConfigItem(
        name = "Anchor Range",
        description = "Max distance from anchor tile before moving back.",
        initialValue = 8
    )

    lateinit var targetNpcName: String
    fun hasTargetNpc() = ::targetNpcName.isInitialized

    var startTime = 0L
    var startingXp = 0
    val kills = AtomicInteger(0)

    var anchorTile: Tile? =null

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.ATTACK)
        if (eatFoodEnabled.value) addParallelScript(EatFoodParallel())
        if (drinkPotionEnabled.value) addParallelScript(DrinkPotionParallel())
        if (lootEnabled.value) addParallelScript(LootParallel())
    }

    override fun render() {
        ImGuiDsl.window("QbCombat") {
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("Target NPC: ${if (hasTargetNpc()) targetNpcName else "None selected"}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.ATTACK), startTime)}")
            text("Kills: ${kills.get()}")
            if (anchorEnabled.value && anchorTile != null) {
                text("Anchor Tile: ${anchorTile}")
                text("Distance from Anchor: ${localPlayer.tile.getDistance(anchorTile!!)}")
            }
            xpProgressBar(Skill.ATTACK)
        }
    }

    fun setTargetNpc(name: String) {
        targetNpcName = name
    }

    fun updateAnchorTile(tile: Tile) {
        anchorTile = tile
    }

    override fun getStartState() = Init
}

object Init : State<QbCombat>() {
    override suspend fun QbCombat.checkNext() = if (hasTargetNpc()) Attack else null
    override suspend fun QbCombat.stateLoop() {}
    override fun QbCombat.onStateEvent(event: Event) {
        if (event is ManualDoAction && event.target is NPC) {
            val npc = event.target as NPC
            if (!hasTargetNpc() && npc.hasOption("Attack")) {
                setTargetNpc(npc.name)
                if (anchorEnabled.value && anchorTile == null) {
                    updateAnchorTile(localPlayer.tile)
                }
            }
        }
    }
}

object Attack : State<QbCombat>() {
    override suspend fun QbCombat.checkNext() = null
    override suspend fun QbCombat.stateLoop() {
        if (!hasTargetNpc()) return
        if (anchorEnabled.value && anchorTile != null && localPlayer.tile.getDistance(anchorTile!!) > anchorRange.value) {
            walkTo(anchorTile!!, true)
            delayUntil(2000) { localPlayer.tile.getDistance(anchorTile!!) <= anchorRange.value }
            return
        }
        if (buryBones.value) {
            val bone = inventory.firstOrNull { it.name.contains("bone", ignoreCase = true) && it.click("Bury") }
            if (bone != null) {
                bone.click("Bury")
                delay(800, 200)
                return
            }
            if (buryBones.value) {
                val bone = inventory.firstOrNull { it.name.contains("bone", ignoreCase = true) && it.click("Bury") }
                if (bone != null) {
                    bone.click("Bury")
                    delay(800, 200)
                    return
                }
            }
            val npc = npcs.values.firstOrNull {
                it.name == targetNpcName &&
                it.hasOption("Attack") &&
                it.currentHealth > 0 &&
                (!checkLos.value || hasLineOfSight(localPlayer.tile, 1, it.tile, 1))
            }
            if (npc != null) {
                if (npc.interact("Attack")) {
                    delay(1500, 500)
                }
            }
            delay(250, 100)
            return
        }
        return
    }
    override fun QbCombat.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("defeated")) {
            kills.incrementAndGet()
        }
    }
}

class EatFoodParallel : Script() {
    override suspend fun loop() {
        if (healthPercent < 30.0) {
            val food = inventory.firstOrNull { it.click("Eat") }
            if (food != null) {
                food.click("Eat")
                delay(1200, 400)
            }
        }
        delay(500, 200)
    }
}

class DrinkPotionParallel : Script() {
    override suspend fun loop() {
        val potion = inventory.firstOrNull { it.click("Drink") }
        if (potion != null) {
            potion.click("Drink")
            delay(1200, 400)
        }
        delay(1000, 400)
    }
}

class LootParallel : Script() {
    override suspend fun loop() {
        val loot = groundItems.firstOrNull { it.interact("Take") }
        if (loot != null) {
            if (loot.interact("Take")) {
                delay(1200, 400)
            }
        }
        delay(800, 300)
    }
}
