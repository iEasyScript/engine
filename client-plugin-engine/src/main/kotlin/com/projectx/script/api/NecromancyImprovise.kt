package com.projectx.script.api

import org.projectx.core.game.combat.Effect
import kotlin.math.floor

/**
 * The Necromancy filler a rotation falls back on once its scripted opener runs out: finish the target when the
 * stacks on hand can execute it, otherwise spend or build stacks in priority order, and basic-attack when nothing
 * better is ready.
 */
internal object NecromancyImprovise {
    private const val BASIC_ATTACK = "Basic Attack"
    private const val FINGER_OF_DEATH = "Finger of Death"
    private const val VOLLEY_OF_SOULS = "Volley of Souls"
    private const val ESSENCE_OF_FINALITY = "Essence of Finality"

    private const val EXECUTE_FLOOR = 30_000
    private const val VOLLEY_DAMAGE_PER_SOUL = 7_000
    private const val FINGER_DAMAGE = 14_000
    private const val FINGER_ADRENALINE = 60
    private const val ADRENALINE_PER_NECROSIS = 10
    private const val MAX_SOULS = 5

    private val putridZombie by lazy { effectNamed("Putrid Zombie") }
    private val deathGrasp by lazy { effectNamed("Death Grasp") }

    private val targetHealth: Int
        get() = bossHealthCurrent.takeIf { it > 0 } ?: combatTarget?.currentHealth ?: 0

    fun next(spend: Boolean): String {
        val adrenaline = adrenaline
        val souls = Effect.RESIDUAL_SOUL.stacks.takeIf { it > 3 } ?: 0
        val necrosis = Effect.NECROSIS.stacks
        val fingers = floor((adrenaline + necrosis * ADRENALINE_PER_NECROSIS) / FINGER_ADRENALINE).toInt()

        val health = targetHealth
        if (health > EXECUTE_FLOOR) {
            val overFloor = health - EXECUTE_FLOOR
            if (souls >= 3 && fingers >= 1 && overFloor <= souls * VOLLEY_DAMAGE_PER_SOUL + FINGER_DAMAGE) return VOLLEY_OF_SOULS
            if (overFloor <= fingers * FINGER_DAMAGE) return FINGER_OF_DEATH
        }

        if (spend) {
            if (deathGrasp?.active() != true && inventory.any { it.name == ESSENCE_OF_FINALITY } && adrenaline > 23) {
                equipFromInventory(ESSENCE_OF_FINALITY)
                return ESSENCE_OF_FINALITY
            }
            if (inventory.any { it.name == "Salve amulet (e)" }) equipFromInventory("Salve amulet (e)")
            if (fingers > 0) return FINGER_OF_DEATH
        }

        return when {
            putridZombie?.active() == true && abilityUsable("Command Putrid Zombie") -> "Command Putrid Zombie"
            Effect.SKELETON_WARRIOR.active && abilityUsable("Command Skeleton Warrior") -> "Command Skeleton Warrior"
            spend && souls == MAX_SOULS -> VOLLEY_OF_SOULS
            necrosis >= (if (spend) 6 else 12) -> FINGER_OF_DEATH
            Effect.DEATH_SPARK.active -> BASIC_ATTACK
            abilityUsable("Touch of Death") -> "Touch of Death"
            abilityUsable("Soul Sap") -> "Soul Sap"
            else -> BASIC_ATTACK
        }
    }
}
