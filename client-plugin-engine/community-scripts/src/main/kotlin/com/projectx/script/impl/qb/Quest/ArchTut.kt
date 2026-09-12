package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.api.*
import com.projectx.util.random
import com.projectx.traversal.Traversal.Companion.traversal

class ArchTut : State<DebugScript>() {
    companion object {
        enum class QuestDialogues(val number: Int, val text: String) {
            PLACEHOLDER(2, "Placeholder!!!!")
        }

        private val startCoord = Tile.of(3384, 3392, 0)
        private val startArea = Area.Circular(startCoord, 10.0)
        private val benchCoord = Tile.of(3357, 3395, 0)
        private val benchArea = Area.Circular(benchCoord, 10.0)
        private val veculciaCoord = Tile.of(3342, 3384, 0)
        private val veculciaArea = Area.Circular(veculciaCoord, 10.0)
        private val monolithCoord = Tile.of(3362, 3382, 0)
        private val monolithArea = Area.Circular(monolithCoord, 10.0)
        private val mapCoord = Tile.of(3325, 3376, 0)
        private val mapArea = Area.Circular(mapCoord, 10.0)
    }

    override suspend fun DebugScript.checkNext(): State<DebugScript>? {
        val questVarp = varps.getVarBit(46463)
        QuestDialogs.resetDialogOptions()
        QuestDialogs.updateQuestDialogOptions(QuestDialogues.entries.map { it.text })

        if (QuestDialogs.isDialogOpen()) {
            return QuestDialogState()
        }

        if (localPlayer.isMoving) {
            return null
        }

        println("Quest varp: $questVarp")

        if (questVarp == 0) {
            println("Starting quest... Arch Tutorial")
            if (startArea.contains(localPlayer.tile)) {
                talkToGuildMaster()
            } else {
                return moveToStart
            }
            return null
        }

        when (questVarp) {
            5, 35, 45 -> {
                talkToGuildMaster()
            }

            10 -> {
                if (inventory.hasItem("Bronze mattock")) {
                    inventory.firstOrNull { it.name == "Bronze mattock" }?.click("Add to tool belt")
                    delay(random(600, 1200))
                    return null
                }
            }

            15 -> {
            }

            20 -> {
                if (localPlayer.isAnimating) return null
                val soil = findClosestObject("Senntisten soil", 20)
                soil?.interact("Uncover")
                delay(random(600, 1200))
            }

            25 -> {
                if (localPlayer.isAnimating) return null
                val soilExc = findClosestObject("Centurion remains", 20)
                soilExc?.interact("Excavate")
                delay(random(600, 1200))
            }

            30 -> {
                if (inventory.hasItem(49741)) {
                    inventory.firstOrNull { it.id == 49741 }?.click("Inspect")
                    delay(random(1200, 1800))
                }
                talkToGuildMaster()
                delay(random(600, 1200))
            }

            40 -> {
                if (interfaces.isOpen(1370)) {
                    IFSlot(1370, 30, -1).click()
                    delay(random(600, 1200))
                    return null
                }
                if (localPlayer.isAnimating) return null
                val mesh = findClosestObject("Mesh", 60)
                mesh?.interact("Screen")
                delay(random(600, 1200))
            }

            50 -> {
                if (benchArea.contains(localPlayer.tile)) {
                    if (interfaces.isOpen(660)) {
                        IFSlot(660, 30, -1).click()
                        delay(1200, 200)
                        IFSlot(660, 14, -1).click()
                        delay(1200, 200)
                        return null
                    }
                    val storage = findClosestObject("Material storage container", 60)
                    storage?.interact("Store")
                    delay(random(600, 1200))
                } else {
                    walkTo(benchCoord, true)
                    waitThenDelayUntil(1200, 5000) { benchArea.contains(localPlayer.tile) }
                }
            }

            55 -> {
                if (localPlayer.isAnimating) return null
                if (benchArea.contains(localPlayer.tile)) {
                    if (interfaces.isOpen(1370)) {
                        delay(random(600, 1200))
                        return null
                    }
                    val bench = findClosestObject("Archaeologist's workbench", 60)
                    bench?.interact("Restore")
                    delay(random(600, 1200))
                } else {
                    walkTo(benchCoord, true)
                    waitThenDelayUntil(1200, 5000) { benchArea.contains(localPlayer.tile) }
                }
            }

            60 -> {
                if (startArea.contains(localPlayer.tile)) {
                    talkToGuildMaster()
                } else {
                    walkTo(startCoord, true)
                    waitThenDelayUntil(1200, 5000) { startArea.contains(localPlayer.tile) }
                }
            }

            65 -> {
                if (veculciaArea.contains(localPlayer.tile)) {
                    if (interfaces.isOpen(656)) {
                        IFSlot(656, 25, 0).click()
                        delay(random(600, 1200))
                        return null
                    }
                    val velucia = findClosestNPC("Velucia", 20)
                    velucia?.interact("Talk to")
                    delay(random(600, 1200))
                } else {
                    return moveToVelucia
                }
            }

            75 -> {
                if (startArea.contains(localPlayer.tile)) {
                    talkToGuildMaster()
                } else {
                    return moveToStart
                }
            }

            80 -> {
                if (monolithArea.contains(localPlayer.tile)) {
                    val monolith = findClosestObject("Mysterious monolith", 60)
                    monolith?.interact("Interact")
                    delay(random(600, 1200))
                } else {
                    walkTo(monolithCoord, true)
                    waitThenDelayUntil(1200, 5000) { monolithArea.contains(localPlayer.tile) }
                }
            }

            85 -> {
                if (interfaces.isOpen(691)) {
                    IFSlot(691, 86, -1).click()
                    delay(random(1200, 1800))
                    IFSlot(691, 72, -1).click()
                    delay(random(1200, 1800))
                    IFSlot(691, 165, -1).click()
                    delay(random(1200, 1800))
                    IFSlot(691, 161, -1).click()
                } else {
                    val monolith = findClosestObject("Mysterious monolith", 60)
                    monolith?.interact("Manage powers")
                    delay(random(600, 1200))
                }
            }

            90 -> {
                if (mapArea.contains(localPlayer.tile)) {
                    talkToGuildMaster()
                } else {
                    walkTo(mapCoord, true)
                    waitThenDelayUntil(1200, 5000) { mapArea.contains(localPlayer.tile) }
                }
            }

            95 -> {
                if (mapArea.contains(localPlayer.tile)) {
                    talkToGuildMaster()
                } else {
                    walkTo(mapCoord, true)
                    waitThenDelayUntil(1200, 5000) { mapArea.contains(localPlayer.tile) }
                }
            }

            96 -> {
                if (interfaces.isOpen(1594)) {
                    IFSlot(1594, 23, 0).click()
                    delay(random(600, 1200))
                    IFSlot(1594, 58, -1).click()
                    delay(random(600, 1200))
                    return null
                }
                val ezreal = findClosestNPC("Ezreal", 60)
                ezreal?.interact("Talk to")
                delay(random(600, 1200))
            }

            97 -> {
                talkToGuildMaster()
            }
        }

        return null
    }

