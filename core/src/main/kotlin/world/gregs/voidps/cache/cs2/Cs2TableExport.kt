package world.gregs.voidps.cache.cs2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Everything one build's opcode read established, written where it survives the
 * build that produced it.
 *
 * The working table under `data/cs2` is keyed to a cache CRC and is not kept, so
 * on the day the ids are scrambled again the effort that solved them would be
 * gone and there would be nothing left to unscramble *from*. This is the
 * distilled result: generated, never edited, and committed so a later build has
 * an anchor and a reader has a history.
 *
 * It is data rather than prose on purpose. The unscrambler consumes it, so
 * anything worth saying about an opcode has to be said in a field.
 */
object Cs2TableExport {

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private val folder = File("re-resources/cs2")

    private const val SCHEMA = 1

    fun file(build: String): File = folder.resolve("opcodes-$build.json")

    fun mapFile(from: String, to: String): File = folder.resolve("opcode-map-${from}_to_$to.json")

    /** The committed table solved against the cache whose index-12 CRC is [indexCrc], if any build's was. */
    fun committedFor(indexCrc: Int): File? =
        folder.listFiles { file -> file.name.startsWith("opcodes-") && file.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .firstOrNull { file ->
                Json.parseToJsonElement(file.readText()).jsonObject["cache"]
                    ?.jsonObject?.get("indexCrc")?.jsonPrimitive?.content?.toIntOrNull() == indexCrc
            }

    class Evidence(
        val names: Map<Int, Map<String, String>>,
        val behaviour: Map<Int, Map<String, String>>,
        val reference: Map<Int, Map<String, String>>,
        val effects: Map<Int, Map<String, String>>,
        val sources: List<File>,
    ) {
        /**
         * The same evidence, re-keyed onto another build's numbering.
         *
         * Nothing is rewritten but the key. What a handler read established is
         * still what it established; the only thing the new build changed is
         * which number it answers to.
         */
        fun carriedThrough(mapping: Map<Int, Int>): Evidence {
            fun rekey(rows: Map<Int, Map<String, String>>): Map<Int, Map<String, String>> =
                mapping.entries.mapNotNull { (newId, oldId) -> rows[oldId]?.let { newId to it } }.toMap()
            return Evidence(rekey(names), rekey(behaviour), rekey(reference), rekey(effects), sources)
        }

        companion object {
            fun read(folder: File): Evidence {
                fun rows(name: String): Pair<Map<Int, Map<String, String>>, File?> {
                    val file = folder.resolve(name)
                    if (!file.isFile) return emptyMap<Int, Map<String, String>>() to null
                    val out = HashMap<Int, Map<String, String>>()
                    for (row in Cs2Csv.read(file)) {
                        val id = row["opcode"]?.toIntOrNull() ?: continue
                        out[id] = row
                    }
                    return out to file
                }
                val (names, namesFile) = rows("opcode-names.csv")
                val (behaviour, behaviourFile) = rows("opcode-behaviour.csv")
                val (reference, referenceFile) = rows("opcode-reference-names.csv")
                val (effects, effectsFile) = rows("stack-effects.csv")
                return Evidence(
                    names, behaviour, reference, effects,
                    listOfNotNull(namesFile, behaviourFile, referenceFile, effectsFile),
                )
            }
        }
    }

    class Stamp(val command: String, val installedTable: File?, val installedSource: String?)

    fun write(
        build: String,
        corpus: Cs2Corpus,
        entries: List<Cs2OpcodeEntry>,
        unresolvedWidths: Map<Int, Int>,
        evidence: Evidence,
        stamp: Stamp,
    ): File {
        folder.mkdirs()
        val sorted = entries.sortedBy { it.id }
        val root = buildJsonObject {
            put("schema", SCHEMA)
            put("build", build)
            put("cache", cacheStamp(corpus))
            put("generatedBy", generation(stamp, evidence))
            put("summary", summary(sorted, corpus))
            put("opcodes", buildJsonArray { sorted.forEach { add(opcode(it, evidence)) } })
            put("withheld", withheld(evidence))
            put("effectCandidates", effectCandidates(evidence, sorted))
            put(
                "unresolvedWidths",
                buildJsonArray {
                    unresolvedWidths.toSortedMap().forEach { (id, occurrences) ->
                        add(buildJsonObject { put("id", id); put("occurrences", occurrences) })
                    }
                },
            )
        }
        val target = file(build)
        target.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
        return target
    }

    private fun cacheStamp(corpus: Cs2Corpus): JsonObject = buildJsonObject {
        put("indexRefTableFormat", corpus.format)
        put("indexRevision", corpus.revision)
        put("indexCrc", corpus.indexCrc)
        put("scriptsWithNameHash", corpus.scripts.count { it.nameHash != 0 })
        put("scripts", corpus.scripts.size)
    }

    private fun generation(stamp: Stamp, evidence: Evidence): JsonObject = buildJsonObject {
        put("command", stamp.command)
        stamp.installedSource?.let { put("installedTableSource", it) }
        put(
            "inputs",
            buildJsonArray {
                for (file in listOfNotNull(stamp.installedTable) + evidence.sources) {
                    add(
                        buildJsonObject {
                            put("path", file.invariantPath())
                            put("sha256", sha256(file))
                        },
                    )
                }
            },
        )
    }

    private fun summary(entries: List<Cs2OpcodeEntry>, corpus: Cs2Corpus): JsonObject = buildJsonObject {
        put("opcodes", entries.size)
        put(
            "byTier",
            buildJsonObject {
                val counts = entries.groupingBy { tier(it.naming) }.eachCount()
                for (origin in Cs2NameOrigin.entries) put(tierName(origin), counts[origin] ?: 0)
            },
        )
        put(
            "byOperand",
            buildJsonObject {
                val counts = entries.groupingBy { it.operand }.eachCount().toSortedMap()
                for ((operand, count) in counts) put(operand.name, count)
            },
        )
        put("withStackEffect", entries.count { it.effect != null })
        put("withArgTypes", entries.count { it.argTypes.isNotEmpty() })
        put("corpusScripts", corpus.scripts.size)
        put("naming", namingReach(entries, corpus))
    }

    /**
     * How much of the corpus the names actually reach.
     *
     * Counting opcodes flatters the table: the ones still unnamed are the ones
     * hardly any script calls. Counting call sites is the honest measure, and it
     * is the number a later build has to be held to.
     */
    private fun namingReach(entries: List<Cs2OpcodeEntry>, corpus: Cs2Corpus): JsonObject {
        val widths = entries.associate { it.id to it.operand }
        val unnamed = entries.filter { it.naming.isEmpty }.mapTo(HashSet()) { it.id }
        var sites = 0
        var unnamedSites = 0
        val unnamedUsed = HashSet<Int>()
        for (script in corpus.scripts) {
            val walked = Cs2Walk.walk(script.id, script.bytes, widths) ?: continue
            sites += walked.size
            for (opcode in walked.opcodes) {
                if (opcode !in unnamed) continue
                unnamedSites++
                unnamedUsed.add(opcode)
            }
        }
        return buildJsonObject {
            put("callSites", sites)
            put("unnamedCallSites", unnamedSites)
            put("unnamedOpcodesReached", unnamedUsed.size)
            put("unnamedShare", "%.4f%%".format(unnamedSites * 100.0 / sites.coerceAtLeast(1)))
        }
    }

    private fun tier(naming: Cs2Naming): Cs2NameOrigin = naming.origin

    private fun tierName(origin: Cs2NameOrigin): String = origin.name.lowercase()

    private fun opcode(entry: Cs2OpcodeEntry, evidence: Evidence): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("operand", entry.operand.name)
        entry.operand.fixedBytes?.let { put("operandBytes", it) }
        preferredName(entry.naming)?.let { put("name", it) }
        put("tier", tierName(entry.naming.origin))
        val names = buildJsonObject {
            entry.naming.canonical?.let { put("canonical", it) }
            entry.naming.derived?.let { put("derived", it) }
            entry.naming.reference?.let { put("reference", it) }
            entry.naming.structural?.let { put("structural", it) }
        }
        if (names.isNotEmpty()) put("names", names)
        if (entry.kind != OpKind.NORMAL) put("kind", entry.kind.name)
        if (entry.hookTrailingPops != 0) put("hookTrailingPops", entry.hookTrailingPops)
        entry.effect?.let { effect ->
            put(
                "stackEffect",
                buildJsonObject {
                    put("popInt", effect.popInt)
                    put("popStr", effect.popStr)
                    put("popLong", effect.popLong)
                    put("pushInt", effect.pushInt)
                    put("pushStr", effect.pushStr)
                    put("pushLong", effect.pushLong)
                },
            )
        }
        entry.confidence?.let { put("stackEffectConfidence", it.name) }
        if (entry.argTypes.isNotEmpty()) {
            put("argTypes", buildJsonArray { entry.argTypes.forEach { add(JsonPrimitive(it.name)) } })
        }
        val provenance = provenance(entry.id, evidence)
        if (provenance.isNotEmpty()) put("evidence", provenance)
    }

