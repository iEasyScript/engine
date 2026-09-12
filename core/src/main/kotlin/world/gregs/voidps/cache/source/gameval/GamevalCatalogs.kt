package world.gregs.voidps.cache.source.gameval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The gameval catalogs of a tree, `gamevals/<type>.json`, read on demand.
 *
 * A catalog is the id to name table of one type - `obj`, `npc`, `varbit_player`, `component` - and
 * is what turns a bare number in an unpacked file into something a reader recognises. The tree
 * carries the catalogs as cache assets (the NXT beta host serves them as index 67), so they are
 * read from the tree and nowhere else; a tree unpacked from a cache without the index has them
 * seeded from the repository's own copies.
 *
 * Nothing here is a source of ids at pack time: a name is a display concern, written next to an
 * id and ignored when the file is read back.
 */
class GamevalCatalogs(private val directory: Path?) {

    private val catalogs = ConcurrentHashMap<String, Map<String, String>>()

    /** Whether the tree has a catalog for [type] at all. */
    fun has(type: String): Boolean = catalog(type).isNotEmpty()

    /** The name of [id] in [type]'s catalog, or null. */
    fun name(type: String, id: Int): String? = catalog(type)[id.toString()]

    /** The name of a component, whose catalog is keyed `<interface>:<component>`. */
    fun component(interfaceId: Int, componentId: Int): String? = catalog(COMPONENT)["$interfaceId:$componentId"]

    /** Every entry of [type]'s catalog, keyed as the file spells the key. */
    fun entries(type: String): Map<String, String> = catalog(type)

    private fun catalog(type: String): Map<String, String> {
        catalogs[type]?.let { return it }
        val loaded = load(type)
        return catalogs.putIfAbsent(type, loaded) ?: loaded
    }

    private fun load(type: String): Map<String, String> {
        val file = directory?.resolve("$type$EXTENSION") ?: return emptyMap()
        if (!Files.isRegularFile(file)) {
            return emptyMap()
        }
        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val entries = root[ENTRIES]?.jsonObject ?: return emptyMap()
        val names = LinkedHashMap<String, String>(entries.size * 2)
        for ((key, value) in entries) {
            names[key] = value.jsonPrimitive.content
        }
        return names
    }

    companion object {
        const val EXTENSION = ".json"
        const val ENTRIES = "entries"
        const val COMPONENT = "component"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
