package org.projectx.tools.cs2

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.gregs.voidps.cache.cs2.Cs2Corpus
import world.gregs.voidps.cache.cs2.Cs2OpcodeEntry
import world.gregs.voidps.cache.cs2.Cs2TableExport

/**
 * The cross-build opcode map, written beside the tables it joins.
 *
 * The mapping itself is the smaller half of what this is for. The two lists it
 * ends with - opcodes the new build has that nothing maps onto, and opcodes the
 * old build had that nothing carries forward - are what makes an update day
 * tractable, because they say how much reverse engineering is actually left
 * instead of leaving it to be discovered one wrong decompilation at a time.
 */
object Cs2MapExport {

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private const val SCHEMA = 1

    class RoundTrip(val scripts: Int, val identical: Int, val failed: Int) {
        val ok: Boolean get() = failed == 0 && identical == scripts
    }

    fun write(
        fromBuild: String,
        toBuild: String,
        from: Cs2Corpus,
        to: Cs2Corpus,
        widths: Cs2Unscrambler.WidthCheck,
        mapping: Cs2Unscrambler.Mapping,
        carried: List<Cs2OpcodeEntry>,
        old: List<Cs2OpcodeEntry>,
        roundTrip: RoundTrip,
        occurrences: Map<Int, Int>,
        command: String,
        synthetic: Boolean = false,
    ): File {
        val oldById = old.associateBy { it.id }
        val newById = carried.associateBy { it.id }
        val root = buildJsonObject {
            put("schema", SCHEMA)
            put("command", command)
            if (synthetic) put("synthetic", true)
            put("from", corpusStamp(fromBuild, from))
            put("to", corpusStamp(toBuild, to))
            put("widths", widthStamp(widths))
            put(
                "verification",
                buildJsonObject {
                    put("injective", mapping.injective)
                    put("widthSourcesAgree", widths.sound)
                    put(
                        "roundTrip",
                        buildJsonObject {
                            put("scripts", roundTrip.scripts)
                            put("identical", roundTrip.identical)
                            put("failed", roundTrip.failed)
                        },
                    )
                },
            )
            put(
                "coverage",
                buildJsonObject {
                    put("scriptsMatchedByNameHash", mapping.matchedPairs)
                    put("pairsAligned", mapping.alignedPairs)
                    put("pairsDiscardedAsContradicting", mapping.poisonedPairs)
                    put("pairsEdited", mapping.changedPairs)
                    put("opcodesInNewCorpus", mapping.newOpcodes.size)
                    put("opcodesInOldCorpus", mapping.oldOpcodes.size)
                    put("mapped", mapping.confirmed.size)
                    put("mappedBySecondPass", mapping.fromSecondPass.size)
                    put("mappedOnASingleWitness", mapping.singleWitness.size)
                    put("contested", mapping.contested.size)
                    put("added", mapping.added.size)
                    put("removed", mapping.removed.size)
                    put("oldOpcodesNoScriptReaches", old.count { it.id !in mapping.oldOpcodes })
                },
            )
            put(
                "mapping",
                buildJsonArray {
                    for ((newId, oldId) in mapping.confirmed) {
                        add(
                            buildJsonObject {
                                put("new", newId)
                                put("old", oldId)
                                put("witnesses", mapping.witnesses[newId] ?: 0)
                                put("sites", mapping.sites[newId] ?: 0)
                                put("pass", if (newId in mapping.fromSecondPass) 2 else 1)
                                oldById[oldId]?.naming?.let { naming ->
                                    Cs2TableExport.preferredName(naming)?.let { put("name", it) }
                                    put("tier", naming.origin.name.lowercase())
                                }
                            },
                        )
                    }
                },
            )
            put(
                "added",
                buildJsonArray {
                    for (id in mapping.added) {
                        add(
                            buildJsonObject {
                                put("id", id)
                                newById[id]?.let { put("operand", it.operand.name) }
                                put("scripts", occurrences[id] ?: 0)
                                mapping.suggested[id]?.let { (oldId, votes) ->
                                    put("suggestedOld", oldId)
                                    put("suggestedWitnesses", votes)
                                    oldById[oldId]?.naming?.let { naming ->
                                        Cs2TableExport.preferredName(naming)?.let { put("suggestedName", it) }
                                    }
                                }
                            },
                        )
                    }
                },
            )
            put(
                "removed",
                buildJsonArray {
                    for (id in mapping.removed) {
                        add(
                            buildJsonObject {
                                put("id", id)
                                oldById[id]?.let { entry ->
                                    put("operand", entry.operand.name)
                                    Cs2TableExport.preferredName(entry.naming)?.let { put("name", it) }
                                    put("tier", entry.naming.origin.name.lowercase())
                                }
                            },
                        )
                    }
                },
            )
            put(
                "contested",
                buildJsonArray {
                    for ((newId, options) in mapping.contested) {
                        add(
                            buildJsonObject {
                                put("new", newId)
                                put(
                                    "candidates",
                                    buildJsonArray {
                                        for ((oldId, votes) in options) {
                                            add(
                                                buildJsonObject {
                                                    put("old", oldId)
                                                    put("witnesses", votes)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        val target = Cs2TableExport.mapFile(fromBuild, toBuild)
        target.parentFile.mkdirs()
        target.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
        return target
    }

    private fun corpusStamp(build: String, corpus: Cs2Corpus): JsonObject = buildJsonObject {
        put("build", build)
        put("indexRevision", corpus.revision)
        put("indexCrc", corpus.indexCrc)
        put("scripts", corpus.scripts.size)
    }

    private fun widthStamp(widths: Cs2Unscrambler.WidthCheck): JsonObject = buildJsonObject {
        put("binarySourceAvailable", widths.binaryAvailable)
        put("solvedByCorpus", widths.agreed.size + widths.disagreed.size + widths.indistinguishable.size + widths.corpusOnly.size)
        put("agreeing", widths.agreed.size)
        put("sameLengthEncodings", widths.indistinguishable.size)
        put("corpusOnly", widths.corpusOnly.size)
        put("binaryOnly", widths.binaryOnly.size)
        put("leftAmbiguousByCorpus", widths.corpusAmbiguous.size)
        put(
            "disagreeing",
            buildJsonArray {
                for ((id, readings) in widths.disagreed.toSortedMap()) {
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("corpus", readings.first.name)
                            put("binary", readings.second.name)
                        },
                    )
                }
            },
        )
        put(
            "byEncoding",
            buildJsonObject {
                val counts = widths.widths.values.groupingBy { it }.eachCount().toSortedMap()
                for ((operand, count) in counts) put(operand.name, count)
            },
        )
    }
}
