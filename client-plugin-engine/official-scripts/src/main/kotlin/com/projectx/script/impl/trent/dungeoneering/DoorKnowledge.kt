package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill

data class DoorKey(val locId: Int, val x: Int, val y: Int)

data class DoorRequirement(val skill: Skill?, val level: Int?, val rawMessage: String)

class DoorKnowledge {
    private val requirements = HashMap<DoorKey, DoorRequirement>()
    private val attempts = HashMap<DoorKey, Int>()
    private val passable = HashSet<DoorKey>()

    fun requirement(key: DoorKey): DoorRequirement? = requirements[key]

    fun isPassable(key: DoorKey): Boolean = key in passable

    fun markOpened(key: DoorKey) {
        passable += key
    }

    fun shouldExamine(key: DoorKey): Boolean =
        key !in requirements && (attempts[key] ?: 0) < MAX_ATTEMPTS

    fun markAttempt(key: DoorKey) {
        attempts[key] = (attempts[key] ?: 0) + 1
    }

    fun record(key: DoorKey, requirement: DoorRequirement) {
        requirements[key] = requirement
    }

    fun entries(): Map<DoorKey, DoorRequirement> = requirements

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
