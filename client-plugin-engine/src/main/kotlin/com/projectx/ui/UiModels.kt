package com.projectx.ui

import org.projectx.core.game.combat.EffectType

enum class InventoryType(val id: Int, val displayName: String) {
    BACKPACK(93, "Backpack"),
    EQUIPMENT(94, "Equipment"),
    AREA_LOOT(773, "Area Loot"),
    BANK(95, "Bank"),
    BEAST_OF_BURDEN(530, "Beast of Burden")
}

data class VarcEntry(
    val type: String,
    val id: Int,
    val prevValue: Int,
    val newValue: Int
)

data class InventoryEntry(
    val slot: Int,
    val itemId: Int,
    val name: String,
    val amount: Int
)

data class BuffDebuffEntry(
    val effect: EffectType,
    val name: String,
    val isActive: Boolean,
    val timeRemaining: Long,
    val stacks: Int,
    val isDebuff: Boolean
)
