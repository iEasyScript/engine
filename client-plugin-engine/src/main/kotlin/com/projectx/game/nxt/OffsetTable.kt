package com.projectx.game.nxt

import com.google.gson.JsonParser
import com.projectx.game.memory.NativeAccess
import com.projectx.game.platform.Platform
import com.projectx.game.platform.Renderer
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class OffsetUnavailableException(message: String) : IllegalStateException(message)

data class DoActionEntry(val name: String, val id: Int, val method: String, val rva: Long)

/** A `by offset()` property and the platforms whose tables are required to carry a value for it. */
data class OffsetDeclaration(
    val key: String,
    val platforms: Set<Platform>,
    /** The field exists on the other platforms too; its value has just not been reverse engineered yet. */
    val portPending: Boolean = false,
    /** The renderer builds whose client has the field at all, e.g. a Vulkan device object. */
    val renderers: Set<Renderer> = Renderer.entries.toSet(),
) {
    val isPlatformScoped: Boolean get() = !portPending && platforms != Platform.entries.toSet()
    fun appliesTo(platform: Platform) = platform in platforms
    fun appliesTo(platform: Platform, renderer: Renderer) = platform in platforms && renderer in renderers
}

/**
 * Per-(platform, build) binary offsets, loaded from the `offsets/` jar resources.
 *
 * Values are module-relative - struct field offsets are used as-is, and `OFunctions` entries are
 * added to the client module base by the caller. Nothing here applies an image base.
 *
 * A property declared with a bare `offset()` must resolve on every bundled platform;
 * `OffsetCoverage` fails the build otherwise. The only declarations allowed to fail a lookup at
 * runtime are `offset(Platform.LINUX)`, for a field that genuinely only exists in that platform's
 * client, and `portPending(Platform.LINUX)`, for one whose value simply has not been reverse
 * engineered elsewhere yet.
 */
object OffsetTable {
    private const val RESOURCE_DIR = "offsets"
    private const val BUILD_PROPERTY = "projectx.clientBuild"
    private const val BUILD_ENV = "PROJECTX_CLIENT_BUILD"
    private const val FUNCTIONS_OBJECT = "OFunctions"

    internal class Table(
        val name: String,
        val platform: String,
        val build: String,
        val renderer: Renderer,
        val values: Map<String, Long>,
        val doActions: Map<String, DoActionEntry>,
    ) {
        val platformKind: Platform = Platform.ofTableKey(platform)
    }

    private val table: Table by lazy { loadTable(resolveTableName()) }
    private val declarations = ConcurrentHashMap<String, OffsetDeclaration>()

    val build: String get() = table.build
    val platform: String get() = table.platform
    val renderer: Renderer get() = table.renderer
    val tableName: String get() = table.name

    fun summary(): String =
        "offsets ${table.platform}/${table.build} (${table.renderer.id}): ${table.values.size} fields, ${table.doActions.size} doActions"

    /** Module-relative address of a hookable function, or null when this build has no entry. */
    fun function(name: String): Long? = table.values["$FUNCTIONS_OBJECT.$name"]

    /** Value of `Object.FIELD` [key], or null when this build's table has no such entry. */
    fun valueOrNull(key: String): Long? = table.values[key]

    fun doAction(name: String): DoActionEntry? = table.doActions[name]

    fun doActions(): Collection<DoActionEntry> = table.doActions.values

    /**
     * How far a wrapper may read into an object of [obj]'s layout on the current table: past the
     * last field the table declares, with room for an inline vector, string or matrix after it, and
     * never below a floor that still covers an object the table has no fields for yet. Windows come
     * from here so a ported offset resizes them; a literal cannot fall behind a layout.
     */
    fun extent(obj: OffsetObject): Long = extents.getOrPut(obj) { extent(obj::class.simpleName!!, table) }

    private val extents = ConcurrentHashMap<OffsetObject, Long>()

