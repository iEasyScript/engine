package com.projectx.script.impl.devin.zuk

import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.WorldCollision
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigurableScript
import com.projectx.script.InfoDisplayConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.StringConfigItem
import com.projectx.script.withAction
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.combatTarget
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.localPlayer
import com.projectx.script.api.onCursesPrayers
import com.projectx.script.api.prayerPoints
import com.projectx.script.api.varps
import com.projectx.script.event.Event
import com.projectx.game.nxt.entity.HitType
import com.projectx.game.nxt.entity.player.Player
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.Hitsplat
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowPos
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowSize
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.native.graphicTexture
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.gameval.Gameval.PARAM
import world.gregs.voidps.gameval.Gameval.STRUCT
import world.gregs.voidps.type.Tile
import kotlin.math.abs

@ScriptDescription(
    name = "Zuk Helper",
    version = "0.1.0",
    author = "Devin",
    description = "Wave/threat HUD, combat-style outlines, mechanic markers, and optional prayer/vuln/defensive " +
        "automation for TzKal-Zuk, normal and hard mode (HM adds wall/challenge/conduit coaching). To work fully " +
        "it needs: the defensive abilities (Freedom, " +
        "Anticipation, Resonance, Barricade, Devotion) on your action bar for highlights and auto-presses; a " +
        "tier 80+ shield in the backpack and captured in Settings for the wave-15 sequence (lower tiers cannot cover " +
        "all four challenge attacks); vulnerability bombs in the backpack for the bomb prompts; the curses book " +
        "for Soul Split (standard book rests by dropping the prayer instead). Every automation toggle is OFF by " +
        "default - indicators always show so each action can be done manually.",
    category = ScriptCategory.BOSSES
)
class ZukHelper : Script(), ConfigurableScript {

    val displaySection = ConfigSection("Display")
    val showHud = BooleanConfigItem("Coach HUD", "Prayer to use, the one action to take, and the wave counter.", true)
    val showMechanicText = BooleanConfigItem(
        "Mechanic captions",
        "Caption special minions with their mechanic on the npc.",
        true
    )
    val showTargetOutline = BooleanConfigItem(
        "Priority target outline",
        "Outline the one npc to kill next - red when you are not engaged with it, green once you are attacking it.",
        true
    )
    val showHazards = BooleanConfigItem(
        "Hazard tiles",
        "Mark lava spouts and other ground hazards.",
        true
    )
    val showSafeSector = BooleanConfigItem(
        "Igneous Rain: next safe spot",
        "During Igneous Rain, mark where the next igneous minion rises - the spot to run to before the rain shifts.",
        true
    )
    val showCoaching = BooleanConfigItem(
        "Movement coaching",
        "Wave start positions, wall gaps and dodge targets.",
        true
    )
    val automationSection = ConfigSection(
        "Automation",
        "Every toggle is OFF by default - indicators always show so each action can be done manually."
    )
    val autoPrayer = BooleanConfigItem(
        "Auto prayer flick",
        "Perform the prayer switch instead of only showing it. Once the room is verifiably clear it rests: Soul Split on curses, prayers off on the standard book.",
        false
    )
    val autoVulnBomb = BooleanConfigItem(
        "Auto vuln bomb",
        "Throw the vulnerability bomb instead of only showing it. Needs bombs in the backpack; only ever thrown at Zuk, Jads and Har-Aken.",
        false
    )
    val autoShieldBreak = BooleanConfigItem(
        "Auto shield break",
        "Press the highlighted stun or threshold ability on igneous minions instead of only showing it - only while your combat target is the shielded minion, so the press cannot land elsewhere.",
        false
    )
    val autoDefensives = BooleanConfigItem(
        "Auto defensives",
        "Press Freedom, Resonance, Anticipation and Devotion on Zuk's telegraphs. Needs those abilities on your action bar. The wave-15 Barricade lives under the wave-15 toggle, not here.",
        false
    )
    val autoShieldSwap = BooleanConfigItem(
        "Auto wave 15 (shield + Barricade)",
        "The whole wave-15 sequence in order: equip the captured shield when the HurKots spawn, press Barricade as the first attack is about to land - never before the shield is on - then put the offhand back. Barricade only: nothing else preserves the perfect challenge. Hard mode wants a tier 90 shield.",
        false
    )
    val challengeShield = StringConfigItem(
        "Wave 15 shield",
        "The shield to Barricade with - wear it and press Capture."
    ).withAction("Capture") { ZukLoadout.wornSpec() }
    val combatOffhand = StringConfigItem(
        "Normal offhand",
        "Put back after the challenge - wear your usual offhand and press Capture."
    ).withAction("Capture") { ZukLoadout.wornSpec() }
    val autoSustain = BooleanConfigItem(
        "Auto food & potions",
        "Eat via the Eat Food actionbar ability and sip Saradomin brews / Super restores from the backpack at the thresholds below. Restores also fire automatically when brews or Searing Pain drain your combat stats. Food is skipped while Searing Pain is up unless health is critical.",
        false
    )
    val eatBelow = IntConfigItem("Eat below health %", "0 disables. Needs the Eat Food ability on the action bar.", 55, 0, 99)
    val brewBelow = IntConfigItem("Brew below health %", "0 disables. Matches any Saradomin brew dose, potion or flask.", 40, 0, 99)
    val restoreBelow = IntConfigItem("Restore below prayer %", "0 disables. Matches any Super restore dose, potion or flask.", 30, 0, 99)
    val testingSection = ConfigSection(
        "Debug & testing",
        "Out-of-instance rehearsal drills and diagnostic logging.",
        defaultOpen = false
    )
    val debugCapture = BooleanConfigItem("Debug capture", "Log waves, hits, projectiles, animations and positions for analysis.", false)
    val drillWave15Info = InfoDisplayConfigItem(
        "Wave-15 drill",
        "Outside the instance only: equips the captured shield, presses Barricade, waits out the immunity, then restores the offhand - the exact production sequence.",
        "idle"
    ).withAction("Run drill") { ZukDrill.requestWave15(); "queued" }
    val sustainTestInfo = InfoDisplayConfigItem(
        "Sustain test",
        "Presses Eat Food once and sips one Saradomin brew and one Super restore, reporting what resolved.",
        "idle"
    ).withAction("Run test") { ZukDrill.requestSustain(); "queued" }
    val highlightTestInfo = InfoDisplayConfigItem(
        "Highlight test",
        "Lights up Barricade, Freedom and Anticipation on the action bar, plus Eat Food, brews, restores and vuln bombs wherever they sit - bar slots and backpack - for six seconds.",
        "idle"
    ).withAction("Run test") { ZukDrill.requestHighlights(); "queued" }
    val hudPreviewInfo = InfoDisplayConfigItem(
        "HUD preview",
        "Shows the coach card with sample content for 20 seconds, so it can be dragged, resized and positioned before a run.",
        "idle"
    ).withAction("Preview") { hudPreviewUntil = System.currentTimeMillis() + HUD_PREVIEW_MS; "showing" }
    val hazardPreviewInfo = InfoDisplayConfigItem(
        "Hazard render preview",
        "Draws a sample 5×5 merged hazard region on the ground next to you for a few seconds - to eyeball the seamless fill and boundary outline.",
        "idle"
    ).withAction("Preview") { ZukDrill.requestHazardPreview(); "queued" }
    val outlinePreviewInfo = InfoDisplayConfigItem(
        "Target outline preview",
        "Outlines the nearest npc through the real single-target path: red for three seconds, green for three, then clears - validates both colours and the clear.",
        "idle"
    ).withAction("Preview") { ZukDrill.requestOutline(); "queued" }
    val collisionDiagInfo = InfoDisplayConfigItem(
        "Collision / LOS diagnostic",
        "Reports the collision flag at your tile, whether the scene is dynamic, and line of sight from the nearest npc - confirms path-aware ranking has data to work with.",
        "idle"
    ).withAction("Check") { ZukDrill.requestCollisionDiag(); "queued" }

