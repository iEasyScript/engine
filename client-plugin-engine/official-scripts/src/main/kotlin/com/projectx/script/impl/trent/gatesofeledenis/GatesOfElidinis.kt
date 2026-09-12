package com.projectx.script.impl.trent.gatesofeledenis

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.MainState
import com.projectx.game.interfaces.InstanceSystem
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.tileOfLocal
import com.projectx.pathfinder.findClosestSafeTile
import com.projectx.pathfinder.routeToTile
import com.projectx.pathfinder.routedDestination
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Hitsplat
import com.projectx.script.impl.trent.gatesofeledenis.ProcessFight.currentTarget
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.dsl.scopes.button
import com.projectx.ui.backend.dsl.scopes.child
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.sameLine
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.Effect
import world.gregs.voidps.collision.CollisionStrategies
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.gameval.Gameval.LOC
import world.gregs.voidps.gameval.Gameval.NPC as NPC_TYPE
import world.gregs.voidps.gameval.Gameval.OBJ
import world.gregs.voidps.gameval.Gameval.VAR_PLAYER
import world.gregs.voidps.path.toTiles
import world.gregs.voidps.type.Tile

private fun componentSlot(name: String) =
    Gameval.requireComponentHash(name).let { IFSlot(it shr 16, it and 0xFFFF) }

private val PILLAR_JUMP_POINTS = (1..4).map { Gameval.requireId(LOC, "elidinis_boss_agility_pillars_jump_point_$it") }
private val BOSS_ENTRANCE = Gameval.requireId(LOC, "elidinis_boss_entrance")
internal val GATE_NPC_IDS = intArrayOf(
    Gameval.requireId(NPC_TYPE, "elidinis_boss_noncorrupt"),
    Gameval.requireId(NPC_TYPE, "elidinis_boss_corrupt")
)
private val ICTHLARIN = Gameval.requireId(NPC_TYPE, "elidinis_boss_icthlarin")
private val MOONSTONE_FRAGMENTS = Gameval.requireId(OBJ, "elidinis_boss_moonstone")
private val STATUE_PIECE = Gameval.requireId(OBJ, "elidinis_boss_statue_piece")
private val BARRIER_VARP = Gameval.requireId(VAR_PLAYER, "activity_progress_bar_extension_progress_bar_value")
private val BARRIER_MAX_VARP = Gameval.requireId(VAR_PLAYER, "activity_progress_bar_extension_progress_bar_max")
private val SPEC_ATTACKS_VARP = Gameval.requireId(VAR_PLAYER, "activity_progress_bar_extension_progress_bar_2_value")
private val SPEC_ATTACKS_MAX_VARP = Gameval.requireId(VAR_PLAYER, "activity_progress_bar_extension_progress_bar_2_max")
private val PILLAR_PROGRESS_VARP = Gameval.requireId(VAR_PLAYER, "elidinis_boss_agility_pillars_player_progress")
private val EXTRA_ACTION_VARP = Gameval.requireId(VAR_PLAYER, "toplevel_v2_extra_action_button_id")
private val CORRUPTION_VARP = Gameval.requireId(VAR_PLAYER, "elidinis_boss_debuff_corruption_stacks")
private val EXTRA_ACTION_BUTTON = componentSlot("toplevel_v2_extra_action_button:click_layer")
private val HEALTH_BUTTON = componentSlot("toplevel_v2_combat_bar:health_button_anim_layer")
private val TIMER_HEADBAR = Gameval.requireId("headbar", "timer_120")

internal const val MOONSTONE = "Moonstone"
private const val CLEANSED_SHARD = "Cleansed shard of Elidinis"
private const val CORRUPT_SHARD = "Corrupt shard of Elidinis"
private const val CONDUIT = "Moonstone conduit"
internal val SHARD_NAMES = setOf(CLEANSED_SHARD, CORRUPT_SHARD)

