package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readShort
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.entity.player.Player
import java.lang.foreign.MemorySegment

class LoggedInPlayer(raw: MemorySegment, val client: Client = Client.getClient(NativeAccess.BASE_ADDR)) {
    val ptr: MemorySegment = raw.atLeast(OLoggedInPlayer.extent)
    val playerRights: Int
        get() = if (ptr.address() == 0L) 0 else ptr.readInt(OLoggedInPlayer.PLAYER_RIGHTS)

    val serverIndex: Int
        get() = if (ptr.address() == 0L) 0 else ptr.readInt(OLoggedInPlayer.PLAYER_INDEX)

    val targetIndex: Int
        get() = if (ptr.address() == 0L) 0 else ptr.readShort(OLoggedInPlayer.TARGET_INDEX).toInt()

    val targetType: EntityType
        get() = EntityType.fromType(ptr.readByte(OLoggedInPlayer.TARGET_TYPE).toInt())

    val self: Player
        get() = Player(client.playerManager[serverIndex])

    val isSelfLoaded: Boolean
        get() = client.playerManager[serverIndex].address() != 0L

    fun getPlayerName(): String? {
        if (ptr.address() == 0L)
            return null
        return EastlString(ptr.pointerAtOffset(OLoggedInPlayer.PLAYER_NAME, 0x24L)).toString()
    }
}