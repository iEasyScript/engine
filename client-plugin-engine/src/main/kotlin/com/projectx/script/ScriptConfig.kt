package com.projectx.script

interface ConfigItem<T> {
    val name: String
    val description: String
    var value: T
}

interface ConfigurableScript

interface ConfigVisibilityProvider {
    fun isConfigItemVisible(fieldName: String, item: ConfigItem<*>): Boolean
}

/** An action rendered as a button beside a config item; [run] returns the new value, or null to leave it unchanged. */
class ConfigAction<T>(val label: String, val run: () -> T?)

/**
 * Opt-in carrier for a [ConfigAction] on any config item. A separate interface plus the
 * [withAction] builder - rather than constructor parameters - keeps every existing constructor
 * signature intact, so scripts built against older engines keep working unchanged.
 */
interface ActionableConfig<T> {
    var action: ConfigAction<T>?
}

fun <T, I> I.withAction(label: String, run: () -> T?): I where I : ConfigItem<T>, I : ActionableConfig<T> {
    action = ConfigAction(label, run)
    return this
}

class BooleanConfigItem(
    override val name: String,
    override val description: String,
    initialValue: Boolean = false
) : ConfigItem<Boolean>, ActionableConfig<Boolean> {
    override var value: Boolean = initialValue
    override var action: ConfigAction<Boolean>? = null
}

class IntConfigItem(
    override val name: String,
    override val description: String,
    initialValue: Int = 0,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE
) : ConfigItem<Int>, ActionableConfig<Int> {
    override var value: Int = initialValue
    override var action: ConfigAction<Int>? = null
}

class StringConfigItem(
    override val name: String,
    override val description: String,
    initialValue: String = ""
) : ConfigItem<String>, ActionableConfig<String> {
    override var value: String = initialValue
    override var action: ConfigAction<String>? = null
}

class OptionsConfigItem<T>(
    override val name: String,
    override val description: String,
    val options: Array<T>,
    initialValue: T
) : ConfigItem<T>, ActionableConfig<T> {
    override var value: T = initialValue
    override var action: ConfigAction<T>? = null
}

class EnumConfigItem<T : Enum<T>>(
    override val name: String,
    override val description: String,
    val enumValues: Array<T>,
    initialValue: T
) : ConfigItem<T>, ActionableConfig<T> {
    override var value: T = initialValue
    override var action: ConfigAction<T>? = null
}

class InfoDisplayConfigItem(
    override val name: String,
    override val description: String,
    initialValue: String = ""
) : ConfigItem<String>, ActionableConfig<String> {
    override var value: String = initialValue
    override var action: ConfigAction<String>? = null
}

/**
 * Valueless marker: the items declared after it (up to the next section) render under a
 * collapsible header. Nothing is persisted; the open state lives in ImGui like any header.
 */
class ConfigSection(
    override val name: String,
    override val description: String = "",
    val defaultOpen: Boolean = true
) : ConfigItem<Unit> {
    override var value: Unit = Unit
}

object ScriptConfigStore {
    private val configCache = mutableMapOf<String, Map<String, Any?>>()

    fun save(script: Any) {
        val values = mutableMapOf<String, Any?>()
        script.javaClass.declaredFields.forEach { field ->
            field.isAccessible = true
            val configItem = field.get(script)
            if (configItem is ConfigItem<*> && configItem !is InfoDisplayConfigItem && configItem !is ConfigSection) {
                values[field.name] = configItem.value
            }
        }
        configCache[script.javaClass.name] = values
    }

    fun applyTo(script: Any) {
        val saved = configCache[script.javaClass.name] ?: return
        script.javaClass.declaredFields.forEach { field ->
            field.isAccessible = true
            val configItem = field.get(script)
            if (configItem is ConfigItem<*> && configItem !is InfoDisplayConfigItem && configItem !is ConfigSection) {
                val savedValue = saved[field.name]?.let { configItem.rebind(it) }
                if (savedValue != null) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (configItem as ConfigItem<Any?>).value = savedValue
                    } catch (e: Exception) {
                        println("[x] Failed to restore config value for ${field.name}: $e")
                    }
                }
            }
        }
    }

    /**
     * A hot-reloaded script loads its classes from a fresh classloader, so a cached enum constant is
     * a different class than the one the reloaded script reads back - restoring it as-is throws a
     * ClassCastException at the script's first read. Match by name against the item's own choices.
     */
    private fun ConfigItem<*>.rebind(saved: Any): Any? {
        if (saved !is Enum<*>) return saved
        val choices = when (this) {
            is EnumConfigItem<*> -> enumValues
            is OptionsConfigItem<*> -> options
            else -> return null
        }
        return choices.firstOrNull { (it as? Enum<*>)?.name == saved.name }
    }
}