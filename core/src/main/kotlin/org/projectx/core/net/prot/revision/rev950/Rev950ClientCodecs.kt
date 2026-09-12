package org.projectx.core.net.prot.revision.rev950

import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.projectx.core.game.chat.ChatFormat
import org.projectx.core.net.prot.*
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.buffer.readByteAdd
import world.gregs.voidps.buffer.readByteInverse
import world.gregs.voidps.buffer.readByteSubtract
import world.gregs.voidps.buffer.readRSString
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUIntLittle
import world.gregs.voidps.buffer.readUIntInverseMiddle
import world.gregs.voidps.buffer.readUIntMiddle
import world.gregs.voidps.buffer.readUMedium
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.readUShortAdd
import world.gregs.voidps.buffer.readUShortAddLittle
import world.gregs.voidps.buffer.readUShortLittle

internal fun Codec.registerRev950ClientProts() {
    clientProt<Ping>(opcode = 104, size = 0)
    clientProt<MapBuildComplete>(opcode = 5, size = 0)
    clientProt<CloseModal>(opcode = 11, size = 0)
    clientProt<AbortPDialog>(opcode = 91, size = 0)

    clientProt<WindowStatus>(opcode = 9, size = 6) {
        WindowStatus(
            mode = readUByte().toInt(),
            width = readUShort(),
            height = readUShort(),
            flag = readUByte().toInt(),
        )
    }

    clientProt<IfDropdownSelect>(opcode = 124, size = 11) {
        val value = readUIntMiddle()
        val slot = readUShort()
        val interfaceHash = readUIntLittle()
        val committed = readByteSubtract() != 0
        IfDropdownSelect(interfaceHash = interfaceHash, slot = slot, value = value, committed = committed)
    }

    clientProt<ResumePCountDialog>(opcode = 114, size = 4) {
        ResumePCountDialog(value = readInt())
    }

    clientProt<RequestWorldList>(opcode = 108, size = 4) {
        RequestWorldList(worldlistVersion = readInt())
    }

    clientProt<MoveGameClick>(opcode = 88, size = 5) {
        val destY = readUShort()
        val run = readByteAdd() != 0
        val destX = readUShortAddLittle()
        MoveGameClick(destX = destX, destY = destY, run = run)
    }

    clientProt<MoveMinimapClick>(opcode = 78, size = 18) {
        val destY = readUShort()
        val run = readByteAdd() != 0
        val destX = readUShortAddLittle()
        skip(13L)
        MoveMinimapClick(destX = destX, destY = destY, run = run)
    }

    clientProt<EventMouseClick>(opcode = 65, size = 6) {
        val position = readInt()
        val packed = readUShort()
        EventMouseClick(
            x = position and 0xFFFF,
            y = (position ushr 16) and 0xFFFF,
            rightButton = (packed ushr 15) and 1 == 1,
            timeDelta = packed and 0x7FFF,
        )
    }

    clientProt<EventMouseMove>(opcode = 33, size = ProtSize.VarByte)

    clientProt<EventKeyboard>(opcodes = intArrayOf(25), size = ProtSize.VarShort) { packetSize ->
        val byteLen = readUShort()
        val recordCount = minOf(byteLen, (packetSize - 2).coerceAtLeast(0)) / 4
        val events = ArrayList<KeyPress>(recordCount)
        repeat(recordCount) { events.add(KeyPress(keyCode = readUByte(), timeDelta = readUMedium())) }
        val consumed = 2 + recordCount * 4
        if (packetSize > consumed) skip((packetSize - consumed).toLong())
        EventKeyboard(events)
    }

    clientProt<EventAppletFocus>(opcode = 115, size = 1) {
        EventAppletFocus(focused = readUByte() != 0)
    }

    clientProt<EventCameraPosition>(opcode = 2, size = 4) {
        EventCameraPosition(yaw = readUShortAddLittle(), pitch = readUShortLittle())
    }

    clientProt<ClientCheat>(opcode = 23, size = ProtSize.VarByte) {
        readUByte()
        readUByte()
        readUByte()
        ClientCheat(command = readRSString())
    }

    clientProt<FriendListAdd>(opcode = 4, size = ProtSize.VarByte) {
        readUByte()
        FriendListAdd(displayName = readRSString())
    }
    clientProt<IgnoreListAdd>(opcode = 111, size = ProtSize.VarByte) {
        readUByte()
        val name = readRSString()
        readUByte()
        IgnoreListAdd(displayName = name)
    }
    clientProt<ClanChannelKickUser>(opcode = 55, size = ProtSize.VarByte) {
        readUByte()
        ClanChannelKickUser(username = readRSString())
    }
    clientProt<ResumePNameDialog>(opcode = 47, size = ProtSize.VarByte) {
        readUByte()
        ResumePNameDialog(name = readRSString())
    }

    clientProt<IfOnNpc>(opcode = 19, size = 12) {
        val npcIndex = readUShort()
        val item = readMediumLittle()
        val run = readByteInverse() != 0
        val slot = readUShortLittle()
        val interfaceHash = readInt()
        IfOnNpc(interfaceHash = interfaceHash, slot = slot, item = item, npcIndex = npcIndex, run = run)
    }

    clientProt<IfOnLoc>(opcode = 90, size = 18) {
        val run = readByteInverse() != 0
        val x = readUShortAdd()
        val slot = readUShort()
        val y = readUShortAddLittle()
        val item = readMediumLittle()
        val locId = readUIntLittle()
        val interfaceHash = readUIntMiddle()
        IfOnLoc(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y, locId = locId, run = run)
    }

    clientProt<IfOnTile>(opcode = 85, size = 13) {
        val slot = readUShort()
        val item = readMediumMidHiLo()
        val interfaceHash = readInt()
        val x = readUShortAdd()
        val y = readUShortLittle()
        IfOnTile(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y)
    }

    clientProt<IfOnObj>(opcode = 112, size = 17) {
        val x = readUShortLittle()
        val run = (readByteInverse() and 1) != 0
        val slot = readUShortAdd()
        val item = readMediumLittle()
        val y = readUShort()
        val interfaceHash = readInt()
        val objId = readMediumMidHiLo()
        IfOnObj(interfaceHash = interfaceHash, slot = slot, item = item, x = x, y = y, objId = objId, run = run)
    }

    clientProt<IfOnComponent>(opcode = 69, size = 18) {
        val slot = readUShortAdd()
        val item = readMediumHiLoMid()
        val targetHash = readUIntMiddle()
        val targetSlot = readUShort()
        val interfaceHash = readUIntMiddle()
        val targetItem = readMediumLittle()
        IfOnComponent(interfaceHash = interfaceHash, slot = slot, item = item, targetHash = targetHash, targetSlot = targetSlot, targetItem = targetItem)
    }

    clientProt<IfDrag>(opcode = 12, size = 18) {
        val fromSlot = readUShortLittle().let { if (it == 0xFFFF) -1 else it }
        val fromHash = readUIntMiddle()
        val fromItem = readMediumLittle().let { if (it == 0xFFFFFF) -1 else it }
        val toSlot = readUShortLittle().let { if (it == 0xFFFF) -1 else it }
        val toHash = readUIntInverseMiddle()
        val toItem = readMediumHiLoMid().let { if (it == 0xFFFFFF) -1 else it }
        IfDrag(fromHash = fromHash, fromSlot = fromSlot, fromItem = fromItem, toHash = toHash, toSlot = toSlot, toItem = toItem)
    }

    clientProt<StoreServerpermVarcs>(opcodes = intArrayOf(14), size = ProtSize.VarShort) { packetSize ->
        readUShort()
        readUByte()
        val pairCount = ((packetSize - 3) / 6).coerceAtLeast(0)
        val entries = LinkedHashMap<Int, Int>(pairCount)
        repeat(pairCount) { entries[readUShort()] = readInt() }
        val consumed = 3 + pairCount * 6
        if (packetSize > consumed) skip((packetSize - consumed).toLong())
        StoreServerpermVarcs(entries)
    }

    clientProt<MessagePublicSend>(opcodes = intArrayOf(87), size = ProtSize.VarByte) { packetSize ->
        readUByte()
        val color = readUByte()
        val effect = readUByte()
        val message = readByteArray(packetSize - 3)
        MessagePublicSend(color = color, effect = effect, message = message)
    }

    clientProt<MessageFilter>(opcode = 99, size = 2) {
        val setting = readUByte()
        val value = readUByte()
        MessageFilter(setting = setting, value = value)
    }

    clientProt<MessageQuickChat>(opcodes = intArrayOf(77), size = ProtSize.VarByte) { packetSize ->
        MessageQuickChat(length = packetSize)
    }

    clientProt<MessagePrivateSend>(opcode = 72, size = ProtSize.VarShort) {
        readUShort()
        val recipient = readRSString()
        val first = readUByte()
        val declared = if (first < 128) first else ((first shl 8) or readUByte()) - 0x8000
        val charCount = ChatFormat.clampCount(declared, ChatFormat.MAX_PRIVATE_CHARS)
        val raw = Cache.huffman.decompress(readByteArray(), charCount) ?: ""
        val message = ChatFormat.fixChatMessage(raw)
        MessagePrivateSend(toDisplayName = recipient, message = message.toByteArray(Charsets.ISO_8859_1))
    }

    clientProt<MessagePrivateEncrypted>(opcodes = intArrayOf(121), size = ProtSize.VarShort) { packetSize ->
        MessagePrivateEncrypted(encrypted = readByteArray(packetSize))
    }

    clientProt<ChatSetFilter>(opcode = 116, size = 3) {
        ChatSetFilter(publicFilter = readUByte(), privateFilter = readUByte(), tradeFilter = readUByte())
    }

    intArrayOf(18, 122, 89, 100, 81, 126, 49, 66, 31, 59).forEachIndexed { i, op -> ifButtonClick(op, i + 1) }
    intArrayOf(34, 48, 24, 41, 73, 79).forEachIndexed { i, op -> opLoc(op, i + 1) }
    intArrayOf(60, 92, 27, 107, 13, 36).forEachIndexed { i, op -> opNpc(op, i + 1) }
    intArrayOf(127, 103, 22, 56, 52, 113).forEachIndexed { i, op -> opObj(op, i + 1) }
    intArrayOf(20, 46, 71, 39, 37, 94, 51, 63, 117, 58).forEachIndexed { i, op -> opPlayer(op, i + 1) }
}