internal val WATCHED_VARPS = listOf(
    "barrier" to BARRIER_VARP,
    "barrierMax" to BARRIER_MAX_VARP,
    "specAttacks" to SPEC_ATTACKS_VARP,
    "pillarProgress" to PILLAR_PROGRESS_VARP,
    "extraAction" to EXTRA_ACTION_VARP,
    "corruption" to CORRUPTION_VARP
)
private const val STATUE_PIECE_DAMAGE = 3125
private const val YIKES_PROJECTILE = 8361

private class BlastKind(val radius: Int, val reactMillis: Long, val walkOutTiles: Int, val detonateMillis: Long)

private val BLAST_KINDS = mapOf(
    8279 to BlastKind(radius = 1, reactMillis = 1600L, walkOutTiles = 2, detonateMillis = 2555L),
    8285 to BlastKind(radius = 4, reactMillis = 1400L, walkOutTiles = 2, detonateMillis = 2597L)
)

private const val DIVE_RANGE = 10
private const val RUSH_OVER_TILES = 4
private const val DEFAULT_WALK_OUT_TILES = 2
private const val FAILED_STARTS_BEFORE_RESET = 3
private const val CHANNEL_IDLE_MILLIS = 9000L
private const val WORK_SETTLE_MILLIS = 1698L
private const val RE_ENGAGE_POLL_MILLIS = 40
private const val RE_ENGAGE_LEAD_MILLIS = 700L
private const val NODE_TIMEOUT_MILLIS = 10000L
private const val REPAIR_TIMEOUT_MILLIS = 12000L
private const val BARRIER_REPAIR_PERCENT = 10.0
private const val FULL_ADRENALINE = 100.0
private const val FRAGMENT_RESERVE = 10
private const val DUNK_PHASE_TIMEOUT_MILLIS = 45_000L
private const val DUNK_HEAL_PERCENT = 35.0
private const val MIN_SHARDS_TO_DUNK = 18
private const val OPENING_FRAGMENTS = 40
private const val BARRIER_FULL_PERCENT = 95.0
private const val CONDUIT_REACH = 2
private const val ABILITY_GRACE_MILLIS = 5000L
private const val DIVE_TO_SURGE_MILLIS = 67
private const val DIVE_TO_SURGE_SPREAD = 18

private const val EVENT_PANEL_ROWS = 4000
private const val WINDOW_WIDTH = 620f
private const val WINDOW_HEIGHT = 1000f
private const val CONDUIT_LINE_Y_IN_MAPSQUARE = 26
private const val REFUGE_Y_IN_MAPSQUARE = 23

private object Activity {
    private const val HISTORY_SIZE = 14

    var current = "starting"
        private set
    private var since = System.currentTimeMillis()
    val history = ArrayDeque<Pair<String, Long>>()

    val elapsedMillis get() = System.currentTimeMillis() - since

    fun set(what: String) {
        if (what == current) return
        history.addFirst(current to elapsedMillis)
        while (history.size > HISTORY_SIZE) history.removeLast()
        current = what
        since = System.currentTimeMillis()
    }
}

@ScriptDescription(
    name = "Gates of Elidinis",
    version = "1.0.0",
    author = "Trent",
    description = "Closes the gates of eledenis"
)
class GatesOfElidinis : StateMachineScript<GatesOfElidinis>(), ConfigurableScript {
    val farmCraftingXp = BooleanConfigItem(
        name = "Farm Crafting XP",
        description = "Farms moonstone fragments for crafting XP instead of killing boss.",
        initialValue = false
    )

    val observeOnly = BooleanConfigItem(
        name = "Observe only",
        description = "Records everything the boss does without automating, for manual reference kills.",
        initialValue = false
    )

    val recordEvents = BooleanConfigItem(
        name = "Record events",
        description = "Samples every boss event each frame for debugging. Costs a full scene scan per frame.",
        initialValue = false
    )

    val targetFragments get() = if (farmCraftingXp.value) 50000 else OPENING_FRAGMENTS

    private val bossNpc: NPC?
        get() = npcs.values.firstOrNull { it.id in GATE_NPC_IDS }

    val chargingLaser
        get() = bossNpc?.headbars?.any {
            it.type == TIMER_HEADBAR && it.durationMillis == 24000L && it.timeLeftMillis > 0L
        } == true

