package org.projectx.core.net.prot.revision.rev949

import kotlinx.io.Source
import org.projectx.core.net.prot.*
import org.projectx.core.net.prot.decode.DecodedPacket
import org.projectx.core.net.prot.decode.FieldKind
import org.projectx.core.net.prot.decode.FieldSink
import org.projectx.core.net.prot.decode.ProtSchema
import org.projectx.core.net.prot.decode.RefDomain
import org.projectx.core.net.prot.decode.SchemaField
import world.gregs.voidps.buffer.*

private const val S2C = 0
private const val C2S = 1

/**
 * Structured decoders for rev949.
 *
 * These exist to make a capture *investigable*: every field an investigation might start from - a
 * component, a varp, a script, an npc, a coordinate, an item - is emitted as a named, typed field
 * rather than baked into a display string, and the ones an investigation typically starts *from* are
 * marked indexed so finding them does not mean scanning.
 *
 * Coverage is deliberately spread across categories rather than deep in one: the point is that any
 * kind of event can anchor a query, not that interfaces in particular are well served.
 */
internal fun Codec.registerRev949StructuredDecoders() {
    variables()
    interfaces()
    interfaceExtras()
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
        S2C, 95, "VARP_SMALL", 1,
        field("varp", FieldKind.INT, RefDomain.VARP, indexed = true),
        field("value", FieldKind.INT, indexed = true),
        reconstruct = { VarpSmall(it["varp"]!!.long!!.toInt(), it["value"]!!.long!!.toInt()) },
    ) { source, _ ->
        val value = source.readByte().toInt()
        int("varp", source.readUShortAddLittle(), RefDomain.VARP)
        int("value", value)
    }

    decoder(
        S2C, 78, "VARP_LARGE", 1,
        field("varp", FieldKind.INT, RefDomain.VARP, indexed = true),
        field("value", FieldKind.INT, indexed = true),
        reconstruct = { VarpLarge(it["varp"]!!.long!!.toInt(), it["value"]!!.long!!.toInt()) },
    ) { source, _ ->
        val value = source.readUIntInverseMiddle()
        int("varp", source.readUShort(), RefDomain.VARP)
        int("value", value)
    }

    decoder(
        S2C, 22, "VARBIT_SMALL", 1,
        field("varbit", FieldKind.INT, RefDomain.VARBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        int("varbit", source.readUShort(), RefDomain.VARBIT)
        int("value", source.readByteSubtract())
    }

    decoder(
        S2C, 51, "VARBIT_LARGE", 1,
        field("varbit", FieldKind.INT, RefDomain.VARBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readUIntInverseMiddle()
        int("varbit", source.readUShortAdd() and 0xFFFF, RefDomain.VARBIT)
        int("value", value)
    }

    decoder(
        S2C, 6, "CLIENT_SETVARC_SMALL", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readByteInverse()
        int("varc", source.readUShortAdd() and 0xFFFF, RefDomain.VARC)
        int("value", value)
    }

    decoder(
        S2C, 40, "CLIENT_SETVARC_LARGE", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        int("varc", source.readUShortLittle(), RefDomain.VARC)
        int("value", source.readInt())
    }

    decoder(
        S2C, 54, "CLIENT_SETVARCBIT_SMALL", 1,
        field("varcbit", FieldKind.INT, RefDomain.VARCBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readByte().toInt()
        int("varcbit", source.readUShort(), RefDomain.VARCBIT)
        int("value", value)
    }

    decoder(
        S2C, 50, "CLIENT_SETVARCBIT_LARGE", 1,
        field("varcbit", FieldKind.INT, RefDomain.VARCBIT, indexed = true),
        field("value", FieldKind.INT, indexed = true),
    ) { source, _ ->
        val value = source.readUIntLittle()
        int("varcbit", source.readUShortAddLittle(), RefDomain.VARCBIT)
        int("value", value)
    }

    decoder(
        S2C, 56, "CLIENT_SETVARCSTR_SMALL", 1,
        field("varc", FieldKind.INT, RefDomain.VARC, indexed = true),
        field("text", FieldKind.STRING, RefDomain.TEXT),
    ) { source, _ ->
        val text = source.readRSString()
        int("varc", source.readUShort(), RefDomain.VARC)
        string("text", text)
    }
}

// ---------------------------------------------------------------- interfaces

private fun Codec.interfaces() {
    decoder(
        S2C, 73, "IF_OPENTOP", 1,
        field("interface", FieldKind.INT, RefDomain.INTERFACE, indexed = true),
    ) { source, _ ->
        source.skip(8L)
        int("interface", source.readUShortAddLittle(), RefDomain.INTERFACE)
        source.skip(9L)
    }

    decoder(
        S2C, 25, "IF_OPENSUB", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("interface", FieldKind.INT, RefDomain.INTERFACE, indexed = true),
        field("layer", FieldKind.INT),
    ) { source, _ ->
        source.skip(4L)
        val layer = source.readByteSubtract()
        source.skip(8L)
        val child = source.readUShort()
        source.skip(4L)
        int("component", source.readUIntMiddle(), RefDomain.COMPONENT)
        int("interface", child, RefDomain.INTERFACE)
        int("layer", layer)
    }

    decoder(
        S2C, 122, "IF_SETEVENTS", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("settings", FieldKind.INT, indexed = true),
        field("fromSlot", FieldKind.INT),
        field("toSlot", FieldKind.INT),
    ) { source, _ ->
        val component = source.readUIntLittle()
        val fromSlot = source.readUShort()
        val settings = source.readUIntInverseMiddle()
        val toSlot = source.readUShortLittle()
        int("component", component, RefDomain.COMPONENT)
        int("settings", settings)
        int("fromSlot", fromSlot)
        int("toSlot", toSlot)
    }

    decoder(
        S2C, 124, "IF_SETHIDE", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("hide", FieldKind.BOOL, indexed = true),
        reconstruct = { IfSetHide(it["component"]!!.long!!.toInt(), it["hide"]!!.bool!!) },
    ) { source, _ ->
        int("component", source.readUIntInverseMiddle(), RefDomain.COMPONENT)
        bool("hide", source.readByteAdd() != 0)
    }

    decoder(
        S2C, 107, "IF_CLOSESUB", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        reconstruct = { IfCloseSub(it["component"]!!.long!!.toInt()) },
    ) { source, _ ->
        int("component", source.readInt(), RefDomain.COMPONENT)
    }

    decoder(
        S2C, 24, "IF_SETPLAYERHEAD", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        reconstruct = { IfSetPlayerHead(it["component"]!!.long!!.toInt()) },
    ) { source, _ ->
        int("component", source.readUIntLittle(), RefDomain.COMPONENT)
    }

    decoder(
        S2C, 34, "IF_SETTEXT", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("text", FieldKind.STRING, RefDomain.TEXT),
    ) { source, _ ->
        val text = source.readRSString()
        int("component", source.readUIntMiddle(), RefDomain.COMPONENT)
        string("text", text)
    }
}

private fun Codec.interfaceExtras() {
    decoder(
        S2C, 17, "IF_SETNPCHEAD", 1,
        field("component", FieldKind.INT, RefDomain.COMPONENT, indexed = true),
        field("colour24", FieldKind.INT),
    ) { source, _ ->
        val colour = source.readUIntMiddle()
        int("component", source.readUIntLittle(), RefDomain.COMPONENT)
        int("colour24", colour)
    }
}

// ---------------------------------------------------------------- scripts

private fun Codec.scripts() {
    decoder(
        S2C, 82, "RUNCLIENTSCRIPT", 1,
        field("script", FieldKind.INT, RefDomain.SCRIPT, indexed = true),
        field("types", FieldKind.STRING, RefDomain.NONE),
        field("args", FieldKind.LIST),
    ) { source, _ ->
        val types = source.readRSString()
        val args = ArrayList<org.projectx.core.net.prot.decode.FieldValue>(types.length)
        for (c in types.reversed()) {
            when (c) {
                'i' -> args.add(org.projectx.core.net.prot.decode.FieldValue.I(source.readInt().toLong()))
                's' -> args.add(org.projectx.core.net.prot.decode.FieldValue.S(source.readRSString()))
                'l' -> args.add(org.projectx.core.net.prot.decode.FieldValue.I(source.readLong()))
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
        S2C, 28, "UPDATE_ZONE_FULL_FOLLOWS", 1,
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
        int("level", source.readUByte())
        int("zoneX", source.readUByte(), RefDomain.COORD)
        int("zoneY", source.readByteAdd(), RefDomain.COORD)
    }
}

// ---------------------------------------------------------------- player state

private fun Codec.playerState() {
    decoder(
        S2C, 4, "UPDATE_STAT", 1,
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
        val xp = source.readUIntLittle()
        val level = source.readByteAdd()
        int("skill", source.readByteInverse(), RefDomain.SKILL)
        int("level", level)
        int("xp", xp)
    }

    decoder(
        S2C, 229, "JCOINS_UPDATE", 1,
        field("jcoins", FieldKind.INT),
        reconstruct = { JcoinsUpdate(it["jcoins"]!!.long!!.toInt()) },
    ) { source, _ -> int("jcoins", source.readInt()) }
}
