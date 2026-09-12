package org.projectx.core.game

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = InventorySerializer::class)
open class Inventory(
    override val id: Int,
    override val capacity: Int,
    val alwaysStack: Boolean = false,
) : Container {
    val items: Array<Obj?> = arrayOfNulls(capacity)

    private var dirtyAll = true
    private val dirtySlots = HashSet<Int>()

    override operator fun get(slot: Int): Obj? = items.getOrNull(slot)

    override fun set(slot: Int, obj: Obj?) {
        if (slot < 0 || slot >= capacity) return
        items[slot] = if (obj != null && obj.amount <= 0) null else obj
        markDirty(slot)
    }

    override fun add(id: Int, amount: Int): Boolean = add(Obj(id, amount))

    override fun add(obj: Obj): Boolean {
        if (obj.amount <= 0) return false
        if (stacks(obj)) {
            val slot = slotOfStackable(obj.id)
            if (slot != -1) {
                val existing = items[slot]!!
                val total = existing.amount.toLong() + obj.amount
                if (total > Int.MAX_VALUE) return false
                items[slot] = existing.withAmount(total.toInt())
                markDirty(slot)
                return true
            }
            val free = firstFreeSlot()
            if (free == -1) return false
            items[free] = obj.copy()
            markDirty(free)
            return true
        }
        if (freeSlots() < obj.amount) return false
        var remaining = obj.amount
        var slot = 0
        while (remaining > 0 && slot < capacity) {
            if (items[slot] == null) {
                items[slot] = obj.copy(amount = 1)
                markDirty(slot)
                remaining--
            }
            slot++
        }
        return true
    }

    override fun remove(id: Int, amount: Int): Boolean {
        if (amount <= 0) return true
        if (count(id) < amount) return false
        var remaining = amount
        for (slot in 0 until capacity) {
            val item = items[slot] ?: continue
            if (item.id != id) continue
            if (item.amount <= remaining) {
                remaining -= item.amount
                items[slot] = null
            } else {
                items[slot] = item.withAmount(item.amount - remaining)
                remaining = 0
            }
            markDirty(slot)
            if (remaining == 0) break
        }
        return true
    }

    override fun removeAt(slot: Int, amount: Int): Obj? {
        val item = items.getOrNull(slot) ?: return null
        val taken = minOf(amount, item.amount)
        if (taken >= item.amount) {
            items[slot] = null
        } else {
            items[slot] = item.withAmount(item.amount - taken)
        }
        markDirty(slot)
        return item.withAmount(taken)
    }

    override fun count(id: Int): Int {
        var total = 0L
        for (item in items) if (item != null && item.id == id) total += item.amount
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun contains(id: Int, amount: Int): Boolean = count(id) >= amount

    override fun slotOf(id: Int): Int = items.indexOfFirst { it?.id == id }

    private fun slotOfStackable(id: Int): Int =
        items.indexOfFirst { it != null && it.id == id && !it.hasMetadata }

    override fun firstFreeSlot(): Int = items.indexOfFirst { it == null }

    override fun freeSlots(): Int = items.count { it == null }

    override fun isFull(): Boolean = firstFreeSlot() == -1

    override fun isEmpty(): Boolean = items.all { it == null }

    override fun clear() {
        for (slot in 0 until capacity) {
            if (items[slot] != null) {
                items[slot] = null
                markDirty(slot)
            }
        }
    }

    override fun swap(from: Int, to: Int) {
        if (from == to || from !in 0 until capacity || to !in 0 until capacity) return
        val tmp = items[from]
        items[from] = items[to]
        items[to] = tmp
        markDirty(from)
        markDirty(to)
    }

    override fun freeSlotsFor(obj: Obj): Boolean {
        if (stacks(obj)) return slotOfStackable(obj.id) != -1 || firstFreeSlot() != -1
        return freeSlots() >= obj.amount
    }

    private fun stacks(obj: Obj): Boolean = (alwaysStack || obj.stackable) && !obj.hasMetadata

    override fun takeDirty(): DirtyState {
        val state = when {
            dirtyAll -> DirtyState.Full
            dirtySlots.isEmpty() -> DirtyState.Clean
            else -> DirtyState.Slots(dirtySlots.toSet())
        }
        dirtyAll = false
        dirtySlots.clear()
        return state
    }

    fun markAllDirty() {
        dirtyAll = true
        dirtySlots.clear()
    }

    private fun markDirty(slot: Int) {
        if (!dirtyAll) dirtySlots.add(slot)
    }

    inline fun forEachIndexed(action: (Int, Obj) -> Unit) {
        for (slot in 0 until capacity) items[slot]?.let { action(slot, it) }
    }
}

object InventorySerializer : KSerializer<Inventory> {
    @Serializable
    private class Entry(val slot: Int, val obj: Obj)

    @Serializable
    private class Surrogate(
        val id: Int,
        val capacity: Int,
        val alwaysStack: Boolean = false,
        val entries: List<Entry> = emptyList(),
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Inventory) {
        val entries = ArrayList<Entry>()
        value.forEachIndexed { slot, obj -> entries.add(Entry(slot, obj)) }
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.id, value.capacity, value.alwaysStack, entries))
    }

    override fun deserialize(decoder: Decoder): Inventory {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        val inv = Inventory(surrogate.id, surrogate.capacity, surrogate.alwaysStack)
        for (entry in surrogate.entries) {
            if (entry.slot in 0 until inv.capacity) inv.items[entry.slot] = entry.obj
        }
        inv.markAllDirty()
        return inv
    }
}
