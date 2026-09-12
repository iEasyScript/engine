package com.projectx.script.impl.devin.zuk

import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.WorldCollision
import com.projectx.pathfinder.hasLineOfSight
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * Out-of-instance drills, so the risky sequences can be rehearsed at the bank instead of debugged
 * on wave 15. Every drill drives the real production paths - loadout, input gate, Barricade press,
 * sustain clicks, highlights - the only thing bypassed is the trigger that normally comes from the
 * encounter. Drills refuse to run (and abort) inside the encounter.
 */
object ZukDrill {

    private enum class Wave15Phase { IDLE, EQUIP, PRESS, HOLD, RESTORE }

    private var wave15Phase = Wave15Phase.IDLE
    private var phaseDeadline = 0L
    private var drillStartedAt = 0L

    @Volatile
    var wave15Status = "idle"
        private set

    @Volatile
    private var wave15Requested = false

    fun requestWave15() {
        wave15Requested = true
    }

    private enum class SustainStep { IDLE, FOOD, BREW, RESTORE }

    private var sustainStep = SustainStep.IDLE
    private var sustainDeadline = 0L
    private val sustainResults = ArrayList<String>(3)

    @Volatile
    var sustainStatus = "idle"
        private set

    @Volatile
    private var sustainRequested = false

    fun requestSustain() {
        sustainRequested = true
    }

    private var highlightUntil = 0L

    @Volatile
    var highlightStatus = "idle"
        private set

    @Volatile
    private var highlightRequested = false

    fun requestHighlights() {
        highlightRequested = true
    }

    @Volatile
    private var hazardPreviewUntil = 0L

    @Volatile
    var hazardPreviewStatus = "idle"
        private set

    @Volatile
    private var hazardPreviewRequested = false

    fun requestHazardPreview() {
        hazardPreviewRequested = true
    }

    fun hazardPreviewActive(): Boolean = System.currentTimeMillis() < hazardPreviewUntil

    private var outlineUntil = 0L
    private var outlineGreenAt = 0L
    private var outlineAddr = 0L
    private var outlineNpc: NPC? = null

    @Volatile
    var outlineStatus = "idle"
        private set

    @Volatile
    private var outlineRequested = false

    fun requestOutline() {
        outlineRequested = true
    }

    @Volatile
    var collisionDiagStatus = "idle"
        private set

    @Volatile
    private var collisionDiagRequested = false

    fun requestCollisionDiag() {
        collisionDiagRequested = true
    }

    fun tick(shieldSpec: String, offhandSpec: String, inEncounter: Boolean) {
        if (inEncounter) {
            if (wave15Phase != Wave15Phase.IDLE) {
                wave15Phase = Wave15Phase.IDLE
                wave15Status = "aborted: encounter started"
            }
            if (sustainStep != SustainStep.IDLE) {
                sustainStep = SustainStep.IDLE
                sustainStatus = "aborted: encounter started"
            }
            clearOutlinePreview()
            hazardPreviewUntil = 0
            wave15Requested = false
            sustainRequested = false
            highlightRequested = false
            hazardPreviewRequested = false
            outlineRequested = false
            collisionDiagRequested = false
            return
        }
        tickWave15(shieldSpec, offhandSpec)
        tickSustain()
        tickHighlights()
        tickHazardPreview()
        tickOutline()
        tickCollisionDiag()
    }