    val pillarsUp
        get() = bossNpc?.headbars?.any {
            it.type == TIMER_HEADBAR && it.durationMillis == 24000L &&
                    it.timeLeftMillis > 0L && it.timeLeftMillis < ((24000L - 7200L) + pillarTimeOffset)
        } == true

    val pillarTimeOffset: Long
        get() {
            val dist = tileOfLocal(31, 28, 2).getDistance(localPlayer.tile)
            return 7200L + when {
                dist <= 5 -> 0L
                dist <= 8 -> 2000L
                dist <= 12 -> 4000L
                else -> 10000L
            }
        }

    var instanceStartTime = System.currentTimeMillis()
    val instanceTimeRemaining get() = (55L * 60L * 1000L) - (System.currentTimeMillis() - instanceStartTime)

    override fun getStartState() = StartInstance

    private var startedAt = 0L
    var haltReason: String? = null
        private set

    override fun onStart() {
        startedAt = System.currentTimeMillis()
        haltReason = null
        super.onStart()
    }

    override suspend fun loop() {
        if (observeOnly.value) return delay(60)
        if (System.currentTimeMillis() - startedAt > ABILITY_GRACE_MILLIS) {
            val missing = buildList {
                if (diveAbility == null) add("Dive")
                if (actionbarAbilities[Ability.SURGE.type] == null) add("Surge")
            }
            if (missing.isNotEmpty()) {
                haltReason = "${missing.joinToString()} not on any action bar"
                println("Gates of Elidinis: $haltReason. The rotation depends on them; stopping.")
                stop()
                return
            }
        }
        super.loop()
    }

    override fun onEvent(event: Event) {
        if (event is Hitsplat) BossRecorder.add("HIT", "${event.damage} ${event.type}")
        super.onEvent(event)
    }

    override fun render() {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        if (recordEvents.value || observeOnly.value) BossRecorder.sample()
        ImGuiDsl.setNextWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT, ImGuiCond.FirstUseEver)
        ImGuiDsl.window("Gates of Elidinis##goe") {
            text("State: ${if (observeOnly.value) "OBSERVING" else currentState::class.simpleName}")
            haltReason?.let { text("HALTED: $it") }
            text("Doing: ${Activity.current}")
            text("For:   ${Activity.elapsedMillis} ms")
            separator()
            readout("goe-vitals") {
                valueRow("Gate", "$bossHealthCurrent")
                valueRow("Shards", "${inventory.count(STATUE_PIECE)} / $MIN_SHARDS_TO_DUNK")
                valueRow("Fragments", "$numFragments")
                valueRow("Barrier", "${barrierPercent.toInt()}%")
                valueRow("Corruption", "$corruptionStacks")
                valueRow("Health", "$healthCurrent / $healthMax")
                valueRow("Adrenaline", "${adrenaline.toInt()}%")
                valueRow("Dunk armed", "$extraActionArmed")
                valueRow("Dive", "${diveAbility?.name ?: "-"} ${diveStatus()}")
                valueRow("Surge", abilityStatus(Ability.SURGE))
                valueRow("Barricade", abilityStatus(Ability.BARRICADE))
            }
            separator()
            section("Recent")
            Activity.history.forEach { (what, millis) -> text("${millis.toString().padStart(6)} ms  $what") }
            separator()
            val dropped = if (BossRecorder.dropped > 0) ", ${BossRecorder.dropped} DROPPED" else ""
            section("Recorder (${BossRecorder.size} events$dropped)")
            button(if (BossRecorder.paused) "Resume" else "Pause") { BossRecorder.paused = !BossRecorder.paused }
            sameLine()
            button("Clear") { BossRecorder.clear() }
            sameLine()
            button("Dump") {
                BossRecorder.all().forEach { println("[GoE] ${it.millis} ${it.cycle} ${it.kind} ${it.detail}") }
            }
            child("goe-events") {
                BossRecorder.recent(EVENT_PANEL_ROWS).forEach {
                    text("${it.millis.toString().padStart(7)}  ${it.kind.padEnd(10)} ${it.detail}")
                }
            }
        }
    }
}

