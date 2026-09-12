package org.projectx.core.net.prot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Captures outlive the build that took them, so reading one back means constructing the codec it
 * was framed by rather than whichever is current.
 */
class ProtRevisionsTest {

    @Test
    fun `every known revision can be constructed`() {
        assertTrue(ProtRevisions.known.isNotEmpty())
        for (revision in ProtRevisions.known) {
            assertNotNull(ProtRevisions.codec(revision), "revision $revision is listed but not buildable")
            assertTrue(ProtRevisions.canRead(revision))
        }
    }

    /** A recorder asks for this instead of naming a number, so a new revision needs no edit there. */
    @Test
    fun `current is the newest known revision`() {
        assertEquals(ProtRevisions.known.max(), ProtRevisions.current)
        assertNotNull(ProtRevisions.currentCodec())
    }

    /** Registration must not depend on a caller remembering to do it. */
    @Test
    fun `a codec resolves without anyone registering first`() {
        assertNotNull(Codec.forRevision(ProtRevisions.current))
    }

    @Test
    fun `a revision this build does not know is reported rather than guessed`() {
        val unknown = ProtRevisions.current + 1000
        assertNull(ProtRevisions.codec(unknown))
        assertFalse(ProtRevisions.canRead(unknown))
    }

    /**
     * Two revisions must not share a codec instance: the whole point of keeping old ones is being
     * able to hold them side by side and see what moved.
     */
    @Test
    fun `each revision gets its own prot table`() {
        val codecs = ProtRevisions.known.map { ProtRevisions.codec(it)!! }
        assertEquals(codecs.size, codecs.distinct().size, "revisions share a codec instance")
    }

    @Test
    fun `a codec is built once and reused`() {
        val first = ProtRevisions.codec(ProtRevisions.current)
        val second = ProtRevisions.codec(ProtRevisions.current)
        assertTrue(first === second, "rebuilding a prot table per call would be wasteful and racy")
    }
}
