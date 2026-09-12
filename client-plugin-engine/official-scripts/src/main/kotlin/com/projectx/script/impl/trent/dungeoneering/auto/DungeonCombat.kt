package com.projectx.script.impl.trent.dungeoneering.auto

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.combatTarget
import com.projectx.script.api.eatFood
import com.projectx.script.api.findClosestReachableNPC
import com.projectx.script.api.healthPercent
import com.projectx.script.api.inCombat
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.localPlayer
import com.projectx.script.impl.trent.dungeoneering.DungeonContext
import com.projectx.script.impl.trent.dungeoneering.carriedFood
import com.projectx.script.impl.trent.dungeoneering.boss.DivineSkinweaver
import com.projectx.script.impl.trent.dungeoneering.boss.NightGazerBoss
import com.projectx.script.impl.trent.dungeoneering.boss.StompBoss
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

// A monster this beefy is a boss/miniboss - always worth fighting even if the room isn't flagged required.
private const val BOSS_HP = 2000

// Swings that never land before we write the target off as walled away, and how long that write-off holds.
private const val ENGAGE_ATTEMPTS = 3
private const val ABANDON_MS = 45_000L

// A fight whose target takes no health change at all for this long is not a fight - auto-retaliate is locked
// onto something it can never actually hit. Standing down for it deadlocks against the main loop, which is
// waiting for that same monster to die.
private const val FIGHT_PROGRESS_MS = 12_000L

/**
 * Monsters combat has written off. Shared with [DungeonNavigator] because navigation must not block on a
 * monster combat has explicitly stopped fighting - one loop waiting on the other's abandoned target is the
 * same contract mismatch that deadlocked them in the first place.
 */
internal object AbandonedMonsters {

    private val until = HashMap<Int, Long>()

    fun abandon(serverIndex: Int, forMs: Long) {
        until[serverIndex] = System.currentTimeMillis() + forMs
    }

    fun isAbandoned(serverIndex: Int): Boolean {
        val expiry = until[serverIndex] ?: return false
        if (System.currentTimeMillis() < expiry) return true
        until.remove(serverIndex)
        return false
    }

    fun clear() = until.clear()
}


/**
 * Reactive combat loop run in parallel by [DungeoneeringBotScript]: eat at the HP threshold and keep a
 * live monster in the current room targeted. Auto-retaliate handles the swings; this is deliberately a
 * minimal starting point - extend `act()` with a real ability rotation for the account's style.
 *
 * [killRequiredOnly] (read live) restricts attacks to rooms where killing is mandatory - guardian rooms
 * and the boss room - so wandering monsters in ordinary rooms are left alone. Eating always applies.
 */
class DungeonCombat(private val eatThresholdPercent: Int, private val killRequiredOnly: () -> Boolean) : Script() {

    private val failedEngages = HashMap<Int, Int>()
    private var watchedIndex = -1
    private var watchedHealth = 0
    private var watchedSince = 0L

    override fun onStart() {
        AbandonedMonsters.clear()
        super.onStart()
    }