    fun extent(objectName: String, tableName: String): Long = extent(objectName, bundled(tableName))

    private fun extent(objectName: String, table: Table): Long {
        val prefix = "$objectName."
        val last = table.values.entries.filter { it.key.startsWith(prefix) }.maxOfOrNull { it.value } ?: 0L
        return maxOf(last + EXTENT_SLACK, EXTENT_FLOOR)
    }

    private const val EXTENT_SLACK = 0x100L
    private const val EXTENT_FLOOR = 0x1000L

    /** Delegate for a `Long` offset; the owning object and property name form the lookup key. */
    fun offset(vararg platforms: Platform): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, Long>> =
        Declaration(platforms, portPending = false) { LongOffset(it) }

    /** Delegate for a field that only exists in the [renderer] build of the client. */
    fun offset(renderer: Renderer): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, Long>> =
        Declaration(emptyArray<Platform>(), portPending = false, renderers = setOf(renderer)) { LongOffset(it) }

    /** Delegate for an `Int` structural constant such as a buffer capacity or slot count. */
    fun count(vararg platforms: Platform): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, Int>> =
        Declaration(platforms, portPending = false) { IntOffset(it) }

    /**
     * A field the client has on every platform but that has only been reverse engineered on
     * [portedTo]. Says so out loud instead of masquerading as platform-specific, and the coverage
     * test drops the marker again the moment the missing value lands in a table.
     */
    fun portPending(vararg portedTo: Platform): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, Long>> =
        Declaration(portedTo, portPending = true) { LongOffset(it) }

    internal fun declarations(): Collection<OffsetDeclaration> = declarations.values

    internal fun bundledTableNames(): List<String> {
        val index = readResource("$RESOURCE_DIR/index.json")
            ?: error("Offset index '$RESOURCE_DIR/index.json' is missing from the engine jar")
        return JsonParser.parseString(index).asJsonObject
            .getAsJsonArray("tables")
            .map { it.asString }
    }

    internal fun bundled(name: String): Table = loadTable(name)

    private class Declaration<T>(
        platforms: Array<out Platform>,
        private val portPending: Boolean,
        private val renderers: Set<Renderer> = Renderer.entries.toSet(),
        private val delegate: (OffsetDeclaration) -> ReadOnlyProperty<Any, T>,
    ) : PropertyDelegateProvider<Any, ReadOnlyProperty<Any, T>> {
        private val platforms = if (platforms.isEmpty()) Platform.entries.toSet() else platforms.toSet()

        override fun provideDelegate(thisRef: Any, property: KProperty<*>): ReadOnlyProperty<Any, T> {
            val declared = OffsetDeclaration(keyOf(thisRef, property), platforms, portPending, renderers)
            declarations[declared.key] = declared
            return delegate(declared)
        }
    }

    private class LongOffset(private val declaration: OffsetDeclaration) : ReadOnlyProperty<Any, Long> {
        private var resolved: Long? = null
        override fun getValue(thisRef: Any, property: KProperty<*>): Long =
            resolved ?: lookup(declaration).also { resolved = it }
    }

    private class IntOffset(private val declaration: OffsetDeclaration) : ReadOnlyProperty<Any, Int> {
        private var resolved: Int? = null
        override fun getValue(thisRef: Any, property: KProperty<*>): Int =
            resolved ?: lookup(declaration).toInt().also { resolved = it }
    }

    private fun keyOf(thisRef: Any, property: KProperty<*>) =
        "${thisRef.javaClass.simpleName}.${property.name}"

