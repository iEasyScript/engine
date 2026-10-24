package com.projectx.traversal.walk

import com.projectx.pathfinder.HopModel
import com.projectx.pathfinder.LiveWalkMap
import com.projectx.pathfinder.ServerWalk
import com.projectx.pathfinder.WalkMap
import com.projectx.pathfinder.hops.HopExplainer
import com.projectx.pathfinder.hops.HopRecorder
import com.projectx.pathfinder.hops.Hops
import com.projectx.profiling.PlayerProfiles
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.ServerTick
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.dive
import com.projectx.script.api.diveAbility
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.npcs
import com.projectx.script.api.surge
import com.projectx.script.api.varps
import com.projectx.script.api.walkTo
import com.projectx.util.FeatureIds
import com.projectx.util.nonProfiledGaussian
import com.projectx.util.random
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.Effect
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Walks a stretch of route on the live map: clicks chosen by walking distance ([ClickPicker]), Surge and Dive cast
 * where the map shows a hop that lands well ahead ([HopPlanner]), a Powerburst of acceleration drunk where a run of
 * hops pays for it ([AccelerationPlanner]), and a stop that the map says cannot be walked past reported within a
 * couple of seconds ([StuckDetector]). Where the map cannot be trusted it answers [Step.FALLBACK] and the caller
 * walks as it did without one.
 *
 * Every hop is logged with its predicted landing beside the real one, and on a learning client recorded ([Hops]).
 */
internal class LiveRouteWalker(private val owner: String, private val map: WalkMap = LiveWalkMap) {

    enum class Step { CONTINUE, ADVANCE, FALLBACK, STUCK }

    var surgeEnabled = true
    var diveEnabled = true
    var accelerationPotion = true

    private val planner = HopPlanner(map)
    private val acceleration = AccelerationPlanner(map, planner)
    private val stuck = StuckDetector()
    private var progress = 0
    private var lastClick: Tile? = null
    private var lastClickAt = 0L
    private var nextClick = 0L
    private var nextAbilityCheck = 0L
    private var hopHoldUntil = 0L
    private var nextBurstCheck = 0L
    private var serverTriedFrom: Tile? = null
    private val track = ArrayDeque<Pair<Long, Tile>>()
    private var tileChangedAt = 0L

    private var pendingKind: String? = null
    private var pendingFrom: Tile? = null
    private var pendingPredicted: Tile? = null
    private var pendingDirection: Direction? = null
    private var pendingAt = 0L
    private var pendingCooldownSeen = false
    private var pendingRecord: HopRecorder.Pending? = null
    private var chainAfterDive = false
    private var lastHopDirection: Direction? = null
    private var lastHopAt = 0L

    fun reset() {
        progress = 0
        lastClick = null
        nextClick = 0
        serverTriedFrom = null
        stuck.reset()
    }

    /** Another action (a door, a link, a teleport) has replaced the walk queue. */
    fun forgetWalk() {
        lastClick = null
        walk.forget()
    }

