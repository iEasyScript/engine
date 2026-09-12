package com.projectx.script.impl.qb.Quest

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.varps

class QuestDialogs {
    companion object {
        var autoCloseEnable = false
        var optionNumbers = mutableListOf<DialogOption>()
        var usedOptionNumbers = mutableListOf<DialogOption>()
        var hasAnyOption = false
        var optionNumber = -1
        var questDialogOptions = mutableListOf<String>()

        fun dialog1188() {
            val number = getDialogueNumber()

            val option = when (number) {
                1 -> 8
                2 -> 13
                3 -> 18
                4 -> 23
                5 -> 28
                else -> -1
            }

            if (option != -1) {
                println("Interacting with option: $option")
                IFSlot(1188, option, -1).dialogueContinue()
            } else {
                if (optionNumbers.isEmpty() && !hasAnyOption) {
                    if (autoCloseEnable) {
                        println("Option not found - Closing dialog")
                        IFSlot(1188, 4, -1).click()
                    }
                } else {
                    println(hasAnyOption.toString())
                    if (hasAnyOption)
                        optionNumber = 1
                    println("Any option instruction - interacting with $optionNumber")
                    when (optionNumber) {
                        1 -> IFSlot(1188, 8, -1).dialogueContinue()
                        2 -> IFSlot(1188, 13, -1).dialogueContinue()
                        3 -> IFSlot(1188, 18, -1).dialogueContinue()
                        4 -> IFSlot(1188, 23, -1).dialogueContinue()
                        5 -> IFSlot(1188, 28, -1).dialogueContinue()
                        else -> IFSlot(1188, 4, -1).click()
                    }
                }
            }
        }

        fun isDialogOpen(): Boolean {
            return interfaces.isOpen(1188) || interfaces.isOpen(1184) || interfaces.isOpen(1191) ||
                    interfaces.isOpen(1193) || interfaces.isOpen(1500) || interfaces.isOpen(1189) ||
                    interfaces.isOpen(1186) || interfaces.isOpen(720) || interfaces.isOpen(1370) ||
                    interfaces.isOpen(1251) || interfaces.isOpen(847) || interfaces.isOpen(1187) || interfaces.isOpen(94) ||
                    varps.getVarBit(21222) == 1 || interfaces.isOpen(1244)
        }

        fun dialog1188pick(num: Int) {
            val option = when (num) {
                1 -> 8
                2 -> 13
                3 -> 18
                4 -> 23
                5 -> 28
                else -> -1
            }

            if (option != -1) {
                IFSlot(1188, option, -1).dialogueContinue()
            }
        }

        fun pressDialog() {
            when {
                interfaces.isOpen(1187) && interfaces.getComponent(1187,20)!=null-> IFSlot(1187, 20, -1).dialogueContinue()
                interfaces.isOpen(94) && interfaces.getComponent(94,6)!=null-> {
                    IFSlot(94, 6, -1).click()
                }

                interfaces.isOpen(1188) -> {
                    if (varps.getVarBit(5326) == 25) {
                        val num = varps.getVarBit(5327)
                        when (num) {
                            1, 5, 9 -> dialog1188pick(1)
                            2, 6, 10 -> dialog1188pick(2)
                            3, 11, 7 -> dialog1188pick(3)
                            4, 8, 12 -> dialog1188pick(4)
                        }
                    } else {
                        dialog1188()
                    }

                }

                interfaces.isOpen(1184) && interfaces.getComponent(1184,15)!=null-> {
                    IFSlot(1184, 15, -1).dialogueContinue()
                }
                interfaces.isOpen(1224) && interfaces.getComponent(1224,21)!=null-> IFSlot(1224, 21, -1).click()
                interfaces.isOpen(1244) && interfaces.getComponent(1244,21)!=null-> IFSlot(1244, 21, -1).click()

                interfaces.isOpen(1187) && interfaces.getComponent(1187,3)!=null-> IFSlot(1187, 3, -1).click()
                interfaces.isOpen(1191) && interfaces.getComponent(1191,15)!=null-> IFSlot(1191, 15, -1).dialogueContinue()
                interfaces.isOpen(1193) -> println("@ me in disc if u see this")
                interfaces.isOpen(1500) && interfaces.getComponent(1500,409)!=null-> IFSlot(1500, 409, -1).click()
                interfaces.isOpen(1189) && interfaces.getComponent(1189,19)!=null-> IFSlot(1189, 19, -1).dialogueContinue()
                interfaces.isOpen(1186) && interfaces.getComponent(1186,8)!=null-> IFSlot(1186, 8, -1).dialogueContinue()
                interfaces.isOpen(720) && interfaces.getComponent(720,1)!=null-> IFSlot(720, 1, -1).dialogueContinue()

                interfaces.isOpen(1370) && interfaces.getComponent(1370,30)!=null-> IFSlot(1370, 30, -1).dialogueContinue()
                interfaces.isOpen(847) && interfaces.getComponent(847,3)!=null-> IFSlot(847, 3, -1).click()
                interfaces.isOpen(960) && interfaces.getComponent(960,3)!=null-> IFSlot(960, 3, -1).click()
                continueHandler() -> IFSlot(955, 15, -1).click()
            }
        }

        fun setAnyOption(b: Boolean) {
            hasAnyOption = b
            optionNumber = 1
        }

        fun setAnyOption(b: Boolean, num: Int) {
            hasAnyOption = b
            optionNumber = num
        }

        fun resetDialogOptions() {
            optionNumber = -1
            optionNumbers = mutableListOf()
            usedOptionNumbers = mutableListOf()
        }

        fun updateQuestDialogOptions(dialogOptions: List<String>) {
            questDialogOptions = dialogOptions.toMutableList()
        }

        fun getDialogueNumber(): Int {
            val dialogOptions = dialogueOptions

            if (dialogOptions.isNotEmpty() && DebugScript.instance.currentQuest != DebugScript.Quest.TEST_DONTSELECT) {
                println("Dialog Options: $dialogOptions")

                for (dialogue in questDialogOptions) {
                    println(dialogue)

                    if (dialogOptions.containsKey(dialogue)) {
                        var searchSpecificDialogOption = dialogue

                        println("Checking for $searchSpecificDialogOption")

                        for ((optionText, slot) in dialogOptions) {
                            if (optionText == searchSpecificDialogOption) {
                                println("searchSpecificDialogOption Exists")
                                val option = when (slot.componentId) {
                                    8 -> 1
                                    13 -> 2
                                    18 -> 3
                                    23 -> 4
                                    28 -> 5
                                    else -> -1
                                }

                                if (option != -1) {
                                    val dialogOption = DialogOption().apply {
                                        this.option = searchSpecificDialogOption
                                        this.optionNumber = option
                                    }

                                    val alreadyOnList = optionNumbers.any {
                                        it.option == dialogOption.option && it.optionNumber == dialogOption.optionNumber
                                    }

                                    if (!alreadyOnList) {
                                        println("Option not found, adding it")
                                        optionNumbers.add(dialogOption)
                                    } else {
                                        println("Option already on list, skipping")
                                    }
                                    break
                                }
                            }
                        }
                    }
                }

                usedOptionNumbers.forEach { used ->
                    optionNumbers.removeAll { it.option == used.option }
                }

                if (optionNumbers.isNotEmpty()) {
                    println("Before sorting")
                    optionNumbers.sortBy { it.optionNumber }
                    println("After sorting")
                }

                return when {
                    optionNumbers.size > 1 -> {
                        println("Interacting with option: ${optionNumbers[0].option} Option: ${optionNumbers[0].optionNumber}")
                        val optionValue = optionNumbers[0]
                        usedOptionNumbers.add(optionValue)
                        optionValue.optionNumber
                    }

                    optionNumbers.size == 1 -> {
                        println("Interacting with option: ${optionNumbers[0].option} Option: ${optionNumbers[0].optionNumber}")
                        usedOptionNumbers.add(optionNumbers[0])
                        optionNumbers[0].optionNumber
                    }

                    else -> {
                        println("No option found - Returning -1")
                        -1
                    }
                }
            }

            println("Default Returning -1")
            return -1
        }

        fun hasItem(item: String): Boolean {
            return inventory.hasItem(item)
        }

        fun continueHandler(): Boolean {
            val thing = interfaces.getComponent(955, 16)
            return thing != null && !thing.text.isNullOrEmpty() && thing.text.isNotBlank()
        }

        fun println(msg: String) {
            kotlin.io.println(msg)
        }
    }

    class DialogOption {
        var option: String = ""
        var optionNumber: Int = 0
    }

    enum class AutoCloseDialogs(val phrase: String) {
        REDBERRY_PIE("redberry pie. They REALLY like redberry pie."),
        BARAEK("If I were you I would talk to Baraek,"),
        BLACK_ARM("The ruthless and notorious Black Arm "),
        PET_SHOP_OWNER("Is there anything else i can help you with?"),
        KING_RONALD("I've told you everything I know."),
        DUTCHNESS("Let us leave the duchess alone,"),
        VELIAF("While you're there, you could see if that murderer"),
        FLORIN("Listen, if you do manage to find a way to get a place here,"),
        RAZVAN("Hmm, perhaps you'd consider fixing up the general store."),
        CORNELIUS("Fix the floopin bank would ya!"),
        AUREL("Please can you fix the bank booth first"),
        FATHER_URHNEY("Can I have a look at it?"),
        VERTIDA("What should I do now?");
    }

    enum class QuestInstruction(val text: String, val quest: DebugScript.Quest) {
    }
}