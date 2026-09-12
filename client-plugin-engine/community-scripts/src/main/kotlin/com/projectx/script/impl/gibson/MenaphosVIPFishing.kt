package com.projectx.script.impl.gibson

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestNPC
import com.projectx.script.api.interactClosestReachableObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer

@ScriptDescription(
    name = "Menaphos Fishing",
    version = "1.0.0",
    author = "Gibson",
    description = "Baits fishing and deposits the fish in Menaphos VIP area"
)
class MenaphosVIPFishing : Script() {
    override suspend fun loop() {
        if (inventory.isFull) {
            if (interactClosestReachableObject("Load Last Preset from"))
                delayUntil(15000) { !inventory.isFull }
            return
        }
        if (localPlayer.isAnimating) return
        val bait = inventory.getItem("Fishing bait")
        if(bait == null) {
            println("No fishing bait found in inventory. Stopping script.")
            stop()
            return
        }
        if (interactClosestNPC("Bait")) {
            delayUntil(15000) { localPlayer.isAnimating }
            delayUntil(120000) { !localPlayer.isAnimating || inventory.isFull }
        }
    }
}