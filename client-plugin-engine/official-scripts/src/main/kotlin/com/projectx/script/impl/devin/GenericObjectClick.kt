package com.projectx.script.impl.devin

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.util.gaussian

private val OBJECT_OPS = arrayOf(
    DoActionOpcode.OBJECT_1,
    DoActionOpcode.OBJECT_2,
    DoActionOpcode.OBJECT_3,
    DoActionOpcode.OBJECT_4,
    DoActionOpcode.OBJECT_5,
    DoActionOpcode.OBJECT_6
)

@ScriptDescription(
    name = "Generic Object Click",
    version = "1.0.0",
    author = "Devin",
    description = "Repeats whichever object option you click after starting.",
    visible = false
)
class GenericObjectClick : StateMachineScript<GenericObjectClick>() {
    lateinit var objectName: String
    lateinit var option: String
    var startTile: Tile = Tile.EMPTY

    fun selectedObject() = ::objectName.isInitialized && ::option.isInitialized

    override fun getStartState() = AwaitObjectClick

    fun selectTarget() = findClosestObject { clickable(it) }

    private fun clickable(obj: SceneObject) =
        obj.name() == objectName && obj.hasOption(option) && !obj.isTransformHidden
}

object AwaitObjectClick : State<GenericObjectClick>() {
    override suspend fun GenericObjectClick.checkNext() = if (selectedObject()) ClickObject else null

    override suspend fun GenericObjectClick.stateLoop() {}

    override fun GenericObjectClick.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is SceneObject) return
        val obj = event.target as SceneObject
        val clicked = obj.getDef().getOp(OBJECT_OPS.indexOf(event.opcode))
        if (selectedObject() || clicked == "null") return
        objectName = obj.name()
        option = clicked
        startTile = obj.tile
    }
}

object ClickObject : State<GenericObjectClick>() {
    override suspend fun GenericObjectClick.checkNext(): State<GenericObjectClick>? = null

    override suspend fun GenericObjectClick.stateLoop() {
        when (val target = selectTarget()) {
            null -> if (walkTo(startTile.randomize(3), true))
                waitThenDelayUntil(gaussian(6436L, 2114L)) { localPlayer.tile.withinDistance(startTile, 3) }
            else -> {
                target.interact(option)
                delay(712, 969)
            }
        }
    }
}
