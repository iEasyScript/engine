package world.gregs.voidps.cache.store

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReferenceTableTest {

    private fun table(format: Int, named: Boolean, whirlpool: Boolean, lengths: Boolean, checksums: Boolean): ReferenceTable {
        val table = ReferenceTable(format, if (format >= 6) 1787139004 else 0, named, whirlpool, lengths, checksums)
        var id = 0
        for (archive in 0 until 40) {
            id += if (archive % 7 == 0) 40_000 else 1
            val entry = ArchiveEntry(id)
            entry.nameHash = if (named) id * 31 else 0
            entry.crc = id xor 0x5a5a5a5a
            entry.uncompressedCrc = if (checksums) id * 3 else 0
            entry.whirlpool = if (whirlpool) ByteArray(64) { (it + id).toByte() } else null
            entry.compressedLength = if (lengths) id + 9 else 0
            entry.uncompressedLength = if (lengths) id * 2 else 0
            entry.version = 1700000000 + id
            var file = 0
            for (count in 0 until (archive % 5) + 1) {
                file += if (count == 3) 70_000 else 1
                entry.files.add(FileEntry(file, if (named) file * 17 else 0))
            }
            table.add(entry)
        }
        return table
    }

    @Test
    fun `every flag combination survives an encode and decode`() {
        for (format in ReferenceTable.MIN_FORMAT..ReferenceTable.MAX_FORMAT) {
            for (flags in 0 until 16) {
                val lengths = flags and 4 != 0
                val checksums = flags and 8 != 0
                val original = table(format, flags and 1 != 0, flags and 2 != 0, lengths, checksums)
                val encoded = try {
                    original.encode()
                } catch (e: IllegalArgumentException) {
                    // Formats below 7 cannot hold the wide ids the sample uses.
                    continue
                }
                val decoded = ReferenceTable.decode(encoded)
                assertEquals(original.flags, decoded.flags)
                assertEquals(original.archiveCount, decoded.archiveCount)
                assertContentEquals(encoded, decoded.encode(), "format $format flags $flags")
                for ((a, b) in original.archives.zip(decoded.archives)) {
                    assertEquals(a.id, b.id)
                    assertEquals(a.version, b.version)
                    assertEquals(a.crc, b.crc)
                    assertEquals(a.uncompressedCrc, b.uncompressedCrc)
                    assertEquals(a.compressedLength, b.compressedLength)
                    assertEquals(a.uncompressedLength, b.uncompressedLength)
                    assertContentEquals(a.fileIds, b.fileIds)
                }
            }
        }
    }
}
