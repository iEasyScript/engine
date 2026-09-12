package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.BooleanConfigItem
import com.projectx.script.StateMachineScript
import com.projectx.script.State
import com.projectx.script.ConfigurableScript
import com.projectx.script.event.Event
import com.projectx.script.api.*
import com.projectx.script.ScriptDescription
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random
import com.projectx.script.ConfigItem

@ScriptDescription(
	name = "STATE MACHINE TEST",
	version = "1.0.0",
	author = "QB",
	description = "STATE MACHINE TEST",
)
class StateMachineTestScript : StateMachineScript<StateMachineTestScript>(), ConfigurableScript {

	val worldHop = BooleanConfigItem("World hop", "Enable world hop", false)

	override fun getStartState(): State<StateMachineTestScript> = TestState()

	val produtionArea = Area.Rectangular(
		Tile.of(3070, 3393, 0), Tile.of(3089, 3430, 0)
	)

	val bankArea = Area.Rectangular(
		Tile.of(3091, 3488, 0), Tile.of(3098, 3499, 0)
	)

	fun onConfigUpdated() {
		println("Config updated")
		this::class.java.declaredFields.filter { ConfigItem::class.java.isAssignableFrom(it.type) }.forEach { field ->
			field.isAccessible = true
			val configItem = field.get(this) as? ConfigItem<*>
			val name = configItem?.name
			val value = configItem?.value
			println("$name: $value")
		}
	}
}

val ASDASDASDSA = listOf<Tile>(
	Tile.of(3066, 3503, 0),
	Tile.of(3089, 3493, 0),
	Tile.of(3074, 3478, 0),
	Tile.of(3073, 3453, 0),
	Tile.of(3071, 3437, 0),
	Tile.of(3070, 3417, 0),
	Tile.of(3082, 3417, 0)
)

var ItemID = 123
var option = "Rub"
var tile = Tile.of(3163, 3462, 0)

private val ASDASDASDSAadfsadsfasfed
	get() = traversal(TestState(), { localPlayer.tile.getDistance(tile) < 5 }) {
		clickItem("Ring of Fortune", "Grand Exchange") { localPlayer.tile.getDistance(tile) < 5 }
	}


class TestState : State<StateMachineTestScript>() {
	override suspend fun StateMachineTestScript.checkNext(): State<StateMachineTestScript>? {
		if (localPlayer.tile.getDistance(tile) > 5) {
			return ASDASDASDSAadfsadsfasfed
		}
		return null
	}

	override suspend fun StateMachineTestScript.stateLoop() {
 var gote = equipment.getItem("Ring of Fortune")
		if(gote!=null){
			gote.getDef().wornActions.forEach { println(it) }
		}
	}

	override fun StateMachineTestScript.onStateEvent(event: Event) {
	}
}

    