object StartInstance : State<GatesOfElidinis>() {
    override suspend fun GatesOfElidinis.checkNext() = if (inInstancedArea) {
        instanceStartTime = System.currentTimeMillis()
        StartFight()
    } else null

    override suspend fun GatesOfElidinis.stateLoop() {
        Activity.set("open instance")
        if (!InstanceSystem.isOpen() && interactClosestObject(BOSS_ENTRANCE, "Enter"))
            return delayUntil(5000) { InstanceSystem.isOpen() }
        else {
            InstanceSystem.startInstance()
            delay(1200, 3000)
        }
    }
}

class StartFight : State<GatesOfElidinis>() {
    private var failedStarts = 0

    override suspend fun GatesOfElidinis.checkNext() =
        if (fightActive)
            ProcessFight
        else if (!inInstancedArea)
            StartInstance
        else null

    override suspend fun GatesOfElidinis.stateLoop() {
        if (instanceTimeRemaining <= 0 || failedStarts >= FAILED_STARTS_BEFORE_RESET) {
            Activity.set("leave for fresh instance")
            if (continueDialogueContaining("Yes.")) {
                delayUntil(5000) { !inInstancedArea }
                return
            }
            if (interactClosestNPC(ICTHLARIN, "Leave"))
                delayUntil(5000) { dialogueOptionVisible("Yes.") }
            return
        }
        if (localPlayer.tile != tileOfLocal(39, 8, 2)) {
            Activity.set("walk to Icthlarin")
            travelTo(tileOfLocal(39, 8, 2))
            waitThenDelayUntil(1200, 2500) { !localPlayer.isAniMoving }
            return
        }
        if (healthCurrent < healthMax && adrenaline >= 90 && !inCombat) {
            Activity.set("regen health")
            HEALTH_BUTTON.click(1)
            delay(500, 400)
        }
        if (fightOnCooldown) {
            Activity.set("wait respawn cooldown")
            return delay(500, 400)
        }
        Activity.set("start fight")
        if (interactClosestNPC(ICTHLARIN, "Start")) {
            waitThenDelayUntil(1000, 3200) { fightActive }
            if (fightActive) failedStarts = 0 else failedStarts++
        }
    }
}

object GatherFragments : State<GatesOfElidinis>() {
    override suspend fun GatesOfElidinis.checkNext() =
        if (!fightActive || !inArena || healthCurrent <= 0)
            StartFight()
        else if (pillarsUp)
            DunkAndHeal()
        else if (numFragments >= targetFragments)
            ProcessFight
        else null

    override suspend fun GatesOfElidinis.stateLoop() {
        dodgeAoes()
        val anchor = if (attacksBeforeSpec in 1..<10) localPlayer.tile else tileOfLocal(38, 9, 2)
        val moonstone = findClosestObjectToTile(anchor) { it.name() == MOONSTONE } ?: return delay(210, 100)
        channelNode(moonstone, "Gather")
    }
}

private suspend fun GatesOfElidinis.channelNode(node: SceneObject, option: String) {
    Activity.set("gather moonstone")
    if (!node.interact(option)) return delay(300, 150)
    var yielded = numFragments
    while (node.exists && !gatheringInterrupted()) {
        waitThenDelayUntil(WORK_SETTLE_MILLIS, CHANNEL_IDLE_MILLIS) {
            numFragments != yielded || gatheringInterrupted() || !node.exists
        }
        if (numFragments == yielded && !localPlayer.isAniMoving) return
        yielded = numFragments
    }
}

private fun GatesOfElidinis.gatheringInterrupted() =
    numFragments >= targetFragments || inAoe || pillarsUp || findAkh() != null

object ProcessFight : State<GatesOfElidinis>() {
    internal var currentTarget: SceneObject? = null

    override suspend fun GatesOfElidinis.checkNext() =
        if (!fightActive || !inArena)
            StartFight()
        else if (pillarsUp)
            DunkAndHeal()
        else if (numFragments < FRAGMENT_RESERVE)
            GatherFragments
        else null

