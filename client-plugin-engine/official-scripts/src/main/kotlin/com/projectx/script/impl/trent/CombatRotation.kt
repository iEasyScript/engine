package com.projectx.script.impl.trent

import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.localPlayer
import com.projectx.script.impl.trent.combatutils.optimizedNecromancyRevo
import com.projectx.script.impl.trent.combatutils.optimizedNecromancyRotation

enum class RotationType(val rotationFunction: suspend Script.() -> Unit) {
    NONE({}),
    NECRO_OPTIMAL(Script::optimizedNecromancyRotation),
    NECRO_REVOLUTION(Script::optimizedNecromancyRevo)
}

@ScriptDescription(
    name = "Combat Rotation",
    version = "1.0.0",
    author = "Trent",
    description = "Performs various combat rotations on the targeted NPC"
)
class CombatRotation : Script(), ConfigurableScript {
    val rotation = EnumConfigItem(
        name = "Rotation type",
        description = "Which rotation the script should do on a target",
        enumValues = RotationType.entries.toTypedArray(),
        initialValue = RotationType.NONE
    )

    override suspend fun loop() {
        if (!localPlayer.isInteracting || healthCurrent <= 0) return
        findClosestNPC { localPlayer.interactingWith(it) && it.maxHealth > 0 && it.currentHealth > 0 } ?: return
        rotation.value.rotationFunction(this)
    }
}