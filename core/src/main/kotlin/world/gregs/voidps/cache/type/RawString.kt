package world.gregs.voidps.cache.type

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.Writer

internal fun Reader.readRawString(): String {
    val text = StringBuilder()
    while (true) {
        val byte = readUnsignedByte()
        if (byte == 0) {
            return text.toString()
        }
        text.append(byte.toChar())
    }
}

internal fun Writer.writeRawString(value: String) {
    for (character in value) {
        writeByte(character.code)
    }
    writeByte(0)
}

/** A non-zero version byte is the whole string: the client takes it as empty and reads no further. */
internal fun Reader.readVersionedRawString(): String = if (readUnsignedByte() != 0) "" else readRawString()

internal fun Writer.writeVersionedRawString(value: String) {
    writeByte(0)
    writeRawString(value)
}
