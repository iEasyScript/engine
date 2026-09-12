package world.gregs.voidps.cache.cs2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.gameval.Gameval
import java.io.File

/**
 * One clientscript as the cache stores it, carrying the identity that survives a
 * rebuild.
 *
 * [nameHash] is the value index 12's reference table holds for the archive: the
 * plain Java string hash of Jagex's own `[category,name]` string. Script ids are
 * renumbered whenever the pack order changes, so the hash - not the id - is what
 * says two builds are talking about the same script.
 */
class Cs2RawScript(val id: Int, val nameHash: Int, val bytes: ByteArray)

/**
 * Every clientscript of one build, read once so a cross-build comparison can
 * hold both in memory at the same time.
 *
 * A synthetic corpus is a first-class citizen here: the unscrambler's own
 * rehearsal builds one by renumbering this build's opcodes, and it has to travel
 * the same code path a real new build would.
 */
class Cs2Corpus(
    val label: String,
    val format: Int,
    val revision: Int,
    val indexCrc: Int,
    val scripts: List<Cs2RawScript>,
) {

    val byId: Map<Int, Cs2RawScript> = scripts.associateBy { it.id }

    /** Scripts whose name hash the reference table carries and no sibling shares. */
    val byNameHash: Map<Int, Cs2RawScript> by lazy {
        val counts = HashMap<Int, Int>()
        for (script in scripts) counts[script.nameHash] = (counts[script.nameHash] ?: 0) + 1
        scripts.filter { it.nameHash != 0 && counts.getValue(it.nameHash) == 1 }.associateBy { it.nameHash }
    }

    fun rawScripts(): List<Pair<Int, ByteArray>> = scripts.map { it.id to it.bytes }

    /**
     * Checks the anchor the whole cross-build match rests on: that the hash the
     * reference table stores really is the hash of Jagex's own name for the
     * script, so a name recovered from any build finds the same script in every
     * other one.
     */
    fun checkNameHashes(): Pair<Int, Int> {
        var checked = 0
        var matched = 0
        for ((id, name) in Gameval.entries(Gameval.CLIENTSCRIPT)) {
            val script = byId[id] ?: continue
            checked++
            if (cs2NameHash(name) == script.nameHash) matched++
        }
        return checked to matched
    }

    companion object {
        fun of(cache: Cache, label: String): Cs2Corpus {
            val scripts = Cs2Cache(cache)
            val table = referenceTable(cache)
            val hashes = table?.archives.orEmpty().associate { it.id to it.nameHash }
            val out = ArrayList<Cs2RawScript>()
            for (id in scripts.scriptIds()) {
                val raw = try {
                    scripts.raw(id) ?: continue
                } catch (e: Exception) {
                    continue
                }
                out.add(Cs2RawScript(id, hashes[id] ?: 0, raw))
            }
            return Cs2Corpus(label, table?.format ?: 0, table?.revision ?: 0, Cs2OpcodeTable.indexCrc(cache), out)
        }

        /** Index 12's reference table, or null when the cache cannot produce one. */
        private fun referenceTable(cache: Cache): ReferenceTable? = try {
            cache.sector(255, Index.CLIENT_SCRIPTS)?.let {
                ReferenceTable.decode(Container.decode(it, trailer = false).data())
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The client build the working tree is currently aimed at.
 *
 * The engine's offset tables are named for the build they were lifted from and
 * are regenerated on update day, which makes their index the one place in the
 * tree that already has to be right about this.
 */
object Cs2BuildId {

    private val TABLE = Regex("""-(\d+-\d+)$""")

    fun current(): String? {
        val index = File("client-plugin-engine/src/main/resources/offsets/index.json")
        if (!index.isFile) return null
        val tables = Json.parseToJsonElement(index.readText())
            .jsonObject["tables"]?.jsonArray.orEmpty()
            .mapNotNull { TABLE.find(it.jsonPrimitive.content)?.groupValues?.get(1) }
        return tables.distinct().singleOrNull() ?: tables.maxOrNull()
    }
}

/**
 * The hash JS5 stores a name under: the plain Java string hash over the CP1252
 * bytes of Jagex's own `[category,name]` spelling.
 */
fun cs2NameHash(name: String): Int {
    var hash = 0
    for (char in name) hash = hash * 31 + (char.code and 0xFF)
    return hash
}
