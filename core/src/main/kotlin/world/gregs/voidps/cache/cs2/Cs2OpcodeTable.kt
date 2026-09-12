package world.gregs.voidps.cache.cs2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.io.File

/**
 * The opcode set the running cache actually uses, keyed by the index-12
 * reference-table CRC so a solve survives exactly as long as the cache it was
 * solved against.
 */
data class Cs2OpcodeEntry(
    val id: Int,
    val operand: Cs2Operand,
    val naming: Cs2Naming = Cs2Naming.NONE,
    val kind: OpKind = OpKind.NORMAL,
    /** Pop/push counts, or null while nothing has settled them. */
    val effect: StackEffect? = null,
    val confidence: Cs2Confidence? = null,
    /** Meaning of each argument, in push order; empty where nothing vouches for one. */
    val argTypes: List<ArgType> = emptyList(),
    /** See [Cs2Op.hookTrailingPops]. */
    val hookTrailingPops: Int = 0,
)

object Cs2OpcodeTable {

    private val folder = File("./data/cs2")
    private val json = Json { prettyPrint = true }

    fun indexCrc(cache: Cache): Int = cache.indexCrcs()[Index.CLIENT_SCRIPTS]

    fun file(cache: Cache): File = folder.resolve("opcodes-${indexCrc(cache)}.json")

    fun namesFile(): File = folder.resolve("opcode-names.csv")

    fun load(cache: Cache): List<Cs2OpcodeEntry>? {
        val file = file(cache)
        if (!file.isFile) return Cs2TableExport.committedFor(indexCrc(cache))?.let { Cs2TableExport.read(it).second }
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("opcodes").jsonArray.map { element ->
            val entry = element.jsonObject
            val id = entry.getValue("id").jsonPrimitive.int
            Cs2OpcodeEntry(
                id = id,
                operand = Cs2Operand.valueOf(entry.getValue("operand").jsonPrimitive.content),
                naming = Cs2Naming(
                    canonical = entry.text("canonical"),
                    derived = entry.text("derived"),
                    reference = entry.text("reference"),
                    structural = entry.text("structural")?.let(Cs2CoreOps::normalise) ?: entry.legacyName(id),
                ),
                kind = entry.text("kind")?.let { OpKind.valueOf(it) } ?: OpKind.NORMAL,
                effect = entry["effect"]?.jsonArray?.toEffect(),
                confidence = entry.text("confidence")?.let { Cs2Confidence.valueOf(it) },
                argTypes = entry["argTypes"]?.jsonArray.orEmpty()
                    .map { ArgType.valueOf(it.jsonPrimitive.content) },
                hookTrailingPops = entry["hookTrailingPops"]?.jsonPrimitive?.int ?: 0,
            )
        }
    }

    fun save(cache: Cache, source: String, entries: List<Cs2OpcodeEntry>, unresolved: Map<Int, Int>) {
        folder.mkdirs()
        val root = buildJsonObject {
            put("indexCrc", indexCrc(cache))
            put("source", source)
            put("opcodes", entries.sortedBy { it.id }.toJsonArray())
            put("unresolved", unresolved.toSortedMap().toJsonArray())
        }
        file(cache).writeText(json.encodeToString(JsonObject.serializer(), root))
    }

    /** Rewrites the table in place, keeping the provenance fields the file already carries. */
    fun update(cache: Cache, entries: List<Cs2OpcodeEntry>) {
        save(cache, source(cache) ?: "unknown", entries, unresolved(cache))
    }

    /** Opcodes whose operand width the corpus solve could not pin, and how often each is used. */
    fun unresolved(cache: Cache): Map<Int, Int> {
        val file = file(cache)
        if (!file.isFile) return emptyMap()
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root["unresolved"]?.jsonArray.orEmpty().associate { element ->
            val entry = element.jsonObject
            entry.getValue("id").jsonPrimitive.int to entry.getValue("occurrences").jsonPrimitive.int
        }
    }

    /** Reads the file's `source` field without building the whole table. */
    fun source(cache: Cache): String? {
        val file = file(cache)
        if (!file.isFile) return Cs2TableExport.committedFor(indexCrc(cache))?.let { "committed:${it.name}" }
        return json.parseToJsonElement(file.readText())
            .jsonObject["source"]?.jsonPrimitive?.contentOrNull
    }