private fun Codec.opLoc(opcode: Int, option: Int) {
    clientProt<OpLoc>(opcode = opcode, size = 9) {
        val y = readUShortAddLittle()
        val locId = readInt()
        val x = readUShortAdd()
        val run = (readByteInverse() and 1) != 0
        OpLoc(option = option, locId = locId, x = x, y = y, run = run)
    }
}

private fun Codec.opNpc(opcode: Int, option: Int) {
    clientProt<OpNpc>(opcode = opcode, size = 3) {
        val run = (readUByte() and 1) != 0
        val npcIndex = readUShortAdd()
        OpNpc(option = option, npcIndex = npcIndex, run = run)
    }
}

private fun Codec.opObj(opcode: Int, option: Int) {
    clientProt<OpObj>(opcode = opcode, size = 8) {
        val run = (readByteSubtract() and 1) != 0
        val x = readUShort()
        val y = readUShort()
        val objId = readUMedium()
        OpObj(option = option, objId = objId, x = x, y = y, run = run)
    }
}

private fun Codec.opPlayer(opcode: Int, option: Int) {
    clientProt<OpPlayer>(opcode = opcode, size = 3) {
        val playerIndex = readUShortLittle()
        val run = (readByteAdd() and 1) != 0
        OpPlayer(option = option, playerIndex = playerIndex, run = run)
    }
}

private fun Codec.ifButtonClick(opcode: Int, buttonId: Int) {
    clientProt<IfButton>(opcode = opcode, size = 9) {
        val itemId = readUMedium()
        val interfaceHash = readUIntInverseMiddle()
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

