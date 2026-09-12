package com.projectx.script.impl.bp.thieving.safecracking

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.spotAnims
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.ManualDoAction

@ScriptDescription(
    name = "Safecracking Activity",
    author = "BP",
    version = "1.0.0",
    description = "Assists with safecracking by re-clicking safes."
)
class SafecrackingActivity : StateMachineScript<SafecrackingActivity>() {
    companion object {
        @JvmStatic val SPOTANIM_ID = 6882
        @JvmStatic val ACTION_CRACK_SAFE = "Crack open"
        @JvmStatic val OBJECT_NAME_SAFE = "Safe"
        @JvmStatic val TEXT_CRACKED_OPEN = "You crack open the safe!"
    }

    var currentSafe: SceneObject? = null
    var lastHandledSpotAnimCreatedTime: Int? = null

    override fun getStartState(): State<SafecrackingActivity> {
        return WaitForSafe()
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        when (event) {
            is ManualDoAction -> {
                val target = event.target
                if (target is SceneObject && target.name() == OBJECT_NAME_SAFE && target.hasOption(ACTION_CRACK_SAFE)) {
                    println("New safe selected by user: ${target.tile}")
                    currentSafe = target
                }
            }
            is Chat -> {
                if (event.message.contains(TEXT_CRACKED_OPEN, ignoreCase = true)) {
                    println("Safe cracked. Waiting for new safe.")
                    currentSafe = null
                }
            }
        }
    }
}

class WaitForSafe : State<SafecrackingActivity>() {
    override suspend fun SafecrackingActivity.checkNext(): State<SafecrackingActivity>? {
        if (currentSafe != null) {
            println("Safe detected, starting cracking process.")
            return SafeCracking()
        }
        return null
    }

    override suspend fun SafecrackingActivity.stateLoop() {
        delay(200)
    }
}

class SafeCracking : State<SafecrackingActivity>() {
    private fun SceneObject.revalidate(): SceneObject? {
        return findClosestObject(10) { it.id == this.id && it.tile == this.tile }
    }

    override suspend fun SafecrackingActivity.checkNext(): State<SafecrackingActivity>? {
        val safe = currentSafe
        if (safe == null || safe.revalidate() == null) {
            println("Current safe is no longer valid. Waiting for a new one.")
            if (safe != null) {
                currentSafe = null
            }
            return WaitForSafe()
        }
        return null
    }

    override suspend fun SafecrackingActivity.stateLoop() {
        val safe = currentSafe ?: return

        if (safe.tile.getDistance(localPlayer.tile) > 8) {
            println("Too far from the safe. Please move closer.")
            currentSafe = null
            return
        }

        val safeQuickAction = spotAnims.find { it.id == SafecrackingActivity.SPOTANIM_ID && it.tile == safe.tile }

        if (safeQuickAction != null) {
            safe.interact(SafecrackingActivity.ACTION_CRACK_SAFE)
            lastHandledSpotAnimCreatedTime = safeQuickAction.createdClientcycle

            delayUntil(2000) { spotAnims.none { it.id == SafecrackingActivity.SPOTANIM_ID && it.tile == safe.tile && it.createdClientcycle == lastHandledSpotAnimCreatedTime} || safe.revalidate() == null }
        } else if (!localPlayer.isAniMoving) {
        }

        delay(150, 500)
    }
}
