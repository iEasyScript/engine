package com.projectx.game.nxt.inventories
import com.projectx.game.memory.atLeast
import com.projectx.game.memory.eastl.OEastlHashTable
import com.projectx.game.nxt.extent

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.eastl.EastlHashTable
import com.projectx.game.nxt.OInventory
import com.projectx.game.nxt.OObjVarDomain
import com.projectx.game.nxt.types.Vector
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.VarBitType.Companion.BIT_MASKS
import java.lang.foreign.MemorySegment

class Inventory(raw: MemorySegment, val interfaceId: Int = -1, val componentId: Int = -1) : Iterable<Item> {
    val ptr: MemorySegment = raw.atLeast(OInventory.extent)
    val exists: Boolean
        get() = ptr.address() != 0L

    val id: Int
        get() = ptr.getInt(OInventory.INVENTORY_ID)

    val items: Vector
        get() = Vector(ptr.pointerAtOffset(OInventory.INVENTORY_ITEMS, 0x20L), 8)

    val objVarDomains: Vector
        get() = Vector(ptr.pointerAtOffset(OInventory.OBJ_VAR_DOMAINS, 0x20L), OInventory.OBJ_VAR_DOMAIN_STRIDE)

    fun slotAt(slot: Int) = IFSlot(interfaceId, componentId, slot)

    operator fun get(index: Int): Item? {
        if (!exists) return null
        if (index < 0 || index >= items.size)
            throw IndexOutOfBoundsException("Index: $index, Size: ${items.size}")

        val item = items[index]
        if (item.readInt() < 0) return null
        val domains = objVarDomains
        val domain = if (index < domains.size.toInt())
            ObjVarDomain(domains[index].pointerAtOffset(OInventory.OBJ_VAR_DOMAIN_FIELD, 0x20L)).takeIf { it.isValid }
        else null
        return Item(item.readInt(), item.readInt(4L), IFSlot(interfaceId, componentId, index), domain)
    }

    override fun iterator(): Iterator<Item> {
        if (!exists) return emptyList<Item>().iterator()
        return object : Iterator<Item> {
            private var currentIndex = 0

            private fun findNextValidIndex(): Int {
                var idx = currentIndex
                while (idx < items.size) {
                    val item = items[idx]
                    if (item.readInt() != -1)
                        return idx
                    idx++
                }
                return items.size.toInt()
            }

            override fun hasNext() = findNextValidIndex() < items.size

            override fun next(): Item {
                currentIndex = findNextValidIndex()
                if (currentIndex >= items.size)
                    throw NoSuchElementException()

                val item = items[currentIndex]
                val domains = objVarDomains
                val domain = if (currentIndex < domains.size.toInt())
                    ObjVarDomain(domains[currentIndex].pointerAtOffset(OInventory.OBJ_VAR_DOMAIN_FIELD, 0x20L)).takeIf { it.isValid }
                else null
                currentIndex++
                return Item(
                    item.readInt(),
                    item.readInt(4L),
                    IFSlot(interfaceId, componentId, currentIndex - 1),
                    domain
                )
            }
        }
    }

    fun getItem(vararg ids: Int) = firstOrNull { ids.contains(it.id) && it.amount > 0 }
    fun getItem(vararg names: String) = firstOrNull { names.contains(it.name) && it.amount > 0 }
    fun getItem(vararg regexes: Regex) =
        firstOrNull { item -> regexes.any { it.matches(item.name) } && item.amount > 0 }

    fun hasItem(vararg ids: Int) = any { ids.contains(it.id) && it.amount > 0 }
    fun hasItem(vararg names: String) = any { names.contains(it.name) && it.amount > 0 }
    fun hasItem(vararg regexes: Regex) = any { item -> regexes.any { it.matches(item.name) } && item.amount > 0 }

    fun hasItems(vararg ids: Int) = ids.all { id -> any { it.id == id && it.amount > 0 } }
    fun hasItems(vararg names: String) = names.all { name -> any { it.name == name && it.amount > 0 } }
    fun hasItems(vararg regexes: Regex) = regexes.all { regex -> any { regex.matches(it.name) && it.amount > 0 } }

    fun count(vararg ids: Int) = filter { ids.contains(it.id) }.sumOf { it.amount }
    fun count(vararg names: String) = filter { names.contains(it.name) }.sumOf { it.amount }
    fun count(vararg regexes: Regex) = filter { item -> regexes.any { it.matches(item.name) } }.sumOf { it.amount }

    fun clickItem(id: Int, option: String) = firstOrNull { it.id == id }?.click(option) == true
    fun clickItem(id: Int, option: Int) = firstOrNull { it.id == id }?.click(option) == true
    fun clickItem(name: String, option: String) = firstOrNull { it.name == name }?.click(option) == true
    fun clickItem(name: String, option: Int) = firstOrNull { it.name == name }?.click(option) == true
    fun clickItem(regex: Regex, option: String) = firstOrNull { regex.matches(it.name) }?.click(option) == true
    fun clickItem(regex: Regex, option: Int) = firstOrNull { regex.matches(it.name) }?.click(option) == true

    fun clickItems(name: String, option: String) = firstOrNull { it.name.contains(name) }?.click(option) == true

    fun findByNameContaining(part: String) = firstOrNull { it.amount > 0 && it.name.contains(part) }

    val usedSlots: Int
        get() = if (!exists) 0 else count { true }

    val freeSlots: Int
        get() = if (!exists) 0 else items.size.toInt() - count { true }

    val isFull
        get() = exists && items.size != 0L && freeSlots == 0

    val isEmpty
        get() = !exists || items.size == 0L || freeSlots == items.size.toInt()
}

class ObjVarDomain(raw: MemorySegment) : EastlHashTable(raw.atLeast(OObjVarDomain.extent).pointerAtOffset(0x0, OEastlHashTable.extent), OObjVarDomain.ELEMENT_SIZE) {
    val ptr: MemorySegment = raw.atLeast(OObjVarDomain.extent)
    /** The hash table's bucket-array pointer must be non-null before any bucket walk. */
    val isValid: Boolean
        get() = runCatching { bucketArray.address() != 0L }.getOrDefault(false)

    fun getVar(id: Int) = if (!isValid) 0 else (get(id)?.readInt() ?: 0)
    fun getVarBit(id: Int): Int {
        if (!isValid) return 0
        try {
            val type = Cache.varbit(id) ?: return 0
            return getVar(type.baseVar) shr type.startBit and BIT_MASKS[type.endBit - type.startBit]
        } catch (e: Throwable) {
            return 0
        }
    }
}