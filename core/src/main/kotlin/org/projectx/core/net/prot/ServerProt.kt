package org.projectx.core.net.prot

import kotlinx.serialization.Serializable
import org.projectx.core.game.InvSlot
import org.projectx.core.model.ChatMessageType
import org.projectx.core.model.IFEvents
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import org.projectx.core.worldlist.WorldList

/** Marker interface for all server-to-client packets. */
interface ServerProt

data class VarpSmall(val id: Int, val value: Int) : ServerProt
data class VarpLarge(val id: Int, val value: Int) : ServerProt
data class VarpLong(val id: Int, val value: Long) : ServerProt
data class ClientSetVarcSmall(val id: Int, val value: Int) : ServerProt
data class ClientSetVarcLarge(val id: Int, val value: Int) : ServerProt
data class ClientSetVarcStr(val id: Int, val value: String) : ServerProt
data class UpdateStat(val skillId: Int, val xp: Int, val level: Int) : ServerProt

data class VarpBitSmall(val id: Int, val value: Int) : ServerProt

data class VarpBitLarge(val id: Int, val value: Int) : ServerProt

data class ClientSetVarcBitSmall(val id: Int, val value: Int) : ServerProt

data class ClientSetVarcBitLarge(val id: Int, val value: Int) : ServerProt

/** String-first variant, distinct from the id-first [ClientSetVarcStr]. */
data class ClientSetVarcStrLarge(val id: Int, val value: String) : ServerProt

@JvmInline
value class ResetClientVarcache(val dummy: Int = 0) : ServerProt

@JvmInline
value class StoreServerpermVarcsAck(val dummy: Int = 0) : ServerProt

@Serializable @JvmInline value class VarclanEnable(val dummy: Int = 0) : ServerProt
@Serializable @JvmInline value class VarclanDisable(val dummy: Int = 0) : ServerProt

data class IfSetScrollPos(val topLevelId: Int, val subId: Int = 0) : ServerProt

data class IfOpenTop(val topLevelId: Int) : ServerProt {
    constructor(interfaceName: String) : this(Gameval.requireId(Gameval.INTERFACE, interfaceName))
}

data class IfSetPosition(val subId: Int, val walkable: Int, val parentHash: Int) : ServerProt

/**
 * Opens a child interface into a parent component slot. [parentHash] is the packed parent
 * component hash `(parentInterface shl 16) or slot`.
 */
data class IfOpenSub(val childId: Int, val layer: Int, val parentHash: Int) : ServerProt

data class IfCloseSub(val componentHash: Int) : ServerProt

data class IfMoveSub(val topId: Int, val mode: Int) : ServerProt

/** Sets the event mask for a range of slots on a component. Use [IFEvents] to build the bitfield. */
data class IfSetEvents(val events: IFEvents) : ServerProt

data class IfSetTargetParam(
    val componentHash: Int,
    val eventsMask: Int,
    val endSlot: Int = -1,
    val startSlot: Int = -1,
) : ServerProt

data class IfSetHide(val componentHash: Int, val hide: Boolean) : ServerProt

data class IfOpenSubActiveLoc(
    val subId: Int,
    val componentHash: Int,
    val locType: Int,
    val packedCoord: Int,
    val angle: Int,
    val layer: Int,
) : ServerProt

data class DoCheat(val imageUrl: String) : ServerProt

data class IfSetPlayerModelSelf(val componentHash: Int) : ServerProt

data class IfSetModel(val value: Int, val componentHash: Int) : ServerProt

data class IfSetPlayerHead(val componentHash: Int) : ServerProt

data class IfSetPlayerHeadIgnoreworn(val scale: Int, val componentHash: Int, val partA: Int, val partB: Int) : ServerProt

data class IfSetPlayerModelOther(val objectSlot: Int, val objectCount: Int, val componentHash: Int) : ServerProt

data class IfSetPlayerHeadOther(val componentHash: Int, val frame: Int, val animId: Int) : ServerProt

