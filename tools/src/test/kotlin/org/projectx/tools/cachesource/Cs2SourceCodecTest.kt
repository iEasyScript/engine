package org.projectx.tools.cachesource

import org.projectx.core.EnvVars
import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.UnpackOptions
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.cs2.Cs2SourceCodec
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Cs2SourceCodecTest {

    @Test
    fun `every clientscript packs back to the bytes it was unpacked from`() {
        val cacheDir = CacheFixture.resolveCacheDir()
        assumeTrue(cacheDir != null, "no game cache on this machine - skipping")
        Cs2SourceCodec.cachePath = cacheDir
        val treeDir = Files.createTempDirectory("cs2-source-tree")
        val store = CacheStore.open(cacheDir!!, writable = false)
        try {
            val tree = SourceTree.create(treeDir, EnvVars.majorVersion, store.kind, store.indexCount())
            val started = System.nanoTime()
            val report = Unpacker.unpack(
                store,
                tree,
                UnpackOptions(indices = setOf(Index.CLIENT_SCRIPTS), gamevals = Path.of(EnvVars.gamevalPath))
            )
            val unpackMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(report.archives > 0, "index ${Index.CLIENT_SCRIPTS} unpacked nothing")

            val codec = SourceCodecs.codec(Index.CLIENT_SCRIPTS)
            assertSame(Cs2SourceCodec, codec)
            val metadata = tree.metadata(Index.CLIENT_SCRIPTS)
            val directory = tree.indexDirectory(Index.CLIENT_SCRIPTS)
            val ids = metadata.archiveIds()
            assertEquals(report.archives, ids.size)

            val packStarted = System.nanoTime()
            codec.packIndex(directory, Index.CLIENT_SCRIPTS, ids, emptyList())
            var pristine = 0
            val mismatched = ArrayList<Int>()
            for (id in ids) {
                val archive = metadata.archives.getValue(id)
                if (archive.pristine.isNotEmpty()) {
                    pristine++
                }
                val expected = group(store, id)
                val packed = codec.pack(directory, id, archive)
                if (!packed.group.contentEquals(expected)) {
                    mismatched.add(id)
                }
            }
            val packMillis = (System.nanoTime() - packStarted) / 1_000_000
            println(
                "clientscripts: ${ids.size} archives, ${ids.size - pristine} compiled exactly, $pristine pristine; " +
                    "unpack ${unpackMillis}ms, pack ${packMillis}ms"
            )
            assertEquals(emptyList(), mismatched, "archives that did not pack back to their shipped bytes")
            assertEquals(pristine, report.pristine)
            pristineFallback(codec, directory, store, ids[0], ids[1])
        } finally {
            store.close()
            Cs2SourceCodec.reset()
            treeDir.toFile().deleteRecursively()
        }
    }

    /**
     * Every script of this build compiles back exactly, so the sidecar path is driven by hand: archive
     * [archive] is unpacked with [other]'s bytes, which its source cannot reproduce.
     */
    private fun pristineFallback(codec: SourceCodec, directory: Path, store: CacheStore, archive: Int, other: Int) {
        val shipped = group(store, other)
        val own = group(store, archive)
        val unpacked = codec.unpack(
            directory,
            SourceArchive(Index.CLIENT_SCRIPTS, archive, 0, intArrayOf(0), shipped, listOf(shipped), listOf(intArrayOf(shipped.size)), ArchiveMetadata())
        )
        assertEquals(2, unpacked.files.size, "an inexact script keeps its shipped bytes beside the source")
        val source = unpacked.files[0]
        val sidecar = unpacked.files[1]
        assertEquals(SourceFiles.pristine(source.path), sidecar.path)
        assertContentEquals(shipped, sidecar.bytes)
        assertEquals(mapOf(0 to SourceFiles.sha256(source.bytes)), unpacked.pristine)

        val scratch = Files.createTempDirectory("cs2-pristine")
        try {
            for (file in unpacked.files) {
                SourceFiles.write(scratch.resolve(file.path), file.bytes)
            }
            val metadata = ArchiveMetadata().apply {
                fileIds(intArrayOf(0))
                pristine = unpacked.pristine
            }
            codec.packIndex(scratch, Index.CLIENT_SCRIPTS, intArrayOf(archive), emptyList())
            assertContentEquals(shipped, codec.pack(scratch, archive, metadata).group, "the guard holds, so the sidecar is served")

            val edited = scratch.resolve(source.path)
            Files.writeString(edited, Files.readString(edited) + "\n")
            assertContentEquals(own, codec.pack(scratch, archive, metadata).group, "an edit breaks the guard, so the source compiles")

            Files.writeString(edited, "// clientscript $archive\nfunction broken(: void {")
            assertFailsWith<IOException> { codec.pack(scratch, archive, metadata) }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun group(store: CacheStore, archive: Int): ByteArray =
        Container.decode(store.read(Index.CLIENT_SCRIPTS, archive)!!, null, store.trailers).data()
}
