package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import world.gregs.voidps.path.PathFinder
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.collision.CollisionStrategies
import world.gregs.voidps.path.toTiles
import com.projectx.script.*
import com.projectx.script.api.Lodestone
import com.projectx.script.api.localPlayer
import com.projectx.script.api.useLodestone
import com.projectx.script.api.walkTo
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.impl.qb.Quest.DebugScript.Companion.running
import com.projectx.util.random

@ScriptDescription(
    name = "Quest Helper",
    version = "1.1",
    author = "Billythebob, Query",
    description = "Comprehensive quest helper with dialog management and automated quest execution."
)
class DebugScript : StateMachineScript<DebugScript>(), ConfigurableScript {
    val selectedQuest = EnumConfigItem(
        name = "Quest",
        description = "Select which quest to run",
        enumValues = Quest.entries.toTypedArray(),
        initialValue = Quest.TEST_DONTSELECT
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = true
    )

    val autoCloseDialogs = BooleanConfigItem(
        name = "Auto Close Dialogs",
        description = "Automatically close dialogs that match known patterns",
        initialValue = true
    )

    val currentQuest: Quest
        get() = selectedQuest.value

    val debug: Boolean
        get() = enableDebug.value

    val autoClose: Boolean
        get() = autoCloseDialogs.value

    var questsCompleted = 0
    var dialogsProcessed = 0

    companion object {
        var running = false
        var message = ""
        lateinit var instance: DebugScript
    }

    init {
        instance = this
    }

    suspend fun moveTo(location: Tile): Boolean {
        val route = PathFinder(
            flags = WorldCollision.allFlags,
            searchMapSize = 1024,
            useRouteBlockerFlags = true,
            moveNear = false
        ).findPath(
            localPlayer.tile.x,
            localPlayer.tile.y,
            location.x,
            location.y,
            localPlayer.tile.plane,
            collision = CollisionStrategies.NORMAL,
            srcSize = 2,
            destWidth = 1,
            destHeight = 1
        )

        if (route.failed) {
            useLodestone(Lodestone.AL_KHARID)
            delay(600, 1800)

            return false
        }

        route.toTiles().forEach {
            if (it.getDistance(localPlayer.tile) > 7) {
                walkTo(it, true)
                delayUntil { it.getDistance(localPlayer.tile) < 4 }
            }
        }
        return true
    }

    override fun getStartState() = QuestMainState()

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (event is Chat && debug) {
            println(event.message)

            when {
                event.message.contains("Delivered 0/5 presents to citizens of Gielinor") -> {
                    message = event.message
                }

                event.message.contains("Delivered 1/5 presents to citizens of Gielinor") -> {
                    message = event.message
                }

                event.message.contains("Delivered 2/5 presents to citizens of Gielinor") -> {
                    message = event.message
                }

                event.message.contains("Delivered 3/5 presents to citizens of Gielinor") -> {
                    message = event.message
                }

                event.message.contains("Delivered 4/5 presents to citizens of Gielinor") -> {
                    message = event.message
                }
            }
        }
    }

    fun onConfigUpdated() {
        println("Config updated")
        this::class.java.declaredFields.filter { ConfigItem::class.java.isAssignableFrom(it.type) }.forEach { field ->
            field.isAccessible = true
            val configItem = field.get(this) as? ConfigItem<*>
            val name = configItem?.name
            val value = configItem?.value
            println("$name: $value")
        }
    }

    fun initialize() {
        instance = this
    }

    enum class Quest(val questId: Int) {
        F2P_LODESTONES(9999999),
        P2P_LODESTONES(9999999),
        ARCH_TUTORIAL(999999),
        ANACHRONIA_TUT(99999999),
        NEW_FOUNDATION(489),
        VIOLET_IS_BLUE(400),
        VIOLET_IS_BLUE_TOO(453),
        TEST_DONTSELECT(135);

        companion object {
            fun getByQuestId(questId: Int): Quest? {
                return entries.find { it.questId == questId }
            }
        }
    }
}

class QuestMainState : State<DebugScript>() {
    override suspend fun DebugScript.checkNext(): State<DebugScript>? {
        println("Current quest: ${currentQuest.name}")

        if (QuestDialogs.isDialogOpen()) {
            return QuestDialogState()
        }

        if (currentQuest == DebugScript.Quest.F2P_LODESTONES) {
            println("F2P_LODESTONES")
            return F2PLoadstone()
        }

        if (currentQuest == DebugScript.Quest.P2P_LODESTONES) {
            println("P2P_LODESTONES")
            return P2PLodestone()
        }

        if (currentQuest == DebugScript.Quest.ANACHRONIA_TUT) {
            println("ANACHRONIA_TUT")
            return AnachroniaTut()
        }

        if (currentQuest == DebugScript.Quest.ARCH_TUTORIAL) {
            println("ARCH_TUTORIAL")
            return ArchTut()
        }

        if (currentQuest == DebugScript.Quest.NEW_FOUNDATION) {
            println("NEW_FOUNDATION")
            return NewFoundation()
        }

        if (currentQuest == DebugScript.Quest.VIOLET_IS_BLUE) {
            println("VIOLET_IS_BLUE")
            return VioletIsBlue()
        }
        if(currentQuest == DebugScript.Quest.VIOLET_IS_BLUE_TOO) {
            println("VIOLET_IS_BLUE_TOO")
            return VioletIsBlueToo()
        }

        return null
    }

    override suspend fun DebugScript.stateLoop() {
        if (debug) {
            println("Quest Helper State: Main controller")
            println("Current quest: ${currentQuest.name}")
            println("Running: $running")
        }

        if (!running && currentQuest != DebugScript.Quest.TEST_DONTSELECT) {
            println("Starting quest: ${currentQuest.name}")
            running = true
        }

        if (!running) {
            delay(random(1000, 2000))
            return
        }

        delay(random(500, 1000))
    }
}

class QuestDialogState : State<DebugScript>() {
    override suspend fun DebugScript.checkNext(): State<DebugScript>? {
        if (!QuestDialogs.isDialogOpen()) {
            return QuestMainState()
        }
        return null
    }

    override suspend fun DebugScript.stateLoop() {
        if (debug) {
            println("Quest Helper State: Dialog handling")
        }

        QuestDialogs.pressDialog()
        dialogsProcessed++

        delay(random(400, 600))
    }
}
