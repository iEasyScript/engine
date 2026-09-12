package org.projectx.packetlog.upload

/**
 * What is stripped from a session before it leaves the machine.
 *
 * ⛔ This is **prot-level**, not field-level: a listed prot loses its whole body. Field-level
 * redaction needs the structured decode layer to address individual fields, and until that exists
 * the only honest choices per prot are "all of it" or "none of it". The policy is deliberately
 * shaped so it can tighten to field-level later without the categories changing.
 *
 * The local database is never redacted - it is the user's own capture and they need it intact to
 * debug. Redaction happens on export, so what is shared is a strictly narrower thing than what is
 * kept.
 */
class RedactionPolicy(
    val version: String,
    val droppedProts: Set<String>,
    val keptWithNames: Set<String>,
) {

    fun drops(protName: String): Boolean = protName in droppedProts

    companion object {

        /**
         * Conversation and social graph. Every one of these carries either message text or the
         * display names of people who never agreed to anything, and none of them is needed to
         * verify that a server renders content correctly.
         */
        private val CONVERSATION_AND_SOCIAL = setOf(
            "MESSAGE_PRIVATE",
            "MESSAGE_PRIVATE_ECHO",
            "MESSAGE_PRIVATE_ENCRYPTED",
            "MESSAGE_QUICKCHAT_PRIVATE",
            "MESSAGE_QUICKCHAT_PRIVATE_ECHO",
            "MESSAGE_PUBLIC",
            "MESSAGE_QUICKCHAT",
            "MESSAGE_FRIENDCHANNEL",
            "MESSAGE_QUICKCHAT_FRIENDCHAT",
            "MESSAGE_CLANCHANNEL",
            "MESSAGE_CLANCHANNEL_SYSTEM",
            "MESSAGE_PLAYER_GROUP",
            "MESSAGE_QUICKCHAT_PLAYER_GROUP",
            "UPDATE_FRIENDLIST",
            "FRIENDLIST_LOADED",
            "FRIENDLIST_ADD",
            "FRIENDLIST_DEL",
            "IGNORELIST_ADD",
            "UPDATE_FRIENDCHAT_CHANNEL_FULL",
            "UPDATE_FRIENDCHAT_CHANNEL_SINGLEUSER",
            "CLANCHANNEL_FULL",
            "CLANCHANNEL_DELTA",
            "CLANCHANNEL_KICKUSER",
            "CLANSETTINGS_FULL",
            "CLANSETTINGS_DELTA",
        )

        /**
         * Prots that survive export while still carrying third-party display names, because dropping
         * them would defeat the point of the corpus. Named explicitly so the exposure is auditable
         * rather than implicit, and so the list has an obvious home when field-level redaction
         * arrives and can strip just the names.
         *
         * `PLAYER_INFO` carries the appearance and name of every nearby player and is also the single
         * most important packet for reconstructing a scene. `MESSAGE_GAME` carries world broadcasts,
         * which name players but are already announced to everyone online.
         */
        private val KEPT_CARRYING_NAMES = setOf("PLAYER_INFO", "MESSAGE_GAME")

        val DEFAULT = RedactionPolicy(
            version = "prot-v1",
            droppedProts = CONVERSATION_AND_SOCIAL,
            keptWithNames = KEPT_CARRYING_NAMES,
        )

        /** Drops the name-carrying prots too, at the cost of most of a scene's fidelity. */
        val STRICT = RedactionPolicy(
            version = "prot-v1-strict",
            droppedProts = CONVERSATION_AND_SOCIAL + KEPT_CARRYING_NAMES,
            keptWithNames = emptySet(),
        )

        fun byName(name: String): RedactionPolicy? = when (name) {
            DEFAULT.version -> DEFAULT
            STRICT.version -> STRICT
            else -> null
        }
    }
}
