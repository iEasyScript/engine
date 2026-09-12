package org.projectx.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Serializer for clan setting/var values which can be Int, Long, or String.
 * Uses JSON primitives for polymorphic storage in MongoDB.
 */
object ClanSettingValueSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClanSettingValue") {
        element<JsonElement>("value")
    }

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported clan setting type: ${value::class}")
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive && element.longOrNull != null -> {
                val long = element.long
                if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
            }
            else -> throw IllegalArgumentException("Unsupported clan setting value: $element")
        }
    }
}

@Serializable
enum class ClanRank(val iconId: Int) {
    NONE(-1),
    RECRUIT(0),
    CORPORAL(1),
    SERGEANT(2),
    LIEUTENANT(3),
    CAPTAIN(4),
    GENERAL(5),
    ADMIN(100),
    ORGANIZER(101),
    COORDINATOR(102),
    OVERSEER(103),
    DEPUTY_OWNER(125),
    OWNER(126),
    JMOD(127);

    companion object {
        private val byIconId = entries.associateBy { it.iconId }
        fun fromIconId(id: Int): ClanRank = byIconId[id] ?: NONE
    }
}

@Serializable
enum class FriendsChatRank(val id: Int) {
    UNRANKED(-1),
    FRIEND(0),
    RECRUIT(1),
    CORPORAL(2),
    SERGEANT(3),
    LIEUTENANT(4),
    CAPTAIN(5),
    GENERAL(6),
    OWNER(7),
    JMOD(127);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): FriendsChatRank = byId[id] ?: UNRANKED
    }
}

@Serializable
data class ClanMemberData(
    var rank: ClanRank = ClanRank.NONE,
    val joinDate: Long = System.currentTimeMillis(),
    var job: Int = 0,
    var banFromKeep: Boolean = false,
    var banFromCitadel: Boolean = false,
    var banFromIsland: Boolean = false,
    var firstWeek: Boolean = false,
)

@Serializable
class Clan(
    val name: String = "",
    var leaderUsername: String = "",
    val members: MutableMap<String, ClanMemberData> = mutableMapOf(),
    val bannedUsers: MutableSet<String> = mutableSetOf(),
    val settings: MutableMap<String, @Serializable(with = ClanSettingValueSerializer::class) Any> = mutableMapOf(),
    val vars: MutableMap<String, @Serializable(with = ClanSettingValueSerializer::class) Any> = mutableMapOf(),
    var ccChatRank: ClanRank = ClanRank.NONE,
    var ccKickRank: ClanRank = ClanRank.ADMIN,
    var motto: String = "",
) {
    @Transient
    var updateBlock: ByteArray? = null

    companion object {
        const val MAX_MEMBERS = 500
    }

    fun getVar(id: Int): Any = vars.getOrDefault(id.toString(), 0)
    fun setVar(id: Int, value: Any) { vars[id.toString()] = value }

    fun getSetting(key: String, default: Any = 0): Any = settings.getOrDefault(key, default)
    fun setSetting(key: String, value: Any) { settings[key] = value }

    fun hasPermissions(username: String, minRank: ClanRank): Boolean {
        val data = members[username] ?: return false
        return data.rank.ordinal >= minRank.ordinal
    }

    fun getRank(username: String): ClanRank = members[username]?.rank ?: ClanRank.NONE
}
