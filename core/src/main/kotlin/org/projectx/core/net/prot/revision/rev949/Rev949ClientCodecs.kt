package org.projectx.core.net.prot.revision.rev949

import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.projectx.core.game.chat.ChatFormat
import org.projectx.core.net.prot.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.buffer.readBooleanAdd
import world.gregs.voidps.buffer.readBooleanSubtract
import world.gregs.voidps.buffer.readByteAdd
import world.gregs.voidps.buffer.readByteSubtract
import world.gregs.voidps.buffer.readRSString
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUIntInverseMiddle
import world.gregs.voidps.buffer.readUIntLittle
import world.gregs.voidps.buffer.readUIntMiddle
import world.gregs.voidps.buffer.readUMedium
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.readUShortAdd
import world.gregs.voidps.buffer.readUShortAddLittle
import world.gregs.voidps.buffer.readUShortLittle

internal fun Codec.registerRev949ClientProts() {
    clientProt<Ping>(opcode = 0, size = 0)
    clientProt<MapBuildComplete>(opcode = 67, size = 0)
    clientProt<CloseModal>(opcode = 74, size = 0)
    clientProt<AbortPDialog>(opcode = 3, size = 0)

    clientProt<WindowStatus>(opcode = 48, size = 6) {
        WindowStatus(
            mode = readUByte().toInt(),
            width = readUShort(),
            height = readUShort(),
            flag = readUByte().toInt(),
        )
    }

    clientProt<RequestWorldList>(opcode = 125, size = 4) {
        RequestWorldList(worldlistVersion = readInt())
    }

    clientProt<MoveGameClick>(opcode = 107, size = 5) {
        val destY = readUShort()
        val destX = readUShortAdd()
        val run = readBooleanSubtract()
        MoveGameClick(destX = destX, destY = destY, run = run)
    }

    clientProt<MoveMinimapClick>(opcode = 66, size = 18) {
        val destY = readUShort()
        val destX = readUShortAdd()
        val run = readBooleanSubtract()
        skip(13L)
        MoveMinimapClick(destX = destX, destY = destY, run = run)
    }

    clientProt<EventMouseClick>(opcode = 2, size = 6) {
        val position = readUIntLittle()
        val packed = readUShortLittle()
        EventMouseClick(
            x = position and 0xFFFF,
            y = (position ushr 16) and 0xFFFF,
            rightButton = (packed ushr 15) and 1 == 1,
            timeDelta = packed and 0x7FFF,
        )
    }

    clientProt<EventMouseMove>(opcode = 4, size = ProtSize.VarByte)

    clientProt<EventKeyboard>(opcodes = intArrayOf(77), size = ProtSize.VarShort) { packetSize ->
        val byteLen = readUShort()
        val recordCount = minOf(byteLen, (packetSize - 2).coerceAtLeast(0)) / 4
        val events = ArrayList<KeyPress>(recordCount)
        repeat(recordCount) { events.add(KeyPress(keyCode = readUByte(), timeDelta = readUMedium())) }
        val consumed = 2 + recordCount * 4
        if (packetSize > consumed) skip((packetSize - consumed).toLong())
        EventKeyboard(events)
    }

    clientProt<EventAppletFocus>(opcode = 135, size = 1) {
        EventAppletFocus(focused = readUByte() != 0)
    }

    clientProt<EventCameraPosition>(opcode = 12, size = 4) {
        EventCameraPosition(yaw = readUShortLittle(), pitch = readUShortAddLittle())
    }

    clientProt<ClientCheat>(opcode = 147, size = ProtSize.VarByte) {
        readUByte()
        readUByte()
        ClientCheat(command = readRSString())
    }

    clientProt<FriendListAdd>(opcode = 38, size = ProtSize.VarByte) {
        FriendListAdd(displayName = readRSString())
    }
    clientProt<FriendListDel>(opcode = 63, size = ProtSize.VarByte) {
        FriendListDel(displayName = readRSString())
    }
    clientProt<IgnoreListAdd>(opcode = 32, size = ProtSize.VarByte) {
        IgnoreListAdd(displayName = readRSString())
    }
    clientProt<ClanChannelKickUser>(opcode = 108, size = ProtSize.VarByte) {
        readUByte()
        ClanChannelKickUser(username = readRSString())
    }
    clientProt<ResumePNameDialog>(opcode = 86, size = ProtSize.VarByte) {
        readUByte()
        ResumePNameDialog(name = readRSString())
    }

    clientProt<IfOnNpc>(opcode = 57, size = 12) {
        val item = readUMedium()
        val slot = readUShortAddLittle()
        val interfaceHash = readUIntMiddle()
        val npcIndex = readUShortAddLittle()
        val run = readUByte() != 0
        IfOnNpc(interfaceHash = interfaceHash, slot = slot, item = item, npcIndex = npcIndex, run = run)
    }

    clientProt<IfOnLoc>(opcode = 116, size = 18) {
        val y = readUShort()
        val x = readUShortAdd()
        val item = readUMedium()
        val run = readBooleanAdd()
        val interfaceHash = readUIntLittle()
        val locId = readUIntMiddle()
        val slot = readUShortAddLittle()
        IfOnLoc(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y, locId = locId, run = run)
    }

    clientProt<IfOnTile>(opcode = 47, size = 13) {
        val interfaceHash = readUIntInverseMiddle()
        val item = readMediumLittle()
        val y = readUShortAddLittle()
        val x = readUShort()
        val slot = readUShortAddLittle()
        IfOnTile(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y)
    }

    clientProt<IfOnObj>(opcode = 49, size = 17) {
        val y = readUShortAddLittle()
        val interfaceHash = readInt()
        val slot = readUShortLittle()
        val run = (readByteSubtract() and 1) != 0
        val objId = readMediumHiLoMid()
        val x = readUShortLittle()
        val item = readMediumHiLoMid()
        IfOnObj(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y, objId = objId, run = run)
    }

    clientProt<IfOnComponent>(opcode = 15, size = 18) {
        val targetSlot = readUShort()
        val slot = readUShortAdd()
        val targetItem = readMediumLittle()
        val item = readMediumHiLoMid()
        val interfaceHash = readUIntInverseMiddle()
        val targetHash = readUIntLittle()
        IfOnComponent(interfaceHash = interfaceHash, slot = slot, item = item, targetHash = targetHash, targetSlot = targetSlot, targetItem = targetItem)
    }

    clientProt<IfDrag>(opcode = 25, size = 18) {
        val toSlot = readUShort().toShort().toInt()
        val toItem = readMediumMidHiLo().let { if (it == 0xFFFFFF) -1 else it }
        val fromItem = readUMedium().let { if (it == 0xFFFFFF) -1 else it }
        val fromSlot = readUShortAddLittle().let { if (it == 0xFFFF) -1 else it }
        val toHash = readInt()
        val fromHash = readUIntMiddle()
        IfDrag(fromHash = fromHash, fromSlot = fromSlot, fromItem = fromItem, toHash = toHash, toSlot = toSlot, toItem = toItem)
    }

    clientProt<StoreServerpermVarcs>(opcodes = intArrayOf(105), size = ProtSize.VarShort) { packetSize ->
        readUByte()
        val pairCount = ((packetSize - 1) / 6).coerceAtLeast(0)
        val entries = LinkedHashMap<Int, Int>(pairCount)
        repeat(pairCount) { entries[readUShort()] = readInt() }
        val consumed = 1 + pairCount * 6
        if (packetSize > consumed) skip((packetSize - consumed).toLong())
        StoreServerpermVarcs(entries)
    }

    clientProt<MessagePublicSend>(opcodes = intArrayOf(35), size = ProtSize.VarByte) { packetSize ->
        val color = readUByte()
        val effect = readUByte()
        val message = readByteArray(packetSize - 2)
        MessagePublicSend(color = color, effect = effect, message = message)
    }

    clientProt<MessageFilter>(opcode = 21, size = 2) {
        val setting = readUByte()
        val value = readUByte()
        MessageFilter(setting = setting, value = value)
    }

    clientProt<MessageQuickChat>(opcodes = intArrayOf(101), size = ProtSize.VarByte) { packetSize ->
        MessageQuickChat(length = packetSize)
    }

    clientProt<MessagePrivateSend>(opcode = 71, size = ProtSize.VarShort) {
        val recipient = readRSString()
        val first = readUByte()
        val declared = if (first < 128) first else ((first shl 8) or readUByte()) - 0x8000
        val charCount = ChatFormat.clampCount(declared, ChatFormat.MAX_PRIVATE_CHARS)
        val raw = Cache.huffman.decompress(readByteArray(), charCount) ?: ""
        val message = ChatFormat.fixChatMessage(raw)
        MessagePrivateSend(toDisplayName = recipient, message = message.toByteArray(Charsets.ISO_8859_1))
    }

    clientProt<MessagePrivateEncrypted>(opcodes = intArrayOf(82), size = ProtSize.VarShort) { packetSize ->
        MessagePrivateEncrypted(encrypted = readByteArray(packetSize))
    }

    clientProt<ChatSetFilter>(opcode = 114, size = 3) {
        ChatSetFilter(publicFilter = readUByte(), privateFilter = readUByte(), tradeFilter = readUByte())
    }

    ifButtonClick(opcode = 55, buttonId = 1)
    ifButtonClick(opcode = 18, buttonId = 2)
    ifButtonClick(opcode = 99, buttonId = 3)
    ifButtonClick(opcode = 142, buttonId = 4)
    ifButtonClick(opcode = 13, buttonId = 5)
    ifButtonClick(opcode = 90, buttonId = 6)
    ifButtonClick(opcode = 17, buttonId = 7)
    ifButtonClick(opcode = 52, buttonId = 8)
    ifButtonClick(opcode = 137, buttonId = 9)
    ifButtonClick(opcode = 110, buttonId = 10)

    intArrayOf(41, 70, 33, 91, 1, 88).forEachIndexed { i, op -> opLoc(op, i + 1) }
    intArrayOf(60, 78, 39, 53, 75, 65).forEachIndexed { i, op -> opNpc(op, i + 1) }
    intArrayOf(28, 64, 143, 43, 45, 68).forEachIndexed { i, op -> opObj(op, i + 1) }
    intArrayOf(130, 79, 140, 103, 59, 96, 26, 122, 80, 54).forEachIndexed { i, op -> opPlayer(op, i + 1) }
}

