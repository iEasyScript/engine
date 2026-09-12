package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.RawCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.cs2.Cs2SourceCodec
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The clientscript index in a process that has only core.
 *
 * A server builds its cache from the tree on startup, so the compiler that turns index 12's
 * TypeScript back into bytecode has to be reachable from core and not only from the tools CLI.
 * When it was not, the tree's sources fell through to [RawCodec], which looks for one `.dat` per
 * archive and fails the whole build on the first one.
 */
class ClientScriptCodecTest {

    @AfterTest
    fun cleanUp() = SourceCodecs.reset()

    @Test
    fun `core resolves the clientscript index to the cs2 codec`() {
        SourceCodecs.reset()
        SourceCodecs.context = SourceContext.none(CacheEra.NXT_REVISION)
        val codec = SourceCodecs.codec(Index.CLIENT_SCRIPTS)
        assertSame(Cs2SourceCodec, codec, "core must own the clientscript codec")
        assertNotEquals(RawCodec.DEFAULT.id, codec.id)
        assertTrue(SourceCodecs.registered().contains(Index.CLIENT_SCRIPTS))
    }

    @Test
    fun `a script source belongs to its archive rather than to nobody`() {
        SourceCodecs.reset()
        SourceCodecs.context = SourceContext.none(CacheEra.NXT_REVISION)
        val codec = SourceCodecs.codec(Index.CLIENT_SCRIPTS)
        val directory = Path.of("clientscripts")
        for ((path, archive) in PATHS) {
            val owner = codec.owner(directory, path)
            assertTrue(owner is Owner.Archive, "$path should belong to an archive, was $owner")
            assertEquals(archive, owner.id)
        }
        assertSame(Owner.Index, codec.owner(directory, Cs2SourceCodec.OPCODES_FILE))
        assertSame(Owner.Index, codec.owner(directory, Cs2SourceCodec.VARS_FILE))
    }

    @Test
    fun `a legacy cache leaves the clientscript index alone`() {
        SourceCodecs.reset()
        SourceCodecs.context = SourceContext.none(LEGACY_REVISION)
        assertNotEquals(Cs2SourceCodec.id, SourceCodecs.codec(Index.CLIENT_SCRIPTS).id)
    }

    private companion object {
        const val LEGACY_REVISION = 727

        /** The decompiler's placeholder paths, which need no gameval catalog to resolve. */
        val PATHS = mapOf("cs2/cs2_9000.ts" to 9000, "cs2/cs2_0.ts" to 0)
    }
}
