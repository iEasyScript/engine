package com.projectx.script.impl.qb.utilities

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.ConfigItem
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.inventory
import com.projectx.script.api.loadLastPresetClosestBank
import com.projectx.script.ScriptDescription
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.api.*

@ScriptDescription(
	name = "Lamp Opener",
	version = "1.0.0",
	author = "QB",
	description = "Opens lamps and uses them on skill"
)
class XPLamps : StateMachineScript<XPLamps>(), ConfigurableScript {
	override fun getStartState(): State<XPLamps> {
		return XPLampsState()
	}

	val selectedSkill = EnumConfigItem(
		name = "Skill",
		description = "Select which skill to use lamps on",
		enumValues = SKILLCHOICE.entries.toTypedArray(),
		initialValue = SKILLCHOICE.ATTACK
	)

	enum class SKILLCHOICE(val displayName: String, val ifSlot: IFSlot) {
		ATTACK("Attack", IFSlot(1263, 14, -1)),
		CONSTITUITION("Constitution", IFSlot(1263, 16, -1)),
		MINING("Mining", IFSlot(1263, 18, -1)),
		STRENGTH("Strength", IFSlot(1263, 20, -1)),
		AGILITY("Agility", IFSlot(1263, 22, -1)),
		SMITHING("Smithing", IFSlot(1263, 24, -1)),
		DEFENCE("Defence", IFSlot(1263, 26, -1)),
		HERBLORE("Herblore", IFSlot(1263, 28, -1)),
		FISHING("Fishing", IFSlot(1263, 30, -1)),
		RANGED("Ranged", IFSlot(1263, 32, -1)),
		THIEVING("Thieving", IFSlot(1263, 34, -1)),
		COOKING("Cooking", IFSlot(1263, 36, -1)),
		PRAYER("Prayer", IFSlot(1263, 38, -1)),
		CRAFTING("Crafting", IFSlot(1263, 40, -1)),
		FIREMAKING("Firemaking", IFSlot(1263, 42, -1)),
		MAGIC("Magic", IFSlot(1263, 44, -1)),
		FLETCHING("Fletching", IFSlot(1263, 46, -1)),
		WOODCUTTING("Woodcutting", IFSlot(1263, 48, -1)),
		RUNECRAFTING("Runecrafting", IFSlot(1263, 50, -1)),
		SLAYER("Slayer", IFSlot(1263, 52, -1)),
		FARMING("Farming", IFSlot(1263, 54, -1)),
		CONSTRUCTION("Construction", IFSlot(1263, 56, -1)),
		HUNTER("Hunter", IFSlot(1263, 58, -1)),
		SUMMONING("Summoning", IFSlot(1263, 60, -1)),
		DUNGEONEERING("Dungeoneering", IFSlot(1263, 62, -1)),
		DIVINATION("Divination", IFSlot(1263, 64, -1)),
		INVENTION("Invention", IFSlot(1263, 66, -1)),
		ARCHAEOLOGY("Archaeology", IFSlot(1263, 68, -1)),
		NECROMANCY("Necromancy", IFSlot(1263, 70, -1)),
	}

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

class XPLampsState : State<XPLamps>() {
	override suspend fun XPLamps.checkNext(): State<XPLamps>? {
		return null
	}

	override suspend fun XPLamps.stateLoop() {
		if (interfaces.isOpen(1263)) {
			selectedSkill.value.ifSlot.click()
			delay(400, 100)
			IFSlot(1263, 74, 22).dialogueContinue(22)
			delay(400, 100)
			return
		}

		var lamp = inventory.getItem(Regex(".*lamp.*", RegexOption.IGNORE_CASE))
		if (lamp != null) {
			lamp.click("Rub")
			delay(400, 100)
			return
		}
	}
}