package com.projectx.script.impl.qb.Temporary

import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer

@ScriptDescription(
    name = "Fort Build",
    version = "1.0.0",
    author = "Billy",
    description = "Builds optimal construction hotspots in Fort Forinthry"
)
class FortBuild : StateMachineScript<FortBuild>() {
    override fun getStartState(): State<FortBuild> = CheckResources()

    var hasClickedBuild = false
    val activePlay = true
}

class CheckResources : State<FortBuild>() {
    override suspend fun FortBuild.checkNext(): State<FortBuild>? {
        inventory.filter { it.name.contains("Plank") }
        inventory.filter { it.name.contains("Stone wall segment") }
        val hotspot = findClosestObject("Optimal Construction hotspot", 60)
        val buildspot = findClosestObject("Construction hotspot", 60)

        return when {
            hotspot != null -> Build()
            buildspot != null -> BuildSpot()
            else -> null
        }
    }

    override suspend fun FortBuild.stateLoop() {
        delay(600, 800)
    }
}

class Build : State<FortBuild>() {
    override suspend fun FortBuild.checkNext(): State<FortBuild>? {
        val hotspot = findClosestObject("Optimal Construction hotspot", 60)
        return if (hotspot == null) CheckResources() else null
    }

    override suspend fun FortBuild.stateLoop() {
        val hotspot = findClosestObject("Optimal Construction hotspot", 60) ?: return

        if (!localPlayer.isMoving) {
            val distance = localPlayer.tile.getDistance(hotspot.tile)

            if (!hasClickedBuild && distance >= 1) {
                if (hotspot.interact("Build")) {
                    println("Found construction spot")
                    hasClickedBuild = true

                    if (activePlay) {
                        delay(800, 1200)
                    } else {
                        delay(3000, 10000)
                    }
                }
            } else if (hasClickedBuild && distance > 1) {
                if (activePlay) {
                    delay(800, 1200)
                } else {
                    delay(3000, 10000)
                }
                hasClickedBuild = false
            }
        }
    }
}

class BuildSpot : State<FortBuild>() {
    override suspend fun FortBuild.checkNext(): State<FortBuild>? {
        val hotspot = findClosestObject("Construction hotspot", 60)
        return if (hotspot == null) CheckResources() else null
    }

    override suspend fun FortBuild.stateLoop() {
        val hotspot = findClosestObject("Construction hotspot", 60) ?: return

        if (!localPlayer.isMoving) {
            val distance = localPlayer.tile.getDistance(hotspot.tile)

            if (!hasClickedBuild && distance >= 1) {
                if (hotspot.interact("Build")) {
                    println("Found construction spot")
                    hasClickedBuild = true

                    if (activePlay) {
                        delay(800, 1200)
                    } else {
                        delay(3000, 10000)
                    }
                }
            } else if (hasClickedBuild && distance > 1) {
                if (activePlay) {
                    delay(800, 1200)
                } else {
                    delay(3000, 10000)
                }
                hasClickedBuild = false
            }
        }
    }
}