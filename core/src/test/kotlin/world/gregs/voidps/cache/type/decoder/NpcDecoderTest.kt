package world.gregs.voidps.cache.type.decoder

import org.junit.jupiter.api.Test
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.type.data.NpcType
import kotlin.test.assertEquals

class NpcDecoderTest {

    @Test
    fun `dynamic model block flag 0x10 entries carry no key byte`() {
        for (opcode in intArrayOf(186, 189)) {
            val writer = BufferWriter(64)
            writer.writeByte(opcode)
            writer.writeShort(1)
            if (opcode == 189) writer.writeMedium(2) else writer.writeShort(2)
            writer.writeShort(3)
            writer.writeByte(0x10)
            writer.writeByte(2)
            repeat(2) { writer.writeBytes(ByteArray(8) { 0x7f }) }
            writer.writeShort(4)
            writer.writeByte(2)
            writer.writeString("Guard")
            writer.writeByte(0)
            val reader = BufferReader(writer.toArray())

            val npc = NpcType(1)
            NpcDecoder().readLoop(npc, reader)

            assertEquals("Guard", npc.name, "opcode $opcode")
            assertEquals(0, reader.remaining, "opcode $opcode")
        }
    }
}