    suspend fun step(script: Script, route: List<Tile>, stepLimit: Int, done: () -> Boolean): Step {
        val player = localPlayer.tile
        LiveWalkMap.observe(player)
        track(player)
        watchPending(player)
        if (route.isEmpty() || !map.trusted(player)) return Step.FALLBACK

        val pick = ClickPicker.choose(map, route, player, stepLimit, progress) ?: return Step.FALLBACK
        progress = ClickPicker.advance(progress, pick, route, player, stepLimit)
        val click = pick.tile
        if (click == null) {
            if (pick.atEnd) return Step.ADVANCE
            val ahead = route[minOf(pick.nearestIndex + 1, route.size - 1)]
            if (map.distance(player, ahead, Int.MAX_VALUE) != WalkMap.UNREACHABLE) {
                stuck.reset()
                return Step.FALLBACK
            }
            if (player.plane != ahead.plane || player.getLongestDelta(ahead) > FAR_OFF_ROUTE) {
                stuck.reset()
                script.delay(POLL_MS.toInt())
                return Step.CONTINUE
            }
            if (!stuck.stuck(player, localPlayer.isMoving) { map.distance(it, ahead, Int.MAX_VALUE) == WalkMap.UNREACHABLE }) {
                script.delay(POLL_MS.toInt())
                return Step.CONTINUE
            }
            stuck.reset()
            println("[$owner] stuck: $ahead is unreachable from $player on the live map")
            // The live map can be wrong where the route is not; let the server's own pathing try once before giving up.
            if (serverTriedFrom == null || serverTriedFrom!!.getLongestDelta(player) > 1) {
                serverTriedFrom = player
                return Step.FALLBACK
            }
            serverTriedFrom = null
            return Step.STUCK
        }
        stuck.reset()

        if (maybeDrinkAcceleration(route, pick.nearestIndex, player)) {
            lastClick = null
            nextClick = 0
        }
        val hopped = castAlongRoute(route, pick.nearestIndex, player)
        if (hopped) {
            lastClick = null
            walk.forget()
            nextClick = 0
        }
        if (chainAfterDive || !hopped && System.currentTimeMillis() < chainUntil) {
            script.delay(POLL_MS.toInt())
            return Step.CONTINUE
        }

        val now = System.currentTimeMillis()
        val moving = !hopped && localPlayer.isMoving
        val previous = lastClick
        if (previous != null && moving && (click == previous || player.getLongestDelta(previous) > LEG_NEAR)) {
            awaitLeg(script, previous, route, done)
            return Step.CONTINUE
        }
        if (previous != null && !moving && click == previous && now - lastClickAt < DUPLICATE_CLICK_MS || now < nextClick) {
            script.delay(POLL_MS.toInt())
            return Step.CONTINUE
        }
        nextClick = now + clickGap(CLICK_GAP_MS)
        if (!walkTo(click, random(100) < PlayerProfiles.get().minimapWalkPerc)) return Step.FALLBACK
        walk.clicked(player, click)
        pendingRecord?.noteWalkClick()
        lastClick = click
        lastClickAt = now
        awaitLeg(script, click, route, done)
        return Step.CONTINUE
    }

    private suspend fun awaitLeg(script: Script, click: Tile, route: List<Tile>, done: () -> Boolean) {
        script.delayUntil(LEG_TIMEOUT_MS, POLL_MS.toInt()) {
            val tile = localPlayer.tile
            track(tile)
            watchPending(tile)
            script.stopped || done() || near(tile, click, LEG_NEAR) || hopDue() || pendingKind == null && justLanded() ||
                route.isNotEmpty() && !localPlayer.isMoving && System.currentTimeMillis() - lastClickAt > LEG_STALL_MS
        }
    }

    private fun near(tile: Tile, target: Tile, distance: Int) = tile.plane == target.plane && tile.getLongestDelta(target) <= distance

    private fun justLanded() = System.currentTimeMillis() - lastHopAt < POLL_MS * 2

    private fun hopDue(): Boolean {
        if (pendingKind != null || !surgeEnabled && !diveEnabled) return false
        val now = System.currentTimeMillis()
        if (now < nextAbilityCheck || now < hopHoldUntil) return false
        return surgeEnabled && actionbarAbilities[Ability.SURGE.type] != null && usable(Ability.SURGE, surge = true) ||
            diveEnabled && diveAbility?.let { usable(it, surge = false) } == true
    }

