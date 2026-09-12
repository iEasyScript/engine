package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.source.codec.ArchiveDirectoryCodec
import world.gregs.voidps.cache.source.codec.ConfigCodec
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Every archive of the indices whose payload is a record rather than an image or a script, unpacked
 * into real files and packed straight back.
 *
 * The bar is the one the whole source tree rests on: the group that comes back is the group that
 * went in, and no file needed a pristine sidecar to get there. A sidecar keeps parity whatever the
 * encoder does, so the honest measure of a codec is the pristine column, and the goal is zero.
 */
class RecordCodecParityTest {

    private class Row(val name: String, val archives: Int, val files: Int, val exact: Int, val pristine: Int)

    @Test
    fun `every record index repacks byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine - skipping")
        SourceCodecs.reset()
        val work = Files.createTempDirectory("record-codec-parity")
        SourceCodecs.context = SourceContext(work, REVISION, GamevalCatalogs(gamevals()))
        val store = SqliteStore.open(cache!!)
        val rows = ArrayList<Row>()
        val types = LinkedHashMap<String, Row>()
        try {
            println(HEADER)
            for (index in INDICES) {
                rows.add(measure(store, work, index, types))
            }
        } finally {
            store.close()
            SourceCodecs.reset()
            work.toFile().deleteRecursively()
        }
        println()
        println(HEADER)
        for (row in types.values) {
            println(line(row))
        }
        for (row in rows) {
            assertEquals(row.files, row.exact + row.pristine, "${row.name} lost files")
            assertEquals(0, row.pristine, "${row.name} still needs ${row.pristine} pristine sidecars")
        }
    }

    private fun measure(store: SqliteStore, work: Path, index: Int, types: MutableMap<String, Row>): Row {
        val codec = SourceCodecs.codec(index)
        val directory = work.resolve(index.toString())
        Files.createDirectories(directory)
        val table = ReferenceTable.decode(Container.decode(store.readTable(index)!!, trailer = false).data())
        var archives = 0
        var files = 0
        var pristine = 0
        for (entry in table.archives) {
            val stored = store.read(index, entry.id) ?: continue
            val group = Container.decode(stored, trailer = false).data()
            val split = ArchiveGroup.split(group, entry.fileIds)
            val metadata = ArchiveMetadata(nameHash = entry.nameHash, layout = split.layout, layoutVersion = split.version)
            metadata.fileIds(entry.fileIds)
            val source = SourceArchive(index, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata)
            val unpacked = codec.unpack(directory, source)
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
            }
            metadata.pristine = unpacked.pristine
            val packed = codec.pack(directory, entry.id, metadata)
            assertEquals(true, packed.group.contentEquals(group), "index $index archive ${entry.id} did not repack")
            archives++
            files += entry.fileIds.size
            pristine += unpacked.pristine.size
            record(types, name(codec, entry.id), entry.fileIds.size, unpacked.pristine.size)
        }
        val row = Row(IndexNames.NXT.name(index), archives, files, files - pristine, pristine)
        println(line(row))
        return row
    }

    /** The codec that actually wrote the archive, which for a dispatching index is a sub-codec. */
    private fun name(codec: SourceCodec, archive: Int): String = when (codec) {
        is ArchiveDirectoryCodec -> codec.codec(archive).id
        is ConfigCodec -> codec.codec(archive).id
        else -> codec.id
    }

    private fun record(types: MutableMap<String, Row>, name: String, files: Int, pristine: Int) {
        val previous = types[name]
        val archives = (previous?.archives ?: 0) + 1
        val total = (previous?.files ?: 0) + files
        val kept = (previous?.pristine ?: 0) + pristine
        types[name] = Row(name, archives, total, total - kept, kept)
    }

    private fun gamevals(): Path? =
        listOf(Path.of("unpacked-cache/gamevals"), Path.of("../unpacked-cache/gamevals"), Path.of("re-resources/gamevals"))
            .firstOrNull { Files.isDirectory(it) }

    private fun line(row: Row): String = row.name.padEnd(22) +
        row.archives.toString().padStart(10) +
        row.files.toString().padStart(10) +
        row.exact.toString().padStart(10) +
        row.pristine.toString().padStart(10)

    private companion object {
        const val REVISION = 949

        const val HEADER = "                        archives     files     exact  pristine"

        val INDICES = intArrayOf(3, 13, 23, 24, 26, 27, 28, 29, 33, 35, 49, 57)
    }
}
