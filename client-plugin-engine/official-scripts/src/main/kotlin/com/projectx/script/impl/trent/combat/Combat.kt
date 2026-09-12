package com.projectx.script.impl.trent.combat

import com.projectx.game.chat.MessageType
import org.projectx.core.game.combat.Ability
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.hasLineOfSight
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.script.impl.trent.CombatRotation
import com.projectx.util.showNotification
import java.io.File

@ScriptDescription(
    name = "Combat",
    version = "1.0.0",
    author = "Trent",
    description = "Combat"
)
class Combat : StateMachineScript<Combat>(), ConfigurableScript {
    val aoeGather = BooleanConfigItem(
        name = "AOE Gather",
        description = "AOE gathers NPCs by attacking things that are nearby without waiting for death",
        initialValue = false
    )

    val checkLos = BooleanConfigItem(
        name = "Check Line of Sight",
        description = "Checks line of sight when getting a new target",
        initialValue = false
    )

    val attackRange = IntConfigItem(
        name = "Attack range",
        description = "Range to attack npcs from",
        initialValue = 5
    )

    val buryBones = BooleanConfigItem(
        name = "Bury Bones",
        description = "Automatically bury bones from inventory if enabled",
        initialValue = false
    )
    val valuablesToLoot = StringConfigItem(
        name = "Valuable Item Names",
        description = "Comma-separated list of valuable item phrases to prioritize (case insensitive)",
        initialValue = "staff of light,hexcrest,focus sight"
    )

    var isStrykewyrm = false

    lateinit var targetString: String
    fun hasTarget() = ::targetString.isInitialized

    fun setTarget(target: String) {
        targetString = target
        isStrykewyrm = target.contains("strykewyrm", true)
    }

    val lootListFile = StringConfigItem(
        name = "Loot List File",
        description = "Path to a .txt file containing comma-separated item names to loot (e.g., \"item1\",\"item2\")",
        initialValue = System.getProperty("user.home") + "/.projectx/scripts/loot_list.txt"
    )

    private var lootList: Set<String> = emptySet()

    fun loadLootList() {
        val filePath = lootListFile.value
        val defaultItems = listOf("")
        try {
            val file = File(filePath)
            val dir = file.parentFile
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (file.exists()) {
                val content = file.readText()
                lootList = content.split(',')
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } else {
                val defaultString = defaultItems.joinToString(",") { "\"$it\"" }
                file.writeText(defaultString)
                lootList = defaultItems.toSet()
            }
        } catch (e: Throwable) {
            lootList = defaultItems.toSet()
        }
    }

    fun shouldLoot(itemName: String): Boolean = lootList.contains(itemName)

    val targetHp: Int
        get() = npcs.filter { it.value.interactionSid == localPlayer.interactionSid }
            .values
            .firstOrNull()
            ?.currentHealth ?: 0
    val valuablesToLootList get() = valuablesToLoot.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    override fun onStart() {
        loadLootList()
        addParallelScript(CombatRotation())
    }

    override fun getStartState() = Init
}

object Init: State<Combat>() {
    override suspend fun Combat.checkNext(): State<Combat>? {
        return if (hasTarget()) Attack else null
    }

    override suspend fun Combat.stateLoop() { }

    override fun Combat.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is NPC) return
        val npc = event.target as NPC
        if (!hasTarget() && npc.hasOption("Attack"))
            setTarget(npc.name)
    }
}

object Attack: State<Combat>() {
    val alchables = Regex(".* (battlestaff|rune salvage|orikalkum salvage|bark body|bark helm)$")

    override suspend fun Combat.checkNext() = if (healthPercent < 50.0) Heal else null

    override suspend fun Combat.stateLoop() {
        if (channelingAbility) return

        if (buryBones.value) {
            val bone = inventory.firstOrNull { it.name.matches(".*bone.*".toRegex(RegexOption.IGNORE_CASE)) }
            if (bone != null) {
                bone.click("Bury")
                delay(800, 200)
                return
            }
        }
        if (areaLootOpen && areaLootContainsHoldableItems) {
            lootAllAreaLoot()
            delay(1000, 630)
        }

        if (inventory.hasItem(554) && inventory.hasItem(561)) {
            if (inventory.getItem(alchables)?.alch() == true) {
                delay(3500, 2000)
                return
            }
        }
        val gigaValuable = groundItems.firstOrNull { groundItem -> valuablesToLootList.any { phrase -> groundItem.name.contains(phrase, ignoreCase = true) } }
        if (gigaValuable != null) {
            if (inventory.isFull && castAbility(Ability.EAT_FOOD))
                delayUntil(1000) { !inventory.isFull }
            if (gigaValuable.interact("Take"))
                delay(8500, 3000)
            return
        }
        if (!aoeGather.value)
            if (localPlayer.isInteracting) return delay(150, 250)
        val closestTarget = allNpcsWithinRange(attackRange.value)
            .filter { it.name == targetString && it.hasOption("Attack") && (if (aoeGather.value) (it.currentHealth >= it.maxHealth) else (it.currentHealth > 0)) && (!checkLos.value || hasLineOfSight(localPlayer.tile, 1, it.tile, 1)) }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }

        if (closestTarget?.interact("Attack") == true)
            delay(1940, 1100)
        if (isStrykewyrm) {
            val mound = allNpcsWithinRange(attackRange.value)
                .filter { it.name == "Mound" && it.hasOption("Investigate") }
                .minByOrNull { it.tile.getDistance(localPlayer.tile) }
            if (mound?.interact("Investigate") == true)
                delay(6029, 3592)
        }
        delay(250, 200)
    }

    override fun Combat.onStateEvent(event: Event) {
        if (event is ManualDoAction && event.target is NPC) {
            val npc = event.target as NPC
            if (!hasTarget() && npc.hasOption("Attack"))
                setTarget(npc.name)
        }
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("over one of your items."))
            showNotification("Golden Beam", event.message.substring(event.message.indexOf(':') + 2).replace("</col>", ""))
    }
}

object Heal: State<Combat>() {
    override suspend fun Combat.checkNext() = if (healthPercent > 30.0) Attack else null
    override suspend fun Combat.stateLoop() = waitThenDelayUntil(1200, 20000) { healthPercent > 30.0 }
}
