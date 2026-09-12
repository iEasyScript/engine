package com.projectx.ui.tabs

import com.projectx.script.*
import com.projectx.ui.backend.dsl.scopes.WindowScope
import com.projectx.ui.backend.dsl.scopes.collapsingHeader
import com.projectx.ui.backend.dsl.scopes.itemTooltip
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags

object ScriptConfigRenderer {
    fun WindowScope.renderScriptConfig(script: ConfigurableScript) {
        try {
            val visibilityProvider = script as? ConfigVisibilityProvider
            val entries = script::class.java.declaredFields
                .filter { ConfigItem::class.java.isAssignableFrom(it.type) }
                .mapNotNull { field ->
                    field.isAccessible = true
                    (field.get(script) as? ConfigItem<*>)?.let { field.name to it }
                }
                .filter { (fieldName, item) -> visibilityProvider?.isConfigItemVisible(fieldName, item) ?: true }

            if (entries.isEmpty()) {
                text("No configuration options available for this script.")
                return
            }

            val onChanged = {
                ScriptConfigStore.save(script)
                invokeOnConfigUpdated(script)
            }

            var index = 0
            while (index < entries.size) {
                val (fieldName, item) = entries[index]
                if (item is ConfigSection) {
                    var end = index + 1
                    while (end < entries.size && entries[end].second !is ConfigSection) end++
                    renderSection(fieldName, item, entries.subList(index + 1, end), onChanged)
                    index = end
                } else {
                    renderConfigItem(fieldName, item, onChanged)
                    index++
                }
            }

        } catch (e: Exception) {
            text("Error loading configuration: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun WindowScope.renderSection(
        fieldName: String,
        section: ConfigSection,
        items: List<Pair<String, ConfigItem<*>>>,
        onChanged: () -> Unit
    ) {
        val flags = if (section.defaultOpen) ImGuiTreeNodeFlags.DefaultOpen else ImGuiTreeNodeFlags.None
        collapsingHeader("${section.name}##section-$fieldName", flags) {
            if (section.description.isNotEmpty()) itemTooltip(section.description)
            items.forEach { (itemField, item) -> renderConfigItem(itemField, item, onChanged) }
        }
    }

    fun invokeOnConfigUpdated(script: Any) {
        try {
            val method = script.javaClass.getMethod("onConfigUpdated")
            method.invoke(script)
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            println("Error invoking onConfigUpdated: ${e.message}")
        }
    }

    fun resetScriptConfigToDefaults(script: Any) {
        try {
            val clazz = script::class.java
            val configFields = clazz.declaredFields
                .filter { ConfigItem::class.java.isAssignableFrom(it.type) }

            configFields.forEach { field ->
                field.isAccessible = true
                val configItem = field.get(script) as? ConfigItem<*>
                if (configItem != null) {
                    val constructor = configItem.javaClass.declaredConstructors.firstOrNull()
                    if (constructor != null) {
                        constructor.isAccessible = true

                        when (configItem) {
                            is BooleanConfigItem -> {
                                configItem.value = false
                            }

                            is IntConfigItem -> {
                                configItem.value = 0
                            }

                            is StringConfigItem -> {
                                configItem.value = ""
                            }

                            is OptionsConfigItem<*> -> {
                                val optionsField = configItem.javaClass.getDeclaredField("options")
                                optionsField.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val options = optionsField.get(configItem) as Array<*>
                                if (options.isNotEmpty()) {
                                    @Suppress("UNCHECKED_CAST")
                                    (configItem as ConfigItem<Any?>).value = options[0]
                                }
                            }

                            is EnumConfigItem<*> -> {
                                val enumValuesField = configItem.javaClass.getDeclaredField("enumValues")
                                enumValuesField.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val enumValues = enumValuesField.get(configItem) as Array<Enum<*>>
                                if (enumValues.isNotEmpty()) {
                                    @Suppress("UNCHECKED_CAST")
                                    (configItem as ConfigItem<Any?>).value = enumValues[0]
                                }
                            }
                        }
                    }
                }
            }

            ScriptConfigStore.save(script)
            invokeOnConfigUpdated(script)

        } catch (e: Exception) {
            println("Error resetting config to defaults: ${e.message}")
            e.printStackTrace()
        }
    }
}