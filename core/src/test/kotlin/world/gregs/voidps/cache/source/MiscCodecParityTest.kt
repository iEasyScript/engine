package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The minor indices - the Huffman table, `defaults`, style sheets, particle presets, blend state
 * machines, interface animations and cutscene overlays - unpacked into real files and packed back.
 *
 * The bar is the same as every other codec's: the group that comes back is the group that went in,
 * and no file needed a pristine sidecar to get there.
 */
class MiscCodecParityTest {

    private class Row(val name: String, val archives: Int, val files: Int, val exact: Int, val pristine: Int)

    @Test
    fun `every misc index repacks byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine - skipping")
        SourceCodecs.reset()
        val work = Files.createTempDirectory("misc-codec-parity")
        SourceCodecs.context = SourceContext(work, REVISION, GamevalCatalogs(gamevals()))
        val store = SqliteStore.open(cache!!)
        val rows = ArrayList<Row>()
        try {
            println(HEADER)
            for (index in INDICES) {
                rows.add(measure(store, work, index))
            }
        } finally {
            store.close()
            SourceCodecs.reset()
            work.toFile().deleteRecursively()
        }
        for (row in rows) {
            assertEquals(row.files, row.exact + row.pristine, "${row.name} lost files")
            assertEquals(0, row.pristine, "${row.name} still needs ${row.pristine} pristine sidecars")
        }
    }

    private fun measure(store: SqliteStore, work: Path, index: Int): Row {
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
        }
        val row = Row(IndexNames.NXT.name(index), archives, files, files - pristine, pristine)
        println(line(row))
        return row
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

        val INDICES = intArrayOf(10, 28, 60, 61, 62, 65, 66)
    }
}