    private fun lookup(declaration: OffsetDeclaration): Long {
        table.values[declaration.key]?.let { return it }
        if (declaration.portPending) throw OffsetUnavailableException(
            "'${declaration.key}' has not been reverse engineered for ${table.platform} yet - it is " +
                "declared port-pending, resolving only on " +
                declaration.platforms.joinToString("/") { it.id } + "."
        )
        if (!declaration.appliesTo(table.platformKind)) throw OffsetUnavailableException(
            "'${declaration.key}' exists only in the " +
                declaration.platforms.joinToString("/") { it.id } +
                " client; this is ${table.platform}. Guard the call site on Platform.current."
        )
        if (table.renderer !in declaration.renderers) throw OffsetUnavailableException(
            "'${declaration.key}' exists only in the " +
                declaration.renderers.joinToString("/") { it.id } +
                " client; this is the ${table.renderer.id} build. Guard the call site on OffsetTable.renderer."
        )
        throw OffsetUnavailableException(
            "'${declaration.key}' is missing from the ${table.platform} build ${table.build} offset " +
                "table, but is declared for every platform. Reverse engineer the value into " +
                "offsets/${table.platform}-${table.build}.json, or declare the property as " +
                "platform-scoped if the field does not exist in this client."
        )
    }

    private fun loadTable(name: String): Table {
        val json = readResource("$RESOURCE_DIR/$name.json")
            ?: error("Offset table resource '$RESOURCE_DIR/$name.json' is missing from the engine jar")

        val root = JsonParser.parseString(json).asJsonObject
        val values = LinkedHashMap<String, Long>()

        for ((objectName, fields) in root.getAsJsonObject("objects").entrySet()) {
            for ((fieldName, raw) in fields.asJsonObject.entrySet()) {
                values["$objectName.$fieldName"] = parseNumber(raw.asJsonObject.get("value").asString)
            }
        }

        val doActions = LinkedHashMap<String, DoActionEntry>()
        root.getAsJsonArray("doActions")?.forEach {
            val entry = it.asJsonObject
            val actionName = entry.get("name").asString
            doActions[actionName] = DoActionEntry(
                name = actionName,
                id = entry.get("id").asInt,
                method = entry.get("method")?.asString ?: actionName,
                rva = parseNumber(entry.get("value").asString),
            )
        }

        return Table(
            name = name,
            platform = root.get("platform").asString,
            build = root.get("build").asString,
            renderer = Renderer.ofTableValue(root.get("renderer")?.asString),
            values = values,
            doActions = doActions,
        )
    }

    private fun resolveTableName(): String {
        val requested = System.getProperty(BUILD_PROPERTY) ?: System.getenv(BUILD_ENV)
        if (requested != null) return "${Platform.key}-$requested"

        val available = bundledTableNames().filter { it.startsWith("${Platform.key}-") }
        if (available.size == 1) return available.single()
        if (available.isEmpty()) error(
            "No offset table bundled for ${Platform.key}. The engine has not been ported to " +
                "this platform, or the offsets resources were not packaged into the jar."
        )

        // The same build ships once per renderer, so the running client's own renderer tag decides.
        // Outside an injected client (unit tests, tools) there is no tag to read; the OpenGL table,
        // which every platform has, stands in. The engine attaches before touching any offset, so
        // this never decides the table inside a client.
        val renderer = if (NativeAccess.isAttached) ClientRenderer.current else Renderer.OPENGL
        val matching = available.filter { loadTable(it).renderer == renderer }
        return matching.singleOrNull() ?: error(
            "${matching.size} offset tables bundled for ${Platform.key} match the running ${renderer.id} client " +
                "(bundled: ${available.joinToString()}). Set -D$BUILD_PROPERTY or $BUILD_ENV to pick one."
        )
    }

    private fun readResource(path: String): String? =
        OffsetTable::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    private fun parseNumber(raw: String): Long {
        val negative = raw.startsWith("-")
        val body = raw.removePrefix("-")
        val magnitude =
            if (body.startsWith("0x") || body.startsWith("0X")) body.substring(2).toLong(16)
            else body.toLong()
        return if (negative) -magnitude else magnitude
    }
}

/** Marks the `O*` offset objects so a wrapper can size its window from the table through [extent]. */
interface OffsetObject

val OffsetObject.extent: Long
    get() = OffsetTable.extent(this)