data class IfSetNpcHead(val colour24: Int, val componentHash: Int) : ServerProt

data class IfSetPlayerModelSnapshot(val componentHash: Int, val smallIdx: Int) : ServerProt

data class IfSetPlayerHeadSnapshot(val smallIdx: Int, val componentHash: Int) : ServerProt

data class IfSetTextAntimacro(val flag: Int, val componentHash: Int) : ServerProt

data class IfSetColour(val rgb555: Int, val componentHash: Int) : ServerProt

data class IfSetGraphic(val graphicId: Int, val componentHash: Int) : ServerProt

data class IfSetHttpImage(val frame: Int, val componentHash: Int) : ServerProt

data class IfSetObject(val componentHash: Int, val objId: Int, val count: Int) : ServerProt

data class IfSetAngle(val componentHash: Int, val angleX: Int, val angleY: Int, val zoom: Int) : ServerProt

data class IfSetAnim(val animationId: Int, val componentHash: Int) : ServerProt

data class IfSetTextFont(val componentHash: Int, val fontId: Int) : ServerProt

data class IfSetRetex(
    val scrollW: Int,
    val scrollH: Int,
    val componentHash: Int,
    val subSlot: Int,
) : ServerProt

data class IfSetClickmask(val flag: Int, val componentHash: Int) : ServerProt

data class IfSetObjectLong(val npcId: Int, val componentHash: Int, val part1: Int, val part2: Int) : ServerProt

data class IfSetRecol(
    val scrollY: Int,
    val componentHash: Int,
    val scrollX: Int,
    val subSlot: Int,
) : ServerProt

data class IfOpenSubActivePlayer(val payload: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is IfOpenSubActivePlayer && payload.contentEquals(other.payload))
    override fun hashCode(): Int = payload.contentHashCode()
}

data class IfOpenSubActiveNpc(val payload: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is IfOpenSubActiveNpc && payload.contentEquals(other.payload))
    override fun hashCode(): Int = payload.contentHashCode()
}

data class IfOpenSubActiveObj(val payload: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is IfOpenSubActiveObj && payload.contentEquals(other.payload))
    override fun hashCode(): Int = payload.contentHashCode()
}

data class IfSubSwap(val componentA: Int, val componentB: Int) : ServerProt

@JvmInline
value class TriggerOndialogabort(val dummy: Int = 0) : ServerProt

data class Cutscene2dPlay(val id: Int) : ServerProt

data class IfSetText(val componentHash: Int, val text: String) : ServerProt

@JvmInline
value class SetReadyFlag(val dummy: Int = 0) : ServerProt

@JvmInline
value class ServerTickEnd(val dummy: Int = 0) : ServerProt

@JvmInline
value class LobbyTickEnd(val dummy: Int = 0) : ServerProt

@JvmInline
value class ChangeLobby(val dummy: Int = 0) : ServerProt

@JvmInline
value class NoTimeout(val dummy: Int = 0) : ServerProt

data class UpdateRunEnergy(val energy: Int) : ServerProt

data class SetTargetMarker(
    val playerLocalX: Int,
    val playerLocalY: Int,
    val targetLocalX: Int,
    val targetLocalY: Int,
    val targetType: Int,
    val targetIndex: Int,
    val clear: Boolean,
) : ServerProt {
    companion object {
        const val TYPE_NPC = 3
        const val TYPE_TILE = 0
    }
}

data class SetPlayerOp(val slot: Int, val text: String?, val priority: Boolean = false) : ServerProt

/** Sets the client's default left-click move/interact action state. Empty body (varByte, len 0). */
@JvmInline value class SetMoveAction(val dummy: Int = 0) : ServerProt

/** Configures the NPC left-click "attack" priority threshold (official sends 0xFF at login). */
data class ReduceNpcAttackPriority(val level: Int = 0xFF) : ServerProt

@Serializable
data class UpdateIgnoreList(val ignores: List<IgnoreEntry>) : ServerProt {
    @Serializable
    data class IgnoreEntry(val displayName: String, val previousName: String = "")
}

