package com.projectx.script.impl.gibson

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestNPC
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer

@ScriptDescription(
        name = "Menaphos Port Fishing",
        version = "1.0.0",
        author = "Billy",
        description = "Baits fishing and deposits the fish in Menaphos Port area"
)
class MenaphosPortFishing : Script() {
    override suspend fun loop() {
        if (inventory.isFull) {
            if (interactClosestObject("Deposit all fish"))
                println("Depositing fish...")
                delayUntil(15000) { !inventory.hasItem(40287) }
                return
            }
            if (localPlayer.isAnimating) return
            val bait = inventory.getItem("Fishing bait")
            if (bait == null) {
                println("No fishing bait found in inventory. Stopping script.")
                stop()
                return
            }
            if (interactClosestNPC("Bait")) {
                println("Baiting fishing spot...")
                delayUntil(15000) { localPlayer.isAnimating }
                delayUntil(120000) { !localPlayer.isAnimating || inventory.isFull }
            }
        }
    }
