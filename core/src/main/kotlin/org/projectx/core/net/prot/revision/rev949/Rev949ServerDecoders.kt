package org.projectx.core.net.prot.revision.rev949

import org.projectx.core.net.prot.Codec
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarDomain
import world.gregs.voidps.gameval.Gameval

internal fun Codec.registerRev949ServerDecoders() {
    serverDecode(95) {
        val value = readByte().toInt()
        val id = readUShortAddLittle()
        "varp=${varpRef(id)} value=$value${packedVarbits(VarDomain.PLAYER, id, value)}"
    }
    serverDecode(78) {
        val value = readUIntInverseMiddle()
        val id = readUShort()
        "varp=${varpRef(id)} value=$value${packedVarbits(VarDomain.PLAYER, id, value)}"
    }

    serverDecode(22) {
        val id = readUShort()
        val value = readByteSubtract()
        "varbit=${varbitRef(id)} value=$value"
    }
    serverDecode(51) {
        val value = readUIntInverseMiddle()
        val id = readUShortAdd() and 0xFFFF
        "varbit=${varbitRef(id)} value=$value"
    }

    serverDecode(6) {
        val value = readByteInverse()
        val id = readUShortAdd() and 0xFFFF
        "varc=${varcRef(id)} value=$value${packedVarbits(VarDomain.CLIENT, id, value)}"
    }
    serverDecode(40) {
        val id = readUShortLittle()
        val value = readInt()
        "varc=${varcRef(id)} value=$value${packedVarbits(VarDomain.CLIENT, id, value)}"
    }
    serverDecode(54) {
        val value = readByte().toInt()
        val id = readUShort()
        "varcbit=${varcRef(id)} value=$value"
    }
    serverDecode(50) {
        val value = readUIntLittle()
        val id = readUShortAddLittle()
        "varcbit=${varcRef(id)} value=$value"
    }
    serverDecode(56) {
        val text = readRSString()
        val id = readUShort()
        "varc=${varcRef(id)} value=\"$text\""
    }

    serverDecode(4) {
        val xp = readUIntLittle()
        val level = readByteAdd()
        val skillId = readByteInverse()
        "skill=${skillRef(skillId)} level=$level xp=$xp"
    }

    serverDecode(73) {
        skip(8L)
        val topLevelId = readUShortAddLittle()
        skip(9L)
        "topLevelInterface=${ifaceRef(topLevelId)}"
    }
    serverDecode(25) {
        skip(4L)
        val layer = readByteSubtract()
        skip(4L)
        skip(4L)
        val childId = readUShort()
        skip(4L)
        val componentHash = readUIntMiddle()
        "componentHash=${compRef(componentHash)} child=${ifaceRef(childId)} layer=$layer"
    }
    serverDecode(122) {
        val componentHash = readUIntLittle()
        val fromSlot = readUShort()
        val settings = readUIntInverseMiddle()
        val toSlot = readUShortLittle()
        "componentHash=${compRef(componentHash)} settings=$settings fromSlot=${slotRef(fromSlot)} toSlot=${slotRef(toSlot)}"
    }
    serverDecode(34) {
        val text = readRSString()
        val componentHash = readUIntMiddle()
        "componentHash=${compRef(componentHash)} text=\"$text\""
    }

    serverDecode(74) {
        "chatFilter=${readUByte()}"
    }
    serverDecode(178) {
        "createAccountReply=${readUByte()}"
    }

    // Both logouts carry one byte whose meaning no capture has caught yet: every recorded logout so
    // far is the last packet of its stream, so the byte is surfaced raw until one lands mid-capture.
    serverDecode(58) {
        "logout byte=${readUByte()}"
    }
    serverDecode(102) {
        "logoutFull byte=${readUByte()}"
    }
    serverDecode(66) {
        val entries = mutableListOf<String>()
        while (!exhausted()) {
            readUByte()
            val name = readRSString()
            val prev = readRSString()
            readRSString()
            entries += if (prev.isEmpty()) name else "$name(was $prev)"
        }
        "ignores=${entries.size}${if (entries.isEmpty()) "" else " [" + entries.joinToString(",") + "]"}"
    }
    serverDecode(229) {
        "jcoins=${readInt()}"
    }
    serverDecode(61) {
        val slot = readByteAdd() - 1
        val priority = readUByte() == 0x80
        val text = readRSString()
        val rawWorld = readUShortAddLittle()
        val worldId = if (rawWorld == 0xFFFF) -1 else rawWorld
        "slot=$slot text=\"$text\" priority=$priority worldId=$worldId"
    }
    serverDecode(82) {
        val types = readRSString()
        val args = ArrayList<Any>(types.length)
        for (c in types.reversed()) {
            when (c) {
                'i' -> args.add(readInt())
                's' -> args.add("\"${readRSString()}\"")
                'l' -> args.add(readLong())
            }
        }
        val scriptId = readInt()
        args.reverse()
        "script=$scriptId types=\"$types\" args=$args"
    }
}

private val SKILL_NAMES = arrayOf(
    "attack", "defence", "strength", "constitution", "ranged", "prayer", "magic", "cooking",
    "woodcutting", "fletching", "fishing", "firemaking", "crafting", "smithing", "mining",
    "herblore", "agility", "thieving", "slayer", "farming", "runecrafting", "hunter",
    "construction", "summoning", "dungeoneering", "divination", "invention", "archaeology",
    "necromancy",
)

private fun varpRef(id: Int) = "${Gameval.varp(id) ?: "?"}($id)"
private fun varcRef(id: Int) = "${Gameval.varc(id) ?: "?"}($id)"
private fun varbitRef(id: Int) = "${Gameval.varbit(id) ?: "?"}($id)"
private fun ifaceRef(id: Int) = "${Gameval.interfaceName(id) ?: "?"}($id)"
private fun skillRef(id: Int) = "${SKILL_NAMES.getOrNull(id) ?: "?"}($id)"
private fun slotRef(v: Int) = if (v == 0xFFFF) "-1" else v.toString()

private fun compRef(hash: Int): String {
    if (hash == -1) return "none(-1)"
    val iface = hash ushr 16
    val comp = hash and 0xFFFF
    val ifaceName = Gameval.interfaceName(iface) ?: "?"
    val compName = Gameval.component(iface, comp) ?: "?"
    return "$compName($iface:$comp) iface=$ifaceName($iface)"
}

private fun packedVarbits(domain: VarDomain, baseVar: Int, value: Int): String {
    val bits = VarBitType.baseVarMap[domain]?.get(baseVar)
    if (bits.isNullOrEmpty()) return ""
    return bits.sortedBy { it.startBit }.joinToString(prefix = " varbits=[", postfix = "]") {
        "${Gameval.varbit(it.id) ?: "?"}(${it.id})=${it.getValue(value)}"
    }
}
