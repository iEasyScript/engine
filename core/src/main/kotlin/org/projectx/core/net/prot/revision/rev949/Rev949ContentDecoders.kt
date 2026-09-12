package org.projectx.core.net.prot.revision.rev949

import kotlinx.io.Source
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.decode.DecodedPacket
import org.projectx.core.net.prot.decode.FieldKind
import org.projectx.core.net.prot.decode.FieldSink
import org.projectx.core.net.prot.decode.FieldValue
import org.projectx.core.net.prot.decode.ProtSchema
import org.projectx.core.net.prot.decode.RefDomain
import org.projectx.core.net.prot.decode.SchemaField
import world.gregs.voidps.buffer.*

/**
 * Decoders for the packets that carry what actually happened: what was in a container, what the
 * world looked like, and what appeared in it.
 *
 * Every one is the inverse of an encoder in this same revision, never a layout read off a capture -
 * a plausible-but-wrong reading produces plausible-but-wrong answers to every question asked of it
 * afterwards, which is worse than having no decoder at all.
 */
internal fun Codec.registerRev949ContentDecoders() {
    containers()
    worldBuild()
    zoneEvents()
}

private const val S2C = 0

private fun Codec.content(
    opcode: Int,
    name: String,
    version: Int,
    vararg fields: SchemaField,
    body: suspend FieldSink.(Source, Int) -> Unit,
) {
    decodeStructured(S2C, opcode, ProtSchema(name, version, fields = fields.toList())) { size ->
        val sink = FieldSink(name, opcode)
        sink.body(this, size)
        sink.build()
    }
}

private fun field(name: String, kind: FieldKind, ref: RefDomain = RefDomain.NONE, indexed: Boolean = false) =
    SchemaField(name, kind, ref, indexed)

// ---------------------------------------------------------------- containers

/** Inverse of `writeInventoryAmount`: a byte, or a marker byte followed by the real count. */
private fun Source.readInventoryAmount(): Int {
    val first = readUByte()
    return if (first == 255) readInt() else first
}

/** Inverse of `writeObjVars`: a count, then that many (id, value) pairs. */
private fun Source.skipObjVars() {
    val count = readUByte()
    skip(count * 6L)
}

private fun Codec.containers() {
    content(
        8, "UPDATE_INV_FULL", 1,
        field("container", FieldKind.INT, RefDomain.INV, indexed = true),
        field("size", FieldKind.INT),
        field("items", FieldKind.LIST, RefDomain.OBJ, indexed = true),
        field("amounts", FieldKind.LIST),
    ) { source, _ ->
        val container = source.readUShort()
        val flags = source.readUByte()
        val hasVars = flags and 2 != 0
        val size = source.readUShort()
        int("container", container, RefDomain.INV)
        int("size", size)

        val items = ArrayList<FieldValue>(size)
        val amounts = ArrayList<FieldValue>(size)
        for (slot in 0 until size) {
            if (source.exhausted()) {
                partial("container ended after $slot of $size slots")
                break
            }
            // Stored as id+1 so that zero can mean an empty slot.
            val objId = source.readUMedium() - 1
            val amount = source.readInventoryAmount()
            if (hasVars) source.skipObjVars()
            items.add(FieldValue.I(objId.toLong()))
            amounts.add(FieldValue.I(amount.toLong()))
        }
        list("items", items, RefDomain.OBJ)
        list("amounts", amounts)
    }

    content(
        43, "UPDATE_INV_PARTIAL", 1,
        field("container", FieldKind.INT, RefDomain.INV, indexed = true),
        field("slots", FieldKind.LIST),
        field("items", FieldKind.LIST, RefDomain.OBJ, indexed = true),
        field("amounts", FieldKind.LIST),
    ) { source, _ ->
        val container = source.readUShort()
        val flags = source.readUByte()
        val hasVars = flags and 2 != 0
        int("container", container, RefDomain.INV)

        val slots = ArrayList<FieldValue>()
        val items = ArrayList<FieldValue>()
        val amounts = ArrayList<FieldValue>()
        while (!source.exhausted()) {
            val slot = source.readSmart()
            val objId = source.readUMedium() - 1
            slots.add(FieldValue.I(slot.toLong()))
            items.add(FieldValue.I(objId.toLong()))
            // An empty slot carries no amount; only a present item does.
            if (objId >= 0) {
                amounts.add(FieldValue.I(source.readInventoryAmount().toLong()))
                if (hasVars) source.skipObjVars()
            } else {
                amounts.add(FieldValue.I(0))
            }
        }
        list("slots", slots)
        list("items", items, RefDomain.OBJ)
        list("amounts", amounts)
    }
}

// ---------------------------------------------------------------- world build

