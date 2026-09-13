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
            @JvmStatic
            fun useItemInSlot(slot: Slot, option: String): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(option) == true
            }

            @JvmStatic
            fun useItemInSlot(slot: Slot): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(0) == true
            }

            @JvmStatic
            fun useItemInSlot(slot: Slot, option: Int): Boolean {
                return equipment.firstOrNull { it.slot.slotId == slot.index }?.click(option) == true
            }

            @Deprecated("Use getItem(slot) which returns Item?", ReplaceWith("getItem(slot)"))
            @JvmStatic
            fun getItemInSlot(slot: Slot): Item {
                return equipment.first { it.slot.slotId == slot.index }
            }

            @JvmStatic
            fun getItem(slot: Slot): Item? {
                return equipment.firstOrNull { it.slot.slotId == slot.index }
            }

            @JvmStatic
            fun hasItem(slot: Slot): Boolean = getItem(slot) != null

            @JvmStatic
            fun isEquipped(id: Int): Boolean = equipment.any { it.id == id }

            @JvmStatic
            fun isEquipped(name: String): Boolean =
                equipment.any { it.name.equals(name, ignoreCase = true) }

            @JvmStatic
            fun getEquippedItems(): Map<Slot, Item> =
                equipment.mapNotNull { item ->
                    fromIndex(item.slot.slotId)?.let { s -> s to item }
                }.toMap()

            @JvmStatic
            fun fromIndex(index: Int): Slot? = entries.firstOrNull { it.index == index }

            @Deprecated("Use fromIndex(var0)", ReplaceWith("fromIndex(var0)"))
            @JvmStatic
            fun resolve(var0: Int): Slot? = fromIndex(var0)
        }
    }
}

fun equipmentClick(slot: Equipment.Slot, option: String): Boolean =
    Equipment.Slot.useItemInSlot(slot, option)

@JvmOverloads
fun equipmentClick(slot: Equipment.Slot, option: Int = 0): Boolean =
    Equipment.Slot.useItemInSlot(slot, option)

fun equipmentItem(slot: Equipment.Slot): Item? =
    Equipment.Slot.getItem(slot)

fun isEquipped(slot: Equipment.Slot, vararg ids: Int): Boolean =
    Equipment.Slot.getItem(slot)?.let { ids.contains(it.id) } == true