    private fun castAlongRoute(route: List<Tile>, nearest: Int, player: Tile): Boolean {
        if (!surgeEnabled && !diveEnabled || pendingKind != null) return false
        val now = System.currentTimeMillis()
        if (now - LiveWalkMap.lastTeleportAt < LANDED_HOLD_MS) return false
        if (player == missedFrom && now - missedAt < MISSED_RETRY_MS) return false
        if (now < nextAbilityCheck || now < hopHoldUntil) return false
        val boosted = burstActive()
        nextAbilityCheck = now + if (boosted) BOOSTED_CHECK_MS else ABILITY_CHECK_MS
        if (route.size - nearest < HopPlanner.DIVE_MIN_GAIN + 2) return false

        val ahead = route[minOf(route.size - 1, nearest + HopPlanner.SURGE_PROGRESS_AHEAD)]
        val surgeReady = surgeEnabled && actionbarAbilities[Ability.SURGE.type] != null && usable(Ability.SURGE, surge = true)
        val diveType = diveAbility
        val diveReady = diveEnabled && diveType != null && usable(diveType, surge = false)

        if (surgeReady) {
            val chained = now < chainUntil && lastClick == null
            val plan = if (chained) null else walk.fires(player, null, surgeReach())
            val choice = when {
                plan?.fires != null -> planner.surgeOnWalk(plan.fires, route, nearest, ahead)
                plan?.hold == true -> HopChoice.held(plan.why ?: "the walk has not started")
                else -> planner.surgeByLastStep(surgeDirection(player), chained, route, nearest, player, ahead)
            }
            if (choice.cast) {
                println("[$owner] surge from $player facing ${choice.direction}, predicted landing ${choice.landing} (gains ${choice.gain})")
                if (surge()) {
                    pending(HopExplainer.SURGE, player, choice.landing, choice.direction, null)
                    markSpent(surge = true)
                    chainUntil = 0
                    return true
                }
            }
        }

        if (diveReady) {
            val arming = walk.ahead(player) ?: planner.armingTiles(player, lastClick)
            val choice = planner.dive(route, nearest, player, arming, ahead)
            val aim = choice.aim
            if (choice.cast && aim != null) {
                val chain = if (surgeEnabled && Ability.SURGE.offCdIgnoreGCD) planner.chainedSurge(route, nearest, player, choice.landing) else null
                println("[$owner] dive from $player toward $aim, predicted landing ${choice.landing} (gains ${choice.gain})")
                if (dive(aim)) {
                    pending(HopExplainer.DIVE, player, choice.landing, null, aim)
                    markSpent(surge = false)
                    if (chain != null) {
                        chainAfterDive = true
                        chainUntil = now + CHAIN_WAIT_MS
                    }
                    return true
                }
            }
        }
        if (surgeReady) chainUntil = 0
        if (surgeReady || diveReady) hopHoldUntil = now + if (boosted) BOOSTED_CHECK_MS else HOP_HOLD_MS
        return false
    }

    private fun pending(kind: String, from: Tile, predicted: Tile?, direction: Direction?, aim: Tile?) {
        pendingKind = kind
        pendingFrom = from
        pendingPredicted = predicted
        pendingDirection = direction
        pendingAt = System.currentTimeMillis()
        pendingCooldownSeen = false
        walk.hopCast()
        pendingRecord = runCatching {
            Hops.recorder.begin(kind, from, direction, aim, map, npcs.values.map { it.tile })
        }.getOrNull()
    }

    private fun watchPending(player: Tile) {
        val kind = pendingKind ?: return
        val from = pendingFrom ?: return
        val now = System.currentTimeMillis()
        pendingRecord?.observe(player)
        val ability = if (kind == HopExplainer.SURGE) Ability.SURGE else diveAbility
        if (ability != null && !ability.offCdIgnoreGCD) pendingCooldownSeen = true
        val predicted = pendingPredicted
        val refusal = if (kind == HopExplainer.SURGE) SURGE_REFUSED_MSG else DIVE_REFUSED_MSG
        when {
            predicted != null && predicted != from && near(player, predicted, 1) -> settle(player, HopExplainer.LANDED)
            RecentChat.containsSince(refusal, pendingAt) -> {
                missedFrom = from
                missedAt = now
                settle(null, HopExplainer.REFUSED)
            }
            pendingCooldownSeen && now - pendingAt >= SETTLE_MS && now - tileChangedAt >= STABLE_MS -> settle(player, HopExplainer.LANDED)
            !pendingCooldownSeen && now - pendingAt >= MISS_CHECK_MS && !(kind == HopExplainer.SURGE && doubleSurge()) -> {
                missedFrom = from
                missedAt = now
                clearSpent(kind == HopExplainer.SURGE)
                settle(null, HopExplainer.NO_CAST)
            }
            now - pendingAt >= LANDING_TIMEOUT_MS -> settle(player, HopExplainer.LANDED)
        }
    }