    /** Replaces every entry's stack effect, keeping its encoding and names. */
    /**
     * Installs the stack effects read out of the handlers.
     *
     * [unsettled] names the opcodes this same read still offers several readings
     * for. A corpus choice was made between exactly those readings, so re-reading
     * the file does not answer it and must not throw it away; a read that has
     * since settled on one signature is a better answer and wins.
     */
    fun reeffect(
        entries: List<Cs2OpcodeEntry>,
        solved: Map<Int, Cs2OpcodeEntry>,
        unsettled: Set<Int> = emptySet(),
    ): List<Cs2OpcodeEntry> =
        entries.map { entry ->
            val effect = solved[entry.id]
                ?: return@map entry.copy(
                    kind = OpKind.NORMAL, effect = null, confidence = null, hookTrailingPops = 0,
                )
            if (entry.confidence == Cs2Confidence.CORPUS_CHOSEN && entry.id in unsettled) return@map entry
            entry.copy(
                kind = Cs2CoreOps.kindOf(entry.naming) ?: effect.kind,
                effect = effect.effect,
                confidence = effect.confidence,
                hookTrailingPops = effect.hookTrailingPops,
            )
        }

    /**
     * Installs the names and argument types read out of the handlers' behaviour.
     *
     * These go in the structural tier, never in [Cs2Naming.canonical]: that field
     * is reserved for strings recovered verbatim from the client, and a name
     * worked out from what a handler does is this toolchain's reading of it.
     * Where Jagex's own name for the handler is already known the read's name is
     * superseded outright, so the entry keeps it whichever order the two imports
     * are run in. What the arguments mean is a separate question the name answers
     * nothing about, so [refined] still applies the read there.
     */
    fun rebehave(entries: List<Cs2OpcodeEntry>, behaviour: Map<Int, Cs2Behaviour>): List<Cs2OpcodeEntry> =
        entries.map { entry ->
            val read = behaviour[entry.id] ?: return@map entry
            if (entry.naming.reference != null) {
                return@map entry.copy(argTypes = refined(entry.argTypes, read.argTypes))
            }
            entry.copy(
                naming = entry.naming.copy(structural = read.name ?: entry.naming.structural),
                argTypes = read.argTypes.ifEmpty { entry.argTypes },
            )
        }

    /**
     * The handler read filling in the slots an entry still holds as plain ints.
     *
     * A slot the entry already types is left alone, and two readings of different
     * lengths are left to disagree rather than one being picked: neither is more
     * specific than the other and the disagreement is itself the finding.
     */
    private fun refined(existing: List<ArgType>, read: List<ArgType>): List<ArgType> =
        if (existing.isEmpty() || read.size != existing.size) existing
        else existing.mapIndexed { at, type -> if (type == ArgType.INT) read[at] else type }

    /** Replaces each entry's recovered names, keeping the ones it already carries. */
    fun rename(entries: List<Cs2OpcodeEntry>, recovered: Map<Int, Cs2Naming>): List<Cs2OpcodeEntry> =
        entries.map { entry ->
            val naming = recovered[entry.id] ?: Cs2Naming.NONE
            entry.copy(
                naming = naming.copy(
                    reference = entry.naming.reference,
                    structural = entry.naming.structural,
                ),
            )
        }

    /**
     * Installs the names Jagex's own symbols carry for handlers matched onto this
     * build, and the argument types that came with them.
     *
     * A name recovered from this build outranks one carried over from another, so
     * [Cs2Naming.canonical] is left where it stands. A structural name is this
     * toolchain's own reading and gives way to Jagex's word for the same handler.
     */
    fun rereference(entries: List<Cs2OpcodeEntry>, recovered: Map<Int, Cs2Reference>): List<Cs2OpcodeEntry> =
        entries.map { entry ->
            val read = recovered[entry.id] ?: return@map entry
            entry.copy(
                naming = entry.naming.copy(reference = read.name, structural = null),
                argTypes = mergeArgTypes(entry.argTypes, read.argTypes),
            )
        }

