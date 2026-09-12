package org.projectx.core.net.prot.revision.rev950

import org.projectx.core.net.prot.Codec
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarDomain
import world.gregs.voidps.gameval.Gameval

internal fun Codec.registerRev950ServerDecoders() {
    serverDecode(79) {
        val value = readByte().toInt()
        val id = readUShortAdd()
        "varp=${varpRef(id)} value=$value${packedVarbits(VarDomain.PLAYER, id, value)}"
    }
    serverDecode(4) {
        val id = readUShortAddLittle()
        val value = readUIntMiddle()
        "varp=${varpRef(id)} value=$value${packedVarbits(VarDomain.PLAYER, id, value)}"
    }

    serverDecode(28) {
        val value = readByteAdd()
        val id = readUShortLittle()
        "varbit=${varbitRef(id)} value=$value"
    }
    serverDecode(82) {
        val value = readInt()
        val id = readUShortLittle()
        "varbit=${varbitRef(id)} value=$value"
    }

    serverDecode(126) {
        val id = readUShortLittle()
        val value = readByteSubtract()
        "varc=${varcRef(id)} value=$value${packedVarbits(VarDomain.CLIENT, id, value)}"
    }
    serverDecode(119) {
        val id = readUShortAdd() and 0xFFFF
        val value = readUIntInverseMiddle()
        "varc=${varcRef(id)} value=$value${packedVarbits(VarDomain.CLIENT, id, value)}"
    }
    serverDecode(48) {
        val value = readByteSubtract()
        val id = readUShort()
        "varcbit=${varcRef(id)} value=$value"
    }
    serverDecode(87) {
        val value = readUIntLittle()
        val id = readUShortAdd() and 0xFFFF
        "varcbit=${varcRef(id)} value=$value"
    }
    serverDecode(30) {
        val id = readUShortAdd() and 0xFFFF
        val text = readRSString()
        "varc=${varcRef(id)} value=\"$text\""
    }

    serverDecode(92) {
        val skillId = readByteInverse()
        val level = readByteInverse()
        val xp = readInt()
        "skill=${skillRef(skillId)} level=$level xp=$xp"
    }

    serverDecode(1) {
        val topLevelId = readUShortLittle()
        skip(17L)
        "topLevelInterface=${ifaceRef(topLevelId)}"
    }
    serverDecode(100) {
        val parentHash = readInt()
        skip(12L)
        val childId = readUShortAddLittle()
        val layer = readByteSubtract()
        skip(4L)
        "componentHash=${compRef(parentHash)} child=${ifaceRef(childId)} layer=$layer"
    }
    serverDecode(24) {
        val settings = readUIntMiddle()
        val toSlot = readUShort()
        val fromSlot = readUShortAdd()
        val componentHash = readUIntLittle()
        "componentHash=${compRef(componentHash)} settings=$settings fromSlot=${slotRef(fromSlot)} toSlot=${slotRef(toSlot)}"
    }
    serverDecode(115) {
        val componentHash = readInt()
        val text = readRSString()
        "componentHash=${compRef(componentHash)} text=\"$text\""
    }

    serverDecode(40) {
        "chatFilter=${readUByte()}"
    }
    serverDecode(156) {
        "createAccountReply=${readUByte()}"
    }

    serverDecode(46) {
        "logout byte=${readUByte()}"
    }
    serverDecode(73) {
        "logoutFull byte=${readUByte()}"
    }
    serverDecode(10) {
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
    serverDecode(153) {
        "jcoins=${readInt()}"
    }
    serverDecode(99) {
        val priority = readUByte() == 0x80
        val text = readRSString()
        val slot = readByteInverse() - 1
        val rawWorld = readUShortAdd()
        val worldId = if (rawWorld == 0xFFFF) -1 else rawWorld
        "slot=$slot text=\"$text\" priority=$priority worldId=$worldId"
    }
    serverDecode(35) {
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
