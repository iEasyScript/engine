package world.gregs.voidps.buffer

import kotlinx.io.Buffer
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Public-behavior tests for the buffer library: BufferWriter -> BufferReader round trips,
 * golden on-wire byte fixtures for the jag transforms, and the kotlinx.io [kotlinx.io.Source]
 * extension readers from JagExtensions that the packet codecs use.
 *
 * Byte fixtures are written against the wire SPEC (jag::Packet semantics), so these tests
 * stay valid across internal refactors of the reader/writer implementations.
 */
class BufferReadWriteTest {

    private fun written(block: BufferWriter.() -> Unit): ByteArray =
        BufferWriter(256).apply(block).toArray()

    private fun source(bytes: ByteArray): Buffer = Buffer().apply { write(bytes) }

    @Test
    fun `writer transforms produce the documented wire bytes`() {
        val cases = listOf<Pair<ByteArray, ByteArray>>(
            written { writeByte(0x12) } to byteArrayOf(0x12),
            written { writeByteAdd(0x12) } to byteArrayOf((0x12 + 128).toByte()),
            written { writeByteInverse(0x12) } to byteArrayOf((-0x12).toByte()),
            written { writeByteSubtract(0x12) } to byteArrayOf((128 - 0x12).toByte()),
            written { writeShort(0x1234) } to byteArrayOf(0x12, 0x34),
            written { writeShortLittle(0x1234) } to byteArrayOf(0x34, 0x12),
            written { writeShortAdd(0x1234) } to byteArrayOf(0x12, (0x34 + 128).toByte()),
            written { writeShortAddLittle(0x1234) } to byteArrayOf((0x34 + 128).toByte(), 0x12),
            written { writeMedium(0x123456) } to byteArrayOf(0x12, 0x34, 0x56),
            written { writeInt(0x12345678) } to byteArrayOf(0x12, 0x34, 0x56, 0x78),
            written { writeIntLittle(0x12345678) } to byteArrayOf(0x78, 0x56, 0x34, 0x12),
            written { writeIntMiddle(0x12345678) } to byteArrayOf(0x56, 0x78, 0x12, 0x34),
            written { writeIntInverseMiddle(0x12345678) } to byteArrayOf(0x34, 0x12, 0x78, 0x56),
            written { writeLong(0x1122334455667788L) } to byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
            written { writeSmart(5) } to byteArrayOf(0x05),
            written { writeSmart(200) } to byteArrayOf(0x80.toByte(), 0xC8.toByte()),
            written { writeBigSmart(5) } to byteArrayOf(0x00, 0x05),
            written { writeBigSmart(-1) } to byteArrayOf(0x7F, 0xFF.toByte()),
            written { writeBigSmart(100_000) } to byteArrayOf(0x80.toByte(), 0x01, 0x86.toByte(), 0xA0.toByte()),
            written { writeString("Ab") } to byteArrayOf(0x41, 0x62, 0x00),
        )
        for ((index, case) in cases.withIndex()) {
            assertContentEquals(case.second, case.first, "golden fixture #$index")
        }
    }