    private fun provenance(id: Int, evidence: Evidence): JsonObject = buildJsonObject {
        evidence.names[id]?.let { row ->
            row.text("evidence")?.let { put("canonicalName", it) }
            row.text("derivation")?.let { put("derivation", it) }
        }
        evidence.behaviour[id]?.let { row ->
            row.text("nameEvidence")?.let { put("structuralName", it) }
            row.text("argEvidence")?.let { put("structuralArgTypes", it) }
            row.text("confidence")?.let { put("structuralConfidence", it) }
        }
        evidence.reference[id]?.let { row ->
            row.text("nameEvidence")?.let { put("referenceName", it) }
            row.text("argEvidence")?.let { put("referenceArgTypes", it) }
            row.text("matchConfidence")?.let { put("referenceConfidence", it) }
        }
        evidence.effects[id]?.let { row -> row.text("evidence")?.let { put("stackEffect", it) } }
    }

    /**
     * The records a read reached a set of readings on and deliberately named
     * none of. They are findings: the next build inherits the same open question
     * and re-deriving the candidate set from scratch would cost what deriving it
     * cost the first time.
     */
    private fun withheld(evidence: Evidence): JsonArray = buildJsonArray {
        val withheld = evidence.reference
            .filterValues { it["name"].isNullOrEmpty() && !it["matchConfidence"].isNullOrEmpty() }
            .toSortedMap()
        for ((id, row) in withheld) {
            add(
                buildJsonObject {
                    put("id", id)
                    put("reason", row.getValue("matchConfidence"))
                    val candidates = CANDIDATES.find(row["nameEvidence"].orEmpty())
                        ?.groupValues?.get(1)
                        ?.split('|')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    if (candidates.isNotEmpty()) {
                        put("candidates", buildJsonArray { candidates.forEach { add(JsonPrimitive(it)) } })
                    }
                    row.text("nameEvidence")?.let { put("evidence", it) }
                },
            )
        }
    }