private fun Codec.opLoc(opcode: Int, option: Int) {
    clientProt<OpLoc>(opcode = opcode, size = 9) {
        val y = readUShort()
        val x = readUShortAdd()
        val locId = readUIntMiddle()
        val run = (readByteAdd() and 1) != 0
        OpLoc(option = option, locId = locId, x = x, y = y, run = run)
    }
}

private fun Codec.opNpc(opcode: Int, option: Int) {
    clientProt<OpNpc>(opcode = opcode, size = 3) {
        val npcIndex = readUShortLittle()
        val run = (readUByte() and 1) != 0
        OpNpc(option = option, npcIndex = npcIndex, run = run)
    }
}

private fun Codec.opObj(opcode: Int, option: Int) {
    clientProt<OpObj>(opcode = opcode, size = 8) {
        val run = (readByteSubtract() and 1) != 0
        val objId = readUMedium()
        val y = readUShort()
        val x = readUShort()
        OpObj(option = option, objId = objId, x = x, y = y, run = run)
    }
}

private fun Codec.opPlayer(opcode: Int, option: Int) {
    clientProt<OpPlayer>(opcode = opcode, size = 3) {
        val run = (readUByte() and 1) != 0
        val playerIndex = readUShortLittle()
        OpPlayer(option = option, playerIndex = playerIndex, run = run)
    }
}

private fun Codec.ifButtonClick(opcode: Int, buttonId: Int) {
    clientProt<IfButton>(opcode = opcode, size = 9) {
        val interfaceHash = readInt()
        val itemId = readUMedium()
        val slotId = readUShort()
        IfButton(buttonId = buttonId, interfaceHash = interfaceHash, slotId = slotId, itemId = itemId)
    }
}

private fun Source.readMediumLittle(): Int = readUByte() or (readUByte() shl 8) or (readUByte() shl 16)

private fun Source.readMediumHiLoMid(): Int {
    val hi = readUByte()
    val lo = readUByte()
    val mid = readUByte()
    return (hi shl 16) or (mid shl 8) or lo
}

private fun Source.readMediumMidHiLo(): Int {
    val mid = readUByte()
    val hi = readUByte()
    val lo = readUByte()
    return (hi shl 16) or (mid shl 8) or lo
}
