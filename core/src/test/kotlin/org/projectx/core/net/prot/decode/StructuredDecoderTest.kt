package org.projectx.core.net.prot.decode

import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.write
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.revision.rev950.register950
import org.projectx.core.net.prot.revision.rev950.registerRevision950
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decodes bytes taken from a real capture, so a decoder that reads a plausible-but-wrong layout
 * fails here rather than producing plausible-but-wrong fields for every query built on it.
 */
class StructuredDecoderTest {

    private val codec = register950()

    private fun decode(dir: Int, opcode: Int, hex: String): DecodedPacket {
        val bytes = hex.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
        val decoder = codec.structuredDecoder(dir, opcode)
        assertNotNull(decoder, "no structured decoder for dir=$dir opcode=$opcode")
        return runBlocking { decoder!!.decode.invoke(Buffer().apply { write(bytes) }, bytes.size) }
    }

    /** VARP_LARGE: id then the middle-endian value, per the 950-1 handler. */
    @Test
    fun `varp large decodes the same id and value the display decoder reports`() {
        val packet = decode(0, 4, "e3 1c 00 00 00 10")
        assertEquals(7267L, packet["varp"]?.long)
        assertEquals(1048576L, packet["value"]?.long)
        assertEquals(RefDomain.VARP, packet["varp"]?.ref)
    }

    /** CLIENT_SETVARC_SMALL: little-endian id then a subtract-byte value. */
    @Test
    fun `varc small decodes its id and value`() {
        val packet = decode(0, 126, "b7 02 80")
        assertEquals(695L, packet["varc"]?.long)
        assertEquals(0L, packet["value"]?.long)
    }

    @Test
    fun `every registered decoder declares a versioned schema`() {
        assertTrue(codec.structuredDecoders.isNotEmpty(), "no structured decoders registered")
        for ((_, decoder) in codec.structuredDecoders) {
            val schema = decoder.schema
            assertTrue(schema.fields.isNotEmpty(), "${schema.opcodeName} declares no fields")
            assertTrue(schema.version >= 1, "${schema.opcodeName} has no schema version")
            assertTrue(schema.fieldsHash.isNotBlank(), "${schema.opcodeName} has no fields hash")
        }
    }

    /** The hash is what makes a targeted re-decode possible, so it must move when a field moves. */
    @Test
    fun `changing a declared field changes the schema hash`() {
        val before = ProtSchema("X", 1, fields = listOf(SchemaField("a", FieldKind.INT)))
        val renamed = ProtSchema("X", 1, fields = listOf(SchemaField("b", FieldKind.INT)))
        val retyped = ProtSchema("X", 1, fields = listOf(SchemaField("a", FieldKind.STRING)))
        assertTrue(before.fieldsHash != renamed.fieldsHash, "a renamed field moves the hash")
        assertTrue(before.fieldsHash != retyped.fieldsHash, "a retyped field moves the hash")
    }

    @Test
    fun `flatten produces the path and value pairs an index is built from`() {
        val packet = DecodedPacket(
            "RUNCLIENTSCRIPT", 82,
            fields = listOf(
                Field("script", FieldValue.I(1234), RefDomain.SCRIPT),
                Field("args", FieldValue.L(listOf(FieldValue.I(1), FieldValue.S("x")))),
            ),
        )
        val flat = packet.flatten().toMap()
        assertEquals(FieldValue.I(1234), flat["script"])
        assertEquals(FieldValue.I(1), flat["args[0]"])
        assertEquals(FieldValue.S("x"), flat["args[1]"])
    }

    /** A build must be able to construct the codec for a revision by number, not only the current one. */
    @Test
    fun `a revision can be resolved by number`() {
        registerRevision950()
        assertNotNull(Codec.forRevision(950))
        assertTrue(950 in Codec.knownRevisions())
    }

    /** Client prots are projected from their verified decoders, never re-parsed from bytes. */
    @Test
    fun `a client prot projects its typed values into named fields`() {
        val button = org.projectx.core.net.prot.IfButton(
            buttonId = 1, interfaceHash = (1477 shl 16) or 42, slotId = 7, itemId = 995,
        )
        val projected = ClientProtProjection.project("IF_BUTTON1", 55, button)
        assertEquals(7L, projected["slotId"]?.long)
        assertEquals(995L, projected["itemId"]?.long)
        assertEquals(RefDomain.OBJ, projected["itemId"]?.ref)
        assertEquals(RefDomain.COMPONENT, projected["interfaceHash"]?.ref)

        val schema = ClientProtProjection.schemaFor("IF_BUTTON1", button)
        assertTrue(schema.fields.first { it.name == "interfaceHash" }.indexed, "an anchor id is indexed")
    }
}