    @Test
    fun `reader decodes the documented wire bytes`() {
        assertEquals(0xFF, BufferReader(byteArrayOf(-1)).readUnsignedByte())
        assertEquals(-1, BufferReader(byteArrayOf(-1)).readByte())
        assertEquals(0x12, BufferReader(byteArrayOf((0x12 + 128).toByte())).readByteAdd())
        assertEquals(0x12, BufferReader(byteArrayOf((-0x12).toByte())).readByteInverse())
        assertEquals(0x12, BufferReader(byteArrayOf((128 - 0x12).toByte())).readByteSubtract())
        assertEquals(0x1234, BufferReader(byteArrayOf(0x12, 0x34)).readUnsignedShort())
        assertEquals(0xFEDC, BufferReader(byteArrayOf(0xFE.toByte(), 0xDC.toByte())).readUnsignedShort())
        assertEquals(-2, BufferReader(byteArrayOf(0xFF.toByte(), 0xFE.toByte())).readShort())
        assertEquals(0x1234, BufferReader(byteArrayOf(0x34, 0x12)).readUnsignedShortLittle())
        assertEquals(0x123456, BufferReader(byteArrayOf(0x12, 0x34, 0x56)).readUnsignedMedium())
        assertEquals(0x12345678, BufferReader(byteArrayOf(0x12, 0x34, 0x56, 0x78)).readInt())
        assertEquals(-1, BufferReader(byteArrayOf(-1, -1, -1, -1)).readInt())
        assertEquals(0x12345678, BufferReader(byteArrayOf(0x56, 0x78, 0x12, 0x34)).readUnsignedIntMiddle())
        assertEquals(0x1122334455667788L, BufferReader(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())).readLong())
        assertEquals(5, BufferReader(byteArrayOf(0x05)).readSmart())
        assertEquals(200, BufferReader(byteArrayOf(0x80.toByte(), 0xC8.toByte())).readSmart())
    }

    @Test
    fun `readMedium sign-extends only the high byte`() {
        // A middle or low byte above 0x7f must not sign-extend into the bytes above it;
        // only the leading byte carries the sign of a 24-bit value.
        assertEquals(0x008000, BufferReader(byteArrayOf(0x00, 0x80.toByte(), 0x00)).readMedium())
        assertEquals(0x00FFFF, BufferReader(byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte())).readMedium())
        assertEquals(0x123456, BufferReader(byteArrayOf(0x12, 0x34, 0x56)).readMedium())
        assertEquals(-1, BufferReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).readMedium())
        assertEquals(-0x800000, BufferReader(byteArrayOf(0x80.toByte(), 0x00, 0x00)).readMedium())
    }

    @Test
    fun `readBigSmart handles short, int and -1 encodings`() {
        assertEquals(0x1234, BufferReader(byteArrayOf(0x12, 0x34)).readBigSmart())
        assertEquals(-1, BufferReader(byteArrayOf(0x7F, 0xFF.toByte())).readBigSmart())
        assertEquals(0x10000, BufferReader(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x00)).readBigSmart())
        assertEquals(100_000, BufferReader(byteArrayOf(0x80.toByte(), 0x01, 0x86.toByte(), 0xA0.toByte())).readBigSmart())
    }

    @Test
    fun `readLargeSmart accumulates 32767 continuations`() {
        // 40000 = 32767 + 7233 -> smart(32767)=FF FF, smart(7233)=9C 41
        assertEquals(40_000, BufferReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x9C.toByte(), 0x41)).readLargeSmart())
        assertEquals(0, BufferReader(byteArrayOf(0x00)).readLargeSmart())
        assertEquals(127, BufferReader(byteArrayOf(0x7F)).readLargeSmart())
    }

    @Test
    fun `byte transform round trips across the signed byte range`() {
        for (value in -128..127) {
            assertEquals(value, BufferReader(written { writeByte(value) }).readByte(), "writeByte/readByte $value")
            assertEquals(value, BufferReader(written { writeByteAdd(value) }).readByteAdd(), "writeByteAdd/readByteAdd $value")
            assertEquals(value, BufferReader(written { writeByteInverse(value) }).readByteInverse().toByte().toInt(), "writeByteInverse/readByteInverse $value")
            assertEquals(value, BufferReader(written { writeByteSubtract(value) }).readByteSubtract(), "writeByteSubtract/readByteSubtract $value")
        }
    }

    @Test
    fun `multi-byte round trips`() {
        val shorts = intArrayOf(0, 1, 127, 128, 255, 256, 0x1234, 0x7FFF, 0x8000, 0xFFFF)
        for (v in shorts) {
            assertEquals(v, BufferReader(written { writeShort(v) }).readUnsignedShort(), "ushort $v")
            assertEquals(v, BufferReader(written { writeShortLittle(v) }).readUnsignedShortLittle(), "ushortLE $v")
        }
        val ints = intArrayOf(0, 1, -1, 0x12345678, Int.MIN_VALUE, Int.MAX_VALUE, 0x00FF00FF)
        for (v in ints) {
            assertEquals(v, BufferReader(written { writeInt(v) }).readInt(), "int $v")
            assertEquals(v, BufferReader(written { writeIntMiddle(v) }).readUnsignedIntMiddle(), "intMiddle $v")
        }
        val longs = longArrayOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL)
        for (v in longs) {
            assertEquals(v, BufferReader(written { writeLong(v) }).readLong(), "long $v")
        }
        for (v in intArrayOf(0, 1, 127, 128, 32766)) {
            assertEquals(v, BufferReader(written { writeSmart(v) }).readSmart(), "smart $v")
        }
        for (v in intArrayOf(-1, 0, 1, 32766, 32767, 32768, 100_000, Int.MAX_VALUE)) {
            assertEquals(v, BufferReader(written { writeBigSmart(v) }).readBigSmart(), "bigSmart $v")
        }
    }

    @Test
    fun `string round trips including CP-1252 specials`() {
        for (s in listOf("", "abc", "Hello World_123", "café", "€ price")) { // euro sign is 0x80 in cp1252
            assertEquals(s, BufferReader(written { writeString(s) }).readString(), "string '$s'")
        }
        // Two consecutive strings stay delimited
        val reader = BufferReader(written { writeString("one"); writeString("two") })
        assertEquals("one", reader.readString())
        assertEquals("two", reader.readString())
    }

    @Test
    fun `source extension readers match the writer transforms`() {
        assertEquals(0x12, source(byteArrayOf((0x12 + 128).toByte())).readByteAdd())
        assertEquals(0x12, source(byteArrayOf((128 - 0x12).toByte())).readByteSubtract())
        assertEquals(0x1234, source(byteArrayOf(0x12, 0x34)).readUShort())
        assertEquals(0x1234, source(byteArrayOf(0x34, 0x12)).readUShortLittle())
        assertEquals(0x1234, source(byteArrayOf(0x12, (0x34 + 128).toByte())).readUShortAdd() and 0xFFFF)
        assertEquals(0x1234, source(byteArrayOf((0x34 + 128).toByte(), 0x12)).readUShortAddLittle())
        assertEquals(0x12345678, source(byteArrayOf(0x56, 0x78, 0x12, 0x34)).readUIntMiddle())
        assertEquals(0x12345678, source(byteArrayOf(0x34, 0x12, 0x78, 0x56)).readUIntInverseMiddle())
        assertEquals(0x12345678, source(byteArrayOf(0x78, 0x56, 0x34, 0x12)).readUIntLittle())
        assertEquals(-0x12345679, source(byteArrayOf(0x87.toByte(), 0xA9.toByte(), 0xCB.toByte(), 0xED.toByte())).readUIntLittle())
        assertEquals(0x123456, source(byteArrayOf(0x12, 0x34, 0x56)).readUMedium())
        assertEquals(5, source(byteArrayOf(0x05)).readSmart())
        assertEquals(200, source(byteArrayOf(0x80.toByte(), 0xC8.toByte())).readSmart())
    }

    @Test
    fun `source RS string and jag string readers`() {
        assertEquals("abc", source(byteArrayOf(0x61, 0x62, 0x63, 0x00)).readRSString())
        // Jag string: version byte 0 prefix then null-terminated string
        assertEquals("abc", source(byteArrayOf(0x00, 0x61, 0x62, 0x63, 0x00)).readJagString())
        // Unterminated string reads to end of source
        assertEquals("ab", source(byteArrayOf(0x61, 0x62)).readRSString())
    }

    @Test
    fun `flags varint round trips through channel-independent source reader`() {
        // writeFlags emits 7-bit groups with continuation bit; readFlags inverts it.
        for (v in intArrayOf(0, 1, 0x7F, 0x80, 0x3FFF, 0x4000, 0x12345678, Int.MAX_VALUE)) {
            val buffer = Buffer()
            var flags = v
            while ((flags and 0x7F.inv()) != 0) {
                buffer.writeByte(((flags and 0x7F) or 0x80).toByte())
                flags = flags ushr 7
            }
            buffer.writeByte((flags and 0x7F).toByte())
            assertEquals(v, buffer.readFlags(), "flags $v")
        }
    }
}