    override suspend fun GatesOfElidinis.stateLoop() {
        if (!Effect.ENHANCED_EXCALIBUR.active && healthMax - healthCurrent > 1500)
            inventory.getItem("Augmented enhanced Excalibur")?.click("Activate")

        dodgeAoes(currentTarget)
        if (fleeCriticalDanger()) return
        if (shouldDunk()) {
            Activity.set("trigger dunk (${inventory.count(STATUE_PIECE)} shards)")
            EXTRA_ACTION_BUTTON.click(1)
            delayUntil(2000) { chargingLaser || !extraActionArmed }
        }
        if (barrierPercent <= BARRIER_REPAIR_PERCENT) return repairBarrier()
        if (killAkhs()) return

        val wasInDanger = inCriticalDanger
        currentTarget?.takeIf { it.exists && it.name() in SHARD_NAMES }?.let {
            if (wasInDanger && inDangerous(it.tile)) return delay(300, 150)
            gatherNode(if (it.name() == CORRUPT_SHARD) "Transmute" else "Mine", it, wasInDanger)
            return
        }
        if (findAndGatherNode(CLEANSED_SHARD, "Mine", wasInDanger)) return
        if (findAndGatherNode(CORRUPT_SHARD, "Transmute", wasInDanger)) return
        val moonstone = findClosestObjectToTile(localPlayer.tile) { it.name() == MOONSTONE }
            ?: return delay(210, 502)
        channelNode(moonstone, "Gather")
    }
}

private suspend fun GatesOfElidinis.findAndGatherNode(
    nodeName: String,
    option: String,
    wasInDanger: Boolean
): Boolean {
    val searchFrom = localPlayer.tile
    val node = currentTarget?.takeIf { it.exists && it.name() == nodeName }
        ?: if (wasInDanger)
            findClosestNode(nodeName, searchFrom, safeZone = true)
        else
            findClosestNode(nodeName, searchFrom, safeZone = false)
                ?: findClosestNode(nodeName, searchFrom, safeZone = true)

    node ?: return false
    gatherNode(option, node, wasInDanger)
    return true
}

private suspend fun GatesOfElidinis.gatherNode(option: String, node: SceneObject, wasInDanger: Boolean) {
    val targetTile = routedDestination(localPlayer.tile, node, collision = CollisionStrategies.NOCLIP)
    if (targetTile?.let { blockedForReEngage(it) } == true || blockedForReEngage(node.tile))
        return delay(RE_ENGAGE_POLL_MILLIS)

    val startedAs = node.name()
    val alreadyWorking = currentTarget?.tile == node.tile && localPlayer.isAniMoving
    currentTarget = node
    Activity.set("$option ${startedAs.substringBefore(' ')} @${node.tile.x},${node.tile.y}")
    if (!alreadyWorking && !node.interact(option)) return delay(300, 150)
    waitThenDelayUntil(WORK_SETTLE_MILLIS, NODE_TIMEOUT_MILLIS) {
        !fightActive || inAoe || pillarsUp ||
                (!wasInDanger && inCriticalDanger) ||
                !node.exists || node.name() != startedAs ||
                findAkh() != null || shouldDunk() ||
                !localPlayer.isAniMoving
    }
}

private fun findAkh() = findClosestNPC { it.name == "Feline akh" && it.currentHealth > 0 }

private suspend fun GatesOfElidinis.killAkhs(): Boolean {
    val akh = findAkh() ?: return false
    while (akh.exists() && akh.currentHealth > 0 && !inAoe && !pillarsUp) {
        Activity.set("dismiss akh")
        if (!akh.interact("Dismiss")) break
        delay(600, 350)
    }
    return true
}