    @Volatile
    private var marks: List<ThreatMark> = emptyList()

    @Volatile
    private var outlined: Map<Int, Long> = emptyMap()

    @Volatile
    private var hudPreviewUntil = 0L

    private var coachWasActive = false

    private var lastLoggedBoard = ""

    @Volatile
    private var wallBaseAt = 0L

    @Volatile
    private var lastWaveNumber = 0

    @Volatile
    private var startHintUntil = 0L

    private var playerTile: Tile? = null
    private var lastAction: ZukAction? = null
    private var lastActionAt = 0L

    @Volatile
    private var dodgeMark: Pair<String, Tile>? = null

    @Volatile
    private var priorityIndex: Int = -1

    @Volatile
    private var priorityEngaged: Boolean = false

    @Volatile
    private var action: ZukAction? = null

    @Volatile
    private var prayerNeeded: CombatStyle? = null

    @Volatile
    private var prayerDisplay: CombatStyle? = null

    @Volatile
    private var hazards: List<Tile> = emptyList()

    @Volatile
    private var abilitySuggestions: List<String> = emptyList()

    @Volatile
    private var threat: ZukThreatModel.Assessment? = null

    @Volatile
    private var safeSector: Tile? = null

    private val alert = ZukAlert {
        runCatching { ZukActions.inZukPhase() }.getOrDefault(false) || ZukThreat.zukPresent()
    }

    override fun onStart() {
        super.onStart()
        ZukWaves.reset()
        ZukArena.reset()
        ZukCapture.reset()
        ZukThreat.reset()
        ZukIgneousRain.reset()
        ZukInputGate.reset()
        ZukDefensive.reset()
        ZukLoadout.reset()
        ZukZone.reset()
        ZukTargeting.reset()
        ZukDodgeThreats.reset()
        PacketLogger.serverPacketListeners.remove(ZukZone.listener)
        PacketLogger.serverPacketListeners.add(ZukZone.listener)
        alert.clear()
        lastAction = null
        lastActionAt = 0
    }

    override fun onStop() {
        super.onStop()
        PacketLogger.serverPacketListeners.remove(ZukZone.listener)
        ZukAbilities.clearHighlights()
        clearOutlines()
    }

    override fun onEvent(event: Event) {
        if (event is Hitsplat) {
            runCatching {
                when (val target = event.target) {
                    is NPC -> ZukAttackMemory.stampHurt(target.serverIndex)
                    is Player -> if (event.type in NPC_ATTACK_HIT_TYPES) ZukAttackMemory.stampPlayerHit()
                    else -> Unit
                }
            }
            return
        }
        if (event !is Chat) return
        val matched = alert.accept(event.message) ?: return
        when (matched) {
            ZukMessage.IGNEOUS_RAIN -> ZukIgneousRain.begin()
            ZukMessage.RISE_HUR, ZukMessage.RISE_XIL, ZukMessage.RISE_MEJ -> ZukIgneousRain.onMinionRisen()
            ZukMessage.ENERGY_UNLEASHED -> ZukIgneousRain.end()
            ZukMessage.AKEN_BOMBARDMENT -> runCatching { ZukArena.markBombardment(localPlayer.tile) }
            else -> Unit
        }
        if (debugCapture.value) ZukCapture.message(matched, event.message)
    }