@Serializable
data class FriendStatus(val updates: List<FriendStatusUpdate>) : ServerProt {
    @Serializable
    data class FriendStatusUpdate(
        val warnMessage: Int = 0,
        val displayName: String,
        val previousName: String = "",
        val worldId: Int = 0,
        val fcRank: Int = 0,
        val flags: Int = 0,
        val worldName: String = "",
        val platform: Int = 0,
        val worldFlags: Int = 0,
        val notes: String = "",
    )
}

/** Sent after the friend list has been fully transmitted. */
@Serializable
data class FriendlistLoaded(val dummy: Int = 0) : ServerProt

/** Private chat filter setting. */
@Serializable
data class ChatFilterSettingsPrivateChat(val filter: Int) : ServerProt

/** GAME_MESSAGE -- sends a filtered chat message to the client chatbox. */
@Serializable
data class GameMessage(
    val type: ChatMessageType,
    val message: String,
    val targetDisplayName: String? = null,
    val effectFlags: Int = 0,
) : ServerProt

/** MESSAGE_PRIVATE -- incoming private message from another player. */
@Serializable
data class MessagePrivate(
    val crown: Int,
    val displayName: String,
    val quickResponseName: String = displayName,
    val message: String,
) : ServerProt

/** MESSAGE_PRIVATE_ECHO -- echo of a PM we sent (appears in our own chatbox). */
@Serializable
data class MessagePrivateEcho(
    val senderDisplayName: String,
    val message: String,
) : ServerProt

/** MESSAGE_FRIENDSCHAT -- a message in a friends chat channel. */
@Serializable
data class MessageFriendsChat(
    val crown: Int,
    val displayName: String,
    val quickResponseName: String = displayName,
    val chatName: String,
    val message: String,
) : ServerProt

/**
 * UPDATE_FRIENDCHAT_CHANNEL_FULL -- full friends chat channel state.
 * Send with [clear]=true and null fields to leave/clear the channel.
 */
