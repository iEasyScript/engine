package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.image.GraphicCodec
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import world.gregs.voidps.cache.type.data.GraphicType
import world.gregs.voidps.cache.type.decoder.GraphicDecoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every archive of the three graphic indices unpacked to real PNG files, packed back from them and
 * demanded identical - the measurement behind `docs/cache-source.md`'s parity table.
 */
class GraphicCodecParityTest {

    @Test
    fun `every graphic archive packs back to its own bytes`() {
        val store = openCache() ?: return
        val root = Files.createTempDirectory("graphic-parity")
        try {
            val reports = INDICES.map { index -> parity(store, index, root.resolve(index.toString())) }
            for (report in reports) {
                println(report)
            }
            assertEquals(emptyList(), reports.flatMap { report -> report.mismatched.map { "index ${report.index} archive $it" } })
        } finally {
            store.close()
            delete(root)
        }
    }

    @Test
    fun `a frame re-saved without its chunks still packs to a renderable group`() {
        val store = openCache() ?: return
        val root = Files.createTempDirectory("graphic-stripped")
        try {
            var checked = 0
            for (index in INDICES) {
                val codec = codec(index)
                val directory = root.resolve(index.toString())
                Files.createDirectories(directory)
                for (entry in entries(store, index).take(SAMPLE)) {
                    val archive = archive(store, index, entry) ?: continue
                    val unpacked = codec.unpack(directory, archive.source())
                    val frames = unpacked.files.filter { it.path.endsWith(".png") }
                    if (frames.isEmpty() || unpacked.pristine.isNotEmpty()) {
                        continue
                    }
                    val stripped = frames.map { SourceFile(it.path, strip(it.bytes)) }
                    write(directory, stripped)
                    val metadata = archive.metadata()
                    val packed = codec.pack(directory, archive.id, metadata)
                    assertRenders(archive.id, packed.group, stripped)
                    checked++
                }
            }
            assertTrue(checked > 0, "no graphic groups were sampled")
        } finally {
            store.close()
            delete(root)
        }
    }

    private fun parity(store: SqliteStore, index: Int, directory: Path): Report {
        val codec = codec(index)
        val report = Report(index)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            Files.createDirectories(directory)
            val futures = entries(store, index).map { entry ->
                pool.submit {
                    // Read inside the task: the whole index at once is far more than a test worker holds.
                    val archive = archive(store, index, entry) ?: return@submit
                    val unpacked = codec.unpack(directory, archive.source())
                    write(directory, unpacked.files)
                    val metadata = archive.metadata()
                    metadata.pristine = unpacked.pristine
                    val packed = codec.pack(directory, archive.id, metadata)
                    if (!packed.group.contentEquals(archive.group)) {
                        report.mismatched.add(archive.id)
                    }
                    report.count(unpacked.files, unpacked.pristine.isNotEmpty())
                }
            }
            for (future in futures) {
                future.get()
            }
        } finally {
            pool.shutdown()
        }
        return report
    }

    private fun entries(store: SqliteStore, index: Int): List<ArchiveEntry> =
        ReferenceTable.decode(Container.decode(store.readTable(index)!!, trailer = false).data()).archives

    private fun archive(store: SqliteStore, index: Int, entry: ArchiveEntry): Archive? {
        val bytes = store.read(index, entry.id) ?: return null
        val group = Container.decode(bytes, null, store.trailers).data()
        return Archive(index, entry.id, entry.nameHash, entry.fileIds, group)
    }

    /** A PNG re-saved the way an image editor saves it: the pixels, and no chunk it did not write. */
    private fun strip(png: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val out = ByteArrayOutputStream(png.size)
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** The packed group draws what the stripped PNGs show, frame for frame and pixel for pixel. */
    private fun assertRenders(archive: Int, group: ByteArray, files: List<SourceFile>) {
        val type = GraphicType(archive)
        GraphicDecoder().readLoop(type, BufferReader(group))
        val frames = type.frames ?: fail("archive $archive decoded to no frames")
        assertEquals(files.size, frames.size, "archive $archive lost frames")
        for ((index, file) in files.withIndex()) {
            val expected = ImageIO.read(ByteArrayInputStream(file.bytes))
            val frame = frames[index]
            val canvas = BufferedImage(expected.width, expected.height, BufferedImage.TYPE_INT_ARGB)
            val rgba = frame.rgba()
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val pixel = (x + y * frame.width) * 4
                    val alpha = rgba[pixel + 3].toInt() and 0xff
                    val colour = ((rgba[pixel].toInt() and 0xff) shl 16) or
                        ((rgba[pixel + 1].toInt() and 0xff) shl 8) or (rgba[pixel + 2].toInt() and 0xff)
                    canvas.setRGB(frame.offsetX + x, frame.offsetY + y, (alpha shl 24) or colour)
                }
            }
            for (y in 0 until expected.height) {
                for (x in 0 until expected.width) {
                    val drawn = canvas.getRGB(x, y)
                    val wanted = expected.getRGB(x, y)
                    val same = if (wanted ushr 24 == 0) drawn ushr 24 == 0 else drawn == wanted
                    assertTrue(same, "archive $archive frame $index pixel $x,$y is $drawn, expected $wanted")
                }
            }
        }
    }

    private fun openCache(): SqliteStore? {
        val directory = CacheFixture.resolveCacheDir()
        assumeTrue(directory != null, "no game cache on this machine — skipping")
        return SqliteStore.open(directory!!, writable = false)
    }

    private fun codec(index: Int): GraphicCodec {
        val codec = SourceCodecs.codec(index)
        return codec as? GraphicCodec ?: fail("index $index is registered to ${codec.id}, not the graphic codec")
    }

    private fun write(directory: Path, files: List<SourceFile>) {
        for (file in files) {
            val path = directory.resolve(file.path)
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
        }
    }

    private fun delete(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class Archive(
        val index: Int,
        val id: Int,
        val nameHash: Int,
        val fileIds: IntArray,
        val group: ByteArray
    ) {
        fun metadata() = ArchiveMetadata(nameHash = nameHash).also { it.fileIds(fileIds) }

        fun source(): SourceArchive {
            val split = ArchiveGroup.split(group, fileIds)
            return SourceArchive(index, id, nameHash, fileIds, group, split.files, split.chunks, metadata())
        }
    }

    private class Report(val index: Int) {
        val mismatched: MutableList<Int> = Collections.synchronizedList(ArrayList())
        private val exact = AtomicInteger()
        private val pristine = AtomicInteger()
        private val kept = AtomicInteger()
        private val frames = AtomicInteger()

        fun count(files: List<SourceFile>, sidecar: Boolean) {
            frames.addAndGet(files.count { it.path.endsWith(".png") })
            when {
                sidecar -> pristine.incrementAndGet()
                files.none { it.path.endsWith(".png") } -> kept.incrementAndGet()
                else -> exact.incrementAndGet()
            }
        }

        override fun toString(): String =
            "index $index: ${exact.get() + pristine.get() + kept.get()} archives, ${frames.get()} frames, " +
                "${exact.get()} exact, ${pristine.get()} pristine, ${kept.get()} kept as shipped bytes, " +
                "${mismatched.size} mismatched"
    }

    private companion object {
        val INDICES = listOf(Index.GRAPHICS, Index.LOADING_GRAPHICS, Index.LOADING_GRAPHICS_RAW)

        const val SAMPLE = 8

        val THREADS = minOf(8, Runtime.getRuntime().availableProcessors())

    }
}
