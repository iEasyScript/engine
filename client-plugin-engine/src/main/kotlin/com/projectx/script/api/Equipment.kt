package com.projectx.script.api

import com.projectx.game.items.Item
import com.projectx.script.api.Equipment.Slot.Companion.fromIndex
import com.projectx.script.api.Equipment.Slot.Companion.getItem

class Equipment {

    enum class Slot(val index: Int) {
        HEAD(0),
        CAPE(1),
        NECK(2),
        WEAPON(3),
        BODY(4),
        SHIELD(5),
        LEGS(7),
        HANDS(9),
        FEET(10),
        RING(12),
        AMMUNITION(13),
        POCKET(17);

        companion object {
            fun useItemInSlot(slot: Slot, option: String): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(option) == true
            }

            fun useItemInSlot(slot: Slot): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(0) == true
            }

            fun useItemInSlot(slot: Slot, option: Int): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(option) == true
            }

            @Deprecated("Use getItem(slot) which returns Item?", ReplaceWith("getItem(slot)"))
            fun getItemInSlot(slot: Slot): Item {
                return equipment.first { it.slot.slotId == slot.index }
            }

            fun getItem(slot: Slot): Item? {
                return equipment.firstOrNull { it.slot.slotId == slot.index }
            }

            fun hasItem(slot: Slot): Boolean = getItem(slot) != null

            fun isEquipped(id: Int): Boolean = equipment.any { it.id == id }

            fun isEquipped(name: String): Boolean =
                equipment.any { it.name.equals(name, ignoreCase = true) }

            fun getEquippedItems(): Map<Slot, Item> =
                equipment.mapNotNull { item ->
                    fromIndex(item.slot.slotId)?.let { s -> s to item }
                }.toMap()

            fun fromIndex(index: Int): Slot? = entries.firstOrNull { it.index == index }

            @Deprecated("Use fromIndex(var0)", ReplaceWith("fromIndex(var0)"))
            fun resolve(var0: Int): Slot? = fromIndex(var0)
        }
    }
}

fun equipmentClick(slot: Equipment.Slot, option: String): Boolean =
    Equipment.Slot.useItemInSlot(slot, option)

fun equipmentClick(slot: Equipment.Slot, option: Int = 0): Boolean =
    Equipment.Slot.useItemInSlot(slot, option)

fun equipmentItem(slot: Equipment.Slot): Item? =
    Equipment.Slot.getItem(slot)

fun isEquipped(slot: Equipment.Slot, vararg ids: Int): Boolean =
    Equipment.Slot.getItem(slot)?.let { ids.contains(it.id) } == true