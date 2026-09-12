package com.projectx.game.chat

enum class CrownType {
    NONE,
    MODERATOR,
    ADMIN,
    UNKNOWN_3,
    UNKNOWN_4,
    UNKNOWN_5,
    UNKNOWN_6,
    UNKNOWN_7,
    UNKNOWN_8,
    UNKNOWN_9,
    UNKNOWN_10,
    UNKNOWN_11,
    UNKNOWN_12,
    UNKNOWN_13,
    UNKNOWN_14,
    UNKNOWN_15,
    UNKNOWN_16,
    UNKNOWN_17,
    UNKNOWN_18,
    UNKNOWN_19,
    UNKNOWN_20,
    UNKNOWN_21,
    UNKNOWN_22, ;

    companion object {
        fun forId(typeId: Int) = entries.getOrNull(typeId) ?: NONE
    }
}