    override suspend fun loop() {
        ZukWaves.refresh()
        ZukArena.refresh()
        ZukZone.tick()
        updateCoachState()
        ZukDrill.tick(challengeShield.value, combatOffhand.value, encounterActive())
        drillWave15Info.value = ZukDrill.wave15Status
        sustainTestInfo.value = ZukDrill.sustainStatus
        highlightTestInfo.value = ZukDrill.highlightStatus
        hudPreviewInfo.value = hudPreviewLabel()
        hazardPreviewInfo.value = ZukDrill.hazardPreviewStatus
        outlinePreviewInfo.value = ZukDrill.outlineStatus
        collisionDiagInfo.value = ZukDrill.collisionDiagStatus
        updateThreatMarks()
        // Nothing that acts may run unless the client scene is still the live arena instance: the
        // moment a teleport rebuilds into a static area the hard gate drops, same tick, no TTL lag.
        if (automationAllowed()) {
            // Survival order: the input gate admits one input per tick, so on any tick where several
            // automations want it, the wave-15 press must never lose its tick to a flick or a bomb.
            if (autoShieldSwap.value) {
                wave15Sequence()
            }
            if (autoDefensives.value) {
                ZukDefensive.respond(alert)
            }
            if (autoSustain.value) {
                ZukSustain.tick(
                    eatBelow.value, brewBelow.value, restoreBelow.value,
                    runCatching { ZukActions.searingPainStacks() }.getOrDefault(0)
                )
            }
            if (autoPrayer.value && prayerPoints > 0) {
                val style = prayerNeeded
                if (style != null) flickProtection(style) else if (soulSplit) restWhenSafe()
            }
            if (autoVulnBomb.value) {
                tryVulnBomb()
            }
            if (autoShieldBreak.value) {
                tryShieldBreak()
            }
        }
        if (debugCapture.value) {
            ZukCapture.tick(action, prayerNeeded)
            if (ZukTargeting.lastBoard != lastLoggedBoard) {
                lastLoggedBoard = ZukTargeting.lastBoard
                println("[ZukCap] TARGETBOARD $lastLoggedBoard")
            }
        }
        delay(LOOP_DELAY_MS)
    }

    /**
     * One switch owns the whole wave-15 sequence so the press can never race the swap: the shield
     * goes on when the HurKots appear, the Barricade chain fires only once the configured shield
     * actually reads worn (or the swap is provably impossible), and the offhand returns after.
     *
     * No swap may run while immunity is up: switching a shield mid-Barricade cancels it outright,
     * and the restore is the dangerous direction - a momentarily failing npc scan drops the action
     * off `SURVIVE` mid-challenge, which would otherwise put the offhand back while Barricade is
     * still holding the attacks off.
     */
    private fun wave15Sequence() {
        if (action == ZukAction.SURVIVE) {
            if (!ZukLoadout.challengeShieldReady(challengeShield.value)) {
                if (!ZukDefensive.immunityUp()) ZukLoadout.wearChallengeShield(challengeShield.value)
                return
            }
            ZukDefensive.pressSurviveChain(action)
        } else {
            ZukDefensive.pressSurviveChain(action)
            if (!ZukDefensive.immunityUp()) ZukLoadout.restoreOffhand(combatOffhand.value)
        }
    }

    @Volatile
    private var soulSplit = false
    private var safeSince = 0L

    /**
     * With nothing in the room able to land a hit, a protection prayer buys nothing and Soul Split
     * turns the same prayer points into health. Judged on the whole room at once, never on one npc -
     * and only once the room has stayed clear for [SAFE_DWELL_MS]: a wire replay measured 6.4% of
     * styled damage landing inside momentary all-clear readings.
     */
    private fun updateSoulSplit(assessment: ZukThreatModel.Assessment?): Boolean {
        val safeNow = assessment != null && assessment.nothingCanHitUs && !ZukThreat.zukPresent()
        if (!safeNow) {
            safeSince = 0
            return false
        }
        val now = System.currentTimeMillis()
        if (safeSince == 0L) safeSince = now
        return now - safeSince >= SAFE_DWELL_MS
    }

    /**
     * Coaching liveness. The mode varbit alone still reads the saved checkpoint at War's Retreat, so
     * the anchor npc confirms it; the scene being a dynamic region cuts it the tick a teleport rebuilds
     * into a static area.
     */
    private fun encounterActive(): Boolean =
        WorldCollision.inDynamic && runCatching { ZukActions.inEncounter() }.getOrDefault(true) && ZukArena.anchorNearby()

    /**
     * Hard gate for anything that acts or sends input: the scene must still be the live arena instance
     * ([WorldCollision.inDynamic] drops the same tick on any rebuild to static), the mode varbit live,
     * and the Zuk anchor recently seen so a stale checkpoint inside some other instance cannot fire.
     */
    private fun automationAllowed(): Boolean =
        WorldCollision.inDynamic && runCatching { ZukActions.inEncounter() }.getOrDefault(false) && ZukArena.anchorNearby()

