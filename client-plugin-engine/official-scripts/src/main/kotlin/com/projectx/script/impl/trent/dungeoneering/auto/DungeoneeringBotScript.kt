package com.projectx.script.impl.trent.dungeoneering.auto

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.MakeX
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.script.api.makeX
import com.projectx.script.api.makeXConfirm
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.impl.trent.dungeoneering.CriticalityAnalyzer
import com.projectx.script.impl.trent.dungeoneering.DungeonContext
import com.projectx.script.impl.trent.dungeoneering.SmugglerShop
import com.projectx.script.impl.trent.dungeoneering.DungeonDebug
import com.projectx.script.impl.trent.dungeoneering.DungeonRefusals
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.script.impl.trent.dungeoneering.carriedFood
import com.projectx.script.impl.trent.dungeoneering.carriedRawFood
import com.projectx.script.impl.trent.dungeoneering.edible
import com.projectx.util.gaussian
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val ROOM_TILES = 16

// rand_dungeon_rewards_v2 - the floor-complete screen. Skip the reward tally to reveal the buttons, then
// ready up to start the next floor. NOT leave_button (933:180), which exits Daemonheim entirely.
private const val REWARD_IFACE = 933
private const val REWARD_SKIP_BUTTON = 177
private const val REWARD_READY_BUTTON = 183

private const val DUNGEON_MAP_INTERFACE = 942       // rand_dungeon_map
private const val DUNGEON_MAP_DRAW_LAYER = 8        // rand_dungeon_map:draw_layer - present only while the map is open
private const val MINIMAP_INTERFACE = 1465
private const val MINIMAP_MAP_BUTTON = 10           // toplevel_v2_minimap:world_map - opens the dungeon map in a dungeon

// Loops sitting in the same room with nothing to fight before we treat it as stuck and force a door.
private const val STUCK_LIMIT = 6

// Consecutive failed door-opens (e.g. a path routed through a wall the world has no door on) before we
// break out by forcing any door - this catches a nav blocker even while a monster keeps us "busy".
private const val NAV_FAIL_LIMIT = 5

// A single room step is seconds of work; past this the solver is not progressing and forfeits its turn.
private const val ROOM_TURN_MS = 45_000L

// Loops with no routing objective before we drop the accumulated edge blocks and let the analyzer look at
// the floor again - long enough that a normal between-target beat never trips it.
private const val NO_OBJECTIVE_LIMIT = 25

// Distinct neighbours a cell must resist, or total failed crossings into it, before it is written off.
private const val APPROACH_BAN_NEIGHBOURS = 2
private const val APPROACH_BAN_TRIES = 3

// Deaths in one room before we stop walking back into it. Respawning puts us at the floor start with the
// room still the frontier, so without this the bot re-enters and dies forever, paying the floor's XP
// penalty each cycle. The line the server sends on death - the only signal the client gets.
private const val DEATH_LIMIT = 3
private const val DEATH_MESSAGE = "you are dead"

// Edible items to carry before walking on. One piece does not survive a boss, and the pack starts a floor
// empty - dungeon kills leave fish on the floor, so the shortfall is made up from the room we are in.
private const val FOOD_TARGET = 5

// Times we fail to cross the SAME step-edge (with nothing to fight) before declaring that passage dead and
// recording it so the router reroutes around it instead of ping-ponging back through the same dead door.
private const val EDGE_BLOCK_LIMIT = 3

// Loops of waiting on a room's monsters with their total health not moving at all before we say so and route
// on. Progress-gated rather than a flat timeout, so a genuinely long boss fight never trips it - only a fight
// that has stopped dealing damage does. An unbounded silent wait here is what let combat and navigation defer
// to each other for four minutes with the log saying nothing.
private const val COMBAT_STALL_LOOPS = 60

// Attempts on a skill door we're allowed to force before the rest of the floor gets a turn. Failing the check
// is the door's documented mechanic, so the first handful of failures say nothing about the room behind it -
// under the ordinary three-strike budget every skill-door room on a floor got written off in ninety seconds.
private const val SKILL_DOOR_ATTEMPT_LIMIT = 15

