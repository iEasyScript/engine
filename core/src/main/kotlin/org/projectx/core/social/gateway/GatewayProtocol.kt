package org.projectx.core.social.gateway

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.projectx.core.EnvVars
import org.projectx.core.model.Account
import org.projectx.core.net.prot.*
import org.projectx.core.worldlist.Country
import org.projectx.core.worldlist.World

/**
 * SocialGateway WebSocket wire protocol.
 *
 * Defines the JSON-serialized message types exchanged between world servers and the
 * lobby server over the social gateway WebSocket. User-originated communications are
 * forwarded in decoded packet format (ClientProt/ServerProt data classes).
 *
 * The polymorphic serializer module registers all ClientProt and ServerProt subclasses
 * that can be transported over the wire. Expand the registrations as new packet types
 * are added to the social system.
 */
object SocialGatewayWireJson {
    val serializersModule: SerializersModule = SerializersModule {
        polymorphic(ClientProt::class) {
            subclass(FriendListAdd::class, FriendListAdd.serializer())
            subclass(FriendListDel::class, FriendListDel.serializer())
            subclass(IgnoreListAdd::class, IgnoreListAdd.serializer())
            subclass(MessagePrivateSend::class, MessagePrivateSend.serializer())
            subclass(ClanChannelKickUser::class, ClanChannelKickUser.serializer())
            subclass(ChatSetFilter::class, ChatSetFilter.serializer())
        }
        polymorphic(ServerProt::class) {
            subclass(FriendStatus::class, FriendStatus.serializer())
            subclass(GameMessage::class, GameMessage.serializer())
            subclass(MessagePrivate::class, MessagePrivate.serializer())
            subclass(MessagePrivateEcho::class, MessagePrivateEcho.serializer())
            subclass(MessageFriendsChat::class, MessageFriendsChat.serializer())
            subclass(FriendsChatChannel::class, FriendsChatChannel.serializer())
            subclass(ClanChannelFull::class, ClanChannelFull.serializer())
            subclass(ClanSettingsFull::class, ClanSettingsFull.serializer())
            subclass(MessageClanChannel::class, MessageClanChannel.serializer())
            subclass(ChatFilterSettingsPrivateChat::class, ChatFilterSettingsPrivateChat.serializer())
            subclass(UpdateIgnoreList::class, UpdateIgnoreList.serializer())
            subclass(FriendlistLoaded::class, FriendlistLoaded.serializer())
            subclass(VarclanEnable::class, VarclanEnable.serializer())
            subclass(VarclanDisable::class, VarclanDisable.serializer())
        }
    }

    val json: Json = Json {
        prettyPrint = false
        coerceInputValues = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        // IMPORTANT: cannot use "type" because some decoded packets contain a "type" field
        // (e.g. chat-related ClientProt), which collides with kotlinx-serialization
        // polymorphism discriminator.
        classDiscriminator = "_type"
        serializersModule = SocialGatewayWireJson.serializersModule
    }
}

@Serializable
sealed interface SocialGatewayWireMessage

@Serializable
enum class AnnouncementChannel {
    GAME,
    FRIENDS_CHAT,
}

@Serializable
sealed interface AnnouncementTarget {
    @Serializable
    @SerialName("AllWorlds")
    data object AllWorlds : AnnouncementTarget

    @Serializable
    @SerialName("World")
    data class World(val worldId: Int) : AnnouncementTarget

    @Serializable
    @SerialName("User")
    data class User(val username: String) : AnnouncementTarget

    @Serializable
    @SerialName("FriendsChat")
    data class FriendsChat(val ownerUsername: String) : AnnouncementTarget
}

