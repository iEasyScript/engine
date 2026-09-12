package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.findNPC
import com.projectx.util.gaussian

private const val BUTTERFLY = "Guthixian butterfly"
private const val CATCH_TIMEOUT_MILLIS = 20000L

@ScriptDescription(
    name = "Spirit Attraction Potion",
    version = "1.0.0",
    author = "Trent",
    description = "Acts as a spirit attraction potion to claim fire spirits, forge phoenixes, seren spirits, etc"
)
class SpiritAttractionPotion : Script() {
    var startTime = 0L

    override fun onStart() {
        startTime = System.currentTimeMillis()
    }

    val attractionNpcs = setOf(
        "Seren spirit",
        "Forge phoenix",
        "Divine blessing",
        "Elder chronicle",
        "Fire spirit",
        "Divine fire spirit",
        "Divine forge phoenix",
        "Manifested knowledge",
        "Divine carpet dust",
        "Catalyst of alteration",
        BUTTERFLY,
        "Blessed Fire Spirits"
    )

    override suspend fun loop() {
        val npc = findNPC { attractionNpcs.contains(it.name) } ?: return delay(1500, 2500)
        if (npc.name != BUTTERFLY) {
            if (npc.interact(0)) delay(gaussian(3000, 2500))
            return
        }
        ScriptExecutor.pauseOthers(this)
        try {
            if (npc.interact(0))
                delayUntil(CATCH_TIMEOUT_MILLIS) { findNPC { it.name == BUTTERFLY } == null }
        } finally {
            ScriptExecutor.resumeOthers(this)
        }
    }

    override fun onStop() {
        ScriptExecutor.resumeOthers(this)
        super.onStop()
    }
}