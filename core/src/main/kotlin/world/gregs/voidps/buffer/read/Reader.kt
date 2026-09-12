package world.gregs.voidps.buffer.read

interface Reader {
    /**
     * Starting length of the packet
     */
    val length: Int

    val remaining: Int

    fun readBoolean() = readByte() == 1

    fun readBooleanAdd() = readByteAdd() == 1

    fun readBooleanInverse() = readByteInverse() == 1

    fun readBooleanSubtract() = readByteSubtract() == 1

    fun readUnsignedBoolean() = readUnsignedByte() == 1

    fun readByte(): Int

    fun readByteAdd(): Int

    fun readByteInverse(): Int

    fun readByteSubtract(): Int

    fun readUnsignedByte(): Int

    fun readUnsignedByteAdd(): Int

    fun readShort(): Int

    fun readShortAdd(): Int

    fun readShortLittle(): Int

    fun readShortAddLittle(): Int

    fun readUnsignedShort(): Int

    fun readUnsignedShortLittle(): Int

    fun readUnsignedShortAdd(): Int

    fun readMedium(): Int

    fun readUnsignedMedium(): Int

    fun readInt(): Int

    fun readIntInverseMiddle(): Int

    fun readIntLittle(): Int

    fun readUnsignedIntMiddle(): Int

    fun readSmart(): Int

    fun readBigSmart(): Int

    fun readLargeSmart(): Int

    /**
     * Unsigned 1-or-2 byte smart (jag `gSmart1or2`). Values < 128 use one byte,
     * otherwise two bytes biased by 0x8000. Identical to [readSmart].
     */
    fun readUnsignedSmart(): Int = readSmart()

    /**
     * Alias of [readSmart] kept for naming parity with the client's small-smart
     * reads (jag `gSmart1or2`) — same byte semantics as [readSmart].
     */
    fun readSmallSmart(): Int = readSmart()

    /**
     * Signed 1-or-2 byte smart (jag `gSmart1or2s`): a value in [-64, 63] is a
     * single byte biased by 0x40, otherwise two bytes biased by -0x4000.
     */
    fun readSignedSmart(): Int {
        val peek = readUnsignedByte()
        return if (peek < 128) {
            peek - 64
        } else {
            (((peek shl 8) or readUnsignedByte()) + 0x4000).toShort().toInt()
        }
    }

    /**
     * Accumulating smart size (jag `gSmart2or4s` size loop): sums repeated
     * [readUnsignedSmart] runs while each equals 32767. Identical to [readLargeSmart].
     */
    fun readSmartSizeVar(): Int = readLargeSmart()

    /**
     * Unsigned 3-byte big-endian integer (jag `g3`). Identical to [readUnsignedMedium].
     */
    fun readTriByte(): Int = readUnsignedMedium()

    fun readLong(): Long

    /**
     * Big-endian 32-bit IEEE-754 float, matching `java.nio.ByteBuffer.getFloat`
     * (the client's default-order float reads used by map water-patch data).
     */
    fun readFloat(): Float = Float.fromBits(readInt())

    fun readString(): String

    fun readVersionedString(): String {
        val version = readUnsignedByte()
        return if (version != 0) "" else readString()
    }

    fun readVarInt(): Int {
        var value = 0
        var shift = 0
        var byte = readUnsignedByte()
        while (byte and 0x80 != 0) {
            value = value or ((byte and 0x7f) shl shift)
            shift += 7
            byte = readUnsignedByte()
        }
        return value or ((byte and 0x7f) shl shift)
    }

    /**
     * Reads all bytes into [ByteArray]
     * @param value The array to be written to.
     */
    fun readBytes(value: ByteArray)

    /**
     * Reads [length] number of bytes starting at [offset] to [array].
     * @param array The [ByteArray] to be written to
     * @param offset Destination index
     * @param length Number of bytes to read
     */
    fun readBytes(array: ByteArray, offset: Int, length: Int = array.size)

    /**
     * Skips the [amount] bytes.
     * @param amount Number of bytes to skip
     */
    fun skip(amount: Int)

    fun position(): Int

    fun array(): ByteArray

    fun position(index: Int)

    /**
     * Returns the remaining number of readable bytes.
     * @return [Int]
     */
    fun readableBytes(): Int

    /**
     * Enables individual decoded byte writing aka 'bit access'
     */
    fun startBitAccess(): Reader

    /**
     * Disables 'bit access' mode
     */
    fun stopBitAccess(): Reader

    /**
     * Writes a bit during 'bit access'
     * @param bitCount number of bits to be written
     */
    fun readBits(bitCount: Int): Int
}