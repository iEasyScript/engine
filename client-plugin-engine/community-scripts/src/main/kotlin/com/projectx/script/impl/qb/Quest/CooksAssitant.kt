package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.script.api.*
import com.projectx.util.random

class CooksAssitant {
    companion object {
        enum class QuestDialogues(val number: Int, val text: String) {
            IM_AFTER_SOME_TOP_QUALITY_MILK(1, "I'm after some top-quality milk."),
            IM_LOOKING_FOR_EXTRA_FINE_FLOWER(1, "I'm looking for extra fine flour."),
            DO_YOU_HAVE_ANY_OTHER_QUESTS(1, "Do you have any other quests for me?"),
            ANGRY_IT_MAKES_ME_ANGRY(1, "Angry! It makes me angry!"),
            WHAT_SEEMS_TO_BE_THE_PROBLEM(1, "What seems to be the problem?"),
            IM_FINE_THANKS(2, "I'm fine, thanks."),
            WHAT_WRONG(1, "What's wrong?"),
            I_GET_ON_IT(1, "I'll get right on it.")
        }

        private val startCoord = Tile.of(3207, 3215, 0)
        private val startArea = Area.Circular(startCoord, 5.0)
        private val cowCoord = Tile.of(3262, 3276, 0)
        private val cowArea = Area.Circular(cowCoord, 5.0)
        private val chickenCoord = Tile.of(3228, 3299, 0)
        private val chickenArea = Area.Circular(chickenCoord, 5.0)
        private val wheatCoord = Tile.of(3161, 3295, 0)
        private val wheatArea = Area.Circular(wheatCoord, 5.0)
        private val hopperCoord = Tile.of(3165, 3307, 2)
        private val hopperArea = Area.Circular(hopperCoord, 5.0)
        private val hopperCoord2 = Tile.of(3165, 3307, 0)
        private val hopperArea2 = Area.Circular(hopperCoord2, 5.0)

        private var claimedAll = false

        suspend fun quest() {
            val questVarp = varps.getVarBit(2492)

            if (localPlayer.isMoving) {
                return
            }

            if (QuestDialogs.isDialogOpen()) {
                return
            }

            QuestDialogs.resetDialogOptions()
            QuestDialogs.updateQuestDialogOptions(QuestDialogues.entries.map { it.text })

            when (questVarp) {
                0 -> {
                    println("Starting quest... Cooks Assistant!")

                    if (!startArea.contains(localPlayer.tile) && !inventory.hasItem("Empty pot")) {
                        DebugScript.instance.moveTo(startCoord)
                        return
                    }

                    if (startArea.contains(localPlayer.tile) && !inventory.hasItem("Empty pot")) {
                        val item = groundItems.firstOrNull { it.name == "Empty pot" }

                        if (item != null) {
                            item.interact("Take")
                            delay(random(600, 1200))
                            if(areaLootOpen) {
                                val valuable = areaLoot.find { item.name == it.name }
                                println("Taking item")
                                if (valuable?.click(1) == true)
                                    delay(random(1500, 1800))
                            }

                            delay(random(600, 1200))
                        }
                        delay(random(600, 800))
                        return
                    }

                    if (!startArea.contains(localPlayer.tile)) {
                        DebugScript.instance.moveTo(startCoord)
                        return
                    }

                    val cook = findClosestNPC("Cook", 20)
                    cook?.interact("Talk-to")
                    delay(random(600, 800))
                }

                1 -> {
                    if (QuestDialogs.isDialogOpen()) {
                        return
                    }

                    if (!claimedAll) {
                        handleGatheringItems()
                        return
                    }

                    if (!startArea.contains(localPlayer.tile)) {
                        DebugScript.instance.moveTo(startCoord)
                        return
                    }

                    val cook = findClosestNPC("Cook", 20)
                    cook?.interact("Talk to")
                    delay(random(600, 800))
                }
            }
        }

        private suspend fun handleGatheringItems() {
            if (!inventory.hasItem("Top-quality milk")) {
                if (!cowArea.contains(localPlayer.tile) && inventory.hasItem("Empty pot")) {
                    DebugScript.instance.moveTo(cowCoord)
                    return
                }

                if (cowArea.contains(localPlayer.tile) && !inventory.hasItem("Bucket")) {
                    val bucket = groundItems.firstOrNull { it.name == "Bucket" }
                    if (bucket != null) {
                        bucket.interact("Take")
                        delay(random(600, 800))
                        if(areaLootOpen) {
                            val valuable = areaLoot.find { bucket.name == it.name }
                            println("Taking item")
                            if (valuable?.click(1) == true)
                                delay(random(1500, 1800))
                        }
                    }
                    return
                }

                if (cowArea.contains(localPlayer.tile) && inventory.hasItem("Bucket")) {
                    val dairyCow = findClosestNPC("Prized dairy cow", 20)
                    if (dairyCow != null) {
                        dairyCow.interact("Milk")
                        delay(random(600, 800))
                    } else {
                        println("Cow null")
                    }
                    return
                }
                return
            }

            if (!inventory.hasItem("Super large egg")) {
                if (!chickenArea.contains(localPlayer.tile)) {
                    DebugScript.instance.moveTo(chickenCoord)
                    return
                }

                if (chickenArea.contains(localPlayer.tile)) {
                    val egg = groundItems.firstOrNull { it.name == "Super large egg" }
                    if (egg != null) {
                        egg.interact("Take")
                        delay(random(600, 800))
                    }
                    return
                }
            }

            if (!inventory.hasItem("Extra fine flour")) {
                if (varps.getVarBit(8180) < 3) {
                    if (!inventory.hasItem("Wheat")) {
                        if (!wheatArea.contains(localPlayer.tile)) {
                            DebugScript.instance.moveTo(wheatCoord)
                        } else {
                            val wheat = findClosestObject("Wheat", 20)
                            wheat?.interact("Pick")
                            delay(random(700, 800))
                        }
                        return
                    }

                    if (!hopperArea2.contains(localPlayer.tile)) {
                        DebugScript.instance.moveTo(hopperCoord2)
                        return
                    } else {
                        val millie = findClosestNPC("Millie Miller", 20)
                        millie?.interact("Talk to")
                        delay(random(700, 800))
                        return
                    }
                } else {
                    if (inventory.hasItem("Wheat") && !hopperArea.contains(localPlayer.tile)) {
                        DebugScript.instance.moveTo(hopperCoord)
                        delay(random(700, 800))
                        return
                    }

                    if (varps.getVarBit(3193) != 1) {
                        if (hopperArea.contains(localPlayer.tile)) {
                            val hopper = findClosestObject("Hopper", 20)
                            if (hopper != null) {
                                val wheat = inventory.firstOrNull { it.name == "Wheat" }
                                if (wheat != null) {
                                    wheat.click("Use")
                                    delay(random(300, 500))
                                    hopper.interact("Use")
                                    delay(random(2000, 3000))
                                }
                            }

                            val lever = findClosestObject("Hopper controls", 20)
                            lever?.interact("Operate")
                            delay(random(1200, 2400))
                            return
                        }
                    } else {
                        if (hopperArea2.contains(localPlayer.tile)) {
                            val flourBin = findClosestObject("Flour bin", 20)
                            flourBin?.interact("Take-flour")
                            delay(random(1200, 2400))
                        } else {
                            DebugScript.instance.moveTo(hopperCoord2)
                        }
                        return
                    }
                }
            }

            claimedAll = true
        }

        private fun println(message: String) {
            if (DebugScript.instance.debug) {
                kotlin.io.println("[CooksAssistant] $message")
            }
        }

        private fun delay(ms: Int) {
            Thread.sleep(ms.toLong())
        }
    }
}