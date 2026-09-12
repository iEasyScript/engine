package world.gregs.voidps.cache.type

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.type.data.ClientScriptSwitchCase
import world.gregs.voidps.cache.type.data.ClientScriptType

/**
 * Reads and writes the clientscript container: the framing of an index 12 file that can be
 * understood without knowing what a single opcode means.
 *
 * ```
 * name              null-terminated string, or a bare 0 byte when absent
 * instructions      opaque until an operand-width table says how wide each one is
 * -- headerStart --
 * i32 instructionCount
 * u16 int/string/long local counts, then int/string/long argument counts
 * -- switch block --
 * u8  table count, then per table u16 case count and that many (i32 key, i32 offset)
 * -- trailer --
 * u16 switch block size
 * ```
 *
 * The instruction stream stays a byte blob. Splitting it into instructions needs a per-opcode
 * operand-width table that is calibrated against the cache in hand and shifts whenever Jagex
 * renumbers the opcode set, so that walk lives with the toolchain owning that table. The framing
 * around it needs no such table: `headerStart` is derived by working backwards from the trailer.
 */
object ClientScriptCodec {

    private const val FIXED_HEADER_BYTES = 16
    private const val TRAILER_BYTES = 2

    fun decode(data: ByteArray): ClientScriptType = ClientScriptType().also { decode(BufferReader(data), it) }

    fun decode(buffer: Reader, script: ClientScriptType) {
        require(buffer.length > TRAILER_BYTES + FIXED_HEADER_BYTES) {
            "Clientscript ${script.id} too small to contain a header (${buffer.length} bytes)"
        }

        buffer.position(buffer.length - TRAILER_BYTES)
        val switchBlockSize = buffer.readUnsignedShort()
        val headerStart = buffer.length - TRAILER_BYTES - switchBlockSize - FIXED_HEADER_BYTES
        require(headerStart in 1 until buffer.length) {
            "Clientscript ${script.id} header starts at $headerStart of ${buffer.length} bytes"
        }

        buffer.position(headerStart)
        script.instructionCount = buffer.readInt()
        script.intLocalCount = buffer.readUnsignedShort()
        script.stringLocalCount = buffer.readUnsignedShort()
        script.longLocalCount = buffer.readUnsignedShort()
        script.intArgumentCount = buffer.readUnsignedShort()
        script.stringArgumentCount = buffer.readUnsignedShort()
        script.longArgumentCount = buffer.readUnsignedShort()

        val tableCount = buffer.readUnsignedByte()
        val tables = ArrayList<List<ClientScriptSwitchCase>>(tableCount)
        repeat(tableCount) {
            val caseCount = buffer.readUnsignedShort()
            val cases = ArrayList<ClientScriptSwitchCase>(caseCount)
            repeat(caseCount) { cases.add(ClientScriptSwitchCase(buffer.readInt(), buffer.readInt())) }
            tables.add(cases)
        }
        script.switchTables = tables

        buffer.position(0)
        script.name = readNullString(buffer)
        val instructionBytes = headerStart - buffer.position()
        require(instructionBytes >= 0) {
            "Clientscript ${script.id} name overruns its header by ${-instructionBytes} bytes"
        }
        script.instructions = ByteArray(instructionBytes)
        buffer.readBytes(script.instructions)
        buffer.position(buffer.length)
    }

    fun encode(script: ClientScriptType): ByteArray {
        val writer = BufferWriter(encodedSize(script))

        if (script.name == null) writer.writeByte(0) else writer.writeString(script.name)
        writer.writeBytes(script.instructions)

        writer.writeInt(script.instructionCount)
        writer.writeShort(script.intLocalCount)
        writer.writeShort(script.stringLocalCount)
        writer.writeShort(script.longLocalCount)
        writer.writeShort(script.intArgumentCount)
        writer.writeShort(script.stringArgumentCount)
        writer.writeShort(script.longArgumentCount)

        val switchBlockStart = writer.position()
        writer.writeByte(script.switchTables.size)
        for (table in script.switchTables) {
            writer.writeShort(table.size)
            for (case in table) {
                writer.writeInt(case.key)
                writer.writeInt(case.offset)
            }
        }
        writer.writeShort(writer.position() - switchBlockStart)

        return writer.toArray()
    }

    private fun encodedSize(script: ClientScriptType): Int {
        var size = (script.name?.length ?: 0) + 1 + script.instructions.size + FIXED_HEADER_BYTES + 1
        for (table in script.switchTables) size += 2 + table.size * 8
        return size + TRAILER_BYTES
    }

    /** A lone 0 byte means "no name", so an empty name is indistinguishable from an absent one. */
    private fun readNullString(buffer: Reader): String? {
        val at = buffer.position()
        if (buffer.readUnsignedByte() == 0) return null
        buffer.position(at)
        return buffer.readString()
    }
}
