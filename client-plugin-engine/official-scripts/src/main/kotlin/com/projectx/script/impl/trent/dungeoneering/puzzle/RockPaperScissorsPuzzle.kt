package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Daemonheim "rock paper scissors" room: a rank of armed DEFENDER statues faces a rank of ATTACKERS, and each
 * attacker takes the weapon that beats the defender opposite it - mined from the crumbling wall, carved, armed.
 *
 * `Arm` never asks which weapon to hand over, so the pack must hold exactly one when a statue takes it; a
 * correctly armed statue then keeps it indefinitely, which is why one statue at a time is both safe and enough.
 */
object RockPaperScissorsPuzzle {

    internal enum class Style(val carved: Int, val carveOp: String) {
        MELEE(17416, "Carve sword"),
        RANGE(17418, "Carve bow"),
        MAGIC(17420, "Carve staff"),
    }

    /** Keyed by the DEFENDER's style, valued with the style the attacker facing it must carry. */
    internal val BEATS = mapOf(Style.RANGE to Style.MELEE, Style.MAGIC to Style.RANGE, Style.MELEE to Style.MAGIC)

    private const val STONE_BLOCK = 17415
    private const val SCAN_RANGE = 24
    private const val ARM_ATTEMPTS = 3
    private const val DROP_LIMIT = 3

    private fun table(vararg groups: Pair<Style, Set<Int>>): Map<Int, Style> =
        groups.flatMap { (style, ids) -> ids.map { it to style } }.toMap()

    // Every statue displays as "Statue", and an armed attacker is byte-identical to a defender of the same
    // style, so role AND weapon can only come from the id - `rand_rock_paper_scissors_<role>_<style>_<theme>`.
    private val UNARMED_ATTACKERS = setOf(11012, 11013, 11014, 12106, 13049)
    private val UNARMED_DEFENDERS = setOf(11015, 11016, 11017, 12107, 13050)
    private val ARMED_ATTACKERS = table(
        Style.MELEE to setOf(11018, 11019, 11020, 12108, 13051),
        Style.RANGE to setOf(11021, 11022, 11023, 12109, 13052),
        Style.MAGIC to setOf(11024, 11025, 11026, 12110, 13053),
    )
    private val ARMED_DEFENDERS = table(
        Style.MELEE to setOf(11027, 11028, 11029, 12111, 13054),
        Style.RANGE to setOf(11030, 11031, 11032, 12112, 13055),
        Style.MAGIC to setOf(11033, 11034, 11035, 12113, 13056),
    )

    private var attemptCell: Pair<Int, Int>? = null
    private var bestArmed = 0
    private var attempts = 0
    private var censused = false

    private fun statues(): List<NPC> =
        npcsInRoom(SCAN_RANGE) { it.name() == "Statue" }.sortedWith(compareBy({ it.tile.x }, { it.tile.y }))

    private fun isAttacker(npc: NPC) = npc.id in UNARMED_ATTACKERS || npc.id in ARMED_ATTACKERS

    private fun isDefender(npc: NPC) = npc.id in UNARMED_DEFENDERS || npc.id in ARMED_DEFENDERS

    private fun heldStyle(npc: NPC) = ARMED_ATTACKERS[npc.id] ?: ARMED_DEFENDERS[npc.id]

    internal fun opponentOf(attacker: Tile, defenders: List<Tile>): Tile? =
        defenders.filter { it.x == attacker.x || it.y == attacker.y }.minByOrNull { it.getDistance(attacker) }

    // The ranks stand four apart on one axis and line up exactly on the other, so the opponent is the defender
    // sharing that perpendicular coordinate - nearest-defender picks the diagonal one and loses every round.
    private fun opponent(attacker: NPC, defenders: List<NPC>): NPC? =
        opponentOf(attacker.tile, defenders.map { it.tile })
            ?.let { tile -> defenders.first { it.tile.x == tile.x && it.tile.y == tile.y } }

