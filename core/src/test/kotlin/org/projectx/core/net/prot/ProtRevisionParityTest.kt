package org.projectx.core.net.prot

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A revision bump must not lose a packet the previous revision could speak: every encoder and
 * decoder rev949 registered is registered again in rev950, and a name carried into the rev950
 * tables is never left as a name-only stub where rev949 had a working codec behind it.
 */
class ProtRevisionParityTest {

    private val previous = ProtRevisions.codec(949)!!
    private val current = ProtRevisions.codec(950)!!

    @Test
    fun `every rev949 server encoder exists in rev950`() {
        val missing = previous.serverProts
            .filter { (type, codec) -> current.serverProts[type].let { it == null || it.isStub() && !codec.isStub() } }
            .keys
            .map { it.simpleName!! }
            .sorted()
        assertEquals(emptyList(), missing, "rev949 server encoders absent or stubbed in rev950")
    }

    @Test
    fun `every rev949 client decoder exists in rev950`() {
        val missing = previous.clientProtsByOpcode.values
            .distinct()
            .filter { it.decoder != null }
            .map { it.protClass }
            .filter { it !in REFUTED_949_CLIENT_BINDINGS }
            .filter { type -> current.clientProtsByOpcode.values.none { it.protClass == type && it.decoder != null } }
            .map { it.simpleName!! }
            .distinct()
            .sorted()
        assertEquals(emptyList(), missing, "rev949 client decoders absent or stubbed in rev950")
    }

    @Test
    fun `no rev950 server entry registered by name is a stub`() {
        val encoded = current.serverProts.values.map { it.opcode }.toSet()
        val stubs = current.serverProtInfo
            .filterKeys { it !in encoded }
            .filterValues { !it.name.startsWith("UNKNOWN_") }
            .filter { (_, info) -> previous.hasServerEncoderNamed(info.name) }
            .map { (opcode, info) -> "${info.name}@$opcode" }
            .sorted()
        assertEquals(emptyList(), stubs, "rev950 names whose rev949 encoder was not carried onto the opcode")
    }

    @Test
    fun `no rev950 client entry registered by name is a stub`() {
        val stubs = current.clientProtInfo
            .filter { (opcode, info) ->
                val codec = current.clientProtsByOpcode[opcode]
                val decodes = codec != null && (codec.decoder != null || codec.size == ProtSize.Fixed(0))
                !decodes && !info.name.startsWith("UNKNOWN_") && previous.hasClientDecoderNamed(info.name)
            }
            .map { (opcode, info) -> "${info.name}@$opcode" }
            .sorted()
        assertEquals(emptyList(), stubs, "rev950 names whose rev949 decoder was not carried onto the opcode")
    }

    /** A payload-less packet has nothing to encode; anything with a body must have an encoder. */
    @Test
    fun `rev950 registers no encoder-less server codec`() {
        val stubs = current.serverProts
            .filter { (_, codec) -> codec.isStub() }
            .map { it.key.simpleName!! }
            .sorted()
        assertEquals(emptyList(), stubs, "server prots registered without an encoder")
    }

    private fun Codec.ServerProtCodec.isStub(): Boolean = encoder == null && size != ProtSize.Fixed(0)

    private companion object {
        /**
         * rev949 decoders whose packet was proven not to exist: the opcode they read was a different
         * packet in both builds, so carrying them forward would carry the misreading forward.
         * FriendListDel decoded the friends-chat channel join/leave packet as a friend removal.
         */
        val REFUTED_949_CLIENT_BINDINGS = setOf(FriendListDel::class)
    }

    private fun Codec.hasServerEncoderNamed(name: String): Boolean =
        serverProtInfo.any { (opcode, info) -> info.name == name && serverProts.values.any { it.opcode == opcode && !it.isStub() } }

    private fun Codec.hasClientDecoderNamed(name: String): Boolean =
        clientProtInfo.any { (opcode, info) ->
            val codec = clientProtsByOpcode[opcode]
            info.name == name && codec != null && (codec.decoder != null || codec.size == ProtSize.Fixed(0))
        }
}
