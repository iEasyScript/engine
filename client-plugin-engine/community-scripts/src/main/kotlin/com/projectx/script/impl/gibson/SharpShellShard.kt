package com.projectx.script.impl.gibson

import org.projectx.core.game.skill.Skill
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*
import com.projectx.util.random

@ScriptDescription(
        name = "Sharp Shell Shard",
        version = "1.0.0",
        author = "Gibson",
        description = "Ignites Sharp shell shard for Firemaking"
)
class SharpShellShard : Script() {
    override suspend fun loop() {
        if (hasActiveMakeXProgress) return

        if (timeSinceLastXpDrop > random(1802, 2359)) {
            if (!makeXOpen && inventory.hasItem("Sharp shell shard")) {
                inventory.clickItem("Sharp shell shard", "Ignite")
                delayUntil { makeXOpen }
                return
            }
            continueMakeX()
            waitForXPDrop(Skill.FIREMAKING)
        }
    }
}