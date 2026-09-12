package com.projectx.script.impl.BugAbuser.Skilling.AIOProgressive

import org.projectx.core.game.skill.Skill
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*

@ScriptDescription(
    name = "Progressive Cooker",
    version = "1.0.0",
    author = "BugAbuser",
    description = "Progressive Cooker - cooks down in the rogues den in taverly",
)
class ProgressiveCooker : Script() {
    private var deposit = false

    override suspend fun loop() {
        val validFish = Fish.entries.filter { it.canCook() }.map { it.fishName }
        val hasFish = inventory.hasItem(*validFish.toTypedArray())

        if (hasFish) {
            deposit = false
            while (inventory.hasItem(*validFish.toTypedArray())) {
                if (!makeXOpen) {
                    if (interactClosestReachableObject("Fire", "Cook at")) {
                        delay(600)
                        delayUntil(2000) { makeXOpen }
                    } else {
                        break
                    }
                }
                if (makeXOpen) {
                    continueMakeX()
                    waitForXPDrop(Skill.COOKING, 1800)
                }
            }
        } else {
            if (!bankOpen) {
                openClosestBank()
                delayUntil(3000) { bankOpen }
                return
            }
            if (inventory.isEmpty && !deposit) {
                deposit = true
            } else if (!deposit || inventory.isFull) {
                depositAllInventory()
                delayUntil(5000) { inventory.isEmpty }
                deposit = true
            } else {
                validFish.forEach { fishName ->
                    val bankQuantity = bank.count(fishName)
                    if (bankQuantity > 0) {
                        withdrawBankItem(fishName, 0)
                        delay(800)
                    }
                }
                delayUntil(2000) { inventory.hasItem(*validFish.toTypedArray()) }
            }
        }
        delay(200)
    }

    private enum class Fish(val fishName: String, val level: Int) {
        SHRIMP("Raw shrimps", 1),
        ANCHOVIES("Raw anchovies", 1),
        SARDINE("Raw sardine", 1),
        HERRING("Raw herring", 5),
        MACKEREL("Raw mackerel", 10),
        TROUT("Raw trout", 15),
        COD("Raw cod", 18),
        PIKE("Raw pike", 20),
        SALMON("Raw salmon", 25),
        TUNA("Raw tuna", 30),
        RAINBOW_FISH("Raw rainbow fish", 35),
        LOBSTER("Raw lobster", 40),
        BASS("Raw bass", 43),
        DESERT_SOLE("Raw desert sole", 52),
        CATFISH("Raw catfish", 60),
        MONKFISH("Raw monkfish", 62),
        GHOSTLY_SOLE("Raw ghostly sole", 66),
        BLUBBER_FISH("Raw green blubber jellyfish", 72),
        BELTFISH("Raw beltfish", 72),
        SWORDFISH("Raw swordfish", 50),
        SHARK("Raw shark", 80),
        SEA_TURTLE("Raw sea turtle", 82),
        BLUE_BLUBBER_FISH("Raw blue blubber jellyfish", 85),
        MANTA_RAY("Raw manta ray", 91);

        fun canCook(): Boolean {
            return getCurrentLevel(Skill.COOKING) >= level
        }
    }
}