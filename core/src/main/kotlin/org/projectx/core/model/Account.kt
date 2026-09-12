package org.projectx.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.projectx.core.net.MachineInformation
import org.projectx.core.net.MachineLoginRecord
import org.projectx.core.net.recordMachineLogin

/**
 * Player account stored in MongoDB `accounts` collection.
 * Schema based on ~/projectx/server reference (logic only, not protocol).
 */
@Serializable
data class Account(
    val username: String,              // protocol-formatted (lowercase, no spaces)
    var email: String = "",
    var displayName: String = "",
    var prevDisplayName: String = "",
    var passwordHash: String = "",     // Argon2
    var rights: Rights = Rights.PLAYER,
    var banned: Long = 0,              // timestamp when ban expires (0 = not banned)
    var muted: Long = 0,               // timestamp when mute expires (0 = not muted)
    var lastIp: String? = null,
    /** True once the player has completed character creation (gamemode + appearance chosen). */
    var characterCreated: Boolean = false,
    var social: Social = Social(),
    /** Bounded history of the machine/device descriptors seen at each lobby login (oldest→newest). */
    var machineHistory: MutableList<MachineLoginRecord> = mutableListOf(),
) {
    /** The machine descriptor of the current session; set at login, not persisted. */
    @Transient
    var currentMachine: MachineInformation? = null

    /** Records this login's [machine]/[ip] into [machineHistory] and sets it as the current session's. */
    fun recordMachine(machine: MachineInformation, ip: String, at: Long = System.currentTimeMillis()) {
        currentMachine = machine
        machineHistory.recordMachineLogin(machine, ip, at)
    }

    fun isBanned() = banned > 0 && System.currentTimeMillis() < banned
    fun isMuted() = muted > 0 && System.currentTimeMillis() < muted

    /** Whether [other] can see this account's online status. */
    fun onlineTo(other: Account): Boolean {
        if (social.status == 2) return false                              // hidden from all
        if (social.status == 1 && other.username !in social.friends) return false // friends only
        return true
    }
}

@Serializable
data class Social(
    var friends: MutableMap<String, FriendData> = mutableMapOf(), // protocol username → per-friend data
    var ignores: MutableSet<String> = mutableSetOf(),              // protocol usernames
    var status: Int = 0,                                           // 0=all, 1=friends only, 2=off
    var currentFriendsChat: String? = null,                        // FC owner username currently joined
    var clanName: String? = null,                                  // clan membership
    var friendsChat: FriendsChat = FriendsChat(),                  // owned FC settings
)

@Serializable
data class FriendData(
    var notes: String = "",            // player-set notes for this friend
    var addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class FriendsChat(
    var name: String? = null,                                    // channel name (null = disabled)
    var ranks: MutableMap<String, Int> = mutableMapOf(),          // username → rank
    var rankToEnter: Int = -1,                                   // -1=anyone
    var rankToSpeak: Int = -1,
    var rankToKick: Int = 7,                                     // 7=owner only
    var rankToLS: Int = -1,
    var coinshare: Boolean = false,
)