    /**
     * A reference row names the argument shape; a slot it leaves as a plain int
     * keeps whatever the handler read had already made of it, which is never less
     * specific.
     */
    private fun mergeArgTypes(existing: List<ArgType>, read: List<ArgType>?): List<ArgType> = when {
        read == null -> existing
        read.size != existing.size -> read
        else -> read.mapIndexed { at, type -> if (type == ArgType.INT) existing[at] else type }
    }

    fun install(entries: List<Cs2OpcodeEntry>) {
        val identifiers = Cs2Identifiers.assign(entries.associate { it.id to it.naming })
        Cs2Opcodes.install(
            entries.map { entry ->
                val identifier = identifiers.getValue(entry.id)
                val kind = Cs2CoreOps.kindOf(entry.naming) ?: entry.kind
                val effect = Cs2CoreOps.effectOf(entry.naming) ?: entry.effect ?: StackEffect()
                Cs2Op(
                    id = entry.id,
                    opName = identifier.opName,
                    tsName = identifier.tsName,
                    largeOperand = entry.operand == Cs2Operand.INT,
                    kind = kind,
                    popInt = effect.popInt, popStr = effect.popStr, popLong = effect.popLong,
                    pushInt = effect.pushInt, pushStr = effect.pushStr, pushLong = effect.pushLong,
                    hasTriggerArray = kind == OpKind.HOOK,
                    hookTrailingPops = entry.hookTrailingPops,
                    argTypes = entry.argTypes,
                    operand = entry.operand,
                    naming = entry.naming,
                )
            },
        )
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** Tables written before names were split by provenance stored one bare `name`. */
    private fun JsonObject.legacyName(id: Int): String? =
        text("name")?.takeIf { it != "OP_$id" }?.let(Cs2CoreOps::normalise)

    private fun JsonArray.toEffect(): StackEffect {
        val values = map { it.jsonPrimitive.int }
        return StackEffect(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private fun List<Cs2OpcodeEntry>.toJsonArray(): JsonArray = buildJsonArray {
        for (entry in this@toJsonArray) {
            add(
                buildJsonObject {
                    put("id", entry.id)
                    put("operand", entry.operand.name)
                    entry.naming.canonical?.let { put("canonical", it) }
                    entry.naming.derived?.let { put("derived", it) }
                    entry.naming.reference?.let { put("reference", it) }
                    entry.naming.structural?.let { put("structural", it) }
                    if (entry.kind != OpKind.NORMAL) put("kind", entry.kind.name)
                    if (entry.argTypes.isNotEmpty()) {
                        put(
                            "argTypes",
                            buildJsonArray {
                                for (type in entry.argTypes) add(JsonPrimitive(type.name))
                            },
                        )
                    }
                    entry.confidence?.let { put("confidence", it.name) }
                    if (entry.hookTrailingPops != 0) put("hookTrailingPops", entry.hookTrailingPops)
                    entry.effect?.let { effect ->
                        put(
                            "effect",
                            buildJsonArray {
                                for (count in effect.counts) add(JsonPrimitive(count))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun Map<Int, Int>.toJsonArray(): JsonArray = buildJsonArray {
        for ((id, occurrences) in this@toJsonArray) {
            add(
                buildJsonObject {
                    put("id", id)
                    put("occurrences", occurrences)
                },
            )
        }
    }
}

/**
 * Reads an opcode table exported from the client's own dispatch table.
 *
 * The dispatch entry carries a wide-operand flag, but three families are decided
 * before it is consulted, so the exporter resolves each entry to a concrete
 * encoding and this only has to map the names it uses.
 */
object Cs2DispatchTableImport {

    private val encodings = mapOf(
        "u8" to Cs2Operand.BYTE,
        "i32" to Cs2Operand.INT,
        "u16 varbitId + u8" to Cs2Operand.TRIBYTE,
        "u24 varbitId + u8" to Cs2Operand.WIDE_VARBIT,
        "u8 domain + u16 varId + u8" to Cs2Operand.VAR,
    )

    fun read(file: File): List<Cs2OpcodeEntry> = Cs2Csv.read(file).map { row ->
        val id = row.getValue("opcode").toInt()
        val encoding = row.getValue("operandEncoding")
        Cs2OpcodeEntry(
            id = id,
            operand = encodings[encoding]
                ?: if (encoding.startsWith("u8 tag")) Cs2Operand.TAGGED else error("Unknown encoding '$encoding' for opcode $id"),
        )
    }
}