    private fun settle(landed: Tile?, outcome: String) {
        val from = pendingFrom
        val direction = if (landed != null && from != null && landed != from) HopModel.dir8(landed.x - from.x, landed.y - from.y) else null
        println("[$owner] ${pendingKind} from $from: predicted $pendingPredicted, $outcome ${landed ?: ""}")
        if (direction != null) {
            lastHopDirection = direction
            lastHopAt = System.currentTimeMillis()
        }
        walk.hopSettled(landed, direction)
        pendingRecord?.finish(landed, outcome)
        pendingRecord = null
        pendingKind = null
        chainAfterDive = false
    }

    private fun track(tile: Tile) {
        val now = System.currentTimeMillis()
        if (track.lastOrNull()?.second != tile) tileChangedAt = now
        track.addLast(now to tile)
        while (track.isNotEmpty() && now - track.first().first > TRACK_MS) track.removeFirst()
    }

    /** The way a surge goes: the player's latest step; a hop counts as a step its own way while the walk keeps to it. */
    private fun surgeDirection(player: Tile): Direction? {
        val newest = track.lastOrNull()?.second ?: player
        val previous = track.reversed().map { it.second }.firstOrNull { it != newest }
        if (previous != null && previous.plane == newest.plane) {
            val dx = newest.x - previous.x
            val dy = newest.y - previous.y
            if (max(abs(dx), abs(dy)) > 2) return hopFacing(HopModel.dir8(dx, dy), newest)
            if (dx == 0 || dy == 0 || abs(dx) == abs(dy)) return HopModel.direction(dx.sign, dy.sign)
            return null
        }
        return if (System.currentTimeMillis() - lastHopAt <= HOP_FACING_MS) hopFacing(lastHopDirection, newest) else null
    }

    private fun hopFacing(direction: Direction?, here: Tile): Direction? =
        direction?.takeIf { HopPlanner.walkRunsAlong(it, here, lastClick) }

    private fun heading(): Double {
        val (at, now) = track.lastOrNull() ?: return -1.0
        val then = track.reversed().firstOrNull { at - it.first >= HEADING_WINDOW_MS }?.second ?: return -1.0
        if (then.plane != now.plane || now.getLongestDelta(then) < 2) return -1.0
        return HopModel.bearing(then, now)
    }

    private fun maybeDrinkAcceleration(route: List<Tile>, nearest: Int, player: Tile): Boolean {
        if (!accelerationPotion || !surgeEnabled && !diveEnabled) return false
        val now = System.currentTimeMillis()
        if (now < nextBurstCheck) return false
        nextBurstCheck = now + BURST_CHECK_MS
        if (burstActive() || Effect.POWERBURST_POTION_IS_ON_COOLDOWN.active) return false
        val potion = runCatching { inventory.firstOrNull { it.id != -1 && it.name.startsWith(ACCELERATION_POTION) } }.getOrNull() ?: return false
        val diveType = diveAbility
        if (Ability.SURGE.offCdIgnoreGCD || diveType?.offCdIgnoreGCD == true) return false
        val anyway = listOfNotNull(Ability.SURGE, diveType).count { it.cooldownTicksIgnoreGCD * TICK_MS < AccelerationPlanner.BURST_MS }
        val why = StringBuilder()
        val estimate = acceleration.evaluate(route, nearest, player, heading(), surgeEnabled, diveEnabled && diveType != null, anyway, why)
            ?: return false
        println("[$owner] drinking a Powerburst of acceleration: ${estimate.hops} hops save ${"%.1f".format(estimate.savingSeconds)} s")
        if (!potion.click("Drink")) return false
        drankAt = now
        return true
    }

    private fun usable(ability: Ability, surge: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val ready = ability.offCdIgnoreGCD && !spent(ability, surge)
        if (!ready) {
            if (surge) surgeUsableAt = 0 else diveUsableAt = 0
            return false
        }
        val usableAt = if (surge) surgeUsableAt else diveUsableAt
        if (usableAt == 0L) {
            val at = now + readyDelay()
            if (surge) surgeUsableAt = at else diveUsableAt = at
            return false
        }
        return now >= usableAt
    }