/**
 * The auto-solver. Shares the exact same sensing as the overlay helper (via [DungeonContext]) and adds an
 * action layer: toward the critical-path GO target it opens the door on each room wall (there is no walking
 * room-to-room in Daemonheim), picks up keys, and delegates fighting to a parallel [DungeonCombat] loop
 * (guardian rooms clear before it moves on). Puzzle auto-solving hangs off the same context in a later
 * phase. Kept intentionally conservative - door/combat behaviour is expected to be tuned live.
 */
@ScriptDescription(
    name = "Dungeoneering Bot",
    version = "0.1.0",
    author = "Trent",
    description = "Auto-navigates Daemonheim toward the critical path, opens doors, and clears guardian rooms.",
    category = ScriptCategory.DUNGEONEERING
)
class DungeoneeringBotScript : StateMachineScript<DungeoneeringBotScript>(), ConfigurableScript {

    val eatThreshold = IntConfigItem("Eat at HP %", "Eat food when health drops below this percent", 50, 10, 90)
    val boostMargin = IntConfigItem("Skill-door boost margin", "Levels above yours a skill door can still be forced", 8, 0, 30)
    val killRequiredOnly = BooleanConfigItem(
        "Kill required monsters only",
        "Only fight in guardian rooms and the boss room; leave wandering monsters in other rooms alone",
        true
    )

    private var combat: DungeonCombat? = null
    var stuckLoops = 0
    var navFails = 0
    var lastCellSeen: Pair<Int, Int>? = null
    // Strikes per directional edge, keyed by CriticalityAnalyzer.edgeKey and CUMULATIVE across re-routes. A
    // single "last edge" slot only ever counted consecutive failures, so a door approached from alternating
    // neighbours reset to one every time and could never reach the limit - an unopenable door became an
    // endless circuit instead of a blocked edge.
    val edgeFailures = HashMap<Long, Int>()
    var noObjectiveLoops = 0
    var waitingOn: String? = null
    var retryingSkillDoor = false
    var combatWaitLoops = 0
    var combatWaitHealth = -1
    var stuckDeclines = 0
    var escalationRung = 0

    // Ground-item tiles the server has told us we cannot path to. Per floor: a key walled inside a puzzle stays
    // walled until the puzzle opens, and the session is rebuilt on the next seed.
    val unreachableItems = HashSet<Pair<Int, Int>>()

    override fun getStartState(): State<DungeoneeringBotScript> = Progress

    override fun onStart() {
        DungeonDebug.install()
        combat = DungeonCombat(eatThreshold.value) { killRequiredOnly.value }.also { addParallelScript(it) }
    }

    override fun onStop() {
        combat?.let { removeParallelScript(it) }
        combat = null
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        DungeonContext.onEvent(event)
        if (event is Chat) {
            DungeonRefusals.observe(event.message)
            if (event.message.contains(DEATH_MESSAGE, ignoreCase = true)) recordDeath()
        }
    }

    // The respawn teleports us to the floor start, so by the time the loop reads the pip again we are nowhere
    // near where we died - the room to blame is the last one the loop stood in.
    private fun recordDeath() {
        val session = DungeonContext.session ?: return
        val cell = lastCellSeen ?: session.currentCell ?: return
        val deaths = (session.deathsByCell[cell] ?: 0) + 1
        session.deathsByCell[cell] = deaths
        if (deaths < DEATH_LIMIT) {
            println("DUNG: died in room $cell ($deaths/$DEATH_LIMIT this floor)")
            return
        }
        session.ban(cell, "$deaths deaths here this floor")
    }
}

/**
 * One rung per call, paced by [NO_OBJECTIVE_LIMIT], from cheapest recovery to giving the floor up. Wandering
 * is not a recovery: with every frontier key-locked and no boss discovered, the analyzer is right that there
 * is nothing to route to, and the bot has to escalate deliberately instead of burning the floor's timer.
 */
