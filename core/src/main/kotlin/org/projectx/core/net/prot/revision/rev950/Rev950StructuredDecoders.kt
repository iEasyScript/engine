package org.projectx.core.net.prot.revision.rev950

import kotlinx.io.Source
import org.projectx.core.net.prot.*
import org.projectx.core.net.prot.decode.DecodedPacket
import org.projectx.core.net.prot.decode.FieldKind
import org.projectx.core.net.prot.decode.FieldSink
import org.projectx.core.net.prot.decode.FieldValue
import org.projectx.core.net.prot.decode.ProtSchema
import org.projectx.core.net.prot.decode.RefDomain
import org.projectx.core.net.prot.decode.SchemaField
import world.gregs.voidps.buffer.*

private const val S2C = 0

/**
 * Structured decoders for rev950.
 *
 * These exist to make a capture *investigable*: every field an investigation might start from - a
 * component, a varp, a script, an npc, a coordinate, an item - is emitted as a named, typed field
 * rather than baked into a display string, and the ones an investigation typically starts *from* are
 * marked indexed so finding them does not mean scanning.
 */
internal fun Codec.registerRev950StructuredDecoders() {
    variables()
    interfaces()
    scripts()
    entitiesAndWorld()
    playerState()
}

private fun Codec.decoder(
    dir: Int,
    opcode: Int,
    name: String,
    version: Int,
    vararg fields: SchemaField,
    reconstruct: ((DecodedPacket) -> Any?)? = null,
    body: suspend FieldSink.(Source, Int) -> Unit,
) {
    val schema = ProtSchema(name, version, fields = fields.toList())
    decodeStructured(dir, opcode, schema, reconstruct) { size ->
        val sink = FieldSink(name, opcode)
        sink.body(this, size)
        sink.build(trailingBytes = remainingBytes())
    }
}

private fun Source.remainingBytes(): Int = runCatching { if (exhausted()) 0 else 1 }.getOrDefault(0)

private fun field(name: String, kind: FieldKind, ref: RefDomain = RefDomain.NONE, indexed: Boolean = false) =
    SchemaField(name, kind, ref, indexed)

// ---------------------------------------------------------------- variables

