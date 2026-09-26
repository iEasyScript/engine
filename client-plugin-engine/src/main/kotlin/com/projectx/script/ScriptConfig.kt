package com.projectx.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

interface ConfigItem<T> {
    val name: String
    val description: String
    var value: T
}

interface ConfigurableScript

/**
 * Holds a script's [ConfigItem]s outside the script class, so a script with many settings keeps them in one place
 * of their own instead of opening with fifty fields. Declare the holder as a field of the script and the engine
 * finds its items, in declaration order, exactly as if they were the script's own:
 *
 * ```kotlin
 * class MinerSettings : ConfigHolder {
 *     val ore = ConfigSection("Ore", "What to mine.")
 *     val bankOre = BooleanConfigItem("Bank the ore", "Off drops it.", true)
 * }
 *
 * class Miner : Script(), ConfigurableScript {
 *     private val settings = MinerSettings()
 * }
 * ```
 *
 * A holder may hold further holders. Its items are stored under `<holder field>.<item field>`, so two holders can
 * use the same field name; settings saved before a script moved its items into a holder are still read back.
 */
interface ConfigHolder

/**
 * Every [ConfigItem] on [owner], in declaration order, paired with the key it is stored under: the field's own name,
 * or `<holder field>.<item field>` for one inside a [ConfigHolder]. [legacyKey] is the bare field name, which is
 * where a script that has since moved its items into a holder finds what it saved before.
 */
fun configItems(owner: Any): List<ConfigField> = configItems(owner, "", java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))

/** A [ConfigItem] found on a script or a [ConfigHolder]; see [configItems]. */
class ConfigField(val key: String, val legacyKey: String, val item: ConfigItem<*>)

private fun configItems(owner: Any, prefix: String, seen: MutableSet<Any>): List<ConfigField> {
    if (!seen.add(owner)) return emptyList()
    return owner.javaClass.declaredFields.flatMap { field ->
        field.isAccessible = true
        when (val value = runCatching { field.get(owner) }.getOrNull()) {
            is ConfigItem<*> -> listOf(ConfigField(prefix + field.name, field.name, value))
            is ConfigHolder -> configItems(value, "${prefix}${field.name}.", seen)
            else -> emptyList()
        }
    }
}

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

class BooleanConfigItem @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    initialValue: Boolean = false
) : ConfigItem<Boolean>, ActionableConfig<Boolean> {
    override var value: Boolean = initialValue
    override var action: ConfigAction<Boolean>? = null
}

class IntConfigItem @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    initialValue: Int = 0,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE
) : ConfigItem<Int>, ActionableConfig<Int> {
    override var value: Int = initialValue
    override var action: ConfigAction<Int>? = null
}

class StringConfigItem @JvmOverloads constructor(
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

class InfoDisplayConfigItem @JvmOverloads constructor(
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
class ConfigSection @JvmOverloads constructor(
    override val name: String,
    override val description: String = "",
    val defaultOpen: Boolean = true
) : ConfigItem<Unit> {
    override var value: Unit = Unit
}

/**
 * Every script's settings, kept between runs and between client sessions. Each change is written to
 * `~/.projectx/script-settings/<script class>.json`, and a script's first run after the client starts reads them
 * back, so settings only ever need setting once. Values are stored by field name and read back by each item's own
 * type: a setting the script has since removed, renamed or retyped is skipped and keeps the script's default.
 */
object ScriptConfigStore {
    private val configCache = ConcurrentHashMap<String, Map<String, JsonPrimitive>>()
    private val json = Json { prettyPrint = true }

    internal var directory: File = File(System.getProperty("user.home"), ".projectx/script-settings")

    fun save(script: Any) {
        val values = LinkedHashMap<String, JsonPrimitive>()
        storedItems(script).forEach { field -> encode(field.item.value)?.let { values[field.key] = it } }
        configCache[script.javaClass.name] = values
        write(script.javaClass.name, values)
    }

    fun applyTo(script: Any) {
        val saved = configCache.getOrPut(script.javaClass.name) { read(script.javaClass.name) }
        storedItems(script).forEach { field ->
            val stored = saved[field.key] ?: saved[field.legacyKey] ?: return@forEach
            val value = field.item.decode(stored) ?: return@forEach
            try {
                @Suppress("UNCHECKED_CAST")
                (field.item as ConfigItem<Any?>).value = value
            } catch (e: Exception) {
                println("[x] Failed to restore config value for ${field.key}: $e")
            }
        }
    }

    private fun storedItems(script: Any): List<ConfigField> =
        configItems(script).filter { it.item !is InfoDisplayConfigItem && it.item !is ConfigSection }

    private fun encode(value: Any?): JsonPrimitive? = when (value) {
        null -> null
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Enum and option choices are matched by name against the item's own choices: a hot-reloaded script loads its
     * classes from a fresh classloader, so the constants stored earlier are not the ones it reads back.
     */
    private fun ConfigItem<*>.decode(saved: JsonPrimitive): Any? = when (this) {
        is BooleanConfigItem -> saved.booleanOrNull
        is IntConfigItem -> saved.intOrNull?.coerceIn(min, max)
        is StringConfigItem -> saved.contentOrNull
        is EnumConfigItem<*> -> enumValues.firstOrNull { it.name == saved.contentOrNull }
        is OptionsConfigItem<*> -> options.firstOrNull { choiceName(it) == saved.contentOrNull }
        else -> null
    }

    private fun choiceName(choice: Any?): String? = (choice as? Enum<*>)?.name ?: choice?.toString()

    private fun fileFor(scriptClass: String) = File(directory, "$scriptClass.json")

    private fun read(scriptClass: String): Map<String, JsonPrimitive> {
        val file = fileFor(scriptClass)
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.let { name to it }
            }.toMap()
        }.onFailure { println("[x] Could not read saved settings ${file.name}: ${it.message}") }.getOrDefault(emptyMap())
    }

    private fun write(scriptClass: String, values: Map<String, JsonPrimitive>) {
        runCatching {
            directory.mkdirs()
            val target = fileFor(scriptClass).toPath()
            val staged = Files.createTempFile(directory.toPath(), scriptClass, ".tmp")
            Files.writeString(staged, json.encodeToString(JsonObject.serializer(), JsonObject(values)))
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { println("[x] Could not save settings for $scriptClass: ${it.message}") }
    }
}
