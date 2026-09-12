package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.ParamRecord
import world.gregs.voidps.cache.type.Parameterized
import world.gregs.voidps.cache.type.Recolourable
import world.gregs.voidps.cache.type.Transforms
import world.gregs.voidps.cache.type.UnusedRecords

// The payloads several definition types share, written without their opcode byte. Unlike the read side
// mixins' own writers these never decide whether a record belongs in the file - the encoder already has -
// so each spells one payload and nothing else.

internal fun Writer.writeColours(definition: Recolourable) {
    val original = definition.originalColours ?: ShortArray(0)
    val modified = definition.modifiedColours ?: ShortArray(0)
    writeByte(original.size)
    for (index in original.indices) {
        writeShort(original[index].toInt())
        writeShort(modified[index].toInt())
    }
}

internal fun Writer.writeTextures(definition: Recolourable) {
    val original = definition.originalTextureColours ?: ShortArray(0)
    val modified = definition.modifiedTextureColours ?: ShortArray(0)
    writeByte(original.size)
    for (index in original.indices) {
        writeShort(original[index].toInt())
        writeShort(modified[index].toInt())
    }
}

internal fun Writer.writePalette(palette: ByteArray?) {
    val colours = palette ?: ByteArray(0)
    writeByte(colours.size)
    for (colour in colours) {
        writeByte(colour.toInt())
    }
}

internal fun Writer.writeParams(definition: Parameterized) {
    val records = definition.paramRecords ?: definition.params?.map { ParamRecord(it.key, it.value) } ?: emptyList()
    writeByte(records.size)
    for (record in records) {
        val value = record.value
        writeByte(value is String)
        writeMedium(record.id)
        if (value is String) {
            writeString(value)
        } else {
            writeInt(value as Int)
        }
    }
}

/** Transform ids as big smarts, the form indices 16 and 22 use. */
internal fun Writer.writeSmartTransforms(definition: Transforms, last: Boolean, wideVarbit: Boolean) {
    val ids = definition.transforms ?: IntArray(2) { -1 }
    writeVarbit(definition.varbit, wideVarbit)
    writeShort(if (definition.varp == -1) NULL else definition.varp)
    if (last) {
        writeBigSmart(ids[ids.size - 1])
    }
    writeSmart(ids.size - 2)
    for (index in 0 until ids.size - 1) {
        writeBigSmart(ids[index])
    }
}

/** Transform ids as shorts with a byte count, the form the config archives use. */
internal fun Writer.writeShortTransforms(definition: Transforms, last: Boolean, wideVarbit: Boolean) {
    val ids = definition.transforms ?: IntArray(2) { -1 }
    writeVarbit(definition.varbit, wideVarbit)
    writeShort(if (definition.varp == -1) NULL else definition.varp)
    if (last) {
        writeShort(if (ids[ids.size - 1] == -1) NULL else ids[ids.size - 1])
    }
    writeByte(ids.size - 2)
    for (index in 0 until ids.size - 1) {
        writeShort(if (ids[index] == -1) NULL else ids[index])
    }
}

/** Transform ids as shorts with a smart count, the form index 18 uses. */
internal fun Writer.writeNpcTransforms(definition: Transforms, last: Boolean, wideVarbit: Boolean) {
    val ids = definition.transforms ?: IntArray(2) { -1 }
    writeVarbit(definition.varbit, wideVarbit)
    writeShort(if (definition.varp == -1) NULL else definition.varp)
    if (last) {
        writeShort(if (ids[ids.size - 1] == -1) NULL else ids[ids.size - 1])
    }
    writeSmart(ids.size - 2)
    for (index in 0 until ids.size - 1) {
        writeShort(if (ids[index] == -1) NULL else ids[index])
    }
}

/** Every record whose payload the decoder kept verbatim, one entry per occurrence. */
internal fun IntArrayList.addUnused(definition: UnusedRecords) {
    val records = definition.unusedRecords ?: return
    for ((opcode, payloads) in records) {
        repeat(payloads.size) { add(opcode) }
    }
}

/** The [occurrence]th payload of [opcode], as the file held it. */
internal fun Writer.writeUnused(definition: UnusedRecords, opcode: Int, occurrence: Int) {
    val payloads = definition.unusedRecords?.get(opcode) ?: error("No kept payload for opcode $opcode.")
    writeBytes(payloads[occurrence])
}

/** Big endian IEEE-754, the form the readers read. */
internal fun Writer.writeFloat(value: Float) {
    writeInt(value.toRawBits())
}

/** The little endian seven bit groups a varint read expects. */
internal fun Writer.writeVarInt(value: Int) {
    var remaining = value
    while (remaining and 0x7f.inv() != 0) {
        writeByte((remaining and 0x7f) or 0x80)
        remaining = remaining ushr 7
    }
    writeByte(remaining and 0x7f)
}

/** A cp1252 type descriptor, the inverse of the decoders' `byteToChar`. */
internal fun Writer.writeTypeChar(value: Char) {
    writeByte(Cp1252.encode(value.toString())[0].toInt() and 0xff)
}

/** The version byte a jag string carries, which every shipped file leaves at zero. */
internal fun Writer.writeVersionedString(value: String) {
    writeByte(0)
    writeString(value)
}

private const val NULL = 65535

private fun Writer.writeVarbit(varbit: Int, wide: Boolean) {
    if (wide) {
        writeMedium(if (varbit == -1) Transforms.NULL_WIDE_VARBIT else varbit)
    } else {
        writeShort(if (varbit == -1) Transforms.NULL_SHORT else varbit)
    }
}
