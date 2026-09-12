package world.gregs.voidps.cache.store

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CacheStoreTest {

    private val scratches = ArrayList<Path>()

    private fun scratch(name: String): Path = Files.createTempDirectory("cache-store-$name").also { scratches.add(it) }

    @AfterTest
    fun cleanUp() {
        for (directory in scratches) {
            directory.toFile().deleteRecursively()
        }
    }

    private fun sample(index: Int, archive: Int): ByteArray =
        Container.compress(ByteArray(500 + archive * 7) { (it * index + archive).toByte() }, Compression.GZIP, null).encode()

    private fun table(index: Int, archives: IntArray): ByteArray {
        val table = ReferenceTable(7, 100 + index, false, false, true, true)
        for (archive in archives) {
            table.add(ArchiveEntry(archive, version = archive + 1, crc = archive * 3).also { it.files.add(FileEntry(0)) })
        }
        return Container.compress(table.encode(), Compression.GZIP, null).encode()
    }

    @Test
    fun `both stores hold what they are given and report it`() {
        for (kind in StoreKind.entries) {
            val directory = scratch(kind.id)
            val archives = intArrayOf(0, 1, 70_000)
            CacheStore.create(directory, kind, 3).use { store ->
                assertEquals(kind, store.kind)
                for (index in listOf(0, 2)) {
                    for (archive in archives) {
                        val bytes = Converter.retrail(sample(index, archive), false, store.trailers, archive + 1)
                        store.write(index, archive, bytes, archive + 1, archive * 3)
                    }
                    store.writeTable(index, table(index, archives))
                }
                store.flush()
            }
            CacheStore.open(directory).use { store ->
                assertEquals(kind, store.kind)
                assertContentEquals(intArrayOf(0, 2), store.indices())
                assertEquals(3, store.indexCount())
                for (index in listOf(0, 2)) {
                    assertContentEquals(archives, store.archives(index))
                    assertContentEquals(table(index, archives), store.readTable(index))
                    for (archive in archives) {
                        val expected = Converter.retrail(sample(index, archive), false, store.trailers, archive + 1)
                        assertContentEquals(expected, store.read(index, archive))
                    }
                    assertNull(store.read(index, 5))
                }
                assertNull(store.readTable(1))
            }
        }
    }

    @Test
    fun `converting between the stores loses nothing`() {
        val sector = scratch("from")
        val archives = intArrayOf(0, 3, 4)
        CacheStore.create(sector, StoreKind.SECTOR, 2).use { store ->
            for (archive in archives) {
                store.write(1, archive, Converter.retrail(sample(1, archive), false, true, archive + 1))
            }
            store.writeTable(1, table(1, archives))
        }
        val sqlite = scratch("sqlite")
        CacheStore.open(sector).use { Converter.convert(it, sqlite, StoreKind.SQLITE) }
        val back = scratch("back")
        CacheStore.open(sqlite).use { Converter.convert(it, back, StoreKind.SECTOR) }
        CacheStore.open(sector).use { original ->
            CacheStore.open(back).use { converted ->
                assertContentEquals(original.readTable(1), converted.readTable(1))
                for (archive in archives) {
                    assertContentEquals(original.read(1, archive), converted.read(1, archive))
                }
            }
        }
        CacheStore.open(sqlite).use { store ->
            assertEquals(false, store.trailers)
            assertContentEquals(sample(1, 3), store.read(1, 3))
        }
    }
}
