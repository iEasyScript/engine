package com.projectx.ui.tabs

import com.projectx.script.*
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/** InputInt's step buttons eat two frame heights of the item width - ~72px at the overlay's 18px font. */
private const val NUMBER_WIDTH = 200f
private const val VALUE_WIDTH = 220f

internal fun LayoutScope.renderConfigItem(
	fieldName: String,
	configItem: ConfigItem<*>,
	onChanged: () -> Unit
) {
	if (configItem is ConfigSection) return
	if (configItem is InfoDisplayConfigItem) {
		renderInfoRow(configItem, onChanged)
		return
	}

	when (configItem) {
		is BooleanConfigItem -> {
			checkbox("${configItem.name}##$fieldName", configItem.value) { newVal ->
				configItem.value = newVal
				onChanged()
			}
		}

		is IntConfigItem -> {
			setNextItemWidth(NUMBER_WIDTH)
			inputInt("${configItem.name}##$fieldName", configItem.value) { newVal ->
				if (newVal != configItem.value && newVal >= configItem.min && newVal <= configItem.max) {
					configItem.value = newVal
					onChanged()
				}
			}
		}

		is StringConfigItem -> {
			setNextItemWidth(VALUE_WIDTH)
			inputText("${configItem.name}##$fieldName", configItem.value, maxLength = 256) { newVal ->
				if (newVal != configItem.value) {
					configItem.value = newVal
					onChanged()
				}
			}
		}

		is OptionsConfigItem<*> -> {
			setNextItemWidth(VALUE_WIDTH)
			combo("${configItem.name}##$fieldName", configItem.value?.toString() ?: "None") {
				configItem.options.forEach { option ->
					val isSelected = (option == configItem.value)
					selectable(option?.toString() ?: "null", isSelected) {
						@Suppress("UNCHECKED_CAST")
						(configItem as ConfigItem<Any?>).value = option
						onChanged()
					}
					if (isSelected) setItemDefaultFocus()
				}
			}
		}

		is EnumConfigItem<*> -> {
			setNextItemWidth(VALUE_WIDTH)
			combo("${configItem.name}##$fieldName", configItem.value.toString()) {
				configItem.enumValues.forEach { enumValue ->
					val isSelected = (enumValue == configItem.value)
					selectable(enumValue.toString(), isSelected) {
						@Suppress("UNCHECKED_CAST")
						(configItem as ConfigItem<Any?>).value = enumValue
						onChanged()
					}
					if (isSelected) setItemDefaultFocus()
				}
			}
		}

		else -> text("${configItem.name}: ${configItem.value}")
	}
	renderDescriptionMarker(configItem)
	renderConfigAction(configItem, onChanged)
}

/** Action button first so the buttons align in a column; the status text trails it. */
private fun LayoutScope.renderInfoRow(configItem: InfoDisplayConfigItem, onChanged: () -> Unit) {
	val action = configItem.action
	if (action != null) {
		button("${action.label}##action-${configItem.name}") {
			action.run()?.let { produced ->
				configItem.value = produced
				onChanged()
			}
		}
		sameLine()
	}
	text("${configItem.name}:")
	sameLine()
	text(configItem.value.ifEmpty { "-" })
	renderDescriptionMarker(configItem)
}

private fun LayoutScope.renderDescriptionMarker(configItem: ConfigItem<*>) {
	if (configItem.description.isEmpty()) return
	itemTooltip(configItem.description)
	sameLine()
	pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
	text("(?)")
	popStyleColor()
	itemTooltip(configItem.description)
}

@Suppress("UNCHECKED_CAST")
private fun LayoutScope.renderConfigAction(configItem: ConfigItem<*>, onChanged: () -> Unit) {
	val action = (configItem as? ActionableConfig<Any?>)?.action ?: return
	sameLine()
	button("${action.label}##action-${configItem.name}") {
		action.run()?.let { produced ->
			(configItem as ConfigItem<Any?>).value = produced
			onChanged()
		}
	}
}
