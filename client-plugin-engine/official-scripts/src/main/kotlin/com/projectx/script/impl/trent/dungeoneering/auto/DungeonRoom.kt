package com.projectx.script.impl.trent.dungeoneering.auto

import com.projectx.script.Script
import com.projectx.script.impl.trent.dungeoneering.DungeonSession

/**
 * What a room handler is handed each tick: the live floor session, the driving script (for delays/waits/scene
 * access), and whether the player is actually standing in the mapped boss room (boss handlers gate on this).
 */
class DungeonRoomContext(val session: DungeonSession, val script: Script, val inBossRoom: Boolean) {
    val roomGivenUp: Boolean
        get() = session.currentCell?.let { it in session.bannedCells } == true
}

/**
 * A self-contained dungeon room handler — a boss or a puzzle. [present] decides whether it owns the room this
 * tick; [solve] drives one step of it and returns (the bot re-dispatches next tick). Register in [DungeonRooms];
 * the bot loop dispatches the first match and never has to know the individual rooms.
 */
class DungeonRoom(
    val name: String,
    val present: (DungeonRoomContext) -> Boolean,
    val postDelayMs: Int = 200,
    val solve: suspend (DungeonRoomContext) -> Unit,
)