    private fun tickWave15(shieldSpec: String, offhandSpec: String) {
        val now = System.currentTimeMillis()
        if (wave15Requested) {
            wave15Requested = false
            if (wave15Phase == Wave15Phase.IDLE) {
                if (shieldSpec.isBlank()) {
                    wave15Status = "no shield captured - use Capture in settings"
                    return
                }
                wave15Phase = Wave15Phase.EQUIP
                phaseDeadline = now + EQUIP_TIMEOUT_MS
                drillStartedAt = now
                wave15Status = "equipping shield..."
            }
        }
        if (wave15Phase == Wave15Phase.IDLE) return
        if (now > phaseDeadline) {
            wave15Status = "FAILED: ${wave15Phase.name.lowercase()} timed out"
            wave15Phase = Wave15Phase.IDLE
            return
        }
        when (wave15Phase) {
            Wave15Phase.EQUIP -> {
                ZukLoadout.wearChallengeShield(shieldSpec)
                if (ZukLoadout.wornMatchesSpec(shieldSpec)) {
                    wave15Phase = Wave15Phase.PRESS
                    phaseDeadline = now + PRESS_TIMEOUT_MS
                    wave15Status = "shield on - pressing Barricade..."
                }
            }
            Wave15Phase.PRESS -> {
                ZukDefensive.pressSurviveNow()
                if (ZukDefensive.immunityUp()) {
                    wave15Phase = Wave15Phase.HOLD
                    phaseDeadline = now + HOLD_TIMEOUT_MS
                    wave15Status = "Barricade up - waiting it out..."
                }
            }
            Wave15Phase.HOLD -> {
                if (!ZukDefensive.immunityUp()) {
                    wave15Phase = Wave15Phase.RESTORE
                    phaseDeadline = now + RESTORE_TIMEOUT_MS
                    wave15Status = "immunity over - restoring offhand..."
                }
            }
            Wave15Phase.RESTORE -> {
                if (offhandSpec.isBlank()) {
                    wave15Status = "PASS in ${(now - drillStartedAt) / 1000}s (no offhand captured to restore)"
                    wave15Phase = Wave15Phase.IDLE
                    return
                }
                ZukLoadout.restoreOffhand(offhandSpec)
                if (ZukLoadout.wornMatchesSpec(offhandSpec)) {
                    wave15Status = "PASS - full sequence in ${(now - drillStartedAt) / 1000}s"
                    wave15Phase = Wave15Phase.IDLE
                }
            }
            Wave15Phase.IDLE -> Unit
        }
    }

    private fun tickSustain() {
        val now = System.currentTimeMillis()
        if (sustainRequested) {
            sustainRequested = false
            if (sustainStep == SustainStep.IDLE) {
                sustainStep = SustainStep.FOOD
                sustainDeadline = now + SUSTAIN_STEP_TIMEOUT_MS
                sustainResults.clear()
                sustainStatus = "testing food..."
            }
        }
        when (sustainStep) {
            SustainStep.IDLE -> return
            SustainStep.FOOD -> {
                when {
                    !ZukSustain.hasEatFoodBarred() -> nextSustainStep("food: NOT BARRED", now)
                    !ZukSustain.hasFood() -> nextSustainStep("food: none carried", now)
                    ZukSustain.testFood() -> nextSustainStep("food [x]", now)
                    now > sustainDeadline -> nextSustainStep("food ! (click never landed)", now)
                }
            }
            SustainStep.BREW -> {
                when {
                    !ZukSustain.hasBrew() -> nextSustainStep("brew: none carried", now)
                    ZukSustain.testBrew() -> nextSustainStep("brew [x]", now)
                    now > sustainDeadline -> nextSustainStep("brew !", now)
                }
            }
            SustainStep.RESTORE -> {
                when {
                    !ZukSustain.hasRestore() -> nextSustainStep("restore: none carried", now)
                    ZukSustain.testRestore() -> nextSustainStep("restore [x]", now)
                    now > sustainDeadline -> nextSustainStep("restore !", now)
                }
            }
        }
    }

    private fun nextSustainStep(result: String, now: Long) {
        sustainResults += result
        sustainDeadline = now + SUSTAIN_STEP_TIMEOUT_MS
        sustainStep = when (sustainStep) {
            SustainStep.FOOD -> SustainStep.BREW.also { sustainStatus = "testing brew..." }
            SustainStep.BREW -> SustainStep.RESTORE.also { sustainStatus = "testing restore..." }
            else -> SustainStep.IDLE.also { sustainStatus = sustainResults.joinToString(" · ") }
        }
    }

    private fun tickHighlights() {
        val now = System.currentTimeMillis()
        if (highlightRequested) {
            highlightRequested = false
            ZukAbilities.suggestionsFor(null, HIGHLIGHT_TEST_CHAIN)
            ZukAbilities.highlightVulnBomb(true)
            ZukAbilities.highlightConsumables(true)
            highlightUntil = now + HIGHLIGHT_TEST_MS
            highlightStatus = "showing - defensives, Eat Food, brew, restore and vuln bomb (bar + backpack)"
        }
        if (highlightUntil != 0L && now > highlightUntil) {
            ZukAbilities.clearHighlights()
            highlightUntil = 0
            highlightStatus = "done"
        }
    }