private suspend fun GatesOfElidinis.repairBarrier() {
    val conduit = findClosestReachableNPC(CONDUIT, 40) ?: findClosestNPC(CONDUIT, 40) ?: return
    if (conduit.tile.getDistance(localPlayer.tile) > CONDUIT_REACH) {
        Activity.set("rush to conduit")
        travelTo(conduit.tile)
        waitThenDelayUntil(600, 5000) {
            conduit.tile.getDistance(localPlayer.tile) <= CONDUIT_REACH || !localPlayer.isAniMoving
        }
        return
    }
    Activity.set("repair conduit")
    if (!conduit.interact("Repair")) return delay(300, 150)
    waitThenDelayUntil(WORK_SETTLE_MILLIS, REPAIR_TIMEOUT_MILLIS) {
        barrierPercent >= BARRIER_FULL_PERCENT || numFragments <= 0 || pillarsUp || inAoe || !localPlayer.isAniMoving
    }
}

class DunkAndHeal : State<GatesOfElidinis>() {
    var dunked = false
    var madeIt = false
    var tanked = false
    private val enteredAt = System.currentTimeMillis()

    private val stalled get() = System.currentTimeMillis() - enteredAt > DUNK_PHASE_TIMEOUT_MILLIS

    override suspend fun GatesOfElidinis.checkNext() =
        if (!fightActive || !inArena)
            StartFight()
        else if ((dunked && tanked) || stalled)
            ProcessFight
        else null

    override suspend fun GatesOfElidinis.stateLoop() {
        if (stalled) return
        dodgeAoes()
        killAkhs()
        if (!dunked) {
            val firstPillar = PILLAR_JUMP_POINTS.first()
            val nearPillars = tileOfLocal(31, 25, 2)
            if (findClosestObject(firstPillar, range = 8) == null && nearPillars.getDistance(localPlayer.tile) > 3) {
                Activity.set("run to pillars")
                if (travelTo(nearPillars.randomize(1)))
                    waitThenDelayUntil(1500, pollingDelayMillis = 600) {
                        stalled || inAoe || !localPlayer.isAniMoving || findClosestObject(firstPillar, range = 8) != null
                    }
                else
                    delay(101)
                return
            }
            if (findClosestObject(firstPillar) != null) dunkIt() else delay(100)
            return
        }
        if (!tanked && !localPlayer.isAnimating) {
            val safeTile = tileOfLocal(31, 14, 2)
            if (localPlayer.tile != safeTile && !madeIt) {
                Activity.set("take cover for laser")
                if (travelTo(safeTile))
                    waitThenDelayUntil(600, 8000) { stalled || localPlayer.tile == safeTile || !localPlayer.isAniMoving }
                else
                    delay(100)
            } else
                madeIt = true
            if (madeIt) {
                val node = findClosestNode(CORRUPT_SHARD, tileOfLocal(33, 13, 2)) ?: return
                if (node.tile == tileOfLocal(33, 13, 2)) {
                    Activity.set("transmute under cover")
                    if (node.interact("Transmute"))
                        waitThenDelayUntil(WORK_SETTLE_MILLIS, NODE_TIMEOUT_MILLIS) { stalled || !chargingLaser }
                }
            }
            if (!chargingLaser) {
                Activity.set("hold cover for beam")
                delay(1200, 400)
                tanked = true
            }
        }
    }

    private suspend fun GatesOfElidinis.dunkIt(): Boolean {
        while (dunkStage <= 3) {
            if (stalled) break
            val prev = dunkStage
            Activity.set("jump pillar ${dunkStage + 1}/4")
            if (interactClosestObject(PILLAR_JUMP_POINTS.getOrNull(dunkStage) ?: return false, "Jump"))
                delayUntil(4000) { prev != dunkStage }
            else
                delay(100)
        }
        delayUntil(2000) { dunkStage == 4 }
        Activity.set("cleanse")
        if (dunkStage == 4 && findClosestNPCWithOption("Cleanse", 30)?.interact("Cleanse") == true) {
            delayUntil(10000) { dunkStage == 5 }
            dunked = true
        }
        return true
    }
}

private val fightActive
    get() = npcs.values.any { it.id == ICTHLARIN && it.hiddenMenuOpFlags and 1 != 0 }