private suspend fun DungeoneeringBotScript.escalateNoObjective(session: DungeonSession) {
    // Each rung is spent as it is CONSIDERED, not when it works. A rung that announces a lead and then fails to
    // produce a crossing would otherwise restart the ladder from the top every window, and any rung that merely
    // thinks it found something starves every rung below it - which is exactly how the key-fetch rung looped.
    // Only a real crossing (see the `opened` branch) rewinds this.
    if (escalationRung <= 0) {
        escalationRung = 1
        if (session.blockedEdges.isNotEmpty()) {
            println("DUNG: still no objective - dropping ${session.blockedEdges.size} blocked edges to reconsider the floor")
            session.blockedEdges.clear()
            return
        }
    }

    // A key on the floor of a room we can already reach is a real lead the analyzer never scores, because it
    // only ranks rooms. But never a key in a room that KILLED us - that is the one place the death ban exists
    // to keep us out of, and fetching from it walks straight back into the fight we already lost three times.
    if (escalationRung <= 1) {
        escalationRung = 2
        val keyCell = DungeonContext.scanner.groundKeys
            .filter { (it.tile.x to it.tile.y) !in unreachableItems }
            .mapNotNull { session.calibration.cellFor(it.tile) }
            .firstOrNull { (session.deathsByCell[it] ?: 0) == 0 }
        if (keyCell != null && keyCell != session.currentCell) {
            println("DUNG: no objective - a ground key sits in $keyCell; heading for it")
            DungeonNavigator.openDoorToward(this, session, DungeonNavigator.nextStepCell(session, keyCell), boostMargin.value)
            return
        }
    }

    // A ban is a give-up, not a wall, so when the alternative is nothing at all a room a solver could not
    // finish is worth re-entering. A room that KILLED us is not: that one costs the floor's XP every cycle,
    // which is the reason bans are permanent in the first place.
    if (escalationRung <= 2) {
        escalationRung = 3
        val liftable = session.bannedCells.firstOrNull { (session.deathsByCell[it] ?: 0) == 0 }
        if (liftable != null) {
            session.bannedCells.remove(liftable)
            session.failedApproaches.remove(liftable)
            println("DUNG: no objective anywhere - lifting the ban on $liftable as the last remaining lead")
            return
        }
    }

    println("DUNG: floor is a dead end - every frontier is locked, no key in reach, no ban safe to lift; stopping")
    stop()
}

private suspend fun DungeoneeringBotScript.cookRawFood(roomX: Int, roomY: Int): Boolean {
    val fire = getAllObjectsWithinRange(ROOM_TILES).firstOrNull {
        it.hasOption("Cook at") && it.tile.x / ROOM_TILES == roomX && it.tile.y / ROOM_TILES == roomY
    } ?: return false
    if (fire.tile.getDistance(localPlayer.tile) > 5) {
        walkTo(fire.tile, false)
        waitUntilNotMoving()
    }
    if (fire.interact("Cook at")) delayUntil(gaussian(4000L, 900L)) { MakeX.isOpen }
    if (!MakeX.isOpen) return false
    val recipe = MakeX.craftables().firstOrNull { edible(it.itemId) }
    if (recipe == null || !makeX({ it == recipe.name })) makeXConfirm()
    delayUntil(gaussian(18000L, 4500L)) { carriedRawFood() == 0 || carriedFood() >= FOOD_TARGET }
    println("DUNG: cooked at the fire - ${carriedFood()} food, ${carriedRawFood()} raw left")
    delay(540, 150)
    return true
}

private object Progress : State<DungeoneeringBotScript>() {

    override suspend fun DungeoneeringBotScript.checkNext(): State<DungeoneeringBotScript>? = null

