package com.projectx.script.impl.qb.utilities

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.inventory
import com.projectx.script.api.loadLastPresetClosestBank
import com.projectx.script.ScriptDescription

@ScriptDescription(
    name = "Geode Opener",
    version = "1.0.0",
    author = "QB",
    description = "Opens geodes"
)
class GeodeOpener : StateMachineScript<GeodeOpener>() {
	override fun getStartState(): State<GeodeOpener> {
		return GeodeOpeningState()
	}
}

class GeodeOpeningState : State<GeodeOpener>() {
	override suspend fun GeodeOpener.checkNext(): State<GeodeOpener>? {
		return null
	}

	override suspend fun GeodeOpener.stateLoop() {
		if (inventory.isFull) {
			loadLastPresetClosestBank()
			delay(1200, 100)
			return
		}
		var item = inventory.getItem(Regex(".*geode.*", RegexOption.IGNORE_CASE))
		if (item != null) {
			IFSlot(1473, 5, item.slot.slotId).click()
			delay(150, 100)
		}
	}
}