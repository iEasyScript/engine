package world.gregs.voidps.cache.store

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ArchiveGroupTest {

    private val files = listOf(ByteArray(10) { it.toByte() }, ByteArray(0), ByteArray(300) { (it * 3).toByte() })
    private val ids = intArrayOf(0, 1, 5)

    @Test
    fun `a trailing table group splits and joins in one chunk`() {
        val group = ArchiveGroup.join(files)
        val split = ArchiveGroup.split(group, ids)
        assertEquals(GroupLayout.TRAILING, split.layout)
        assertEquals(1, split.chunks.size)
        for ((a, b) in files.zip(split.files)) {
            assertContentEquals(a, b)
        }
        assertContentEquals(group, ArchiveGroup.join(split.files, split.chunks))
    }

    @Test
    fun `a recorded multi chunk layout comes back byte for byte`() {
        val chunks = listOf(intArrayOf(4, 0, 100), intArrayOf(6, 0, 200))
        val group = ArchiveGroup.join(files, chunks)
        val split = ArchiveGroup.split(group, ids)
        assertEquals(2, split.chunks.size)
        assertContentEquals(group, ArchiveGroup.join(split.files, split.chunks))
    }

    @Test
    fun `an offsets group is recognised and rebuilt`() {
        val group = ArchiveGroup.join(files, null, GroupLayout.OFFSETS, 1)
        val split = ArchiveGroup.split(group, ids)
        assertEquals(GroupLayout.OFFSETS, split.layout)
        assertEquals(1, split.version)
        for ((a, b) in files.zip(split.files)) {
            assertContentEquals(a, b)
        }
        assertContentEquals(group, ArchiveGroup.join(split.files, null, split.layout, split.version))
    }

    @Test
    fun `a single file group is the file`() {
        val split = ArchiveGroup.split(files[2], intArrayOf(3))
        assertContentEquals(files[2], split.files[0])
        assertContentEquals(files[2], ArchiveGroup.join(split.files))
    }
}