    /** Automation toggles gate only the action taken - the indicator is resolved regardless. */
    private fun updateCoachState() {
        if (!encounterActive()) {
            // Clear once on leaving, not every pass - a per-pass clear would also wipe whatever
            // the out-of-instance drills are highlighting.
            if (coachWasActive) clearCoachState()
            coachWasActive = false
            return
        }
        if (!coachWasActive) {
            wallBaseAt = System.currentTimeMillis()
            lastWaveNumber = 0
        }
        coachWasActive = true
        alert.lastSeenAt(ZukMessage.LAVA_WALL)?.let { if (it > wallBaseAt) wallBaseAt = it }
        val wave = ZukWaves.current()
        // Wiki: the wall cooldown resets during igneous waves - WALL SOON must not arm through them.
        if (wave?.type == WaveType.IGNEOUS) wallBaseAt = System.currentTimeMillis()
        wave?.number?.let { n ->
            if (n != lastWaveNumber) {
                lastWaveNumber = n
                startHintUntil = System.currentTimeMillis() + START_HINT_MS
            }
        }
        val assessment = runCatching { ZukThreatModel.assess() }.getOrNull()
        threat = assessment
        // The flick may only follow the live room. The roster average is a display hint, and only when
        // the live read itself is missing - a roster-forced prayer with nothing attacking is the bug.
        val live = alert.prayerOverride ?: ZukThreat.incomingStyle(assessment)
        soulSplit = updateSoulSplit(assessment)
        prayerNeeded = if (soulSplit) null else live
        prayerDisplay = prayerNeeded ?: if (!soulSplit && assessment == null) ZukWaves.dominantStyle() else null
        hazards = runCatching { ZukArena.hazardTiles() }.getOrDefault(emptyList())
        safeSector = runCatching { ZukIgneousRain.nextSafeTile() }.getOrNull()
        val standingInHazard = playerTile?.let { tile -> hazards.any { it.x == tile.x && it.y == tile.y } } ?: false
        val flood = playerTile?.let { runCatching { ZukArena.pathDistances(it) }.getOrNull() }
        val bodyFlood = playerTile?.let { runCatching { ZukReach.bodyFlood(it) }.getOrNull() }
        dodgeMark = runCatching { resolveDodge(standingInHazard, flood) }.getOrNull()
        val kihDraining = alert.sawRecently(ZukMessage.KIH_DRAIN, KIH_DRAIN_WINDOW_MS)
        val priority = runCatching { ZukTargeting.resolve(kihDraining, flood, bodyFlood) }.getOrNull()
        priorityIndex = priority?.let { runCatching { it.serverIndex }.getOrDefault(-1) } ?: -1
        priorityEngaged = priority?.let { runCatching { localPlayer.interactingWith(it) }.getOrDefault(false) } ?: false
        val attackingWorthy = runCatching { combatTarget?.let { it.exists() && it.worthVulnBomb() } == true }.getOrDefault(false)
        val resolved = runCatching { ZukActions.current(wave, standingInHazard, kihDraining, attackingWorthy) }.getOrNull()
        val held = holdAction(resolved)
        action = held
        val prescribed = alert.prescribedAbilities().ifEmpty {
            if (held == ZukAction.SURVIVE) listOf(ZukMessage.Abilities.BARRICADE) else emptyList()
        }
        abilitySuggestions = ZukAbilities.suggestionsFor(held, prescribed)
        ZukAbilities.highlightVulnBomb(held == ZukAction.VULN)
    }

    private val shieldBreakPressed = HashMap<Int, Pair<Long, Int>>()

    /**
     * The press lands on the current combat target, so it only fires while that target is the
     * shielded igneous minion the prompt is about - the vuln-on-trash lesson applies unchanged.
     *
     * One press per npc, then hands-off: there is no known shield-state varbit, so damage landing
     * on the npc since the press is the "shield is down" signal and ends the attempts. Only a
     * target still at its pressed-time health after [SHIELD_BREAK_RETRY_MS] earns a retry (the
     * press missed or lacked adrenaline). STUN additionally self-terminates via the stun varbit
     * that gates its prompt.
     */
    private fun tryShieldBreak() {
        val current = action ?: return
        val wanted = when (current) {
            ZukAction.STUN -> ZukMinion.IGNEOUS_HUR
            ZukAction.THRESHOLD -> ZukMinion.IGNEOUS_XIL
            else -> return
        }
        val target = runCatching { combatTarget?.takeIf { it.exists() } }.getOrNull() ?: return
        if (runCatching { target.zukMinion() }.getOrNull() != wanted) return
        val now = System.currentTimeMillis()
        shieldBreakPressed.values.removeIf { now - it.first > SHIELD_BREAK_FORGET_MS }
        val index = runCatching { target.serverIndex }.getOrNull() ?: return
        val health = runCatching { target.currentHealth }.getOrDefault(0)
        shieldBreakPressed[index]?.let { (pressedAt, healthThen) ->
            if (health < healthThen) return
            if (now - pressedAt < SHIELD_BREAK_RETRY_MS) return
        }
        if (!ZukInputGate.allow("shieldBreak", ZukInputGate.SHIELD_BREAK_MS)) return
        if (ZukAbilities.pressPrimary()) {
            shieldBreakPressed[index] = now to health
            println("[ZukCap] SHIELDBREAK $current idx=$index hp=$health")
        }
    }

    private fun clearCoachState() {
        prayerNeeded = null
        prayerDisplay = null
        soulSplit = false
        safeSince = 0
        action = null
        lastAction = null
        lastActionAt = 0
        hazards = emptyList()
        safeSector = null
        dodgeMark = null
        priorityIndex = -1
        priorityEngaged = false
        shieldBreakPressed.clear()
        marks = emptyList()
        abilitySuggestions = emptyList()
        alert.clear()
        ZukIgneousRain.reset()
        ZukDefensive.reset()
        ZukTargeting.reset()
        ZukAbilities.clearHighlights()
    }

    /**
     * An npc scan that momentarily fails or a minion stepping out of range would otherwise blink the
     * prompt off and straight back on, which is unreadable mid-fight - so a cleared prompt has to
     * stay cleared for [ACTION_HOLD_MS] before it is believed.
     */
    private fun holdAction(resolved: ZukAction?): ZukAction? {
        val now = System.currentTimeMillis()
        if (resolved != null) {
            lastActionAt = now
            lastAction = resolved
            return resolved
        }
        if (now - lastActionAt <= ACTION_HOLD_MS) return lastAction
        lastAction = null
        return null
    }

