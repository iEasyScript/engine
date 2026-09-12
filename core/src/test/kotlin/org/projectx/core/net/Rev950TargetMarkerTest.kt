package org.projectx.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.projectx.core.net.prot.ServerProt
import org.projectx.core.net.prot.SetTargetMarker
import org.projectx.core.net.prot.revision.rev950.register950
import kotlin.test.Test
import kotlin.test.assertEquals

/** Byte-checks SET_INTERACTION_TARGET against the 950-1 handler's field order and transforms. */
class Rev950TargetMarkerTest {
    private val codec = register950()

    private fun encode(prot: ServerProt): String = runBlocking {
        val entry = codec.serverProts[prot::class] ?: error("no encoder for ${prot::class.simpleName}")
        val encoder = entry.encoder ?: error("encoder null")
        val buffer = Buffer()
        val channel = buffer.asByteWriteChannel()
        encoder.invoke(prot, channel)
        channel.flush()
        buffer.readByteArray().joinToString(" ") { "%02x".format(it) }
    }

    @Test
    fun `set NPC target`() {
        assertEquals(
            "fe 83 00 7f 62 1e 00 00 fd ff",
            encode(SetTargetMarker(131, 129, 130, 129, SetTargetMarker.TYPE_NPC, 25118, clear = false)),
        )
    }

    @Test
    fun `clear`() {
        assertEquals(
            "80 7f 00 00 00 00 00 00 81 80",
            encode(SetTargetMarker(139, 141, 0, 0, SetTargetMarker.TYPE_TILE, 0, clear = true)),
        )
    }
}