private val fightOnCooldown
    get() = npcs.values.any { npc -> npc.id == ICTHLARIN && npc.headbars.any { it.timeLeftMillis > 0 } }

private val barrierPercent
    get() = varps.getVar(BARRIER_MAX_VARP).let { max ->
        if (max <= 0) 100.0 else (varps.getVar(BARRIER_VARP).toDouble() / max.toDouble()) * 100.0
    }

private val attacksBeforeSpec
    get() = varps.getVar(SPEC_ATTACKS_MAX_VARP) - varps.getVar(SPEC_ATTACKS_VARP)

private val dunkStage get() = varps.getVar(PILLAR_PROGRESS_VARP)

private val extraActionArmed get() = varps.getVar(EXTRA_ACTION_VARP) != 0

private val corruptionStacks get() = varps.getVar(CORRUPTION_VARP)

private val numFragments get() = inventory.count(MOONSTONE_FRAGMENTS)

private val inArena
    get() = localPlayer.localTile.x >= 11 && localPlayer.localTile.x <= 51 && localPlayer.localTile.y >= 6

private fun inDangerous(tile: Tile) = tile.yInMapSquare >= CONDUIT_LINE_Y_IN_MAPSQUARE

private val inCriticalDanger
    get() = !Effect.BARRICADE.active && projectiles.any {
        it.id == YIKES_PROJECTILE && it.lockedOnto(localPlayer) && it.tile.withinDistance(localPlayer.tile, 15)
    }

private fun diveStatus() = diveAbility?.let { abilityStatus(it) } ?: "not on bar"

private fun abilityStatus(ability: Ability) = when {
    actionbarAbilities[ability.type] == null -> "not on bar"
    !ability.offCdIgnoreGCD -> "cooldown"
    else -> "ready"
}

private fun barricade() =
    adrenaline >= FULL_ADRENALINE && Ability.BARRICADE.offCdIgnoreGCD && castAbility(Ability.BARRICADE)

private suspend fun GatesOfElidinis.fleeCriticalDanger(): Boolean {
    if (!inCriticalDanger || !inDangerous(localPlayer.tile)) return false
    if (barricade()) {
        Activity.set("barricade blast")
        return false
    }
    Activity.set("flee south of conduits")
    val here = localPlayer.tile
    val refuge = findClosestNode(CLEANSED_SHARD, here, safeZone = true)?.tile
        ?: findClosestNode(CORRUPT_SHARD, here, safeZone = true)?.tile
        ?: here.transform(0, REFUGE_Y_IN_MAPSQUARE - here.yInMapSquare)
    travelTo(refuge)
    waitThenDelayUntil(600, 6000) {
        !inDangerous(localPlayer.tile) || !inCriticalDanger || !localPlayer.isAniMoving
    }
    return true
}

private val inAoe get() = tileInAoe(localPlayer.tile)

private fun tileInAoe(tile: Tile) = spotAnims.any {
    val kind = BLAST_KINDS[it.id] ?: return@any false
    it.timeAliveMillis > kind.reactMillis && tile.withinDistance(it.tile, kind.radius)
}

private fun blockedForReEngage(tile: Tile) = spotAnims.any {
    val kind = BLAST_KINDS[it.id] ?: return@any false
    kind.detonateMillis - it.timeAliveMillis > RE_ENGAGE_LEAD_MILLIS && tile.withinDistance(it.tile, kind.radius)
}

private fun blastOnPlayer() = spotAnims
    .mapNotNull { BLAST_KINDS[it.id]?.takeIf { kind -> localPlayer.tile.withinDistance(it.tile, kind.radius) } }
    .maxByOrNull { it.radius }

private fun findClosestNode(name: String, tile: Tile, safeZone: Boolean? = null) = findClosestObjectToTile(tile) {
    it.name() == name && when (safeZone) {
        true -> !inDangerous(it.tile)
        false -> inDangerous(it.tile)
        null -> true
    }
}

