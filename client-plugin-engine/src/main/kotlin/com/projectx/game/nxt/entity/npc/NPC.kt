package com.projectx.game.nxt.entity.npc
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.ONPC
import com.projectx.game.nxt.entity.PathingEntity
import com.projectx.script.api.combatTarget
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.NpcType
import java.lang.foreign.MemorySegment

private val MENU_OPS = arrayOf(
    DoActionOpcode.NPC_1,
    DoActionOpcode.NPC_2,
    DoActionOpcode.NPC_3,
    DoActionOpcode.NPC_4,
    DoActionOpcode.NPC_5,
    DoActionOpcode.NPC_6
)

class NPC(raw: MemorySegment) : PathingEntity(raw) {
    val id: Int
        get() = ptr.readInt(ONPC.ID)

    val typeId: Int
        get() = ptr.readInt(ONPC.TYPE_ID)

    val hiddenMenuOpFlags: Int
        get() = ptr.readByte(ONPC.HIDDEN_MENUOP_FLAGS).toInt()

    val renderAnim: Int
        get() = ptr.readInt(ONPC.RENDER_ANIM)

    val currentHealth: Int
        get() = ptr.readInt(ONPC.CURRENT_HP)

    val maxHealth: Int
        get() = ptr.readInt(ONPC.MAX_HP)

    val isCombatTarget
        get() = exists() && serverIndex == combatTarget?.serverIndex

    private val _firstServerIndex = serverIndex

    fun exists() = _firstServerIndex == serverIndex && Bootstrap.client.npcManager[serverIndex] != null

    fun interact(action: Int): Boolean {
        if (!exists()) return false
        if (action < 0 || action >= MENU_OPS.size) return false
        val doAction = MENU_OPS.getOrNull(action) ?: return false
        if (getDef().getOp(action) == "null") return false
        doAction.fire(serverIndex, 0, 0)
        return true
    }

    fun interact(action: String): Boolean {
        if (!exists()) return false
        val op = getDef().getOpIdForName(action)
        return if (op != -1) {
            interact(op)
            true
        } else {
            false
        }
    }

    /** Uses [action] when the NPC offers it, otherwise its first option. */
    fun interactOrFirst(action: String) = interact(action) || interact(0)

    fun target(): Boolean {
        if (!exists()) return false
        DoActionOpcode.SELECT_NPC.fire(serverIndex, 0, 0)
        return true
    }

    fun getDef(): NpcType = Cache.npc(if (typeId == -1) id else typeId) ?: NpcType.EMPTY

    fun hasOption(option: String): Boolean = getDef().containsOp(option)

    fun name(): String = getDef().name
}