@Serializable
data class FriendsChatChannel(
    val clear: Boolean = false,
    val ownerDisplayName: String? = null,
    val ownerUsername: String? = ownerDisplayName,
    val chatName: String? = null,
    val minRankCanKick: Int = 0,
    val players: Array<FriendsChatPlayer>? = null,
) : ServerProt {
    @Serializable
    data class FriendsChatPlayer(
        val displayName: String,
        val username: String = displayName,
        val worldId: Int = 0,
        val rank: Int = 0,
        val worldName: String = "",
    )

    init {
        if (!clear) {
            require(ownerDisplayName != null) { "ownerDisplayName is required unless clear=true" }
            require(chatName != null) { "chatName is required unless clear=true" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FriendsChatChannel
        if (clear != other.clear) return false
        if (ownerDisplayName != other.ownerDisplayName) return false
        if (ownerUsername != other.ownerUsername) return false
        if (chatName != other.chatName) return false
        if (minRankCanKick != other.minRankCanKick) return false
        return players.contentEquals(other.players)
    }

    override fun hashCode(): Int {
        var result = clear.hashCode()
        result = 31 * result + (ownerDisplayName?.hashCode() ?: 0)
        result = 31 * result + (ownerUsername?.hashCode() ?: 0)
        result = 31 * result + (chatName?.hashCode() ?: 0)
        result = 31 * result + minRankCanKick
        result = 31 * result + (players?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * CLANCHANNEL_FULL -- full clan channel state.
 * [main]=true for the player's own clan, false for a guest clan channel.
 */
@Serializable
data class ClanChannelFull(
    val main: Boolean,
    val clanName: String? = null,
    val clanHash: Long = 0,
    val updateNum: Long = 0,
    val kickRank: Int = -1,
    val talkRank: Int = -1,
    val chatters: Array<ClanChannelChatter>? = null,
) : ServerProt {
    @Serializable
    data class ClanChannelChatter(
        val displayName: String,
        val rank: Int,
        val worldId: Int = 0,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanChannelFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (clanHash != other.clanHash) return false
        if (updateNum != other.updateNum) return false
        if (kickRank != other.kickRank) return false
        if (talkRank != other.talkRank) return false
        return chatters.contentEquals(other.chatters)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + clanHash.hashCode()
        result = 31 * result + updateNum.hashCode()
        result = 31 * result + kickRank
        result = 31 * result + talkRank
        result = 31 * result + (chatters?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * CLANSETTINGS_FULL -- full clan settings state.
 * [main]=true for the player's own clan, false for a guest clan.
 */
@Serializable
data class ClanSettingsFull(
    val main: Boolean,
    val clanName: String? = null,
    val updateCount: Int = 0,
    val allowGuests: Boolean = false,
    val talkRank: Int = -1,
    val kickRank: Int = -1,
    val members: Array<ClanSettingsMember>? = null,
    val bannedUsers: Array<String>? = null,
    val settings: Array<ClanVarSetting>? = null,
) : ServerProt {
    @Serializable
    data class ClanSettingsMember(
        val displayName: String,
        val rank: Int,
    )

    @Serializable
    data class ClanVarSetting(
        val key: Int,
        val intValue: Int? = null,
        val longValue: Long? = null,
        val stringValue: String? = null,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClanSettingsFull
        if (main != other.main) return false
        if (clanName != other.clanName) return false
        if (updateCount != other.updateCount) return false
        if (allowGuests != other.allowGuests) return false
        if (talkRank != other.talkRank) return false
        if (kickRank != other.kickRank) return false
        if (!members.contentEquals(other.members)) return false
        if (!bannedUsers.contentEquals(other.bannedUsers)) return false
        return settings.contentEquals(other.settings)
    }

    override fun hashCode(): Int {
        var result = main.hashCode()
        result = 31 * result + (clanName?.hashCode() ?: 0)
        result = 31 * result + updateCount
        result = 31 * result + allowGuests.hashCode()
        result = 31 * result + talkRank
        result = 31 * result + kickRank
        result = 31 * result + (members?.contentHashCode() ?: 0)
        result = 31 * result + (bannedUsers?.contentHashCode() ?: 0)
        result = 31 * result + (settings?.contentHashCode() ?: 0)
        return result
    }
}

/** MESSAGE_CLANCHANNEL -- a message in a clan channel. */
@Serializable
data class MessageClanChannel(
    val guest: Boolean,
    val crown: Int,
    val displayName: String,
    val message: String,
) : ServerProt

/** MESSAGE_QUICKCHAT_PRIVATE -- incoming quick chat private message. */
@Serializable
data class MessageQuickChatPrivate(
    val crown: Int,
    val displayName: String,
    val quickResponseName: String = displayName,
    val qcId: Int,
    val qcData: ByteArray? = null,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageQuickChatPrivate
        if (crown != other.crown) return false
        if (displayName != other.displayName) return false
        if (quickResponseName != other.quickResponseName) return false
        if (qcId != other.qcId) return false
        return qcData.contentEquals(other.qcData)
    }

    override fun hashCode(): Int {
        var result = crown
        result = 31 * result + displayName.hashCode()
        result = 31 * result + quickResponseName.hashCode()
        result = 31 * result + qcId
        result = 31 * result + (qcData?.contentHashCode() ?: 0)
        return result
    }
}

/** MESSAGE_QUICKCHAT_PRIVATE_ECHO -- echo of a quick chat PM we sent. */
@Serializable
data class MessageQuickChatPrivateEcho(
    val senderDisplayName: String,
    val qcId: Int,
    val qcData: ByteArray? = null,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageQuickChatPrivateEcho
        if (senderDisplayName != other.senderDisplayName) return false
        if (qcId != other.qcId) return false
        return qcData.contentEquals(other.qcData)
    }

    override fun hashCode(): Int {
        var result = senderDisplayName.hashCode()
        result = 31 * result + qcId
        result = 31 * result + (qcData?.contentHashCode() ?: 0)
        return result
    }
}

/** MESSAGE_QUICKCHAT_FRIENDSCHAT -- quick chat in a friends chat channel. */
@Serializable
data class MessageQuickChatFriendsChat(
    val chatName: String,
    val crown: Int,
    val displayName: String,
    val quickResponseName: String = displayName,
    val qcId: Int,
    val qcData: ByteArray? = null,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageQuickChatFriendsChat
        if (chatName != other.chatName) return false
        if (crown != other.crown) return false
        if (displayName != other.displayName) return false
        if (quickResponseName != other.quickResponseName) return false
        if (qcId != other.qcId) return false
        return qcData.contentEquals(other.qcData)
    }

    override fun hashCode(): Int {
        var result = chatName.hashCode()
        result = 31 * result + crown
        result = 31 * result + displayName.hashCode()
        result = 31 * result + quickResponseName.hashCode()
        result = 31 * result + qcId
        result = 31 * result + (qcData?.contentHashCode() ?: 0)
        return result
    }
}

/** MESSAGE_QUICKCHAT_CLANCHANNEL -- quick chat in a clan channel. */
@Serializable
data class MessageQuickChatClanChannel(
    val guest: Boolean,
    val crown: Int,
    val displayName: String,
    val qcId: Int,
    val qcData: ByteArray? = null,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageQuickChatClanChannel
        if (guest != other.guest) return false
        if (crown != other.crown) return false
        if (displayName != other.displayName) return false
        if (qcId != other.qcId) return false
        return qcData.contentEquals(other.qcData)
    }

    override fun hashCode(): Int {
        var result = guest.hashCode()
        result = 31 * result + crown
        result = 31 * result + displayName.hashCode()
        result = 31 * result + qcId
        result = 31 * result + (qcData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Invokes a CS2 script on the client. [types] is a char string ('i'=int, 's'=string, 'l'=long);
 * args are encoded in reversed type order because the client reads them reversed.
 */
data class RunClientScript(val scriptId: Int, val types: String, val args: Array<Any>) : ServerProt {
    companion object {
        fun of(scriptId: Int, vararg args: Any): RunClientScript {
            val types = StringBuilder()
            for (arg in args) {
                when (arg) {
                    is Int -> types.append('i')
                    is String -> types.append('s')
                    is Long -> types.append('l')
                    else -> error("Unsupported arg type: ${arg::class}")
                }
            }
            return RunClientScript(scriptId, types.toString(), arrayOf(*args))
        }

        fun componentHash(interfaceId: Int, componentId: Int) = (interfaceId shl 16) or componentId
    }
}

/** Must be sent without ISAAC, as the cipher is not yet active at this point in the handshake. */
data class WorldLoginDetails(
    val rights: Int,
    val modLevel: Int,
    val quickChat: Boolean,
    val verifiedEmail: Boolean,
    val aBool7322: Boolean,
    val quickChatOnly: Boolean,
    val playerIndex: Int,
    val members: Boolean,
    val dob: Int,
    val memberWorld: Boolean,
    val worldName: String,
) : ServerProt

data class HashedWorldToken(val token: String) : ServerProt

/**
 * Populates only the lobby login slot in WorldSwitcher; does NOT trigger a world transfer.
 * Use [SwitchWorld] for lobby to world transfers.
 */
data class SetWorldTarget(
    val hostname: String,
    val worldId: Int,
    val port1: Int,
    val port2: Int = port1,
) : ServerProt

/**
 * Triggers the lobby to world transfer: the client opens a TCP connection to hostname:port1
 * for world login.
 */
data class SwitchWorld(
    val hostname: String,
    val worldId: Int,
    val port1: Int,
    val port2: Int = port1,
    val pendingFlag: Int = 0,
) : ServerProt

data class JcoinsUpdate(val balance: Int) : ServerProt

data class RebuildNormalSimple(
    val playerCoordX: Int,
    val playerCoordY: Int,
    val npcInfoCoordBitWidth: Int,
    val worldAreaTypeId: Int,
    val worldSouthWest: Tile,
    val worldNorthEast: Tile,
    val gpiInit: ByteArray? = null,
) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is RebuildNormalSimple &&
            playerCoordX == other.playerCoordX && playerCoordY == other.playerCoordY &&
            npcInfoCoordBitWidth == other.npcInfoCoordBitWidth && worldAreaTypeId == other.worldAreaTypeId &&
            worldSouthWest == other.worldSouthWest && worldNorthEast == other.worldNorthEast &&
            (gpiInit?.contentEquals(other.gpiInit) ?: (other.gpiInit == null)))

    override fun hashCode(): Int {
        var result = playerCoordX
        result = 31 * result + playerCoordY
        result = 31 * result + npcInfoCoordBitWidth
        result = 31 * result + worldAreaTypeId
        result = 31 * result + worldSouthWest.id
        result = 31 * result + worldNorthEast.id
        result = 31 * result + (gpiInit?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Instance / dynamic-region scene: builds [widthZones]x[heightZones] virtual zones (per plane) as
 * rotated copies of source zones. [packedZones] is indexed `((plane*widthZones)+zoneX)*heightZones+zoneY`
 * and holds either [VOID] (empty virtual zone) or a packed source descriptor (see [pack]).
 */
data class RebuildRegion(
    val type: Int,
    val npcInfoCoordBitWidth: Int,
    val centerZoneX: Int,
    val centerZoneY: Int,
    val baseZoneX: Int,
    val baseZoneY: Int,
    val widthZones: Int,
    val heightZones: Int,
    val packedZones: IntArray,
) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is RebuildRegion &&
            type == other.type && npcInfoCoordBitWidth == other.npcInfoCoordBitWidth &&
            centerZoneX == other.centerZoneX && centerZoneY == other.centerZoneY &&
            baseZoneX == other.baseZoneX && baseZoneY == other.baseZoneY &&
            widthZones == other.widthZones && heightZones == other.heightZones &&
            packedZones.contentEquals(other.packedZones))

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + centerZoneX
        result = 31 * result + centerZoneY
        result = 31 * result + baseZoneX
        result = 31 * result + baseZoneY
        result = 31 * result + widthZones
        result = 31 * result + heightZones
        result = 31 * result + packedZones.contentHashCode()
        return result
    }

    companion object {
        const val VOID = -1

        fun pack(
            sourceMapSquareX: Int,
            sourceMapSquareY: Int,
            sourceLocalZoneX: Int,
            sourceLocalZoneY: Int,
            level: Int,
            rotation: Int,
        ): Int {
            val sourceZoneX = (sourceMapSquareX shl 3) or sourceLocalZoneX
            val sourceZoneY = (sourceMapSquareY shl 3) or sourceLocalZoneY
            return (level shl 24) or (sourceZoneX shl 14) or (sourceZoneY shl 3) or (rotation shl 1)
        }
    }
}

data class UpdateZoneFullFollows(val level: Int, val zoneX: Int, val zoneY: Int) : ServerProt

data class UpdateZonePartialFollows(val level: Int, val zoneX: Int, val zoneY: Int) : ServerProt

/** Zone header plus an embedded list of sub-packets. */
data class UpdateZonePartialEnclosed(
    val level: Int,
    val zoneX: Int,
    val zoneY: Int,
    val subPackets: List<ServerProt>,
) : ServerProt

data class LocAdd(
    val packedCoord: Int,
    val locId: Int,
    val shapeFlags: Int,
) : ServerProt

data class LocDel(val shapeFlags: Int, val packedCoord: Int) : ServerProt

data class LocCustomise(val payload: ByteArray) : ServerProt {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is LocCustomise && payload.contentEquals(other.payload))
    override fun hashCode(): Int = payload.contentHashCode()
}

data class LocAnim(
    val packedCoord: Int,
    val shapeRotation: Int,
    val seqId: Int,
) : ServerProt

data class SoundArea(
    val packedCoord: Int,
    val soundId: Int,
    val rotationDirection: Int,
    val loopCount: Int,
    val heightOffset: Int,
    val scale: Int,
) : ServerProt

data class LocPrefetch(val entityServerIndex: Int, val packedCoordAndShape: Int) : ServerProt

data class ObjAdd(
    val packedCoord: Int,
    val count: Int,
    val objId: Int,
) : ServerProt

data class ObjAddBig(
    val packedCoord: Int,
    val count: Int,
    val objId: Int,
) : ServerProt

data class ObjDel(val packedCoord: Int, val objIdLo: Int, val objIdHi: Int) : ServerProt

data class ObjReveal(
    val playerIndex: Int,
    val objIdLo: Int,
    val objIdHi: Int,
    val packedCoord: Int,
    val count: Int,
) : ServerProt

data class ObjCount(
    val packedCoord: Int,
    val objId: Int,
    val oldCount: Int,
    val newCount: Int,
) : ServerProt

data class MapAnim(
    val packedCoord: Int,
    val graphicId: Int,
    val rotationDirection: Int,
    val loopCount: Int,
    val heightOffset: Int,
    val scale: Int,
    val selector: Int,
) : ServerProt

data class MapAnimAlt(
    val packedCoord: Int,
    val graphicId: Int,
    val heightRotation: Int,
    val flags: Int,
    val heightOffset: Int,
    val fineOffset: Int,
) : ServerProt

/**
 * Tile-to-tile projectile. [lockOnId] makes the graphic track a live entity instead of flying at a
 * fixed destination; 0 leaves it aimed at `src + delta`, which is all this packet can do without one.
 * The source entity is not on the wire — the client supplies its own default — so a projectile that
 * must also track its firer needs [MapProjAnimHalfsq].
 */
data class MapProjAnim(
    val srcPackedCoord: Int,
    val targetDeltaX: Int,
    val targetDeltaY: Int,
    val lockOnId: Int,
    val spotAnim: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val lockOnSlot: Int = 0,
    val alpha: Int = 0xFF,
) : ServerProt

/**
 * Tile-to-tile projectile with the wider capacity: heights become signed 16-bit and both endpoints
 * can carry a sub-tile [FineOffset]. Standalone only — unlike the other three this one has no zone
 * sub-opcode in 949, so it cannot ride an UPDATE_ZONE_PARTIAL_ENCLOSED batch and must be sent to
 * each session directly.
 */
data class MapProjAnimAlt(
    val srcPackedCoord: Int,
    val targetDeltaX: Int,
    val targetDeltaY: Int,
    val lockOnId: Int,
    val spotAnim: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val sourceOffset: Int,
    val destOffset: Int,
    val lockOnSlot: Int = 0,
    val alpha: Int = 0xFF,
) : ServerProt

/**
 * Projectile positioned to the half-square: coordinates and deltas are in half-tiles, letting the
 * graphic start and end between tile centres. Carries both entity ids, so it can track a moving
 * firer as well as a moving target. [flags] bit 1 leaves [startHeight] unscaled.
 */
data class MapProjAnimHalfsq(
    val srcPackedCoord: Int,
    val flags: Int,
    val destDeltaX: Int,
    val destDeltaY: Int,
    val sourceId: Int,
    val lockOnId: Int,
    val spotAnim: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val lockOnSlot: Int = 0,
    val alpha: Int = 0xFF,
) : ServerProt

/**
 * The widest projectile packet, and the one live RS3 actually sends for spell projectiles: half-tile
 * positioning, signed 16-bit heights, both entity ids, and a sub-tile [FineOffset] at each end.
 * Rides the zone batch as sub-opcode 16.
 */
data class MapProjAnimHalfsqAlt(
    val srcPackedCoord: Int,
    val flags: Int,
    val destDeltaX: Int,
    val destDeltaY: Int,
    val sourceId: Int,
    val lockOnId: Int,
    val spotAnim: Int,
    val startHeight: Int,
    val endHeight: Int,
    val startTime: Int,
    val endTime: Int,
    val sourceOffset: Int,
    val destOffset: Int,
    val lockOnSlot: Int = 0,
    val alpha: Int = 0xFF,
) : ServerProt {
    companion object {
        /** Bit 1 stops the client scaling [startHeight], letting it address quarter units directly. */
        const val FLAG_UNSCALED_START_HEIGHT = 0x2
    }
}

data class TextCoord(
    val packedCoord: Int,
    val soundId: Int,
    val volume: Int,
    val paramA: Int,
    val paramB: Int,
) : ServerProt

data class PlayerInfo(
    val bitBlock: ByteArray,
    val extendedInfo: List<ByteArray>,
    val firstTick: Boolean,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerInfo) return false
        if (firstTick != other.firstTick) return false
        if (!bitBlock.contentEquals(other.bitBlock)) return false
        if (extendedInfo.size != other.extendedInfo.size) return false
        return extendedInfo.indices.all { extendedInfo[it].contentEquals(other.extendedInfo[it]) }
    }
    override fun hashCode(): Int {
        var result = bitBlock.contentHashCode()
        result = 31 * result + extendedInfo.fold(0) { acc, b -> 31 * acc + b.contentHashCode() }
        result = 31 * result + firstTick.hashCode()
        return result
    }
}

data class NpcInfo(
    val bitBlock: ByteArray,
    val extendedInfo: List<ByteArray>,
) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NpcInfo) return false
        if (!bitBlock.contentEquals(other.bitBlock)) return false
        if (extendedInfo.size != other.extendedInfo.size) return false
        return extendedInfo.indices.all { extendedInfo[it].contentEquals(other.extendedInfo[it]) }
    }
    override fun hashCode(): Int {
        var result = bitBlock.contentHashCode()
        result = 31 * result + extendedInfo.fold(0) { acc, b -> 31 * acc + b.contentHashCode() }
        return result
    }
}