private fun GatesOfElidinis.shouldDunk(): Boolean {
    if (!extraActionArmed || chargingLaser) return false
    val shards = inventory.count(STATUE_PIECE)
    return healthPercent <= DUNK_HEAL_PERCENT ||
            shards >= MIN_SHARDS_TO_DUNK ||
            (bossHealthCurrent + STATUE_PIECE_DAMAGE) < (shards * STATUE_PIECE_DAMAGE) ||
            inventory.freeSlots < 1
}

private val unclippedNodeTiles
    get() = getAllObjectsWithinRange(30)
        .filter { it.name() == MOONSTONE || it.name() in SHARD_NAMES }
        .flatMap { it.occupiedTiles() }
        .toSet() + npcs.values
        .filter { it.tile.withinDistance(localPlayer.tile, 30) }
        .flatMap { npc ->
            val size = npc.getDef().size.coerceIn(1, 5)
            (0 until size).flatMap { dx -> (0 until size).map { dy -> npc.tile.transform(dx, dy) } }
        }

private suspend fun GatesOfElidinis.travelTo(destination: Tile): Boolean {
    if (localPlayer.tile == destination) return true
    val path = routeToTile(localPlayer.tile, destination, moveNear = true).toTiles(destination.plane)
    if (path.size > RUSH_OVER_TILES) closeDistance(path)
    return walkTo(destination, false)
}

private suspend fun GatesOfElidinis.closeDistance(path: List<Tile>): Boolean {
    val blocked = unclippedNodeTiles
    val diveTarget = path.lastOrNull { it.getDistance(localPlayer.tile) <= DIVE_RANGE && it !in blocked }
        ?: return false
    val dived = dive(diveTarget)
    delay(DIVE_TO_SURGE_MILLIS, DIVE_TO_SURGE_SPREAD)
    val surged = surge()
    if (!dived) BossRecorder.add("DIVE-FAIL", diveStatus())
    if (!surged) BossRecorder.add("SURGE-FAIL", abilityStatus(Ability.SURGE))
    Activity.set("close ${path.size} tiles dive=$dived surge=$surged")
    return dived || surged
}

private suspend fun GatesOfElidinis.rushTo(destination: Tile): Boolean {
    if (localPlayer.tile == destination) return true
    val path = routeToTile(localPlayer.tile, destination, moveNear = true).toTiles(destination.plane)
    if (closeDistance(path)) return true
    return walkTo(destination, false)
}

private var dodgeDestination: Tile? = null

private suspend fun GatesOfElidinis.dodgeAoes(currentTarget: SceneObject? = null) {
    if (!inAoe) {
        dodgeDestination = null
        return
    }
    if (localPlayer.isMoving && dodgeDestination?.let { !tileInAoe(it) } == true) return
    val safeTile = safeTileFromAoes(
        currentTarget?.let { TileArea(it.tile, it.defs.sizeX, it.defs.sizeY) }
    ) ?: return
    dodgeDestination = safeTile
    val tiles = safeTile.getDistance(localPlayer.tile)
    if (tiles > (blastOnPlayer()?.walkOutTiles ?: DEFAULT_WALK_OUT_TILES)) {
        Activity.set("dive $tiles clear")
        if (dive(safeTile)) return
        BossRecorder.add("DIVE-FAIL", diveStatus())
        Activity.set("run $tiles clear - dive ${diveStatus()}")
    } else {
        Activity.set("step $tiles clear")
    }
    walkTo(safeTile, false)
}

private fun safeTileFromAoes(target: TileArea? = null): Tile? {
    if (!inAoe) return null
    val playerPos = localPlayer.tile
    val dangerZones = spotAnims.mapNotNull { BLAST_KINDS[it.id]?.let { kind -> DangerZone(it.tile, kind.radius) } }
    val obstacles = unclippedNodeTiles.map { TileArea(it, 1, 1) } + npcs.values
        .filter { it.tile.withinDistance(playerPos, 15) }
        .map { npc -> npc.getDef().size.coerceIn(1, 5).let { TileArea(npc.tile, it, it) } }
    return calculateClosestSafeTile(playerPos, dangerZones, target ?: TileArea(playerPos, 1, 1), obstacles)
}
