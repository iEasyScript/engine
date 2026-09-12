package com.projectx.game.nxt.mainlogicmanager
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import org.projectx.core.game.skill.Skill
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OStat
import com.projectx.game.nxt.OStatTable
import com.projectx.game.nxt.types.Vector
import java.lang.foreign.MemorySegment

class StatTable(raw: MemorySegment) : Iterable<Stat> {
    val ptr: MemorySegment = raw.atLeast(OStatTable.extent)
    val size: Int
        get() = ptr.readInt(OStatTable.TABLE_SIZE)
    val stats
        get() = Vector(ptr.pointerAtOffset(OStatTable.TABLE_BEGIN, 0x20L), OStatTable.ENTRY_SIZE)

    operator fun get(skill: Skill): Stat {
        return Stat(stats[skill.ordinal])
    }

    override fun iterator(): Iterator<Stat> {
        return object : Iterator<Stat> {
            private var index = 0
            override fun hasNext(): Boolean {
                return index < size
            }

            override fun next(): Stat {
                val stat = get(Skill.entries[index])
                index++
                return stat
            }
        }
    }
}

class Stat(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OStat.extent)
    val xp: Int
        get() = ptr.readInt(OStat.EXPERIENCE).let { if (xpInTenths) it / 10 else it }

    /** One byte wide — the three bytes above it are uninitialised padding, never part of the flag. */
    val xpInTenths: Boolean
        get() = ptr.readByte(OStat.XP_IN_TENTHS) != 0.toByte()
    val realLevel: Int
        get() = ptr.readInt(OStat.REAL_LEVEL)
    val currentLevel: Int
        get() = ptr.readInt(OStat.CURRENT_LEVEL)
}