    override suspend fun DungeoneeringBotScript.stateLoop() {
        // The end-of-floor reward screen opens after "End dungeon" and takes us out of the instance, so it
        // must be handled before the in-instance guards below. Skip the tally, then ready up for the next
        // floor; the new floor loads under a fresh seed and DungeonContext re-adopts it.
        if (interfaces.isOpen(REWARD_IFACE)) {
            IFSlot(REWARD_IFACE, REWARD_SKIP_BUTTON).click()
            delay(820, 220)
            IFSlot(REWARD_IFACE, REWARD_READY_BUTTON).click()
            delayUntil(gaussian(6000L, 1500L)) { !interfaces.isOpen(REWARD_IFACE) }
            return
        }

        DungeonContext.update(this, boostMargin.value, autoExamine = true, examineSpacingMs = 1500)
        val session = DungeonContext.session
        if (session == null || !inInstancedArea) {
            delay(400, 110)
            return
        }

        // The dungeon map must be open for the reader to calibrate; without it every routing decision has no
        // cell to anchor to and the bot stalls. It starts closed on a fresh floor/inject, so re-open it via the
        // minimap map button whenever the draw layer is absent. The button TOGGLES the map, so after clicking we
        // must poll-wait for it to actually open - re-clicking every loop just slams the freshly-opened map shut
        // again (the spam-toggle that deadlocked us on a fresh floor's start room).
        if (interfaces.getComponentRaw(DUNGEON_MAP_INTERFACE, DUNGEON_MAP_DRAW_LAYER) == null) {
            IFSlot(MINIMAP_INTERFACE, MINIMAP_MAP_BUTTON).click(1)
            delayUntil(gaussian(3000L, 700L)) { interfaces.getComponentRaw(DUNGEON_MAP_INTERFACE, DUNGEON_MAP_DRAW_LAYER) != null }
            return
        }


        // Start-of-floor supply run: while the Smuggler is in scene (only ever the start room), buy
        // dungeoneering feathers so the pondskater fishing-key puzzle can be solved rather than skipped. Buys
        // the exact captured interaction (956:3 slot ordinal, option 6) after a settle delay; only when the
        // pack is out of feathers. Runs once per floor.
        if (!session.suppliesBought && SmugglerShop.smugglerInRoom()) {
            if (SmugglerShop.needsSupplies()) {
                if (SmugglerShop.stockUp(this)) session.suppliesBought = true
            } else {
                session.suppliesBought = true
            }
            delay(200, 60)
            return
        }

        // Room handlers (bosses + puzzles) live in DungeonRooms in priority order; the first whose present()
        // matches owns the room this tick and drives one step of it. A boss solver only engages once we're
        // actually IN its room (its present() gates on inBossRoom) - otherwise it would flail at the boss
        // through the wall of the adjacent room before the navigator crosses in. Floors with no mapped boss
        // cell (abandoned floors) fall back to range-only so those still trigger. Adding a puzzle/boss is one
        // entry in DungeonRooms; this loop never changes.
        val bossCell = session.map.bossCell()
        val roomCtx = DungeonRoomContext(
            session, this,
            inBossRoom = bossCell == null || session.currentCell == (bossCell.gx to bossCell.gy),
        )
        // A solver gets a bounded turn. One step of a room is seconds of work, so anything still running after
        // this has stopped making progress - and a solver that never returns takes down every recovery built
        // on top of this loop at once: no ban, no give-up, no abandon-floor, because none of them get a tick.
        for (room in DungeonRooms.registered) {
            if (room.present(roomCtx)) {
                try {
                    withTimeout(gaussian(ROOM_TURN_MS, ROOM_TURN_MS / 5)) { room.solve(roomCtx) }
                } catch (timeout: TimeoutCancellationException) {
                    println("DUNG: ${room.name} solver overran its turn - aborting the step")
                }
                delay(room.postDelayMs)
                return
            }
        }

        // Never drive doors from an unknown position. If the player's world tile doesn't resolve to a drawn
        // map cell, the world↔map calibration is mid-resync (or has just drifted): every routing decision
        // would degenerate to "head at the raw target", which is exactly what produced the door ping-pong.
        // Hold for a beat - the reader re-locks the transform within a few frames - and never force a door
        // here. (Scene-based steps above, like end-dungeon and key pickup, don't need the cell and already ran.)
        val onMap = DungeonNavigator.currentCell(session) != null

        // Global anti-stuck safety net: reset only when we reach a different map CELL (real progress) or have
        // something to fight. A whole ice slide runs inside one openDoorToward call, so between loops the cell
        // either changed (crossed) or didn't - cell-based catches a freeze or a same-cell shuffle that a
        // tile-based check would miss, without ever false-firing mid-slide. Only fires while on the map, so a
        // drifting calibration can never make us force a door from nowhere.
        val cell = session.currentCell
        // A patient skill-door retry sits in one room BY DESIGN, so the previous loop's retry withdraws this
        // loop's stuck vote - shoving the avatar out mid-budget is what turned the retry into a two-room bounce.
        // One-tick handshake so a room solver taking over the tick can never leave the detector disabled.
        val patientlyRetrying = retryingSkillDoor
        retryingSkillDoor = false
        if (cell != lastCellSeen || DungeonNavigator.roomHasLiveMonster()) {
            lastCellSeen = cell
            stuckLoops = 0
            stuckDeclines = 0
        } else if (onMap && !patientlyRetrying && ++stuckLoops >= STUCK_LIMIT) {
            stuckLoops = 0
            if (DungeonNavigator.forceAnyDoor(this)) {
                println("DUNG: STUCK at cell=$cell - forced a door out")
                stuckDeclines = 0
                lastCellSeen = session.currentCell
                delay(400, 110)
                return
            }
            // The recovery declined: no door here it is allowed to take. Returning would hand the tick back to
            // a detector that just re-arms, relogging an identical line while nothing moves and starving the
            // routing and no-objective escalation below - which are the only things that can still make
            // progress. So say it once and fall through.
            if (stuckDeclines++ == 0) println("DUNG: STUCK at cell=$cell - recovery has no door to take; deferring to routing")
        }

        // Wait out combat only where it's actually required (guardian/boss rooms) - otherwise wandering
        // monsters in an ordinary room we don't fight would stall us forever. When the toggle is off we
        // clear every room, so we wait wherever monsters remain.
        val mustClear = !killRequiredOnly.value || DungeonNavigator.currentRoomIsCombatRequired(session)
        val roomHealth = DungeonNavigator.roomMonsterHealth()
        if (mustClear && roomHealth > 0) {
            if (roomHealth != combatWaitHealth) {
                combatWaitHealth = roomHealth
                combatWaitLoops = 0
            }
            if (++combatWaitLoops < COMBAT_STALL_LOOPS) {
                delay(500, 140)
                return
            }
            println("DUNG: combat stalled at cell=${session.currentCell} - room hp $roomHealth unmoved for $combatWaitLoops loops; routing on")
        }
        combatWaitLoops = 0
        combatWaitHealth = -1

        // Floor cleared: the end-dungeon trapdoor unlocks once the boss is dead. Taking it completes the
        // floor; the next floor then loads under a fresh seed and DungeonContext re-adopts it automatically.
        if (DungeonNavigator.endDungeon(this)) {
            delay(600, 160)
            return
        }

        // Grab a key lying on the floor of THIS room - you can't reach a key in another room (walls seal
        // rooms; only doors cross them), so a key elsewhere is not actionable until we've opened our way in.
        val roomX = localPlayer.tile.x / ROOM_TILES
        val roomY = localPlayer.tile.y / ROOM_TILES
        val key = DungeonContext.scanner.groundKeys.firstOrNull {
            it.tile.x / ROOM_TILES == roomX && it.tile.y / ROOM_TILES == roomY &&
                (it.tile.x to it.tile.y) !in unreachableItems
        }
        if (key != null) {
            val attemptedAt = System.currentTimeMillis()
            DungeonNavigator.pickUpGroundItemAt(this, key.tile.x, key.tile.y)
            // Sharing a room is not the same as being able to path to it: a key sealed inside a puzzle answers
            // every attempt with "you can't reach that", and nothing was reading that. The pickup re-fired 162
            // times in one room while the toxin in it did the killing.
            DungeonRefusals.unreachableSince(attemptedAt)?.let {
                unreachableItems += key.tile.x to key.tile.y
                println("DUNG: ground item at (${key.tile.x},${key.tile.y}) is walled off - \"$it\"; leaving it")
            }
            delay(400, 110)
            return
        }

        // An empty pack means the combat loop's heal can never fire, and a boss then kills us on repeat. Restock
        // from THIS room before walking into the next fight - a stack one room over is unreachable until we've
        // opened our way in, same as a key.
        // The Smuggler only sells raw fish, so the pack fills with something that heals nothing until a fire
        // turns it into food. Dungeon rooms hand out fires as ordinary resource nodes; cook at the first one
        // we share a room with. Falls through when the interface never opens, so a bad fire can't park us.
        if (carriedFood() < FOOD_TARGET && carriedRawFood() > 0 && cookRawFood(roomX, roomY)) return

        val carried = carriedFood()
        if (carried < FOOD_TARGET) {
            val food = groundItems.firstOrNull {
                it.tile.x / ROOM_TILES == roomX && it.tile.y / ROOM_TILES == roomY && edible(it.id)
            }
            if (food != null) {
                val takeOp = food.groundOps.indexOfFirst { it?.equals("Take", ignoreCase = true) == true }
                if (food.interact(takeOp.coerceAtLeast(0))) {
                    waitUntilNotMoving()
                    delayUntil(gaussian(4000L, 1000L)) { carriedFood() > carried }
                }
                delay(420, 120)
                return
            }
        }

        // Off the drawn map (calibration drifting/resyncing) - hold rather than route from a bogus cell.
        if (!onMap) {
            delay(400, 110)
            return
        }

        // Reduced to aimlessly re-entering already-cleared rooms ("explore room") means the router found no
        // real lead - but a stale blocked edge can hide the only one (e.g. a puzzle door we've since solved,
        // whose whole wing then reads as unreachable). Drop the blocks once and let it reconsider the real path
        // rather than wander.
        if (session.map.goTarget?.reason == "explore room" && session.blockedEdges.isNotEmpty()) {
            session.blockedEdges.clear()
            delay(150, 40)
            return
        }

        // Advance toward the GO target by OPENING the door on the wall between here and the next room -
        // there is no walking room-to-room in Daemonheim; every transition is a door interaction.
        val target = DungeonNavigator.targetCell(session)
        if (target == null) {
            // No analyzer objective - a key door may be unlocked-but-uncrossed (its auto-"Enter" eaten by an
            // interruption). Recover it (navigate back + Enter); otherwise there is genuinely nothing to do.
            //
            // This branch returns before the routing log, so an objective-less floor used to sit here in
            // total silence while the stuck detector bounced the avatar between two rooms - the bot looked
            // busy and said nothing. Say it, and drop the accumulated edge blocks once in case a stale one
            // is hiding the only remaining lead. Deliberate give-ups (bannedCells) are NOT cleared: those
            // are rooms that killed us or that a solver could not finish, and re-enabling them re-enters a
            // fight we already decided to leave.
            if (DungeonNavigator.recoverStrandedDoor(this, session, boostMargin.value)) return
            if (noObjectiveLoops++ == 0) {
                println("DUNG: no objective at cell=${session.currentCell} - ${session.blockedEdges.size} blocked edges, ${session.bannedCells.size} banned cells")
            }
            if (noObjectiveLoops >= NO_OBJECTIVE_LIMIT) {
                noObjectiveLoops = 0
                escalateNoObjective(session)
            }
            delay(500, 140)
            return
        }
        noObjectiveLoops = 0
        val step = DungeonNavigator.nextStepCell(session, target)
        val fromCell = session.currentCell
        val attemptedAt = System.currentTimeMillis()
        val opened = DungeonNavigator.openDoorToward(this, session, step, boostMargin.value)
        println("DUNG: room=(${localPlayer.tile.x / ROOM_TILES},${localPlayer.tile.y / ROOM_TILES}) cell=${session.currentCell} target=$target step=$step opened=$opened liveMon=${DungeonNavigator.roomHasLiveMonster()}")
        if (opened) {
            navFails = 0
            // A crossing is the only thing that proves an escalation actually helped, so it is the only thing
            // that rewinds the ladder. Anything weaker lets a rung that merely announces a lead re-fire forever.
            escalationRung = 0
            fromCell?.let { edgeFailures.remove(CriticalityAnalyzer.edgeKey(it, step)) }
            // A crossing that actually happened is what proves normal routing works again - merely HAVING a
            // target does not, and clearing on that nulled the anti-bounce memory on every single loop.
            DungeonNavigator.clearForcedMemory()
        } else {
            // The server said outright that this passage cannot be cleared. That answers in one attempt what
            // counting failures takes a dozen to infer, so the edge goes down immediately rather than after
            // the usual strikes - and it is the difference between one refused door and thirty.
            val here = session.currentCell

            // "Not yet" is not "no". The door opens by itself once whatever the room is running finishes, so
            // the only correct response is to stop hammering it and let this room's own solver have the ticks.
            // Counting these as failures would ban a route that was seconds from opening.
            val waiting = DungeonRefusals.passageWaitingSince(attemptedAt)
            if (waiting != null) {
                if (waitingOn != waiting) {
                    waitingOn = waiting
                    println("DUNG: passage not ready - \"$waiting\"")
                }
                here?.let { edgeFailures.remove(CriticalityAnalyzer.edgeKey(it, step)) }
                navFails = 0
                delay(2200, 600)
                return
            }
            waitingOn = null

            val refusal = DungeonRefusals.passageRefusedSince(attemptedAt)
            if (refusal != null && here != null) {
                session.blockedEdges += CriticalityAnalyzer.edgeKey(here, step)
                session.ban(step, "server refused the passage: \"$refusal\"")
                edgeFailures.remove(CriticalityAnalyzer.edgeKey(here, step))
                navFails = 0
                delay(400, 110)
                return
            }
            // "You can't reach that" says we could not PATH to the door - typically because the room's own
            // puzzle has not opened the way yet - not that the passage is dead. Counting it as a dead-edge
            // strike banned a room three refusals after a maze solved itself on the far side.
            if (DungeonRefusals.unreachableSince(attemptedAt) != null ||
                DungeonNavigator.wallDoorsAllRefused(session, step)
            ) {
                // OUR delivery failing, not evidence the room beyond is unreachable - so it never advances the
                // approach count. Letting it do so wrote off two frontier rooms holding the key the maze had
                // just been solved for. The edge IS recorded: a cell with no unblocked approach then stops
                // being offered as a candidate at all, which is what the ban used to do, minus the permanence.
                here?.let { session.blockedEdges += CriticalityAnalyzer.edgeKey(it, step) }
                delay(900, 240)
                return
            }
            // A step we keep failing to cross while there's nothing to fight (so it isn't a guardian door
            // waiting on a kill) is a dead passage - record the edge so the router reroutes around it instead
            // of ping-ponging back through the same unreachable door.
            if (here != null && !DungeonNavigator.roomHasLiveMonster()) {
                val edge = CriticalityAnalyzer.edgeKey(here, step)
                val strikes = edgeFailures.merge(edge, 1, Int::plus) ?: 1
                // A skill door refusing an attempt is the door working as designed, not a dead passage, so it
                // gets a budget instead of strikes and NEVER a ban: bannedCells is never cleared, so a room
                // written off for failing a check it is expected to fail is lost for the rest of the floor.
                if (DungeonNavigator.gatedByForceableSkillDoor(session.map, step)) {
                    if (strikes < SKILL_DOOR_ATTEMPT_LIMIT) {
                        if (strikes == 1) println("DUNG: skill door into $step didn't give - retrying up to $SKILL_DOOR_ATTEMPT_LIMIT times")
                        retryingSkillDoor = true
                        navFails = 0
                        delay(1900, 520)
                        return
                    }
                    println("DUNG: skill door into $step still shut after $strikes attempts - rerouting, not banning")
                    session.blockedEdges += edge
                    edgeFailures.remove(edge)
                    navFails = 0
                    delay(600, 160)
                    return
                }
                if (strikes >= EDGE_BLOCK_LIMIT) {
                    session.blockedEdges += edge
                    // Blocking the edge alone can't break the loop when `step` IS the GO target: the router
                    // then finds no path, the navigator falls back to heading straight at it, and the
                    // "explore room" reset wipes the block again next loop - which is the door we poked for
                    // an hour. A durable cell ban does break it, but one failed crossing only proves that
                    // door didn't work that time, and every ban permanently shrinks the floor. So the cell
                    // is written off only once it has resisted several approaches - from two different
                    // neighbours, or the same one repeatedly after a re-route.
                    val approaches = session.failedApproaches.getOrPut(step) { ArrayList() }
                    approaches += here
                    if (approaches.distinct().size >= APPROACH_BAN_NEIGHBOURS || approaches.size >= APPROACH_BAN_TRIES) {
                        session.ban(step, "unreachable from ${approaches.distinct().size} neighbour(s) in ${approaches.size} tries")
                    } else {
                        println("DUNG: blocked dead edge $here -> $step (approach ${approaches.size}/$APPROACH_BAN_TRIES)")
                    }
                    edgeFailures.remove(edge)
                    navFails = 0
                    delay(400, 110)
                    return
                }
            }
            if (++navFails >= NAV_FAIL_LIMIT) {
                // Nothing rerouteable (e.g. path routed through a wall the world has no door on) - break the
                // hang by taking any real door out of this room instead of retrying the dead step forever.
                println("DUNG: nav blocked ($navFails fails) step=$step - forcing a door out")
                session.blockedEdges.clear()
                DungeonNavigator.forceAnyDoor(this)
                navFails = 0
            }
        }
        delay(400, 110)
    }
}
