package world.gregs.voidps.buffer.write

import world.gregs.voidps.buffer.Cp1252

interface Writer {
    fun setByte(index: Int, value: Int)

    fun writeByte(value: Int)

    fun writeByteAdd(value: Int)

    fun writeByteInverse(value: Int)

    fun writeByteSubtract(value: Int)

    fun writeByte(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeShort(value: Int)

    fun writeShortAdd(value: Int)

    fun writeShortLittle(value: Int)

    fun writeShortAddLittle(value: Int)

    fun writeMedium(value: Int)

    fun writeInt(value: Int)

    fun writeIntMiddle(value: Int)

    fun writeIntInverse(value: Int)

    fun writeIntInverseMiddle(value: Int)

    fun writeIntLittle(value: Int)

    fun writeIntInverseLittle(value: Int)

    fun writeLong(value: Long)

    fun writeSmart(value: Int) {
        if (value >= 128) {
            writeShort(value + 32768)
        } else {
            writeByte(value)
        }
    }

    fun writeBigSmart(value: Int) {
        when {
            value == -1 -> writeShort(32767)
            value in 0..32766 -> writeShort(value)
            else -> writeInt(value or 0x80000000.toInt())
        }
    }

    /** The inverse of [world.gregs.voidps.buffer.read.Reader.readSignedSmart]. */
    fun writeSignedSmart(value: Int) {
        if (value in -64..63) {
            writeByte(value + 64)
        } else {
            writeShort(value - 0x4000 and 0xffff)
        }
    }

    /** The inverse of [world.gregs.voidps.buffer.read.Reader.readLargeSmart]. */
    fun writeLargeSmart(value: Int) {
        var remaining = value
        while (remaining >= 32767) {
            writeSmart(32767)
            remaining -= 32767
        }
        writeSmart(remaining)
    }

    fun writeFloat(value: Float) {
        writeInt(value.toRawBits())
    }

    /** The inverse of [world.gregs.voidps.buffer.read.Reader.readVarInt]. */
    fun writeVarInt(value: Int) {
        var remaining = value
        while (remaining and 0x7f.inv() != 0) {
            writeByte(remaining and 0x7f or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining and 0x7f)
    }

    fun writeString(value: String?) {
        if (value != null) {
            for (byte in Cp1252.encode(value)) {
                writeByte(byte.toInt() and 0xff)
            }
        }
        writeByte(0)
    }

    fun writePrefixedString(value: String) {
        writeByte(0)
        for (byte in Cp1252.encode(value)) {
            writeByte(byte.toInt() and 0xff)
        }
        writeByte(0)
    }

    fun writeBytes(value: ByteArray)

    fun writeBytes(data: ByteArray, offset: Int, length: Int)

    fun startBitAccess()

    fun stopBitAccess()

    fun writeBits(bitCount: Int, value: Boolean) {
        writeBits(bitCount, if (value) 1 else 0)
    }

    fun writeBits(bitCount: Int, value: Int)

    fun skip(position: Int) {
        for (i in 0 until position) {
            writeByte(0)
        }
    }

    fun position(): Int

    fun position(index: Int)

    fun bitIndex(): Int

    fun bitIndex(index: Int)

    fun toArray(): ByteArray

    fun array(): ByteArray

    fun clear()

    fun remaining(): Int
}