data class WorldListPacket(
    val worldList: WorldList,
    val fullRefresh: Boolean,         // true = send full world defs, false = delta (counts only)
) : ServerProt

data class UpdateInvFull(val containerKey: Int, val slots: List<InvSlot>, val keyFlag: Int = 0) : ServerProt

data class UpdateInvPartial(val containerKey: Int, val slots: List<InvSlot>, val keyFlag: Int = 0) : ServerProt

data class UpdateInvStopTransmit(val containerKey: Int, val keyFlag: Int = 0) : ServerProt

/** No payload: drops the camera back to its default follow position immediately. */
class CamReset : ServerProt

/** No payload: eases the camera back to its default follow position. */
class CamSmoothreset : ServerProt

/** No payload: clears every running entity animation. */
class ResetAnims : ServerProt

/** No payload: makes the client re-open its JS5 connection. */
class Js5Reload : ServerProt

/** Selects the minimap state the client should show. */
data class MinimapToggle(val state: Int) : ServerProt

/** Ends the session; [reason] is the code the client's login state machine reports. */
data class Logout(val reason: Int) : ServerProt

/** Ends the session and returns the client to the login screen rather than the lobby. */
data class LogoutFull(val reason: Int) : ServerProt

/** Sets the render order the client applies to entities. */
data class Setdraworder(val order: Int) : ServerProt

/** The carried weight shown on the run-energy orb, in whole kilograms. */
data class UpdateRunweight(val kilograms: Int) : ServerProt

/** Ticks until the world restarts, or 0 to clear the countdown. */
data class UpdateRebootTimer(val ticks: Int) : ServerProt

/** Removes roofs around [packedCoord]; -1 clears the override and restores normal roof drawing. */
data class CamRemoveroof(val packedCoord: Int) : ServerProt

/** Last-login detail; the client stores the word verbatim for its login banner. */
data class LastLoginInfo(val value: Int) : ServerProt
