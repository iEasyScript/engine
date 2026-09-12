package com.projectx.script.impl.devin.solak

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.allNpcsWithinRange

class LimbStatus(val limb: SolakLimb, val npc: NPC, val lifepoints: Int, val maxLifepoints: Int)

class TrackedBar(val label: String, val lifepoints: Int, val maxLifepoints: Int, val tracksCore: Boolean)

class SolakState(
    val teamSize: Int,
    val blightStacks: Int?,
    val blessingReady: Boolean,
    val extraActionArmed: Boolean,
    val boss: NPC?,
    val bossLifepoints: Int,
    val bossAnimation: Int,
    val telegraph: SolakTelegraph?,
    val dpsWindow: DpsWindow?,
    val trackedBar: TrackedBar?,
    val limbs: List<LimbStatus>,
    val core: NPC?,
    val coreLifepoints: Int,
    val coreMaxLifepoints: Int,
    val rootlings: List<NPC>,
    val lashers: List<NPC>
) {
    val windowLimbs: List<LimbStatus>
        get() = dpsWindow?.let { window -> limbs.filter { it.limb.window == window } } ?: emptyList()

    val focusLimb: LimbStatus?
        get() = windowLimbs.minByOrNull { it.lifepoints }
}

object SolakEncounter {

    private const val SCAN_RANGE = 40
    private const val TELEGRAPH_HOLD_MS = 2_400L

    /** The window ends on an explicit recovery animation; the cap only stops a missed one latching it. */
    private const val WINDOW_CAP_MS = 30_000L

    private const val STATE_ONE_LP = 200_000
    private const val STATE_TWO_LP = 100_000

    private var telegraph: SolakTelegraph? = null
    private var telegraphAt = 0L
    private var window: DpsWindow? = null
    private var windowAt = 0L

    fun reset() {
        telegraph = null
        telegraphAt = 0
        window = null
        windowAt = 0
    }

    fun read(): SolakState? {
        if (!SolakArena.encounterActive()) {
            reset()
            return null
        }
        val nearby = runCatching { allNpcsWithinRange(SCAN_RANGE) { it.exists() } }.getOrDefault(emptyList())
        val boss = nearby.firstOrNull { it.isType(SolakIds.BOSS) }
        val animation = boss?.let { runCatching { it.animationId }.getOrDefault(-1) } ?: -1
        advance(animation)

        val teamSize = readVarbit(SolakIds.TEAM_SIZE) ?: 0
        val core = nearby.firstOrNull { it.isType(SolakIds.CORE) }
        return SolakState(
            teamSize = teamSize,
            blightStacks = readVarbit(SolakIds.BLIGHT_STACKS),
            blessingReady = (readVarbit(SolakIds.NATURES_BLESSING) ?: 0) != 0,
            extraActionArmed = (readVarbit(SolakIds.EXTRA_ACTION_ACTIVE) ?: 0) != 0,
            boss = boss,
            bossLifepoints = boss.lifepoints(),
            bossAnimation = animation,
            telegraph = telegraph,
            dpsWindow = window,
            trackedBar = trackedBar(teamSize),
            limbs = limbs(nearby, teamSize),
            core = core,
            coreLifepoints = core.lifepoints(),
            coreMaxLifepoints = scaledLifepoints(SolakIds.CORE_LP_BY_TEAM, teamSize),
            rootlings = nearby.filter { it.isType(SolakIds.ROOTLING) },
            lashers = nearby.filter { it.isType(SolakIds.LASHER) }
        )
    }

    private fun advance(animation: Int) {
        val now = System.currentTimeMillis()
        SolakTelegraph.of(animation)?.let { seen ->
            telegraph = seen
            telegraphAt = now
            when {
                seen.closesWindow -> window = null
                seen.opensWindow != null -> {
                    window = seen.opensWindow
                    windowAt = now
                }
            }
        }
        if (now - telegraphAt > TELEGRAPH_HOLD_MS) telegraph = null
        if (now - windowAt > WINDOW_CAP_MS) window = null
    }

    private fun limbs(nearby: List<NPC>, teamSize: Int): List<LimbStatus> =
        SolakLimb.resolved.mapNotNull { limb ->
            val npc = nearby.firstOrNull { it.isType(limb.id) } ?: return@mapNotNull null
            val lifepoints = npc.lifepoints()
            if (lifepoints <= 0) return@mapNotNull null
            LimbStatus(limb, npc, lifepoints, npc.maxLifepoints(scaledLifepoints(limb.maxLifepointsEnum, teamSize)))
        }

    private fun trackedBar(teamSize: Int): TrackedBar? {
        val state = readVarbit(SolakIds.HEALTHBAR_STATE) ?: return null
        val lifepoints = readVarbit(SolakIds.SECOND_BAR_LP) ?: return null
        if (lifepoints <= 0) return null
        val tracksCore = state != 1 && state != 2 && state != 3
        val max = when (state) {
            1 -> STATE_ONE_LP
            2 -> STATE_TWO_LP
            3 -> scaledLifepoints(SolakIds.PHASE_3_LP_BY_TEAM, teamSize)
            else -> scaledLifepoints(SolakIds.CORE_LP_BY_TEAM, teamSize)
        }
        val label = when {
            tracksCore -> "BLIGHT-AFFLICTED CORE"
            state == 3 -> "PHASE 3"
            else -> "SECOND BAR"
        }
        return TrackedBar(label, lifepoints, max, tracksCore)
    }

    private fun NPC?.lifepoints(): Int =
        this?.let { runCatching { it.currentHealth }.getOrDefault(0) } ?: 0

    private fun NPC.maxLifepoints(scaled: Int): Int =
        runCatching { maxHealth }.getOrDefault(0).takeIf { it > 0 } ?: scaled
}