    private val HIGHLIGHT_TEST_CHAIN = listOf(
        ZukMessage.Abilities.BARRICADE,
        ZukMessage.Abilities.FREEDOM,
        ZukMessage.Abilities.ANTICIPATION
    )

    private fun tickHazardPreview() {
        val now = System.currentTimeMillis()
        if (hazardPreviewRequested) {
            hazardPreviewRequested = false
            hazardPreviewUntil = now + HAZARD_PREVIEW_MS
            hazardPreviewStatus = "showing - sample merged region on the ground"
        }
        if (hazardPreviewUntil != 0L && now > hazardPreviewUntil) {
            hazardPreviewUntil = 0
            hazardPreviewStatus = "done"
        }
    }

    private fun tickOutline() {
        val now = System.currentTimeMillis()
        if (outlineRequested) {
            outlineRequested = false
            val npc = nearestNpc()
            if (npc == null) {
                outlineStatus = "no npc nearby"
            } else {
                outlineNpc = npc
                outlineGreenAt = now + OUTLINE_PREVIEW_MS
                outlineUntil = now + OUTLINE_PREVIEW_MS * 2
                outlineStatus = "red - not engaged..."
            }
        }
        if (outlineUntil == 0L) return
        if (now > outlineUntil) {
            clearOutlinePreview()
            outlineStatus = "done - cleared"
            return
        }
        val engaged = now >= outlineGreenAt
        if (engaged && outlineStatus.startsWith("red")) outlineStatus = "green - engaged..."
        outlineNpc?.let { npc ->
            runCatching {
                EntityHighlight.apply(npc, if (engaged) OUTLINE_ENGAGED_RGB else OUTLINE_UNENGAGED_RGB, EntityHighlight.Mode.OUTLINE, OUTLINE_PREVIEW_SCALE)
                outlineAddr = EntityHighlight.renderModelAddr(npc)
            }
        }
    }

    private fun clearOutlinePreview() {
        if (outlineAddr != 0L) runCatching { EntityHighlight.clearByRenderModelAddr(outlineAddr) }
        outlineAddr = 0
        outlineNpc = null
        outlineUntil = 0
        outlineGreenAt = 0
    }

    private fun tickCollisionDiag() {
        if (!collisionDiagRequested) return
        collisionDiagRequested = false
        collisionDiagStatus = runCatching {
            val playerTile = localPlayer.tile
            val flags = WorldCollision.getFlags(playerTile)
            val nearest = nearestNpc()
            val los = nearest?.let { runCatching { hasLineOfSight(it.tile, it.size, playerTile, 1) }.getOrNull() }
            buildString {
                append("flags@player=0x${flags.toUInt().toString(16)} populated=${flags != -1} dynamic=${WorldCollision.inDynamic}")
                if (nearest != null) append(" · LOS from ${nearest.name()}=$los") else append(" · no npc for LOS")
            }
        }.getOrElse { "diag failed: ${it.message}" }
    }

    private fun nearestNpc(): NPC? = runCatching {
        val playerTile = localPlayer.tile
        allNpcsWithinRange(OUTLINE_PREVIEW_RANGE) { it.exists() }
            .minByOrNull { runCatching { chebyshev(playerTile, it.tile) }.getOrDefault(Int.MAX_VALUE) }
    }.getOrNull()

    private fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.y - b.y))

    private const val HAZARD_PREVIEW_MS = 6_000L

    /** Each engagement phase runs this long - red for the first, green for the second, then clear. */
    private const val OUTLINE_PREVIEW_MS = 3_000L
    private const val OUTLINE_PREVIEW_RANGE = 15
    private const val OUTLINE_UNENGAGED_RGB = 0xFF3B30
    private const val OUTLINE_ENGAGED_RGB = 0x30D158
    private const val OUTLINE_PREVIEW_SCALE = 10

    private const val EQUIP_TIMEOUT_MS = 10_000L
    private const val PRESS_TIMEOUT_MS = 6_000L

    /** Longer than any real Barricade (13.8s ceiling with perks). */
    private const val HOLD_TIMEOUT_MS = 20_000L
    private const val RESTORE_TIMEOUT_MS = 10_000L
    private const val SUSTAIN_STEP_TIMEOUT_MS = 5_000L
    private const val HIGHLIGHT_TEST_MS = 6_000L
}
