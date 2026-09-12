package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian

/**
 * Catch-all for Daemonheim puzzle rooms that have no solver yet.
 *
 * Registered LAST, so it only sees rooms every real handler declined. Its job is not to solve anything - it
 * claims the room so the navigator stops force-poking a door that stays frozen until the puzzle is done, and
 * prints one census of the room's objects and NPCs with the options their cache types expose. That census is
 * how each remaining room's mechanic gets written: one live encounter yields the ids and option names needed
 * to implement it properly, instead of another round of guessing from gameval names alone.
 *
 * A census that comes back with blank names or empty option lists is itself the finding - that points at the
 * offset table rather than at missing content.
 */
object UnmappedPuzzleRoom {

    private const val RANGE = 24

    /**
     * Every marker id of each solver-less puzzle, across all five themes.
     *
     * Keying on one theme's door id was the earlier mistake: a frozen-theme id misses the identical room in
     * abandoned, furnished, warped and occult, which is four rooms in five going unrecognised. These are every
     * `rand_<room>_*` loc, so a room is claimed whatever theme it rolls and whatever part of it is in view.
     *
     * A room a real handler OWNS never belongs here - its markers survive the solve, so the catch-all would
     * claim the finished room on the way out and stall the bot in it for a census it already has. That is what
     * `isap` (sliding statues), `synch_switch`, `critter_key`, `fishing_keys` and `ghost_with_key` did before
     * they were dropped; the last of those is the poltergeist room under a second internal name, censers and
     * sarcophagus and all.
     */
    private val ROOMS = mapOf(
        "base" to setOf(35273, 35275, 35276, 35277, 35278, 35279, 35280, 49516, 49517, 49518, 49519, 49520,
            49521, 49522, 49523, 49524, 49525, 49526, 49527, 49528, 49529, 49530, 49531, 49532, 49533, 49534,
            49535, 49536, 49537, 49538, 49539, 54300, 54302, 54303, 54304, 54305, 54306, 54307),
        "guardian_sphere" to setOf(35853, 35858, 35863, 49567, 49568, 49569, 49570, 49571, 49572, 49573,
            49574, 49575, 49576, 54235, 54236),
        "riddle" to setOf(37197, 37198, 37199, 49594, 49595, 49596, 49597, 49598, 49599, 49600, 49601, 49602,
            49603, 49604, 49605, 49606, 49607, 49608, 54000),
        "fill_barrel" to setOf(39965, 39966, 39967, 39968, 39969, 49677, 49678, 49679, 49680, 49681, 49682,
            49683, 49684, 49685, 49686, 49687, 49688, 49689, 49690, 49692, 54284, 54285, 54286, 54287, 54288),
        // Never encountered. Only the warped theme ships it (every id is rand_wrpd_), which is why months of
        // floors have not turned one up - so the first sighting has to yield everything at once.
        "seeker" to setOf(56545, 56546, 56547, 56548, 56549, 56550, 56551),
        "sliding_block_puzzle" to setOf(33654, 33674, 54317, 54318, 54319, 54320, 54321, 54323),
        "ransacked_tomb" to setOf(40172, 40173, 40175, 40180, 40181, 54571, 54572, 54573, 54574, 54575, 54576,
            54577, 54578, 54579, 54580, 54581, 54582, 54583, 54584, 54585, 54586, 54587, 54588, 54589, 54590,
            54591, 54592, 54593, 54594, 54595, 54596, 54597, 54598, 54599, 54600, 54601, 54602, 54603, 55451,
            55452, 55453, 55454, 55455, 55456, 55457, 55458, 55459, 55460, 55461, 55462, 55463, 55464, 55465,
            55466, 55467, 55468, 55469, 55470, 55471, 55472, 55473, 55474, 55475, 55476, 55477, 55478, 55479,
            55480, 55481, 55482),
        "pushing_blocks" to setOf(35241, 35242, 35243, 35245, 35246, 54502, 54523, 54544, 54620, 54621, 54623,
            54625, 54627, 54629, 54630, 54631, 54632, 54633, 54634, 54635, 54636, 54637, 54640, 54641, 54642,
            56058, 56079, 56080, 56081, 56082),
    )

    private val censused = HashSet<String>()

    private fun match(): String? {
        val ids = objectsInRoom(RANGE).map { it.id }.toSet()
        return ROOMS.entries.firstOrNull { (_, markers) -> markers.any { it in ids } }?.key
    }

    /**
     * Claim a room only until it has been recorded. Holding it afterwards parks the bot in a room it cannot
     * solve - and worse, in one a real handler has just finished, since this runs last and inherits whatever
     * the others released. A banned cell is a room a real handler DECLINED (unsolvable this floor), not one
     * without a solver: censusing it would report a missing feature that already exists.
     */
    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        return match()?.let { it !in censused } == true
    }

    suspend fun solve(script: Script) {
        val room = match() ?: return
        if (censused.add(room)) logCensus(room)
        script.delay(1200, 300)
    }

    private fun logCensus(room: String) {
        println("DUNG: UNMAPPED PUZZLE '$room' - no solver; census follows")
        objectsInRoom(RANGE).distinctBy { it.id }.forEach { obj ->
            val ops = obj.defs.options?.filterNotNull()?.filter { it.isNotBlank() } ?: emptyList()
            println("DUNG:   loc ${obj.id} '${obj.name()}' ops=$ops tile=(${obj.tile.x},${obj.tile.y})")
        }
        npcsInRoom(RANGE) { true }.distinctBy { it.id }.forEach { npc ->
            val ops = npc.getDef().options.filterNotNull().filter { it.isNotBlank() }
            println("DUNG:   npc ${npc.id} '${npc.name}' ops=$ops tile=(${npc.tile.x},${npc.tile.y})")
        }
        println("DUNG: census end for '$room' - implement a solver from these ids/options")
    }
}