    private val CANDIDATES = Regex("""Candidates:\s*([^.]+)\.""")

    /**
     * Signatures the handler read left open, with the reading the corpus chose.
     * The alternatives are why that choice is a choice and not a measurement.
     */
    private fun effectCandidates(evidence: Evidence, entries: List<Cs2OpcodeEntry>): JsonArray {
        val chosen = entries.associate { it.id to it.effect }
        val candidates = Cs2StackEffectImport.candidates(evidence.effects).toSortedMap()
        return buildJsonArray {
            for ((id, options) in candidates) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put(
                            "candidates",
                            buildJsonArray {
                                options.forEach { option ->
                                    add(buildJsonArray { option.counts.forEach { add(JsonPrimitive(it)) } })
                                }
                            },
                        )
                        chosen[id]?.let { effect ->
                            put("chosen", buildJsonArray { effect.counts.forEach { add(JsonPrimitive(it)) } })
                        }
                        evidence.effects[id]?.text("evidence")?.let { put("evidence", it) }
                    },
                )
            }
        }
    }

    fun preferredName(naming: Cs2Naming): String? =
        naming.derived ?: naming.canonical ?: naming.reference ?: naming.structural?.lowercase()

    private fun Map<String, String>.text(key: String): String? = this[key]?.ifBlank { null }

    /** Paths are recorded relative to the repository root so the file is reproducible anywhere. */
    private fun File.invariantPath(): String = path.removePrefix("./")

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Reads a committed table back, so a later build can be derived from it. */
    fun read(file: File): Pair<String, List<Cs2OpcodeEntry>> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val build = root.getValue("build").jsonPrimitive.content
        val entries = root.getValue("opcodes").jsonArray.map { element ->
            val entry = element.jsonObject
            Cs2OpcodeEntry(
                id = entry.getValue("id").jsonPrimitive.int(),
                operand = Cs2Operand.valueOf(entry.getValue("operand").jsonPrimitive.content),
                naming = entry["names"]?.jsonObject.let { names ->
                    Cs2Naming(
                        canonical = names?.get("canonical")?.jsonPrimitive?.content,
                        derived = names?.get("derived")?.jsonPrimitive?.content,
                        reference = names?.get("reference")?.jsonPrimitive?.content,
                        structural = names?.get("structural")?.jsonPrimitive?.content,
                    )
                },
                kind = entry["kind"]?.jsonPrimitive?.content?.let { OpKind.valueOf(it) } ?: OpKind.NORMAL,
                effect = entry["stackEffect"]?.jsonObject?.let { effect ->
                    StackEffect(
                        popInt = effect.getValue("popInt").jsonPrimitive.int(),
                        popStr = effect.getValue("popStr").jsonPrimitive.int(),
                        popLong = effect.getValue("popLong").jsonPrimitive.int(),
                        pushInt = effect.getValue("pushInt").jsonPrimitive.int(),
                        pushStr = effect.getValue("pushStr").jsonPrimitive.int(),
                        pushLong = effect.getValue("pushLong").jsonPrimitive.int(),
                    )
                },
                confidence = entry["stackEffectConfidence"]?.jsonPrimitive?.content
                    ?.let { Cs2Confidence.valueOf(it) },
                argTypes = entry["argTypes"]?.jsonArray.orEmpty()
                    .map { ArgType.valueOf(it.jsonPrimitive.content) },
                hookTrailingPops = entry["hookTrailingPops"]?.jsonPrimitive?.int() ?: 0,
            )
        }
        return build to entries
    }

    private fun JsonPrimitive.int(): Int = content.toInt()
}