    private fun required(attacker: NPC, defenders: List<NPC>): Style? =
        opponent(attacker, defenders)?.let { ARMED_DEFENDERS[it.id] }?.let { BEATS.getValue(it) }

    private fun correctlyArmed(attacker: NPC, defenders: List<NPC>): Boolean {
        val style = ARMED_ATTACKERS[attacker.id] ?: return false
        return style == required(attacker, defenders)
    }

    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        val statues = statues()
        val defenders = statues.filter(::isDefender)
        return statues.filter(::isAttacker).any { !correctlyArmed(it, defenders) }
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        if (session.currentCell != attemptCell) {
            attemptCell = session.currentCell
            attempts = 0
            bestArmed = 0
            censused = false
        }
        val statues = statues()
        val defenders = statues.filter(::isDefender)
        logCensus(statues, defenders)

        val attackers = statues.filter(::isAttacker)
        val armed = attackers.count { correctlyArmed(it, defenders) }
        if (armed > bestArmed) {
            bestArmed = armed
            attempts = 0
        }

        val pending = attackers.filterNot { correctlyArmed(it, defenders) }
        val plan = pending.mapNotNull { atk -> required(atk, defenders)?.let { atk to it } }
        if (plan.isEmpty()) {
            if (++attempts >= ARM_ATTEMPTS)
                ban(session, "no defender lines up with ${pending.size} unsolved attacker(s)")
            script.delay(700, 200)
            return
        }

        // A statue holding the wrong weapon refuses `Arm` until the room strips it back; waiting that out beats
        // starting a second statue, which is how the pack ends up holding two weapons at once.
        val armable = plan.filter { it.first.hasOption("Arm") }
        if (armable.isEmpty()) {
            script.delayUntil(gaussian(9000L, 2200L)) { statues().any { it.hasOption("Arm") } }
            return
        }
        val (statue, want) = armable.firstOrNull { inventory.hasItem(it.second.carved) } ?: armable.first()

