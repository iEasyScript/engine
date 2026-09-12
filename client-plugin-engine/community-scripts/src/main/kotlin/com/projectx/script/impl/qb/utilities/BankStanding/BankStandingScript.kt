package com.projectx.script.impl.qb.utilities.BankStanding

import com.projectx.game.hooks.impl.DoAction
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.inventory
import com.projectx.script.api.loadLastPresetClosestBank
import com.projectx.script.ScriptDescription
import com.projectx.script.api.bankOpen
import com.projectx.script.api.interfaces
import com.projectx.script.api.openClosestBank

@ScriptDescription(
	name = "BankStandingScript", version = "1.0.0", author = "Query", description = "BankStandingScript"
)
class BankStandingScript : StateMachineScript<BankStandingScript>() {
	override fun getStartState(): State<BankStandingScript> {
		return BankStandingState()
	}
}

class BankStandingState : State<BankStandingScript>() {
	override suspend fun BankStandingScript.checkNext(): State<BankStandingScript>? {
		return null
	}

	override suspend fun BankStandingScript.stateLoop() {
		if (inventory.getItem(Regex(".*lamp.*", RegexOption.IGNORE_CASE)) != null) {
			if (interfaces.isOpen(678)) {
				IFSlot(678, 19, -1).dialogueContinue()
			}
			if(interfaces.isOpen(1263)){
				IFSlot(1263, 18, -1).click()
				delay(1200,200)
				IFSlot(1263, 74, 13).dialogueContinue(13)
				delay(1200,200)
				return
			}

			inventory.getItem(Regex(".*lamp.*", RegexOption.IGNORE_CASE))?.click("Rub")
			delay(1200,200)
			return
		}

		if (inventory.getItem(Regex(".*star.*", RegexOption.IGNORE_CASE)) != null) {
			if (interfaces.isOpen(678)) {
				IFSlot(678, 19, -1).dialogueContinue()
			}
			if(interfaces.isOpen(1263)){
				IFSlot(1263, 18, -1).click()
				delay(1200,200)
				IFSlot(1263, 74, 13).dialogueContinue(13)
				delay(1200,200)
				return
			}
			inventory.getItem(Regex(".*star.*", RegexOption.IGNORE_CASE))?.click("Choose skill")
			delay(1200,200)
			return
		}

		if (inventory.getItem(Regex(".*token box.*", RegexOption.IGNORE_CASE)) != null) {

			inventory.getItem(Regex(".*token box.*", RegexOption.IGNORE_CASE))?.click("Open")
			delay(1200,200)
			return
		}

		if (inventory.getItem(Regex(".*cash bag.*", RegexOption.IGNORE_CASE)) != null) {

			inventory.getItem(Regex(".*cash bag.*", RegexOption.IGNORE_CASE))?.click("Open")
			delay(1200,200)
			return
		}

		if (inventory.getItem(Regex(".*Coupon.*", RegexOption.IGNORE_CASE)) != null) {

			inventory.getItem(Regex(".*Coupon.*", RegexOption.IGNORE_CASE))?.click("Consume")
			delay(1200,200)
			return
		}

		if (inventory.getItem(Regex(".*rune.*", RegexOption.IGNORE_CASE)) != null) {

			inventory.getItem(Regex(".*rune.*", RegexOption.IGNORE_CASE))?.click("Investigate")
			delay(1200,200)
			return
		}

		if (inventory.freeSlots > 16 && inventory.getItem(59427) != null) {
			inventory.getItem(59427)?.click("Open all")
			delay(2400,200)
			return
		}

		if(bankOpen){
			IFSlot(517,39,-1).click()
			delay(1200,200)
			IFSlot(517,318,-1).click()
			delay(1200,200)
			return
		}else{
			openClosestBank()
			delay(1200,200)
			return
		}
	}
}
