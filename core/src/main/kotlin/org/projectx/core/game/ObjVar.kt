package org.projectx.core.game

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

data class ObjVar(val varId: Int, val value: Int)

data class InvSlot(val slotIndex: Int, val objId: Int, val amount: Int, val objVars: List<ObjVar> = emptyList()) {
    val empty: Boolean get() = objId < 0
}

fun Obj.objVars(): List<ObjVar> {
    val attrs = attributes ?: return emptyList()
    if (attrs.isEmpty) return emptyList()
    val byBaseVar = LinkedHashMap<Int, Int>()
    for (key in attrs.keys) {
        val value = when (val v = attrs[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Boolean -> if (v) 1 else 0
            else -> continue
        }
        val directVar = Gameval.id(Gameval.VAR_OBJECT, key)
        if (directVar != null) {
            byBaseVar.merge(directVar, value, Int::or)
            continue
        }
        val varbit = Cache.objectVarbit(key) ?: continue
        val packed = (value and varbit.maxValue) shl varbit.startBit
        byBaseVar.merge(varbit.baseVar, packed, Int::or)
    }
    return byBaseVar.map { (varId, value) -> ObjVar(varId, value) }
}

private fun Obj?.toInvSlot(slot: Int): InvSlot =
    if (this == null) InvSlot(slot, -1, 0) else InvSlot(slot, id, amount, objVars())

fun Inventory.snapshotFull(): List<InvSlot> = (0 until capacity).map { this[it].toInvSlot(it) }

fun Inventory.snapshotSlots(slots: Collection<Int>): List<InvSlot> =
    slots.asSequence().filter { it in 0 until capacity }.sorted().map { this[it].toInvSlot(it) }.toList()