    private fun spent(ability: Ability, surge: Boolean): Boolean {
        if (surge && doubleSurge()) return false
        val at = if (surge) surgeSpentAt else diveSpentAt
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > SPENT_MAX_MS || !ability.offCdIgnoreGCD) {
            clearSpent(surge)
            return false
        }
        return true
    }

    private fun markSpent(surge: Boolean) {
        if (surge) surgeSpentAt = System.currentTimeMillis() else diveSpentAt = System.currentTimeMillis()
        if (surge) surgeUsableAt = 0 else diveUsableAt = 0
    }

    private fun clearSpent(surge: Boolean) {
        if (surge) surgeSpentAt = 0 else diveSpentAt = 0
    }

    private fun readyDelay(): Long {
        val (mean, low, high) = if (burstActive()) Triple(BOOSTED_DELAY_MEAN_MS, BOOSTED_DELAY_MIN_MS, BOOSTED_DELAY_MAX_MS)
        else Triple(READY_DELAY_MEAN_MS, READY_DELAY_MIN_MS, READY_DELAY_MAX_MS)
        val sd = mean / 3
        return nonProfiledGaussian(mean.toInt(), (sd * sd).toInt()).toLong().coerceIn(low, high)
    }

    private fun clickGap(mean: Long): Long {
        val sd = mean / 4
        return nonProfiledGaussian(mean.toInt(), (sd * sd).toInt()).toLong().coerceAtLeast(mean / 4)
    }

    private fun surgeReach(): Int = if (ServerTick.millisSinceTick < EARLY_CAST_MS) 0 else ServerWalk.TICK_STEPS

    companion object {
        /** The walk the server is running, shared by every walker: the last click is the player's, whoever sent it. */
        val walk = ServerWalk(LiveWalkMap)

        private const val FAR_OFF_ROUTE = 30
        private const val LEG_NEAR = 3
        private const val POLL_MS = 50L
        private const val LEG_TIMEOUT_MS = 8_000L
        private const val LEG_STALL_MS = 1_200L
        private const val DUPLICATE_CLICK_MS = 1_500L
        private const val CLICK_GAP_MS = 300L
        private const val ABILITY_CHECK_MS = 300L
        private const val BOOSTED_CHECK_MS = 150L
        private const val HOP_HOLD_MS = 600L
        private const val BURST_CHECK_MS = 1_000L
        private const val READY_DELAY_MEAN_MS = 700L
        private const val READY_DELAY_MIN_MS = 150L
        private const val READY_DELAY_MAX_MS = 2_500L
        private const val BOOSTED_DELAY_MEAN_MS = 150L
        private const val BOOSTED_DELAY_MIN_MS = 60L
        private const val BOOSTED_DELAY_MAX_MS = 300L
        private const val LANDED_HOLD_MS = 1_200L
        private const val MISSED_RETRY_MS = 6_000L
        private const val SPENT_MAX_MS = 2_500L
        private const val MISS_CHECK_MS = 1_500L
        private const val SETTLE_MS = 900L
        private const val STABLE_MS = 300L
        private const val LANDING_TIMEOUT_MS = 3_000L
        private const val CHAIN_WAIT_MS = 2_000L
        private const val TRACK_MS = 2_000L
        private const val HEADING_WINDOW_MS = 450L
        private const val HOP_FACING_MS = 1_500L
        private const val EARLY_CAST_MS = 450L
        private const val TICK_MS = 600
        private const val ACCELERATION_POTION = "Powerburst of acceleration"
        private const val DIVE_REFUSED_MSG = "You can't dive through"
        private const val SURGE_REFUSED_MSG = "Destination unreachable"

        @Volatile
        private var surgeUsableAt = 0L

        @Volatile
        private var diveUsableAt = 0L

        @Volatile
        private var surgeSpentAt = 0L

        @Volatile
        private var diveSpentAt = 0L

        @Volatile
        private var missedFrom: Tile? = null

        @Volatile
        private var missedAt = 0L

        @Volatile
        private var chainUntil = 0L

        @Volatile
        private var drankAt = 0L

        private val doubleSurgeVarbit = FeatureIds("WebWalker") { Gameval.requireId(Gameval.VARBIT, "dino_agility_double_surge_unlocked") }

        private fun doubleSurge(): Boolean = doubleSurgeVarbit.get()?.let { varps.getVarBit(it) > 0 } == true

        fun burstActive(): Boolean = System.currentTimeMillis() - drankAt < AccelerationPlanner.BURST_MS
    }
}