        shedOtherWeapons(script, want)
        if (!inventory.hasItem(want.carved)) {
            if (inventory.hasItem(STONE_BLOCK)) carveWeapon(session, script, want) else mineBlock(session, script)
            return
        }
        armStatue(session, script, statue, want)
    }

    private suspend fun armStatue(session: DungeonSession, script: Script, statue: NPC, want: Style) {
        val at = statue.tile
        approach(script, statue)
        if (!statue.interact("Arm")) return
        script.waitUntilNotMoving()
        script.delayUntil(gaussian(6000L, 1500L)) { armedStyleAt(at) != null }
        val took = armedStyleAt(at)
        println("DUNG-RPS: armed (${at.x},${at.y}) wanted=$want took=${took ?: "nothing"}")
        if (took == want) attempts = 0
        else if (++attempts >= ARM_ATTEMPTS)
            ban(session, "the statue at (${at.x},${at.y}) took ${took ?: "nothing"} when handed $want")
        script.delay(620, 170)
    }

    private fun armedStyleAt(tile: Tile): Style? =
        statues().firstOrNull { it.tile.x == tile.x && it.tile.y == tile.y }?.let { ARMED_ATTACKERS[it.id] }

    private suspend fun shedOtherWeapons(script: Script, want: Style) {
        Style.entries.filter { it != want }.forEach { style ->
            repeat(DROP_LIMIT) {
                if (!inventory.hasItem(style.carved) || !dropWeapon(script, style)) return@forEach
            }
        }
    }

    private suspend fun dropWeapon(script: Script, style: Style): Boolean {
        val item = inventory.firstOrNull { it.id == style.carved } ?: return false
        val op = item.getDef().getInvOpIdForName("Drop")
        if (op < 0) {
            println("DUNG-RPS: ${item.name} offers no Drop - ops=${item.invOps.filterNotNull()}")
            return false
        }
        val before = inventory.count(style.carved)
        item.click(op.toMenuOp())
        script.delayUntil(gaussian(1800L, 450L)) { inventory.count(style.carved) < before }
        script.delay(410, 120)
        return inventory.count(style.carved) < before
    }

    private fun logCensus(statues: List<NPC>, defenders: List<NPC>) {
        if (censused) return
        censused = true
        println("DUNG-RPS: ${statues.size} statues in the room")
        statues.forEach {
            val role = if (isAttacker(it)) "attacker" else if (isDefender(it)) "defender" else "?"
            val needs = if (isAttacker(it)) required(it, defenders) else null
            println("DUNG-RPS:   npc ${it.id} $role holds=${heldStyle(it)} needs=$needs tile=(${it.tile.x},${it.tile.y})")
        }
        objectsInRoom(SCAN_RANGE).distinctBy { it.id }.forEach { obj ->
            val ops = obj.defs.options?.filterNotNull()?.filter { op -> op.isNotBlank() } ?: emptyList()
            println("DUNG-RPS:   loc ${obj.id} '${obj.name()}' ops=$ops tile=(${obj.tile.x},${obj.tile.y})")
        }
    }

    private suspend fun mineBlock(session: DungeonSession, script: Script) {
        val wall = objectsInRoom(SCAN_RANGE).firstOrNull { it.name() == "Crumbling wall" && it.hasOption("Mine") }
        if (wall == null) {
            ban(session, "no crumbling wall to mine a stone block from")
            return
        }
        if (wall.tile.getDistance(localPlayer.tile) > 2) {
            walkTo(wall.tile, false)
            script.waitUntilNotMoving()
        }
        if (wall.interact("Mine")) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(9000L, 2200L)) { inventory.hasItem(STONE_BLOCK) }
        }
        if (inventory.hasItem(STONE_BLOCK)) attempts = 0
        else if (++attempts >= ARM_ATTEMPTS) ban(session, "the crumbling wall yielded no stone block (Mining too low?)")
        script.delay(560, 150)
    }

    private suspend fun carveWeapon(session: DungeonSession, script: Script, want: Style) {
        val block = inventory.firstOrNull { it.id == STONE_BLOCK } ?: return
        val index = block.getDef().getInvOpIdForName(want.carveOp)
        if (index < 0) {
            ban(session, "the stone block has no '${want.carveOp}' option; it offers ${block.invOps.filterNotNull()}")
            return
        }

        // The block carries a direct op per weapon, so the style is named rather than picked off a production
        // list; the bare "Carve" that a first-non-Drop scan lands on opens nothing and reaches no wire.
        val sent = block.click(index.toMenuOp())
        script.delayUntil(gaussian(6000L, 1400L)) { inventory.hasItem(want.carved) }
        val made = inventory.hasItem(want.carved)
        println("DUNG-RPS: carve $want op='${want.carveOp}' index=$index sent=$sent made=$made")

        if (made) {
            attempts = 0
            return
        }
        if (++attempts >= ARM_ATTEMPTS) ban(session, "'${want.carveOp}' produced no weapon (sent=$sent)")
        script.delay(560, 150)
    }

    private fun Int.toMenuOp(): Int = when (this) {
        0 -> 1
        1 -> 2
        2 -> 3
        3 -> 7
        4 -> 8
        else -> -1
    }

    private suspend fun approach(script: Script, npc: NPC) {
        if (npc.tile.getDistance(localPlayer.tile) <= 2) return
        walkTo(npc.tile, false)
        script.waitUntilNotMoving()
    }

    private fun ban(session: DungeonSession, reason: String) {
        val carried = (listOf(STONE_BLOCK to "block") + Style.entries.map { it.carved to it.name.lowercase() })
            .filter { inventory.hasItem(it.first) }
            .joinToString(",") { it.second }
            .ifEmpty { "nothing" }
        session.ban(session.currentCell, "rock-paper-scissors: $reason [carrying $carried]")
    }
}