private fun Codec.worldBuild() {
    /**
     * The header only. What follows is the zone mapping bit-stream, whose length depends on values
     * this build does not expose here - and the header is what identifies an instance anyway.
     */
    content(
        93, "REBUILD_REGION", 1,
        field("type", FieldKind.INT),
        field("centerZoneX", FieldKind.INT, RefDomain.COORD, indexed = true),
        field("centerZoneY", FieldKind.INT, RefDomain.COORD, indexed = true),
        field("baseZoneX", FieldKind.INT, RefDomain.COORD),
        field("baseZoneY", FieldKind.INT, RefDomain.COORD),
        field("widthZones", FieldKind.INT),
        field("heightZones", FieldKind.INT),
    ) { source, _ ->
        int("type", source.readUByte())
        int("npcInfoCoordBitWidth", source.readUByte())
        source.skip(1L)
        val centerY = source.readUShortLittle()
        source.skip(1L)
        int("centerZoneX", source.readUShort(), RefDomain.COORD)
        int("centerZoneY", centerY, RefDomain.COORD)
        int("baseZoneX", source.readUShort(), RefDomain.COORD)
        int("baseZoneY", source.readUShort(), RefDomain.COORD)
        int("widthZones", source.readUByte())
        int("heightZones", source.readUByte())
    }
}

// ---------------------------------------------------------------- zone events

/**
 * A zone packet's coordinate is packed relative to the zone the preceding [UPDATE_ZONE_FULL_FOLLOWS]
 * or the enclosing partial named, so only the offset within it is carried here. Resolving it to a
 * world tile needs that zone, which the replay holds - these decoders expose the offset and leave
 * the join to the caller rather than inventing an absolute coordinate they cannot know.
 */
private fun FieldSink.packedCoord(packed: Int) {
    int("offsetX", (packed shr 4) and 0x7)
    int("offsetY", packed and 0x7)
    int("level", (packed shr 7) and 0x3)
}

private fun Codec.zoneEvents() {
    content(
        149, "LOC_ADD", 1,
        field("loc", FieldKind.INT, RefDomain.LOC, indexed = true),
        field("shape", FieldKind.INT),
        field("rotation", FieldKind.INT),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        source.skip(1L)
        val packed = source.readByteAdd()
        int("loc", source.readUIntMiddle(), RefDomain.LOC)
        val shapeFlags = source.readByteAdd()
        int("shape", (shapeFlags shr 2) and 0x1F)
        int("rotation", shapeFlags and 0x3)
        packedCoord(packed)
    }

    content(
        41, "OBJ_ADD", 1,
        field("obj", FieldKind.INT, RefDomain.OBJ, indexed = true),
        field("count", FieldKind.INT),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        val packed = source.readUByte()
        val low = source.readByteAdd()
        val high = source.readUByte()
        int("obj", source.readUShort(), RefDomain.OBJ)
        int("count", (high shl 8) or (low and 0xFF))
        packedCoord(packed)
    }

    content(
        14, "OBJ_DEL", 1,
        field("obj", FieldKind.INT, RefDomain.OBJ, indexed = true),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        val packed = source.readUByte()
        val low = source.readUByte()
        val high = source.readUByte()
        int("obj", (high shl 8) or low, RefDomain.OBJ)
        packedCoord(packed)
    }

    content(
        23, "OBJ_COUNT", 1,
        field("obj", FieldKind.INT, RefDomain.OBJ, indexed = true),
        field("oldCount", FieldKind.INT),
        field("newCount", FieldKind.INT),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        val packed = source.readUByte()
        int("obj", source.readUShort(), RefDomain.OBJ)
        int("oldCount", source.readUShort())
        int("newCount", source.readUShort())
        packedCoord(packed)
    }

    content(
        173, "MAP_ANIM", 1,
        field("graphic", FieldKind.INT, RefDomain.GRAPHIC, indexed = true),
        field("heightOffset", FieldKind.INT),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        val packed = source.readUByte()
        int("graphic", source.readInt(), RefDomain.GRAPHIC)
        int("rotationDirection", source.readUByte())
        int("loopCount", source.readUByte())
        int("heightOffset", source.readUByte())
        int("scale", source.readUShort())
        packedCoord(packed)
    }

    content(
        160, "SOUND_AREA", 1,
        field("sound", FieldKind.INT, RefDomain.NONE, indexed = true),
        field("offsetX", FieldKind.INT),
        field("offsetY", FieldKind.INT),
    ) { source, _ ->
        val packed = source.readUByte()
        int("sound", source.readInt())
        int("rotationDirection", source.readUByte())
        int("loopCount", source.readUByte())
        int("heightOffset", source.readUByte())
        int("scale", source.readUShort())
        packedCoord(packed)
    }
}