    override suspend fun loop() {
        try {
            if (inInstancedArea) act()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        delay(300, 80)
    }

    private suspend fun act() {
        // A boss solver owns combat + positioning in its room (e.g. the skinweaver - chasing skeletons off the
        // healing pad would be fatal), so the generic loop stands down there.
        if (DivineSkinweaver.present()) return
        // Wait for the bite to actually land: the ability takes a moment to resolve, and re-firing every loop
        // in the meantime chewed through the whole stack in seconds instead of healing on demand.
        val food = carriedFood()
        if (healthPercent < eatThresholdPercent && food > 0) {
            eatFood()
            delayUntil(gaussian(2400L, 600L)) { carriedFood() < food }
            return
        }
        // Stomp's solver owns attacks + the crystal-gather movement; we only keep eating (above) for it, so a
        // parallel attack loop doesn't lock us onto the boss instead of running crystals to the lodestones.
        if (StompBoss.present()) return
        // Same for the night-gazer: it is immune until every pillar is lit, so swinging here only cancels the
        // walk between pillars and leaves the room dark.
        if (NightGazerBoss.present()) return
        val room = roomOf(localPlayer.tile)
        // Already fighting a live monster in THIS room? leave it alone - but only while the fight is actually
        // going somewhere. A target whose health never moves is one auto-retaliate cannot hit, and standing
        // down for it forever is half of a deadlock: the main loop waits for it to die, and it never does.
        // Written off BEFORE the target is picked so the pick below skips it.
        val fighting = combatTarget
        if (fighting != null && fighting.currentHealth > 0 && roomOf(fighting.tile) == room &&
            !AbandonedMonsters.isAbandoned(fighting.serverIndex)
        ) {
            if (!fightStalled(fighting)) return
            abandon(fighting, "fight stuck at ${fighting.currentHealth}/${fighting.maxHealth}")
        }

        val livingInRoom = { npc: NPC ->
            npc.hasOption("Attack") && npc.currentHealth > 0 && roomOf(npc.tile) == room &&
                !AbandonedMonsters.isAbandoned(npc.serverIndex)
        }
        // Prefer a monster we can actually path to (real instance collision) so we never stay locked on a
        // wall-blocked one while the boss free-hits us. If collision hasn't streamed in yet (mid-inject), fall
        // back to the closest.
        val inRoomTarget = findClosestReachableNPC(14) { livingInRoom(it) }
            ?: allNpcsWithinRange(14) { livingInRoom(it) }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
            ?: return

        // Fight the in-room monster when: we're already in combat (don't stand there while auto-retaliate is
        // stuck on a wall-blocked/out-of-room monster and the boss free-hits us), a boss-tier monster is here
        // (the map doesn't always flag the boss room), the room is combat-required, or the toggle is off.
        val required = DungeonContext.session?.let { DungeonNavigator.currentRoomIsCombatRequired(it) } == true
        val mustFight = inCombat || inRoomTarget.maxHealth >= BOSS_HP || required || !killRequiredOnly()
        if (mustFight) engage(inRoomTarget)
    }

    // A monster the room walls off - the hedge of an unsolved puzzle, a wrong-side spawn - answers every swing
    // with "You can't reach that" forever, and each re-fire also cancels whatever a room solver was walking to
    // do. Wait for the swing to actually land on it, and write the target off for a while once it never does.
    private suspend fun engage(target: NPC) {
        if (!target.interact("Attack")) return
        val index = target.serverIndex
        val healthBefore = target.currentHealth
        val landed = { target.isCombatTarget || target.currentHealth < healthBefore }
        delayUntil(gaussian(2400L, 600L)) { landed() }
        if (landed()) {
            failedEngages.remove(index)
            return
        }
        if (localPlayer.isMoving) return
        val failures = (failedEngages[index] ?: 0) + 1
        failedEngages[index] = failures
        if (failures < ENGAGE_ATTEMPTS) return
        // The count survives the write-off, so once it lapses a single fresh miss is enough to renew it -
        // a monster the room permanently walls off costs one swing per lapse, not another three.
        abandon(target, "unreachable after $failures swings")
    }

    private fun abandon(target: NPC, why: String) {
        AbandonedMonsters.abandon(target.serverIndex, gaussian(ABANDON_MS, ABANDON_MS / 4))
        watchedIndex = -1
        println("DUNG: '${target.name()}' $why - leaving it alone for now")
    }

    // Any health movement at all means the fight is live; a target pinned at one value is one we cannot touch.
    private fun fightStalled(target: NPC): Boolean {
        val now = System.currentTimeMillis()
        if (target.serverIndex != watchedIndex || target.currentHealth != watchedHealth) {
            watchedIndex = target.serverIndex
            watchedHealth = target.currentHealth
            watchedSince = now
            return false
        }
        return now - watchedSince >= FIGHT_PROGRESS_MS
    }

    private fun roomOf(tile: Tile) = (tile.x / 16) to (tile.y / 16)
}
