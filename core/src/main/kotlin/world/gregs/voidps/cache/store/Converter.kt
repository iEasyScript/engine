package world.gregs.voidps.cache.store

import world.gregs.voidps.cache.secure.CRC
import java.nio.file.Files
import java.nio.file.Path

/** What one [Converter.convert] did. */
class ConvertResult(val indices: Int, val archives: Int, val bytes: Long, val millis: Long) {
    override fun toString(): String = "$indices indices, $archives archives, $bytes bytes, in ${millis}ms"
}

/**
 * Copies a packed cache from one store into another, container for container.
 *
 * Nothing is decompressed or re-encoded: the only thing that changes between the two shapes is
 * the version trailer a sector store keeps on every archive container, which is added or stripped
 * on the way through. The reference tables are copied as they are, so every CRC the client checks
 * survives, and the archive versions a SQLite store keeps beside its rows come out of the tables.
 */
object Converter {

    fun convert(source: CacheStore, output: Path, kind: StoreKind, report: (String) -> Unit = {}): ConvertResult {
        val start = System.nanoTime()
        Files.createDirectories(output)
        var indices = 0
        var archives = 0
        var bytes = 0L
        CacheStore.create(output, kind, source.indexCount()).use { target ->
            if (target is FileStore) {
                target.durable = false
            }
            for (index in source.indices()) {
                val tableBytes = source.readTable(index) ?: continue
                val table = ReferenceTable.decode(Container.decode(tableBytes, trailer = false).data())
                var count = 0
                for (entry in table.archives) {
                    val stored = source.read(index, entry.id) ?: continue
                    val container = retrail(stored, source.trailers, target.trailers, entry.version)
                    target.write(index, entry.id, container, entry.version, entry.crc)
                    bytes += container.size
                    count++
                }
                target.writeTable(index, tableBytes, 0, CRC.calculate(tableBytes, 0, tableBytes.size))
                bytes += tableBytes.size
                archives += count
                indices++
                report("index $index: $count archives")
            }
            target.flush()
        }
        return ConvertResult(indices, archives, bytes, (System.nanoTime() - start) / 1_000_000)
    }

    /** [bytes] with the trailer [to] expects, given that it carries one when [from] is set. */
    fun retrail(bytes: ByteArray, from: Boolean, to: Boolean, version: Int): ByteArray {
        if (from == to) {
            return bytes
        }
        if (from) {
            return bytes.copyOf(bytes.size - Container.TRAILER_SIZE)
        }
        val out = bytes.copyOf(bytes.size + Container.TRAILER_SIZE)
        out[bytes.size] = (version shr 8).toByte()
        out[bytes.size + 1] = version.toByte()
        return out
    }
}