private fun Codec.variables() {
    decoder(
        S2C, 79, "VARP_SMALL", 1,
        field("varp", FieldKind.INT, RefDomain.VARP, indexed = true),
        field("value", FieldKind.INT, indexed = true),
        reconstruct = { VarpSmall(it["varp"]!!.long!!.toInt(), it["value"]!!.long!!.toInt()) },
    ) { source, _ ->
        val value = source.readByte().toInt()
        int("varp", source.readUShortAdd(), RefDomain.VARP)
        int("value", value)
    }

    decoder(
        S2C, 4, "VARP_LARGE", 1,
        field("varp", FieldKind.INT, RefDomain.VARP, indexed = true),
        field("value", FieldKind.INT, indexed = true),
        reconstruct = { VarpLarge(it["varp"]!!.long!!.toInt(), it["value"]!!.long!!.toInt()) },
    ) { source, _ ->
        int("varp", source.readUShortAddLittle(), RefDomain.VARP)
        int("value", source.readUIntMiddle())
    }

    decoder(
        S2C, 28, "VARBIT_SMALL", 1,
        field("varbit", FieldKind.INT, RefDomain.VARBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readByteAdd()
        int("varbit", source.readUShortLittle(), RefDomain.VARBIT)
        int("value", value)
    }

    decoder(
        S2C, 82, "VARBIT_LARGE", 1,
        field("varbit", FieldKind.INT, RefDomain.VARBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readInt()
        int("varbit", source.readUShortLittle(), RefDomain.VARBIT)
        int("value", value)
    }

    decoder(
        S2C, 126, "CLIENT_SETVARC_SMALL", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        int("varc", source.readUShortLittle(), RefDomain.VARC)
        int("value", source.readByteSubtract())
    }

    decoder(
        S2C, 119, "CLIENT_SETVARC_LARGE", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        int("varc", source.readUShortAdd() and 0xFFFF, RefDomain.VARC)
        int("value", source.readUIntInverseMiddle())
    }

    decoder(
        S2C, 48, "CLIENT_SETVARCBIT_SMALL", 1,
        field("varcbit", FieldKind.INT, RefDomain.VARCBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readByteSubtract()
        int("varcbit", source.readUShort(), RefDomain.VARCBIT)
        int("value", value)
    }

    decoder(
        S2C, 87, "CLIENT_SETVARCBIT_LARGE", 1,
        field("varcbit", FieldKind.INT, RefDomain.VARCBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readUIntLittle()
        int("varcbit", source.readUShortAdd() and 0xFFFF, RefDomain.VARCBIT)
        int("value", value)
    }

    decoder(
        S2C, 30, "CLIENT_SETVARCSTR_SMALL", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("text", FieldKind.STRING, RefDomain.TEXT),
    ) { source, _ ->
        int("varc", source.readUShortAdd() and 0xFFFF, RefDomain.VARC)
        string("text", source.readRSString())
    }
}

// ---------------------------------------------------------------- interfaces

private fun Codec.interfaces() {
    decoder(
        S2C, 1, "IF_OPENTOP", 1,
        field("interface", FieldKind.INT, RefDomain.INTERFACE, indexed = true),
    ) { source, _ ->
        int("interface", source.readUShortLittle(), RefDomain.INTERFACE)
        source.skip(17L)
    }

    decoder(
        S2C, 100, "IF_OPENSUB", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("interface", FieldKind.INT, RefDomain.INTERFACE, indexed = true),
        field("layer", FieldKind.INT),
    ) { source, _ ->
        int("component", source.readInt(), RefDomain.COMPONENT)
        source.skip(12L)
        int("interface", source.readUShortAddLittle(), RefDomain.INTERFACE)
        int("layer", source.readByteSubtract())
        source.skip(4L)
    }

    decoder(
        S2C, 24, "IF_SETEVENTS", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("settings", FieldKind.INT, indexed = true),
        field("fromSlot", FieldKind.INT),
        field("toSlot", FieldKind.INT),
    ) { source, _ ->
        val settings = source.readUIntMiddle()
        val toSlot = source.readUShort()
        val fromSlot = source.readUShortAdd()
        int("component", source.readUIntLittle(), RefDomain.COMPONENT)
        int("settings", settings)
        int("fromSlot", fromSlot)
        int("toSlot", toSlot)
    }

    decoder(
        S2C, 67, "IF_SETHIDE", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("hide", FieldKind.BOOL, indexed = true),
        reconstruct = { IfSetHide(it["component"]!!.long!!.toInt(), it["hide"]!!.bool!!) },
    ) { source, _ ->
        int("component", source.readUIntMiddle(), RefDomain.COMPONENT)
        bool("hide", source.readUByte() == 1)
    }

    decoder(
        S2C, 69, "IF_CLOSESUB", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        reconstruct = { IfCloseSub(it["component"]!!.long!!.toInt()) },
    ) { source, _ ->
        int("component", source.readUIntInverseMiddle(), RefDomain.COMPONENT)
    }

    decoder(
        S2C, 115, "IF_SETTEXT", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("text", FieldKind.STRING, RefDomain.TEXT),
    ) { source, _ ->
        int("component", source.readInt(), RefDomain.COMPONENT)
        string("text", source.readRSString())
    }

    decoder(
        S2C, 25, "IF_SETNPCHEAD", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("npc", FieldKind.INT, RefDomain.NPC),
    ) { source, _ ->
        int("component", source.readUIntMiddle(), RefDomain.COMPONENT)
        int("npc", source.readUIntInverseMiddle(), RefDomain.NPC)
    }
}

// ---------------------------------------------------------------- scripts

private fun Codec.scripts() {
    decoder(
        S2C, 35, "RUNCLIENTSCRIPT", 1,
        field("script", FieldKind.INT, RefDomain.SCRIPT, indexed = true),
        field("types", FieldKind.STRING, RefDomain.NONE),
        field("args", FieldKind.LIST),
    ) { source, _ ->
        val types = source.readRSString()
        val args = ArrayList<FieldValue>(types.length)
        for (c in types.reversed()) {
            when (c) {
                'i' -> args.add(FieldValue.I(source.readInt().toLong()))
                's' -> args.add(FieldValue.S(source.readRSString()))
                'l' -> args.add(FieldValue.I(source.readLong()))
            }
        }
        val script = source.readInt()
        args.reverse()
        int("script", script, RefDomain.SCRIPT)
        string("types", types, RefDomain.NONE)
        list("args", args)
    }
}

// ---------------------------------------------------------------- entities and world

private fun Codec.entitiesAndWorld() {
    decoder(
        S2C, 2, "UPDATE_ZONE_FULL_FOLLOWS", 1,
        field("level", FieldKind.INT),
        field("zoneX", FieldKind.INT, RefDomain.COORD, indexed = true),
        field("zoneY", FieldKind.INT, RefDomain.COORD, indexed = true),
        reconstruct = {
            UpdateZoneFullFollows(
                level = it["level"]!!.long!!.toInt(),
                zoneX = it["zoneX"]!!.long!!.toInt(),
                zoneY = it["zoneY"]!!.long!!.toInt(),
            )
        },
    ) { source, _ ->
        int("level", source.readByteAdd())
        int("zoneX", source.readByte().toInt(), RefDomain.COORD)
        int("zoneY", source.readByte().toInt(), RefDomain.COORD)
    }
}

// ---------------------------------------------------------------- player state

private fun Codec.playerState() {
    decoder(
        S2C, 92, "UPDATE_STAT", 1,
        field("skill", FieldKind.INT, RefDomain.SKILL, indexed = true),
        field("level", FieldKind.INT),
        field("xp", FieldKind.INT, indexed = true),
        reconstruct = {
            UpdateStat(
                skillId = it["skill"]!!.long!!.toInt(),
                xp = it["xp"]!!.long!!.toInt(),
                level = it["level"]!!.long!!.toInt(),
            )
        },
    ) { source, _ ->
        int("skill", source.readByteInverse(), RefDomain.SKILL)
        int("level", source.readByteInverse())
        int("xp", source.readInt())
    }

    decoder(
        S2C, 153, "JCOINS_UPDATE", 1,
        field("jcoins", FieldKind.INT),
        reconstruct = { JcoinsUpdate(it["jcoins"]!!.long!!.toInt()) },
    ) { source, _ -> int("jcoins", source.readInt()) }
}
