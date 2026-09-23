package com.projectx.ui.compose.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectx.script.ActionableConfig
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigVisibilityProvider
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.InfoDisplayConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.OptionsConfigItem
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.script.StringConfigItem
import com.projectx.ui.compose.OverlayClock
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.Divider
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.Toggle
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/**
 * A script's own settings, edited in place.
 *
 * A running script is edited live. One that is not running gets an instance built for editing, loaded from and saved
 * to the config store, which is where starting it reads from - so there is no separate save step to forget.
 */
object ScriptSettings {
    private val drafts = mutableMapOf<String, ConfigurableScript>()

    /** Config items are plain fields Compose cannot watch, so every edit bumps this and the settings redraw. */
    var revision by mutableIntStateOf(0)
        private set

    fun instanceFor(meta: ScriptMetadata): ConfigurableScript? {
        (ScriptExecutor.getScriptInstance(meta.scriptClass) as? ConfigurableScript)?.let { return it }
        return drafts.getOrPut(meta.scriptClass.name) {
            val created = runCatching { meta.scriptClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull() as? ConfigurableScript
                ?: return null
            ScriptConfigStore.applyTo(created)
            created
        }
    }

    fun items(script: ConfigurableScript): List<Pair<String, ConfigItem<*>>> {
        val visibility = script as? ConfigVisibilityProvider
        return script.javaClass.declaredFields
            .filter { ConfigItem::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                (field.get(script) as? ConfigItem<*>)?.let { field.name to it }
            }
            .filter { (name, item) -> visibility?.isConfigItemVisible(name, item) ?: true }
    }

    fun changed(script: ConfigurableScript) {
        ScriptConfigStore.save(script)
        runCatching { script.javaClass.getMethod("onConfigUpdated").invoke(script) }
            .onFailure { if (it !is NoSuchMethodException) println("[Overlay] onConfigUpdated failed: ${it.message}") }
        revision++
    }

    /** Back to what the script itself declares, read off a fresh instance rather than guessed per type. */
    fun resetToDefaults(script: ConfigurableScript) {
        val fresh = runCatching { script.javaClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull() ?: return
        script.javaClass.declaredFields
            .filter { ConfigItem::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val target = field.get(script) as? ConfigItem<Any?> ?: return@forEach
                val source = field.get(fresh) as? ConfigItem<*> ?: return@forEach
                if (target !is ConfigSection && target !is InfoDisplayConfigItem) target.value = source.value
            }
        changed(script)
    }

    fun forget() = drafts.clear()
}

@Composable
fun ScriptSettingsView(meta: ScriptMetadata) {
    val script = remember(meta.scriptClass.name) { ScriptSettings.instanceFor(meta) }
    if (script == null) {
        Hint("This script's settings could not be loaded.")
        return
    }
    // Read so the view redraws after an edit, and once a second for values a running script reports.
    ScriptSettings.revision
    OverlayClock.nowSeconds

    val items = ScriptSettings.items(script)
    val type = LocalType.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (items.none { it.second !is ConfigSection }) Hint("This script has nothing to set.")
        items.forEach { (name, item) ->
            when (item) {
                is ConfigSection -> {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(item.name.uppercase(), style = type.eyebrow)
                        Divider()
                    }
                }
                is InfoDisplayConfigItem -> SettingRow(item.name, item.description) {
                    BasicText(item.value.ifEmpty { "-" }, style = type.data.copy(color = Palette.text))
                    ItemAction(script, item)
                }
                else -> SettingRow(item.name, item.description) {
                    ItemControl(script, name, item)
                    ItemAction(script, item)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Changes save as you make them.")
            Spacer(Modifier.weight(1f))
            ActionButton("Reset to defaults", { ScriptSettings.resetToDefaults(script) }, height = 30.dp)
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ItemControl(script: ConfigurableScript, fieldName: String, item: ConfigItem<*>) {
    fun set(value: Any?) {
        (item as ConfigItem<Any?>).value = value
        ScriptSettings.changed(script)
    }
    when (item) {
        is BooleanConfigItem -> Toggle(item.value) { set(it) }
        is IntConfigItem -> NumberInput(item.value, { set(it) }, item.min..item.max, width = 150.dp)
        is StringConfigItem -> {
            val field = remember(script, fieldName) {
                OverlayText(read = { item.value }, write = { item.value = it }, maxLength = 256, onDone = { ScriptSettings.changed(script) })
            }
            TextInput(field, "", 220.dp)
        }
        is EnumConfigItem<*> -> Dropdown(item.value, item.enumValues.toList(), { set(it) }, width = 220.dp)
        is OptionsConfigItem<*> -> Dropdown(item.value, item.options.toList(), { set(it) }, width = 220.dp) { it?.toString() ?: "None" }
        else -> BasicText("${item.value}", style = LocalType.current.data)
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ItemAction(script: ConfigurableScript, item: ConfigItem<*>) {
    val action = (item as? ActionableConfig<Any?>)?.action ?: return
    ActionButton(action.label, {
        action.run()?.let { produced ->
            (item as ConfigItem<Any?>).value = produced
            ScriptSettings.changed(script)
        }
    }, height = 32.dp)
}