    /**
     * Everything the overlay draws is resolved here so [render] never touches client memory that may
     * have gone stale mid-frame.
     */
    private fun updateThreatMarks() {
        val player = runCatching { localPlayer.tile }.getOrNull()
        if (player == null) {
            marks = emptyList()
            return
        }
        playerTile = player

        marks = allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.isOverlayTarget() && !it.isHazard() }
            .mapNotNull { npc ->
                runCatching {
                    ThreatMark(npc, npc.serverIndex, npc.tile, npc.size, npc.mechanicHint())
                }.getOrNull()
            }
    }

    override fun render() {
        if (showHud.value) drawCoachHud()
        drawWorldOverlay()
    }

    /**
     * A chromeless ImGui window, so dragging and resizing come from ImGui itself; the card is drawn
     * against the window's live geometry on the render thread from an immutable snapshot. Text size
     * is fixed (single-font binding), so resizing changes width and wrapping, not scale.
     */
    private fun drawCoachHud() {
        val data = hudData() ?: return
        val (screenW, _) = runCatching { NativeBridge.getDisplaySize() }.getOrDefault(0f to 0f)
        if (screenW > 0f) {
            setNextWindowPos(screenW * 0.5f - ZukHudRenderer.DEFAULT_WIDTH * 0.5f, PANEL_TOP, ImGuiCond.FirstUseEver)
        }
        setNextWindowSize(ZukHudRenderer.DEFAULT_WIDTH, ZukHudRenderer.DEFAULT_HEIGHT, ImGuiCond.FirstUseEver)
        window("Zuk Coach##zuk-hud", HUD_WINDOW_FLAGS) {
            windowDraw { drawList, x, y, width, _ -> ZukHudRenderer.draw(data, drawList, x, y, width) }
        }
    }

    private fun hudPreviewLabel(): String {
        val left = (hudPreviewUntil - System.currentTimeMillis()) / 1000
        return if (left > 0) "showing - ${left}s left" else "idle"
    }

    /** Canned content so the card can be placed and sized without entering the encounter. */
    private fun sampleHudData(): ZukHudData = ZukHudData(
        prayerLabel = "PROTECT MAGIC",
        prayerAccent = CombatStyle.MAGIC.drawColor,
        icon = prayerIcon(CombatStyle.MAGIC, onCurses = false, soulSplit = false),
        mode = "HARD MODE" to MODE_HARD,
        instruction = "SAMPLE - DRAG & RESIZE ME" to ImGuiColors.withAlpha(TELEGRAPH_RGB.asDrawColor(), 245),
        abilities = "Barricade   ·   Freedom",
        sections = listOf(
            ZukHudData.Section(
                "WAVE 12 - REGULAR", listOf(
                    "2× Ket-Zek" to CombatStyle.MAGIC.drawColor,
                    "4× Mej" to CombatStyle.MAGIC.drawColor,
                    "2× Tok-Xil" to CombatStyle.RANGED.drawColor,
                    "1× Kih" to ZukHudRenderer.SECTION_TEXT
                )
            ),
            ZukHudData.Section("NEXT 13 - REGULAR", listOf("4× Ket-Zek" to CombatStyle.MAGIC.drawColor))
        )
    )

    private fun hudData(): ZukHudData? {
        if (System.currentTimeMillis() < hudPreviewUntil) return sampleHudData()
        val sections = runCatching { waveSections() }.getOrDefault(emptyList())
        val prayer = prayerDisplay
        if (sections.isEmpty() && prayer == null && action == null) return null
        val instruction = instruction()
        val onCurses = runCatching { onCursesPrayers }.getOrDefault(false)
        val label = prayer?.let { "${if (onCurses) "DEFLECT" else "PROTECT"} ${it.displayName.uppercase()}" }
            ?: if (soulSplit) "SOUL SPLIT" else "NO PRAYER"
        return ZukHudData(
            prayerLabel = label,
            prayerAccent = prayer?.drawColor ?: ZukHudRenderer.NEUTRAL_ACCENT,
            icon = prayerIcon(prayer, onCurses, soulSplit),
            mode = modeBadge(),
            instruction = instruction,
            abilities = abilitySuggestions.takeIf { it.isNotEmpty() && instruction != null }?.joinToString("   ·   "),
            sections = sections
        )
    }

    private fun modeBadge(): Pair<String, Int> {
        val practice = runCatching { varps.getVarBit(ZukIds.PRACTICE_MODE_VARBIT) != 0 }.getOrDefault(false)
        val hard = hardMode()
        return when {
            practice && hard -> "PRACTICE · HARD" to MODE_PRACTICE
            practice -> "PRACTICE" to MODE_PRACTICE
            hard -> "HARD MODE" to MODE_HARD
            else -> "NORMAL" to MODE_NORMAL
        }
    }

    /**
     * The wave varbit stops at the final wave and stays there for the whole Zuk fight, so the boss
     * phase has to be detected separately or the card claims the Har-Aken wave is still live.
     */
    private fun waveSections(): List<ZukHudData.Section> {
        if (!encounterActive()) return emptyList()
        if (runCatching { ZukActions.inZukPhase() }.getOrDefault(false) || ZukThreat.zukPresent()) {
            return listOf(ZukHudData.Section(zukTitle(), emptyList()))
        }
        val current = ZukWaves.current() ?: return emptyList()
        val sections = ArrayList<ZukHudData.Section>(2)
        // A persistent instruction would bury real prompts for the whole ~50s rain phase, so the
        // ambient warnings ride the wave title instead; the per-tile cyan marks carry the dodging.
        val extras = StringBuilder()
        when {
            ZukArena.lavaWallActive() -> extras.append(" · LAVA WALL - FIND THE GAP")
            wallDue(current.type) -> extras.append(" · WALL SOON - WATCH FOR THE GAP")
        }
        if (ZukArena.lavaRainActive()) extras.append(" · LAVA RAIN - WATCH THE GROUND")
        if (hardMode() && current.type == WaveType.IGNEOUS) extras.append(" · KILL THE IGNEOUS - ADDS OPTIONAL")
        if (current.type == WaveType.AKEN) extras.append(akenSuffix())
        extras.append(sustainShortages())
        sections += waveSection("WAVE ${current.number} - ${current.type}$extras", current)
        val next = ZukWaves.next()
        sections += if (next != null) {
            waveSection("NEXT ${next.number} - ${next.type}${startSuffix(next.number)}", next)
        } else {
            ZukHudData.Section("NEXT - TZKAL-ZUK", emptyList())
        }
        return sections
    }

    /** Mob names carry their combat-style colour, so the roster doubles as a threat preview. */
    private fun waveSection(title: String, wave: WaveInfo): ZukHudData.Section =
        ZukHudData.Section(title, wave.spawns.map { spawn ->
            "${spawn.count}× ${shortName(spawn.minion)}" to (spawn.minion.style?.drawColor ?: ZukHudRenderer.SECTION_TEXT)
        })

    private fun shortName(minion: ZukMinion): String = minion.displayName.replace("TzekHaar-", "")

    /**
     * At 100k LP the last phase begins: normal mode restarts the rotation from Geothermal Burn,
     * hard mode raises the Conduit of Ful - either way the fight changes character right there.
     */
    private fun zukTitle(): String {
        val hp = runCatching {
            findClosestNPC(ZukIds.ZUK_SHOWDOWN)?.takeIf { it.exists() }?.currentHealth
        }.getOrNull()
        var label = hp?.let { "TZKAL-ZUK - ${it / 1000}K" } ?: "TZKAL-ZUK - FINAL FIGHT"
        // Same 100k threshold, different meaning per mode: NM restarts the rotation; HM raises the
        // Conduit of Ful and Zuk stops attacking entirely - Soul Split there is free healing.
        if (hp != null && hp in 1..ZukIds.ZUK_FINAL_ROTATION_LP) {
            label += if (hardMode()) " · CONDUIT - CAMP SOUL SPLIT" else " · LAST PHASE"
        }
        val energy = runCatching { varps.getVarBit(ZukIds.IGNEOUS_ENERGY_VARBIT) }.getOrDefault(0)
        if (energy in 1 until ZukIds.IGNEOUS_ENERGY_FULL) label += " · ENERGY $energy/3"
        return label + sustainShortages()
    }

    private fun hardMode(): Boolean =
        runCatching { varps.getVarBit(ZukIds.ENCOUNTER_MODE_VARBIT) == ZukIds.MODE_HARD }.getOrDefault(false)

    /** Pre-positioning is the point: the next wave's start rides its preview line before it spawns. */
    private fun startSuffix(wave: Int): String =
        if (hardMode()) ZukArena.startLabel(wave)?.let { " · START $it" } ?: "" else ""

    /**
     * Walls run a ~45s cooldown on non-challenge waves (wiki; 52–75s gaps measured in the 20:42
     * capture), so once the cooldown has elapsed the next one can land any moment. Armed until the
     * wall actually fires - the LAVA_WALL message re-bases the timer.
     */
    private fun wallDue(type: WaveType): Boolean =
        hardMode() && type != WaveType.CHALLENGE && wallBaseAt != 0L &&
            System.currentTimeMillis() - wallBaseAt >= WALL_COOLDOWN_MS

    /**
     * Only for thresholds the sustain automation is actually watching - a brew-less loadout on
     * purpose stays quiet. Restores also matter whenever brews are armed: every dose drains the
     * damage stats and only a restore puts them back.
     */
    private fun sustainShortages(): String {
        if (!autoSustain.value) return ""
        val out = StringBuilder()
        if (eatBelow.value > 0 && !ZukSustain.hasFood()) out.append(" · OUT OF FOOD")
        if (brewBelow.value > 0 && !ZukSustain.hasBrew()) out.append(" · OUT OF BREWS")
        if ((restoreBelow.value > 0 || brewBelow.value > 0) && !ZukSustain.hasRestore()) out.append(" · OUT OF RESTORES")
        return out.toString()
    }

    /**
     * Magic Tentacles grant Aken +100 damage reduction each; hard mode's Warding Magic Tentacles
     * +500 each with no cap (wiki) - killing them first is the damage unlock either mode.
     */
    private fun akenSuffix(): String = runCatching {
        val suffix = StringBuilder()
        findClosestNPC(ZukMinion.AKEN.id)?.takeIf { it.exists() }?.currentHealth
            ?.let { suffix.append(" · AKEN ${it / 1000}K") }
        val magic = allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) {
            it.exists() && it.zukMinion() == ZukMinion.TENTACLE_MAGIC
        }.size
        val warding = allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) {
            it.exists() && it.zukMinion() == ZukMinion.TENTACLE_MAGIC_STRONG
        }.size
        val reduction = magic * 100 + warding * 500
        if (reduction > 0) suffix.append(" · KILL MAGIC TENTS (DR +$reduction)")
        suffix.toString()
    }.getOrDefault("")

    /**
     * Exactly one instruction is ever on screen. A telegraphed special always wins: it is the thing
     * about to land, and stacking it under a standing chore like the vuln bomb buries it. A known
     * wall gap outranks routine actions but not a live telegraph.
     */
    private fun instruction(): Pair<String, Int>? {
        alert.current()?.let { return it to ImGuiColors.withAlpha(TELEGRAPH_RGB.asDrawColor(), 245) }
        wallGapLabel()?.let { return it to ImGuiColors.withAlpha(ZukAction.MOVE.rgb.asDrawColor(), 235) }
        val current = action ?: return null
        val fill = ImGuiColors.withAlpha(current.rgb.asDrawColor(), if (current.isCritical) 235 else 200)
        return actionLabel(current) to fill
    }

    private fun wallGapLabel(): String? {
        val (kind, tile) = dodgeMark ?: return null
        if (kind != DODGE_GAP || !ZukZone.wallActive()) return null
        val from = playerTile ?: return "RUN TO GAP"
        return "RUN TO GAP - ${directionLabel(from, tile)} ${chebyshev(from, tile)}"
    }

    /** Each remaining stack is one more tile to run, so the count is the instruction. */
    private fun actionLabel(action: ZukAction): String = when (action) {
        ZukAction.RUN -> {
            val stacks = runCatching { ZukActions.searingPainStacks() }.getOrDefault(0)
            "RUN - $stacks TILES  (don't eat)"
        }
        ZukAction.UNGROUND -> {
            val stacks = runCatching { ZukActions.groundedStacks() }.getOrDefault(0)
            "TOK-XIL +$stacks% - MAKE IT MOVE"
        }
        ZukAction.MOVE -> {
            val mark = dodgeMark
            val from = playerTile
            if (mark != null && from != null) "MOVE - ${directionLabel(from, mark.second)} ${chebyshev(from, mark.second)}"
            else action.label
        }
        else -> action.label
    }

    /**
     * The wall gap is the one destination that beats plain hazard escape: the sweep covers the
     * whole arena, so any tile that is not the gap is only briefly safe.
     */
    private fun resolveDodge(standingInHazard: Boolean, steps: Map<Long, Int>?): Pair<String, Tile>? {
        val from = playerTile ?: return null
        val gap = if (ZukZone.wallActive()) ZukZone.gapTiles().minByOrNull { chebyshev(from, it) } else null
        if (gap != null && !standingInHazard) return DODGE_GAP to gap
        if (!standingInHazard) return null
        if (gap != null) {
            // A short sidestep off the band beats a long sprint to the gap; the gap wins ties.
            val sidestep = dodgeCandidate(from, steps)
            return if (sidestep != null && chebyshev(from, sidestep) < chebyshev(from, gap)) DODGE_MOVE to sidestep
            else DODGE_GAP to gap
        }
        return dodgeCandidate(from, steps)?.let { DODGE_MOVE to it }
    }

    private fun dodgeCandidate(from: Tile, steps: Map<Long, Int>?): Tile? =
        ZukArena.dodgeTarget(from, hazards, ZukDodgeThreats.of(from), steps)

    private fun chebyshev(a: Tile, b: Tile): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y))

    private fun directionLabel(from: Tile, to: Tile): String {
        val ns = if (to.y > from.y) "N" else if (to.y < from.y) "S" else ""
        val ew = if (to.x > from.x) "E" else if (to.x < from.x) "W" else ""
        return (ns + ew).ifEmpty { "HERE" }
    }

    private fun drawWorldOverlay() {
        val snapshot = marks

        if (showTargetOutline.value) {
            val current = HashMap<Int, Long>(1)
            if (priorityIndex != -1) {
                snapshot.firstOrNull { it.serverIndex == priorityIndex }?.let { mark ->
                    val color = if (priorityEngaged) TARGET_ENGAGED_RGB else TARGET_UNENGAGED_RGB
                    EntityHighlight.apply(mark.npc, color, EntityHighlight.Mode.OUTLINE, TARGET_OUTLINE_SCALE)
                    val addr = runCatching { EntityHighlight.renderModelAddr(mark.npc) }.getOrDefault(0L)
                    if (addr != 0L) current[mark.serverIndex] = addr
                }
            }
            for ((index, addr) in outlined) {
                if (index !in current) runCatching { EntityHighlight.clearByRenderModelAddr(addr) }
            }
            outlined = current
        } else {
            clearOutlines()
        }

        backgroundDrawList {
            if (ZukDrill.hazardPreviewActive()) {
                playerTile?.let { tileRegion(sampleHazardRegion(it), HAZARD_TILE) }
            }
            if (showHazards.value) {
                tileRegion(hazards, HAZARD_TILE)
            }
            if (showSafeSector.value) {
                safeSector?.let {
                    tileArea(it, SAFE_SECTOR_SIZE, SAFE_TILE)
                    textOnTile(it, SAFE_TEXT, "NEXT SAFE SPOT", SAFE_SECTOR_SIZE)
                }
            }
            if (showMechanicText.value) {
                for (mark in snapshot) {
                    val mechanic = mark.mechanic ?: continue
                    tileArea(mark.tile, mark.size, MECHANIC_TILE)
                    textOnTile(mark.tile, MECHANIC_TEXT, mechanic, mark.size)
                }
            }
            if (showCoaching.value) {
                if (System.currentTimeMillis() < startHintUntil && hardMode()) {
                    ZukArena.startHint(lastWaveNumber)?.let { (label, tile) ->
                        tileArea(tile, START_HINT_SIZE, START_TILE)
                        textOnTile(tile, START_TEXT, "START $label", START_HINT_SIZE)
                    }
                }
                if (ZukZone.wallActive()) {
                    for (tile in ZukZone.gapTiles()) {
                        tileArea(tile, 1, GAP_TILE)
                    }
                }
                dodgeMark?.let { (kind, tile) ->
                    tileArea(tile, 1, GO_TILE)
                    textOnTile(tile, GO_TEXT, if (kind == DODGE_GAP) "GO - GAP" else "GO", 1)
                }
            }
        }
    }

    private fun sampleHazardRegion(center: Tile): List<Tile> {
        val half = HAZARD_PREVIEW_SIZE / 2
        val base = Tile.of(center.x, center.y + HAZARD_PREVIEW_SIZE, center.plane)
        return (-half..half).flatMap { dx -> (-half..half).map { dy -> Tile.of(base.x + dx, base.y + dy, base.plane) } }
    }

    /** The outline is a write into the client's own render model, so it outlives us unless cleared. */
    private fun clearOutlines() {
        val stale = outlined
        if (stale.isEmpty()) return
        outlined = emptyMap()
        for (addr in stale.values) runCatching { EntityHighlight.clearByRenderModelAddr(addr) }
    }

    private class ThreatMark(
        val npc: NPC,
        val serverIndex: Int,
        val tile: Tile,
        val size: Int,
        val mechanic: String?
    )

    /**
     * The real prayer-book icon for the prayer the coach is calling: the combatv2 prayer/curse struct
     * resolved by gameval name, then its lit-icon graphic (`prayer_graphic_on`) - never a hardcoded
     * graphic id, so a build that renumbers graphics cannot serve the wrong icon. The rest state on the
     * standard book has no prayer, so no icon. Single-frame graphics, safe for [graphicTexture].
     */
    private fun prayerIcon(style: CombatStyle?, onCurses: Boolean, soulSplit: Boolean): ImGuiTexture? {
        val struct = when (style) {
            CombatStyle.MELEE -> if (onCurses) "combatv2_curse_deflect_melee" else "combatv2_prayer_protect_melee"
            CombatStyle.RANGED -> if (onCurses) "combatv2_curse_deflect_missiles" else "combatv2_prayer_protect_missiles"
            CombatStyle.MAGIC -> if (onCurses) "combatv2_curse_deflect_magic" else "combatv2_prayer_protect_magic"
            null -> if (soulSplit) "combatv2_curse_soulsplit" else return null
        }
        return prayerIconCache.getOrPut(struct) {
            runCatching {
                val graphic = Cache.struct(Gameval.requireId(STRUCT, struct))?.getIntValue(Gameval.requireId(PARAM, "prayer_graphic_on")) ?: 0
                graphic.takeIf { it > 0 }?.let { graphicTexture(it) }
            }.getOrNull()
        }
    }

    private fun Int.asDrawColor(): Int =
        ImGuiColors.rgba((this ushr 16) and 0xFF, (this ushr 8) and 0xFF, this and 0xFF)

    private companion object {
        val NPC_ATTACK_HIT_TYPES = setOf(HitType.MELEE, HitType.RANGED, HitType.MAGIC, HitType.NECROMANCY, HitType.MISS, HitType.DODGE)

        /**
         * The single kill-target outline: red until the player is engaged with it, green once
         * attacking. A soft border - the quest-helper outline reads fine at 8; well under the ~32
         * where the native highlight turns overpowering.
         */
        const val TARGET_UNENGAGED_RGB = 0xFF3B30
        const val TARGET_ENGAGED_RGB = 0x30D158
        const val TARGET_OUTLINE_SCALE = 5
        const val HAZARD_PREVIEW_SIZE = 5
        /**
         * A game tick is 600ms and telegraphs arrive as chat events regardless of this cadence, so
         * ~10 passes per tick is ample. The old 20ms scanned every npc 50 times a second.
         */
        const val LOOP_DELAY_MS = 60
        const val ACTION_HOLD_MS = 900L
        const val KIH_DRAIN_WINDOW_MS = 8000L
        const val SHIELD_BREAK_RETRY_MS = 4_800L
        const val SHIELD_BREAK_FORGET_MS = 60_000L
        const val SAFE_DWELL_MS = 2400L
        const val HUD_PREVIEW_MS = 20_000L
        const val WALL_COOLDOWN_MS = 45_000L

        const val PANEL_TOP = 60f

        val HUD_WINDOW_FLAGS = WindowFlags.NoTitleBar + WindowFlags.NoBackground + WindowFlags.NoScrollbar +
            WindowFlags.NoScrollWithMouse + WindowFlags.NoCollapse + WindowFlags.NoFocusOnAppearing

        val prayerIconCache = HashMap<String, ImGuiTexture?>()

        /** Muted for practice, bronze for normal, hot for hard - a badge, not roster text. */
        val MODE_PRACTICE = ImGuiColors.rgba(120, 110, 92, 235)
        val MODE_NORMAL = ImGuiColors.rgba(148, 118, 66, 235)
        val MODE_HARD = ImGuiColors.rgba(214, 70, 42, 235)

        val MECHANIC_TEXT = ImGuiColors.rgba(255, 194, 74)

        /** Telegraphs get their own colour so an incoming special never reads as a routine prompt. */
        const val TELEGRAPH_RGB = 0xFF2D55

        /**
         * The arena floor is volcanic red-orange, so danger is drawn in cyan rather than the usual
         * red - a red hazard tile is invisible against lava.
         */
        val HAZARD_TILE = ImGuiColors.rgba(0, 229, 255, 175)
        val MECHANIC_TILE = ImGuiColors.rgba(180, 90, 255, 120)

        /** Predicted, not observed - kept visually distinct from the verified mechanic marks. */
        const val SAFE_SECTOR_SIZE = 3
        val SAFE_TILE = ImGuiColors.rgba(90, 255, 140, 105)
        val SAFE_TEXT = ImGuiColors.rgba(150, 255, 190)
        const val START_HINT_MS = 12_000L
        const val START_HINT_SIZE = 3
        val START_TILE = ImGuiColors.rgba(120, 180, 255, 110)
        val START_TEXT = ImGuiColors.rgba(170, 210, 255)
        const val DODGE_GAP = "GAP"
        const val DODGE_MOVE = "MOVE"
        val GAP_TILE = ImGuiColors.rgba(90, 255, 140, 150)
        val GO_TILE = ImGuiColors.rgba(48, 209, 88, 200)
        val GO_TEXT = ImGuiColors.rgba(190, 255, 210)
    }
}