    override suspend fun DebugScript.stateLoop() {
    }

    private suspend fun DebugScript.talkToGuildMaster() {
        val guildMaster = findClosestNPC("Acting Guildmaster Reiniger", 80)
        guildMaster?.interact("Talk to")
        delay(random(600, 1200))
    }

    private fun println(message: String) {
        if (DebugScript.instance.debug) {
            kotlin.io.println("[ArchTut] $message")
        }
    }

    private val pathToStart: List<Tile> = listOf(
        Tile.of(3213, 3375, 0),
        Tile.of(3272, 3373, 0),
        Tile.of(3319, 3366, 0),
        Tile.of(3333, 3365, 0),
        Tile.of(3335, 3378, 0),
        Tile.of(3358, 3395, 0),
        Tile.of(3384, 3392, 0)
    )

    private val moveToStart
        get() = traversal(
            ArchTut(),
            { interfaces.isOpen(1188) }) {
            chebychevPath(
                localPlayer.tile,
                pathToStart,
                fallback = { useLodestone(Lodestone.VARROCK) },
                reached = { startArea.contains(localPlayer.tile) }
            )
        }

    private val pathToVelucia: List<Tile> = listOf(
        Tile.of(3384, 3390, 0),
        Tile.of(3345, 3387, 0)
    )

    private val moveToVelucia
        get() = traversal(
            ArchTut(),
            { interfaces.isOpen(1188) }) {
            chebychevPath(
                localPlayer.tile,
                pathToVelucia,
                fallback = {
                    chebychevPath(
                        localPlayer.tile,
                        pathToStart,
                        fallback = { useLodestone(Lodestone.VARROCK) },
                        reached = { startArea.contains(localPlayer.tile) }
                    )
                },
                reached = { veculciaArea.contains(localPlayer.tile) }
            )
        }
}
