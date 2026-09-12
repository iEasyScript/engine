package org.projectx.tools.cacheunpack

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.TypeDecoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decodes one type in ascending id order and streams it straight to disk.
 *
 * Most ids in a scanned range have no archive behind them, so writing every id would bury the real
 * records in empty rows. A definition that decodes to nothing is therefore only dropped once the
 * cache confirms it has no data — a record that genuinely holds nothing but its defaults, which
 * params are full of, is real and is kept.
 */
class TypeUnpacker(
    private val json: DefinitionJson,
    /** Swaps a decoded definition for the object actually rendered, so a type whose decoder leaves a blob can still be spelt out. */
    private val project: (CacheType) -> Any = { it },
) {

    class Result(
        val records: Int,
        val scanned: Int,
        val defaulted: Int,
        val failures: Int,
        val firstFailure: String?,
        val bytes: Long,
    )

    @Suppress("UNCHECKED_CAST")
    fun unpack(decoder: TypeDecoder<out CacheType>, cache: Cache, file: Path): Result =
        stream(decoder as TypeDecoder<CacheType>, cache, file)

    private fun stream(decoder: TypeDecoder<CacheType>, cache: Cache, file: Path): Result {
        Files.createDirectories(file.parent)
        val scanned = decoder.size(cache) + 1
        if (scanned <= 0) {
            Files.writeString(file, "")
            return Result(0, 0, 0, 0, null, 0)
        }
        val definitions = decoder.create(scanned)
        val blank = json.body(project(decoder.create(1)[0]))
        val recycles = !crossReferences(decoder)
        var records = 0
        var defaulted = 0
        var failures = 0
        var firstFailure: String? = null

        Files.newBufferedWriter(file).use { writer ->
            for (id in 0 until scanned) {
                val body = try {
                    decoder.load(definitions, cache, id)
                    json.body(project(definitions[id]))
                } catch (e: Throwable) {
                    failures++
                    if (firstFailure == null) firstFailure = "${e::class.simpleName}: ${e.message}"
                    continue
                }
                if (recycles) release(definitions, id)
                if (body == blank) {
                    if (!present(decoder, cache, id)) continue
                    defaulted++
                }
                writer.write(json.line(id, body))
                writer.newLine()
                records++
            }
        }
        return Result(records, scanned, defaulted, failures, firstFailure, Files.size(file))
    }

    /** Exactly the lookup [TypeDecoder.load] itself performs, so presence is answered by the cache. */
    private fun present(decoder: TypeDecoder<CacheType>, cache: Cache, id: Int): Boolean =
        cache.data(decoder.index, decoder.getArchive(id), decoder.getFile(id)) != null

    /**
     * A decoder that reads its neighbours during [TypeDecoder.changeValues] needs the whole array to
     * stay populated; every other decoder can have each slot dropped the moment it is written, which
     * is what keeps pixel-heavy types from holding an entire index in memory at once.
     */
    private fun crossReferences(decoder: TypeDecoder<CacheType>): Boolean =
        decoder.javaClass.methods.any { it.name == "changeValues" && it.declaringClass != TypeDecoder::class.java }

    @Suppress("UNCHECKED_CAST")
    private fun release(definitions: Array<out CacheType>, id: Int) {
        (definitions as Array<Any?>)[id] = null
    }
}
