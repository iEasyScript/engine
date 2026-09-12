package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.api.*
import com.projectx.util.random

class NewFoundation : State<DebugScript>() {

    companion object {
        enum class QuestDialogues(val number: Int, val text: String) {
            YES(2, "Yes."),
            SIGN_ME_UP(2, "Sign me up!")
        }

        private val startCord = Tile.of(3217, 3473, 0)
        private val startArea = Area.Circular(startCord, 10.0)

        private val instanceCord = Tile.of(6673, 4305, 0)
        private val instanceArea = Area.Circular(instanceCord, 12.0)

        private val fortForinthry = Tile.of(3303, 3526, 0)
        private val fortArea = Area.Circular(fortForinthry, 12.0)

        private val aster = Tile.of(3216, 3405, 0)
        private val asterArea = Area.Circular(aster, 10.0)

        private val overseer = Tile.of(3086, 3421, 0)
        private val overseerArea = Area.Circular(overseer, 10.0)

        private val fatherFlint = Tile.of(3085, 3250, 0)
        private val fatherFlintArea = Area.Circular(fatherFlint, 10.0)

        private val nearBill = Tile.of(3290, 3555, 0)
        private val nearBillArea = Area.Circular(nearBill, 10.0)
    }

    override suspend fun DebugScript.checkNext(): State<DebugScript>? {
        val questVarp = varps.getVarBit(52592)

        QuestDialogs.resetDialogOptions()
        QuestDialogs.updateQuestDialogOptions(QuestDialogues.entries.map { it.text })
        if (QuestDialogs.isDialogOpen()) return QuestDialogState()

        println("QuestVarp: "+questVarp)

        if (localPlayer.isMoving) return null

        when (questVarp) {
            0 -> {
                log("Starting quest... New Foundations")
                val door = findClosestObject("Door", 10)
                if (door != null && instanceArea.contains(localPlayer.tile)) {
                    door.interact("Open")
                    delay(random(900, 1400))
                    talkTo("King Roald")
                    return null
                }
                if (startArea.contains(localPlayer.tile)) {
                    val portal = findClosestObject("New Foundations", 20)
                    if (portal != null) {
                        portal.interact("Start Quest")
                        delay(random(700, 1000))
                    }
                } else {
                    val instanceObj = findClosestObject("Instance", 30)
                    if (instanceObj != null) {
                        val maybeDoor = findClosestObject("Door", 10)
                        if (maybeDoor != null) {
                            maybeDoor.interact("Open")
                            delay(random(900, 1400))
                            talkTo("King Roald")
                        }
                    } else {
                        DebugScript.instance.moveTo(startCord)
                    }
                }
            }

            5 -> {
            }

            10 -> {
                val instanceObj = findClosestObject("Instance", 20)
                if (instanceObj != null) {
                    instanceObj.interact("Leave")
                    delay(random(1000, 1600))
                }
            }

            15 -> {
                if (!fortArea.contains(localPlayer.tile)) {
                    DebugScript.instance.moveTo(fortForinthry)
                } else {
                    val portal = findClosestObject("New Foundations", 20)
                    if (portal != null) {
                        portal.interact("Continue")
                        delay(random(600, 900))
                    } else {
                        val zombie = findClosestNPC("Armoured zombie", 20)
                        if (zombie != null && !inCombat) {
                            zombie.interact("Attack")
                            delay(random(600, 900))
                        }
                    }
                }
            }

            20 -> talkTo("Bill")

            25 -> {
                val hotspot = findClosestObject("Optimal Construction hotspot", 60)
                if (hotspot != null) {
                    hotspot.interact("Build")
                    delay(random(800, 1200))
                } else {
                    val chest = findClosestObject("Bank chest", 60)
                    if (chest != null) {
                        chest.interact("Use")
                        delay(random(800, 1200))
                    } else {
                        val blueprints = findClosestObject("Fort Forinthry blueprints", 60)
                        if (blueprints != null) {
                            blueprints.interact("Check plans")
                            delay(random(800, 1200))
                            if (interfaces.isOpen(1370)) {
                                IFSlot(1370, 30, -1).click()
                                delay(random(600, 900))
                            }
                        }
                    }
                }
            }

            30 -> {
            }

            35 -> {
                if (!asterArea.contains(localPlayer.tile)) DebugScript.instance.moveTo(aster) else talkTo("Aster")
            }

            40 -> {
                if (!overseerArea.contains(localPlayer.tile)) DebugScript.instance.moveTo(overseer) else talkTo("Overseer Siv")
            }

            45 -> {
                if (!fatherFlintArea.contains(localPlayer.tile)) DebugScript.instance.moveTo(fatherFlint) else talkTo("Father Flint")
            }

            50 -> {
                if (!nearBillArea.contains(localPlayer.tile)) DebugScript.instance.moveTo(nearBill) else talkTo("Bill")
            }

            55 -> {
                val hotspot = findClosestObject("Optimal Construction hotspot", 20)
                if (hotspot != null) {
                    hotspot.interact("Build")
                    delay(random(800, 1200))
                } else {
                    val chest = findClosestObject("Bank chest", 20)
                    chest?.interact("Use")
                }
            }

            60 -> talkTo("Bill")
        }

        return null
    }

    override suspend fun DebugScript.stateLoop() {
    }

    private fun log(msg: String) {
        if (DebugScript.instance.debug) println("[NewFoundation] $msg")
    }

    private suspend fun DebugScript.talkTo(npcName: String) {
        val n = findClosestNPC(npcName, 60)
        n?.interact("Talk to")
        delay(random(600, 1000))
    }
}