@Serializable
@SerialName("AnnouncementBroadcast")
data class AnnouncementBroadcast(
    val channel: AnnouncementChannel,
    val message: String,
    val target: AnnouncementTarget = AnnouncementTarget.AllWorlds,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldInfo")
data class GatewayWorldInfo(
    val worldId: Int,
    val worldName: String,
    val host: String,
    val publicHost: String,
    val port: Int,
    val activity: String = "",
    val members: Boolean = true,
    val quickChat: Boolean = false,
    val pvp: Boolean = false,
    val lootShare: Boolean = true,
    val country: String = "USA",
    val highlighted: Boolean = false,
) {
    /** Convert to the worldlist [World] data class used by the lobby. */
    fun toWorld(): World = World(
        number = worldId,
        hostname = publicHost,
        port = port,
        activity = activity.ifEmpty { worldName },
        country = Country.valueOf(country),
        quickchat = quickChat,
        lootShare = lootShare,
        members = members,
        pvp = pvp,
        highlighted = highlighted,
    )

    companion object {
        /** Build from environment variables (used by the world server). */
        fun fromEnv(): GatewayWorldInfo = GatewayWorldInfo(
            worldId = EnvVars.worldId,
            worldName = EnvVars.worldName,
            host = EnvVars.worldHost,
            publicHost = EnvVars.worldPublicHost,
            port = EnvVars.worldPort,
            activity = EnvVars.worldActivity,
            members = EnvVars.worldMembers,
            quickChat = EnvVars.worldQuickChat,
            pvp = EnvVars.worldPvp,
            lootShare = EnvVars.worldLootShare,
            country = EnvVars.worldCountry,
            highlighted = EnvVars.worldHighlighted,
        )
    }
}

@Serializable
@SerialName("WorldHello")
data class WorldHello(
    val world: GatewayWorldInfo,
    val token: String,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldHelloAck")
data class WorldHelloAck(
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

/**
 * Pushed by the lobby to connected world(s) at login-success. Carries the lobby-issued
 * authorization [token] (verified statelessly by the world) plus the [account] snapshot the world
 * needs at reconnect — so the world resolves a world login without any account-store read. See
 * [org.projectx.core.net.login.WorldLoginTokens].
 */
@Serializable
@SerialName("WorldLoginGrant")
data class WorldLoginGrant(
    val account: Account,
    val sessionId1: Long,
    val sessionId2: Long,
    val token: String,
    val issuedAt: Long,
    val expiresAt: Long,
) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOnline")
data class PlayerOnline(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOffline")
data class PlayerOffline(
    val username: String,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClientPacket")
data class ClientPacketEnvelope(
    val username: String,
    @Polymorphic val packet: ClientProt,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ServerPacket")
data class ServerPacketEnvelope(
    val username: String,
    @Polymorphic val packet: ServerProt,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ServerPacketBatch")
data class ServerPacketBatch(
    val usernames: List<String>,
    @Polymorphic val packet: ServerProt,
) : SocialGatewayWireMessage

@Serializable
@SerialName("FcSettingsUpdate")
data class FcSettingsUpdate(
    val username: String,
    val name: String?,
    val rankToEnter: Int,
    val rankToSpeak: Int,
    val rankToKick: Int,
    val rankToLS: Int,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanCreateRequest")
data class ClanCreateRequest(val username: String, val clanName: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanCreateResponse")
data class ClanCreateResponse(val username: String, val success: Boolean, val message: String?) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanLeaveRequest")
data class ClanLeaveRequest(val username: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanAddMemberRequest")
data class ClanAddMemberRequest(val inviterUsername: String, val targetUsername: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanKickMemberRequest")
data class ClanKickMemberRequest(val kickerUsername: String, val targetUsername: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanSetRankRequest")
data class ClanSetRankRequest(val username: String, val targetUsername: String, val rank: Int) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanBanMemberRequest")
data class ClanBanMemberRequest(val username: String, val targetUsername: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanUnbanMemberRequest")
data class ClanUnbanMemberRequest(val username: String, val targetUsername: String) : SocialGatewayWireMessage

/**
 * Published by a world content plugin when it changes clan settings/vars/motif. The lobby is the
 * authority: it persists the changes and rebroadcasts the resulting CLANSETTINGS_FULL to members.
 */
@Serializable
@SerialName("ClanSettingsUpdate")
data class ClanSettingsUpdate(
    val username: String,
    val settings: List<ClanSettingsFull.ClanVarSetting>,
) : SocialGatewayWireMessage
