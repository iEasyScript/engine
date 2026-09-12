package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.game.interfaces.parseAllActionBarItems
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.OptionsConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.adrenaline
import com.projectx.script.api.channelingAbility
import com.projectx.script.api.combatTarget
import com.projectx.script.api.inCombat
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.commands.BeginTableCommand
import com.projectx.ui.backend.dsl.commands.EndTableCommand
import com.projectx.ui.backend.dsl.scopes.LayoutScope
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.popStyleColor
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.pushStyleColor
import com.projectx.ui.backend.dsl.scopes.popStyleVar
import com.projectx.ui.backend.dsl.scopes.pushStyleVar
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.setCursorPosX
import com.projectx.ui.backend.dsl.scopes.tableHeadersRow
import com.projectx.ui.backend.dsl.scopes.tableNextColumn
import com.projectx.ui.backend.dsl.scopes.tableNextRow
import com.projectx.ui.backend.dsl.scopes.tableSetupColumn
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.script.impl.devin.aiobozocombat.QueueRenderer.drawQueue
import com.projectx.script.impl.devin.aiobozocombat.rotations.MeleeIds
import com.projectx.script.impl.devin.aiobozocombat.rotations.RotationCatalog
import com.projectx.script.impl.devin.aiobozocombat.rotations.RotationEntry
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.dsl.utils.ImGuiColors.hex
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.graphicTexture
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.CombatIds
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

@ScriptDescription(
    name = "AIO Bozo Combat",
    version = "0.2.0",
    author = "Devin",
    description = "Rotation coach for EOC 2.0. Shows a tick metronome and a queue of what you just " +
        "pressed, what to press next and when, and what is coming after - abilities, prayers and " +
        "consumables alike. Display-only by default; automation is opt-in under Automation and is " +
        "capped at one input per game tick. Debug tables and a [BozoCap] log are under Debug. " +
        "Boss scripts can install their own rotation.",
    visible = false,
    category = ScriptCategory.COMBAT
)
class AIOBozoCombat : Script(), ConfigurableScript {

    val rotationSection = ConfigSection("Rotation")
    val rotationChoice = OptionsConfigItem(
        "Rotation",
        "Which rotation to coach. \"auto\" installs the one written for whatever you are fighting and " +
            "leaves it alone until a different boss is targeted. A boss script that installs its own " +
            "rotation always wins.",
        RotationCatalog.choices,
        RotationCatalog.AUTO
    )
    val queueHistory = IntConfigItem("Recent shown", "How many previously used actions to show.", 3)
    val queueDepth = IntConfigItem("Lookahead", "How many upcoming abilities to predict.", 3)

    val automationSection = ConfigSection("Automation")
    val autoActivate = BooleanConfigItem(
        "Press abilities for me",
        "Fires the highlighted ability when it is genuinely time. Off by default - only enable once " +
            "the displayed rotation has proven correct, because a wrong suggestion then costs the kill " +
            "rather than a wrong label. Capped at one input per game tick.",
        false
    )

    val displaySection = ConfigSection("Display")
    val showClock = BooleanConfigItem("Tick metronome", "Sweeping bar synced to the game tick.", true)
    val showQueue = BooleanConfigItem("Rotation queue", "Recently used, the next press, and upcoming.", true)

    val debugSection = ConfigSection("Debug")
    val showItems = BooleanConfigItem("Bar items", "Table of item slots on the action bar.", false)
    val showBar = BooleanConfigItem("Ability table", "Every bar ability with its cache-derived values.", false)
    val showFeed = BooleanConfigItem("Action feed", "Rolling log of everything observed firing.", false)
    val showDiagnostics = BooleanConfigItem(
        "Diagnostic columns",
        "Adds bar/slot and cooldown-varc resolution to the ability table, and the raw tick counter.",
        false
    )

    private val feed = ActionFeed()

    @Volatile
    private var snapshot: Snapshot? = null

    private var slowTickCounter = 0
    private var bar: Map<AbilityType, Int> = emptyMap()
    private var tierAbilities: List<AbilityType> = emptyList()
    private var slots: Map<Int, IFSlot> = emptyMap()
    private val activator = RotationActivator()

    @Volatile
    private var pendingPress: Int? = null

    @Volatile
    private var pendingKind: ActionKind? = null
    private var lastBlockReason: String? = null
    private var lastPressDetail: String? = null
    private var boundPrayerBar: String? = null
    private var barRows: List<AbilityRow> = emptyList()
    private var itemRows: List<ItemRow> = emptyList()
    private var dumpRows: List<String> = emptyList()
    private var loggedBarSignature: String? = null
    private var loggedDumpStruct = Int.MIN_VALUE
    private var loggedItemSignature: String? = null
    private var loggedQueueSignature: String? = null
    private var installedLabel: String? = null
    private var lastChannelTick = Int.MIN_VALUE
    private var channellingStruct: Int? = null
    private var channelIteration = 0
    private var channelIterationTick = 0
    private var channelTick = 0
    private var expectedChannelEndTick = Int.MIN_VALUE
    private var seededChannelCast = -1
    private val stabiliser = QueueStabiliser()
    private val textures = HashMap<Int, ImGuiTexture?>()

    override fun onStart() {
        feed.clear()
        PrayerWatcher.reset()
        ItemWatcher.reset()
        activator.reset()
        syncRotation()
        println("[BozoCap] harness started; cycles per tick = ${CombatIds.CYCLES_PER_TICK}")
    }

    override suspend fun loop() {
        runCatching { poll() }
        delay(40, 8)
    }

    private fun poll() {
        val cycle = Bootstrap.client.clientCycle
        if (slowTickCounter-- <= 0) {
            slowTickCounter = SLOW_REFRESH_LOOPS
            refreshSlowState()
            syncRotation()
        }

        val stacks = BLOODLUST_STACKS?.let { varps.getVar(it) } ?: -1
        val empowered = BLOODLUST_EMPOWERED?.let { varps.getVar(it) > 0 } == true
        val adren = adrenaline

        feed.pollAbilities(bar.keys + tierAbilities, cycle) { varcs.getVar(it) }
        PrayerWatcher.poll { struct, label ->
            feed.record(cycle, "prayer $label", "activated", struct)
        }
        ItemWatcher.poll { itemId, name, remaining ->
            feed.record(cycle, "used $name", "$remaining left", null, itemId, name)
        }
        refreshHeldNames()
        feed.pollBloodlust(stacks, empowered, cycle)
        feed.pollAdrenaline(adren, cycle)
        trackChannelSource(cycle)
        AbilityTiers.logIfChanged()
        logChannel(cycle)

        // The queue must be built before activation: it is what decides the pending press.
        val queue = buildQueue(adren, stacks, empowered, cycle)
        if (autoActivate.value) activate(combatSnapshot(adren, stacks, empowered, cycle), cycle)

        snapshot = Snapshot(
            cycle = cycle,
            adrenaline = adren,
            bloodlustStacks = stacks,
            bloodlustEmpowered = empowered,
            abilities = barRows,
            cooldowns = barRows.associate { it.structId to it.liveCooldownTicks(cycle) { v -> varcs.getVar(v) } },
            queue = queue,
            items = itemRows,
            events = feed.snapshot()
        )
    }

    /**
     * Installs whatever the picker asks for, and never touches a provider it did not install itself -
     * a boss script that installs its own rotation knows more about the encounter than a dropdown.
     */
    private fun syncRotation() {
        if (Rotation.installed && Rotation.label != installedLabel) {
            installedLabel = null
            return
        }
        val wanted = when (val choice = rotationChoice.value) {
            RotationCatalog.NONE -> null
            RotationCatalog.AUTO -> targetRotation()
            else -> RotationCatalog.byLabel(choice)
        }
        if (wanted == null) {
            if (installedLabel != null) {
                Rotation.clear()
                installedLabel = null
            }
            return
        }
        if (wanted.label == installedLabel) return
        Rotation.install(wanted.label, wanted.create())
        installedLabel = wanted.label
    }

    /** The rotation for the style the player's bar is set up for, or none when it cannot be told. */
    private fun targetRotation(): RotationEntry? {
        val style = RotationCatalog.styleOnBar(barRows.map { it.structId }) ?: return null
        return RotationCatalog.fallbackFor(style)
    }

    /** Presses the queue's current head, subject to every gate in [RotationActivator]. */
    private fun activate(state: CombatSnapshot, cycle: Int) {
        val next = pendingPress
        if (next == null) {
            // A prayer, a consumable or a positional cue is a real suggestion that simply is not
            // ours to press - saying "no rotation step" there reads as the rotation having failed.
            logBlock(pendingKind?.let { "next step is a ${it.name.lowercase()}, not auto-pressed" } ?: "no rotation step")
            return
        }
        val ability = state.ability(next)
        when {
            !state.inCombat -> logBlock("not in combat")
            ability == null -> logBlock("struct $next not on a bar")
            !ability.ready -> logBlock("${ability.name} on cooldown ${ability.cooldownTicks}t")
            state.adrenaline < ability.adrenalineRequired ->
                logBlock("${ability.name} needs ${ability.adrenalineRequired}%, have ${"%.0f".format(state.adrenaline)}%")
            state.globalCooldownTicks > PRESS_LEAD_TICKS -> logBlock("gcd ${state.globalCooldownTicks}t")
            state.channelTicksRemaining > PRESS_LEAD_TICKS ->
                logBlock("channelling ${state.channelTicksRemaining}t")
            else -> logBlock(null)
        }
        if (ability != null) {
            val slot = slots[AbilityTiers.baseOf(next)] ?: slots[next]
            logPressAttempt(
                "struct=$next ${ability.name} slot=${slot?.interfaceId}:${slot?.componentId} " +
                    "adren=${"%.1f".format(state.adrenaline)} need=${ability.adrenalineRequired} " +
                    "ready=${ability.ready} cd=${ability.cooldownTicks} gcd=${state.globalCooldownTicks} " +
                    "chan=${state.channelTicksRemaining} combat=${state.inCombat} " +
                    "leadReady=${ticksUntilPressable(next, state) <= 0}"
            )
        }
        activator.tryPress(
            structId = next,
            slot = slots[AbilityTiers.baseOf(next)] ?: slots[next],
            keybind = barRows.firstOrNull { it.structId == AbilityTiers.baseOf(next) }?.keybind,
            tick = cycle / CombatIds.CYCLES_PER_TICK,
            inCombat = state.inCombat,
            // Same instant the panel says NOW. Waiting for the global cooldown to actually reach zero
            // is waiting for a moment that never arrives: the auto-attack claims that tick and resets
            // it, so the observed cycle is 3, 2, 1, 3 and never 0.
            pressable = ticksUntilPressable(next, state) <= 0,
            observedCast = { struct -> feed.recentCasts(1).lastOrNull() == struct }
        )
    }

    private fun logPressAttempt(detail: String) {
        if (detail == lastPressDetail) return
        lastPressDetail = detail
        println("[BozoCap] AUTOSTATE $detail")
    }

    private fun logBlock(reason: String?) {
        if (reason == lastBlockReason) return
        lastBlockReason = reason
        if (reason != null) println("[BozoCap] AUTOBLOCK $reason")
    }

    private fun buildQueue(adren: Double, stacks: Int, empowered: Boolean, cycle: Int): QueueModel {
        val byStruct = barRows.associateBy { it.structId }
        fun entry(structId: Int, kind: QueueKind, confidence: Float = 1f): QueueEntry? {
            // A recorded cast may name a recast tier that has no bar slot of its own, so fall back to
            // the base slot for the keybind and read name and icon from the tier's own struct.
            val base = AbilityTiers.baseOf(structId)
            val row = byStruct[structId] ?: byStruct[base] ?: return null
            val shown = if (kind == QueueKind.USED) structId else AbilityTiers.resolve(structId)
            val tiered = AbilityTiers.chainOf(base).size > 1
            if (shown == row.structId && !tiered) {
                return QueueEntry(shown, row.name, row.keybind, row.iconGraphic, kind, confidence)
            }
            val tier = AbilityType(shown)
            return QueueEntry(
                shown,
                tier.name.ifBlank { row.name },
                row.keybind,
                ParamProbe.iconGraphic(shown).takeIf { it > 0 } ?: row.iconGraphic,
                kind,
                confidence
            )
        }

        fun itemEntry(itemId: Int, kind: QueueKind, confidence: Float): QueueEntry {
            val row = itemRows.firstOrNull { it.itemId == itemId }
            return QueueEntry(
                structId = -itemId,
                name = row?.name ?: runCatching { Cache.obj(itemId)?.name }.getOrNull() ?: "item $itemId",
                keybind = row?.keybind,
                iconGraphic = -1,
                kind = kind,
                confidence = confidence,
                actionKind = ActionKind.ITEM
            )
        }

        fun actionEntry(action: RotationAction, kind: QueueKind, confidence: Float = 1f): QueueEntry? =
            when (action) {
                is RotationAction.Ability -> entry(action.structId, kind, confidence)
                is RotationAction.Prayer -> entry(action.structId, kind, confidence)
                    ?.let { it.copy(name = prayerLabel(it.name, action.active), actionKind = ActionKind.PRAYER) }
                is RotationAction.Item -> itemEntry(action.itemId, kind, confidence)
                is RotationAction.Consumable -> QueueEntry(
                    structId = 0,
                    name = action.label,
                    keybind = null,
                    iconGraphic = -1,
                    kind = kind,
                    confidence = confidence,
                    actionKind = ActionKind.ITEM
                )
                is RotationAction.Cue -> QueueEntry(
                    structId = 0,
                    name = action.label,
                    keybind = null,
                    iconGraphic = -1,
                    kind = kind,
                    confidence = confidence,
                    actionKind = ActionKind.CUE
                )
            }

        val history = queueHistory.value.coerceIn(0, MAX_QUEUE_SIDE)
        val recent = feed.recentActions(history).mapNotNull { event ->
            when {
                event.structId != null -> entry(event.structId, QueueKind.USED)
                // An action-bar item slot draws the item's 3D model rather than a cache graphic, and
                // the component's graphicId is aliased to the item id - feeding that to the graphic
                // decoder yields a real but unrelated image. Items render as an abbreviated name.
                event.itemId != null -> QueueEntry(
                    structId = -event.itemId,
                    name = event.displayName ?: "item",
                    keybind = itemRows.firstOrNull { it.itemId == event.itemId }?.keybind,
                    iconGraphic = -1,
                    kind = QueueKind.USED
                )
                else -> null
            }
        }
        val used = List(history) { index ->
            val offset = index - (history - recent.size)
            recent.getOrNull(offset)
        }

        val depth = queueDepth.value.coerceIn(0, MAX_QUEUE_SIDE)
        val live = combatSnapshot(adren, stacks, empowered, cycle)
        val predicted = Rotation.upcoming(live, depth + 1)
        val held = stabiliser.stabilise(predicted.firstOrNull())
        // Only abilities are ever auto-pressed. A prayer or a brew fired by mistake costs far more
        // than a missed ability, and a cue has nothing to press at all.
        pendingPress = held?.abilityStruct
        pendingKind = held?.action?.kind
        val next = held?.let { step ->
            actionEntry(step.action, QueueKind.NEXT)?.copy(
                urgency = step.urgency,
                reason = step.reason,
                timing = timingForAction(step.action, live),
                readyInTicks = ticksUntilAction(step.action, live)
            )
        }

        // An interrupt displaces the plan rather than replacing it, so the player still sees what
        // they were building toward and the queue does not appear to restart.
        val tail = if (held != null && predicted.firstOrNull()?.key != held.key) predicted
        else predicted.drop(1)
        val upcoming = List(depth) { index ->
            tail.getOrNull(index)?.let {
                actionEntry(it.action, QueueKind.UPCOMING, confidence = 1f - (index + 1) * CONFIDENCE_DECAY)
            }
        }
        logQueueIfChanged(recent, used)
        return QueueModel(used, next, upcoming)
    }

    /**
     * Ticks until the press will actually land, whichever constraint is longest. Reported as a single
     * number so the caption never changes shape - a label that swaps between reasons is harder to
     * read at a glance than a countdown that always means the same thing.
     */
    private fun ticksUntilPressable(structId: Int, state: CombatSnapshot): Int {
        val own = state.cooldownTicks(structId).coerceAtLeast(0)
        val gcd = state.globalCooldownTicks.coerceAtLeast(0)
        val blocked = maxOf(own, gcd, state.channelTicksRemaining)
        return (blocked - PRESS_LEAD_TICKS).coerceAtLeast(0)
    }

    /**
     * Only an ability can be blocked by a cooldown. A prayer is off the global cooldown, an item use
     * is immediate, and a cue has nothing to press - all three are always "do it now", and showing a
     * countdown against them would invent a constraint the game does not impose.
     */
    private fun ticksUntilAction(action: RotationAction, state: CombatSnapshot): Int =
        if (action is RotationAction.Ability) ticksUntilPressable(action.structId, state) else 0

    private fun timingForAction(action: RotationAction, state: CombatSnapshot): Timing =
        if (action is RotationAction.Ability) timingFor(action.structId, state) else Timing.NOW

    /** Why the next press cannot happen yet, in the order the player is actually blocked by. */
    private fun timingFor(structId: Int, state: CombatSnapshot): Timing = when {
        ticksUntilPressable(structId, state) <= 0 -> Timing.NOW
        state.channelTicksRemaining > 0 -> Timing.CHANNELLING
        !state.ready(structId) -> Timing.ON_COOLDOWN
        !state.affordable(structId) -> Timing.NO_ADRENALINE
        state.globalCooldownTicks > 0 -> Timing.GLOBAL_COOLDOWN
        else -> Timing.NOW
    }

    /** Traces history from raw recorded ids through to rendered entries, to localise a wrong icon. */
    private fun logQueueIfChanged(recent: List<QueueEntry>, padded: List<QueueEntry?>) {
        val signature = recent.joinToString(",") { "${it.structId}" } + "|" +
            padded.joinToString(",") { it?.let { e -> "${e.structId}:${e.iconGraphic}" } ?: "-" }
        if (signature == loggedQueueSignature) return
        loggedQueueSignature = signature
        val raw = feed.recentCasts(MAX_QUEUE_SIDE).joinToString(",")
        println("[BozoCap] QUEUE rawCasts=[$raw]")
        for ((index, e) in padded.withIndex()) {
            println(
                "[BozoCap] QUEUE  used[$index] " +
                    (e?.let { "struct=${it.structId} icon=${it.iconGraphic} name=${it.name}" } ?: "empty")
            )
        }
    }

    private fun combatSnapshot(adren: Double, stacks: Int, empowered: Boolean, cycle: Int) = CombatSnapshot(
        tick = cycle / CombatIds.CYCLES_PER_TICK,
        adrenaline = adren,
        bloodlustStacks = stacks,
        bloodlustEmpowered = empowered,
        inCombat = runCatching { inCombat }.getOrDefault(false),
        channelTicksRemaining = channelTicksRemaining(),
        targetHealthPercent = runCatching { combatTarget?.let { it.currentHealth * 100.0 / it.maxHealth } }.getOrNull(),
        abilities = barRows.associate { it.structId to it.toAbilityState(cycle) { v -> varcs.getVar(v) } },
        globalCooldownTicks = ceil(globalCooldownTicks(cycle)).toInt(),
        channelling = runCatching { channelingAbility }.getOrDefault(false),
        activePrayers = PrayerWatcher.active,
        itemCounts = ItemWatcher.held,
        heldNames = heldItemNames
    )

    /**
     * Backpack names, refreshed only when the set of held ids changes. Resolving every name through
     * the cache on the fast poll is wasted work in a fight where the backpack rarely changes.
     */
    private var heldItemIds: Set<Int> = emptySet()
    private var heldItemNames: Set<String> = emptySet()

    private fun refreshHeldNames() {
        val ids = ItemWatcher.held.keys
        if (ids == heldItemIds) return
        heldItemIds = ids.toSet()
        heldItemNames = ids.mapNotNullTo(HashSet()) {
            runCatching { Cache.obj(it)?.name }.getOrNull()?.lowercase()
        }
    }

    /** The channelling ability is not readable from a var, so it is inferred from the last cast. */
    private fun trackChannelSource(cycle: Int) {
        channelTick = cycle / CombatIds.CYCLES_PER_TICK
        seedExpectedChannel()
        val current = runCatching { CHANNEL_ITERATIONS_VARP?.let { varps.getVar(it) } ?: 0 }.getOrDefault(0)
        if (current <= 0) {
            if (channelTick >= expectedChannelEndTick) channellingStruct = null
            channelIteration = 0
            return
        }
        if (current != channelIteration) {
            channelIteration = current
            channelIterationTick = channelTick
        }
        if (channellingStruct != null) return
        channellingStruct = feed.recentCasts(1).lastOrNull()
            ?.takeIf { structParam(it, CHANNEL_ITERATIONS) > 0 }
    }

    /** Correlates the computed channel remainder against the game's own bar, once per tick. */
    private fun logChannel(cycle: Int) {
        val iterations = runCatching { CHANNEL_ITERATIONS_VARP?.let { varps.getVar(it) } ?: 0 }.getOrDefault(0)
        val tick = cycle / CombatIds.CYCLES_PER_TICK
        if (iterations <= 0) {
            if (lastChannelTick != Int.MIN_VALUE) {
                println("[BozoCap] CHANNEL end tick=$tick")
                lastChannelTick = Int.MIN_VALUE
            }
            return
        }
        if (tick == lastChannelTick) return
        lastChannelTick = tick
        val struct = channellingStruct ?: -1
        val name = runCatching { if (struct > 0) AbilityType(struct).name else "?" }.getOrDefault("?")
        println(
            "[BozoCap] CHANNEL tick=$tick ability=$struct($name) iteration=$iterations " +
                "of=${structParam(struct, CHANNEL_ITERATIONS)} rate=${structParam(struct, CHANNEL_RATE)} " +
                "computed=${channelTicksRemaining()} gcd=${ceil(globalCooldownTicks(cycle)).toInt()}"
        )
    }

    /**
     * Claims the channel the moment its cast is seen, rather than waiting for the varp.
     *
     * The iteration varp does not populate until a tick after the cast, so for that one tick the only
     * visible constraint is the global cooldown - the countdown shows ~2 and then jumps up to the real
     * channel length. The full duration is known from the ability's own params at cast time, so there
     * is no reason to wait for it.
     */
    private fun seedExpectedChannel() {
        val cast = feed.recentCasts(1).lastOrNull() ?: return
        if (cast == seededChannelCast) return
        seededChannelCast = cast
        val iterations = structParam(cast, CHANNEL_ITERATIONS)
        if (iterations <= 0) return
        val rate = structParam(cast, CHANNEL_RATE).coerceAtLeast(1)
        expectedChannelEndTick = channelTick + iterations * rate
        channellingStruct = cast
    }

    /**
     * Ticks left in a running channel; pressing before it expires loses the remaining hits.
     *
     * The iteration varp counts *up* toward the ability's total rather than down toward zero, and it
     * only advances once per `rate` ticks - so deriving the remainder from it alone both expires the
     * countdown early and makes a rate-2 ability count down 5, 3, 1, visibly skipping ticks. Elapsed
     * ticks within the current iteration are tracked separately so it falls by exactly one per tick.
     *
     * The estimate is deliberately kept a tick long: waiting one tick too many costs nothing, while
     * clipping a channel costs every hit that was left in it.
     */
    private fun channelTicksRemaining(): Int {
        // Guard the subtraction rather than clamping it: the unset sentinel is Int.MIN_VALUE, and
        // MIN_VALUE minus a positive tick overflows to a huge positive instead of going negative.
        val expected = if (expectedChannelEndTick <= channelTick) 0 else expectedChannelEndTick - channelTick
        val current = runCatching { CHANNEL_ITERATIONS_VARP?.let { varps.getVar(it) } ?: 0 }.getOrDefault(0)
        if (current <= 0) return expected
        val struct = channellingStruct ?: return maxOf(current, expected)
        val total = structParam(struct, CHANNEL_ITERATIONS)
        val rate = structParam(struct, CHANNEL_RATE).coerceAtLeast(1)
        if (total <= 0) return maxOf(current, expected)
        val elapsed = (channelTick - channelIterationTick).coerceIn(0, rate - 1)
        val fromVarp = ((total - current) * rate + (rate - elapsed)).coerceAtLeast(0)
        return maxOf(fromVarp, expected)
    }

    private fun structParam(structId: Int, param: String): Int = runCatching {
        Gameval.id(Gameval.PARAM, param)?.let { Cache.struct(structId)?.getIntValue(it, 0) } ?: 0
    }.getOrDefault(0)

    private fun globalCooldownTicks(cycle: Int): Double = runCatching {
        val end = varcs.getVar(CombatIds.GLOBAL_COOLDOWN_END_VARC)
        if (end > cycle) (end - cycle).toDouble() / CombatIds.CYCLES_PER_TICK else 0.0
    }.getOrDefault(0.0)

    private fun refreshSlowState() {
        val parsed = runCatching { parseAllActionBarAbilities() }.getOrDefault(emptyMap())
        bar = parsed.mapValues { it.value.componentId }
        // Retarget each slot onto its actual button before anything tries to click it.
        slots = parsed.entries.associate { (ability, slot) ->
            val button = BarLayout.buttonComponent(slot.interfaceId, slot.componentId)
            ability.structId to (button?.let { IFSlot(slot.interfaceId, it) } ?: slot)
        }
        tierAbilities = AbilityTiers.extraStructsFor(parsed.keys.map { it.structId }).map { AbilityType(it) }
        val barSignature = parsed.keys.joinToString(",") { "${it.structId}" }
        if (barSignature != boundPrayerBar) {
            boundPrayerBar = barSignature
            PrayerWatcher.bind(parsed)
        }
        barRows = parsed.entries
            .map { (ability, slot) ->
                val place = BarLayout.location(slot.interfaceId, slot.componentId)
                ability.toRow(
                    BarLayout.keybind(slot.interfaceId, slot.componentId)?.escapeNonPrintable(),
                    place?.first ?: Int.MAX_VALUE,
                    place?.second ?: Int.MAX_VALUE,
                    BarLayout.iconGraphic(slot.interfaceId, slot.componentId)
                )
            }
            .sortedWith(compareBy({ it.barLoc }, { it.slot }))
        itemRows = runCatching {
            parseAllActionBarItems().map { (itemId, slot) ->
                ItemRow(
                    name = Cache.obj(itemId)?.name ?: "item $itemId",
                    itemId = itemId,
                    keybind = BarLayout.keybind(slot.interfaceId, slot.componentId)?.escapeNonPrintable(),
                    location = BarLayout.describe(slot.interfaceId, slot.componentId)
                )
            }
        }.getOrDefault(emptyList())
        dumpRows = dumpParams(PARAM_DUMP_STRUCT)
        logTablesIfChanged()
    }

    /** The static tables are the answer to most open questions, so they go to the log, not just the UI. */
    private fun logTablesIfChanged() {
        val signature = barRows.joinToString("|") { it.name }
        if (signature != loggedBarSignature) {
            loggedBarSignature = signature
            println("[BozoCap] BAR ${barRows.size} abilities")
            for (row in barRows) println("[BozoCap] BAR ${row.logLine()}")
        }
        val itemSignature = itemRows.joinToString("|") { "${it.itemId}@${it.location}" }
        if (itemSignature != loggedItemSignature) {
            loggedItemSignature = itemSignature
            for (row in itemRows) println("[BozoCap] ITEM ${row.itemId} ${row.name} at ${row.location} key=${row.keybind ?: "-"}")
        }
        if (PARAM_DUMP_STRUCT != loggedDumpStruct) {
            loggedDumpStruct = PARAM_DUMP_STRUCT
            for (row in dumpRows) println("[BozoCap] PARAM struct=$PARAM_DUMP_STRUCT $row")
        }
    }

    private fun dumpParams(structId: Int): List<String> {
        if (structId <= 0) return emptyList()
        val struct = runCatching { Cache.struct(structId) }.getOrNull() ?: return listOf("struct $structId not found")
        val values = struct.getValues()
        if (values.isEmpty()) return listOf("struct $structId has no params")
        return values.entries.sortedBy { it.key }.map { (id, value) ->
            "%-6d %-46s %s".format(id, Gameval.param(id) ?: "(unnamed)", value)
        }
    }

    private fun LayoutScope.metronome(cycle: Int, railWidth: Float, showTick: Boolean) {
        val phase = cycle % CombatIds.CYCLES_PER_TICK
        val barWidth = railWidth.coerceAtLeast(METRONOME_MIN_WIDTH)
        pushStyleColor(ImGuiCol.PlotHistogram, METRONOME_FILL)
        pushStyleColor(ImGuiCol.FrameBg, METRONOME_TRACK)
        pushStyleVar(ImGuiStyleVar.FrameRounding, METRONOME_ROUNDING)
        progressBar(phase.toFloat() / CombatIds.CYCLES_PER_TICK, barWidth, METRONOME_HEIGHT, BLANK_OVERLAY)
        popStyleVar(1)
        popStyleColor(2)

        if (!showTick) return
        val label = "${cycle / CombatIds.CYCLES_PER_TICK}"
        setCursorPosX(((railWidth - QueueRenderer.textWidth(label)) / 2f).coerceAtLeast(0f))
        pushStyleColor(ImGuiCol.Text, TICK_TEXT)
        text(label)
        popStyleColor(1)
    }

    /**
     * The snapshot refreshes far slower than the frame rate, which makes the metronome step in
     * visible jumps, so the cycle counter is read live here and falls back to the snapshot.
     */
    private fun liveCycle(fallback: Int): Int =
        runCatching { Bootstrap.client.clientCycle }.getOrDefault(fallback)

    /** Textures are GL objects, so they are only ever created here on the render thread. */
    private fun textureFor(graphic: Int): ImGuiTexture? {
        if (graphic <= 0) return null
        if (textures.containsKey(graphic)) return textures[graphic]
        val texture = runCatching { graphicTexture(graphic) }.getOrNull()
        textures[graphic] = texture
        return texture
    }

    override fun render() {
        val state = snapshot ?: return
        window("AIO Bozo Combat##aiobozo-harness", HUD_FLAGS) {
            pushStyleVar(ImGuiStyleVar.ItemSpacing, ITEM_GAP, ITEM_GAP)
            pushStyleVar(ImGuiStyleVar.CellPadding, CELL_PAD, CELL_PAD)
            pushStyleVar(ImGuiStyleVar.FramePadding, FRAME_PAD, FRAME_PAD)
            val cycle = liveCycle(state.cycle)
            val railWidth = QueueRenderer.railWidthFor(state.queue.used.size, state.queue.upcoming.size)
            if (showClock.value) metronome(cycle, railWidth, showDiagnostics.value)
            if (showQueue.value) {
                val phase = cycle % CombatIds.CYCLES_PER_TICK
                drawQueue(state.queue, phase.toFloat() / CombatIds.CYCLES_PER_TICK) { textureFor(it) }
            }

            if (showBar.value) {
                separator()
                text("ABILITIES ON YOUR ACTION BARS - cache values vs live state (${state.abilities.size})")
                val diagnostics = showDiagnostics.value
                val columns = buildList {
                    addAll(listOf("icon", "ability", "key", "tier", "adren", "ready in", "base cd", "buff"))
                    if (diagnostics) addAll(listOf("bar/slot", "varc"))
                }
                imageTable("bar##aiobozo", columns) {
                    for (row in state.abilities) {
                        tableNextRow()
                        tableNextColumn()
                        val texture = if (row.iconGraphic > 0) textureFor(row.iconGraphic) else null
                        if (texture != null) image(texture, ICON_SIZE, ICON_SIZE) else text("-")
                        tableNextColumn(); text(row.name + if (row.empowerable) " *" else "")
                        tableNextColumn(); text(row.keybind ?: "-")
                        tableNextColumn(); text(row.tierName)
                        tableNextColumn(); text(row.adrenLabel())
                        tableNextColumn(); text(row.readyInLabel(state.cooldowns[row.structId] ?: 0.0))
                        tableNextColumn(); text(row.baseCooldownLabel())
                        tableNextColumn(); text(row.buffLabel())
                        if (diagnostics) {
                            tableNextColumn(); text("${row.barLoc}.${row.slot}")
                            tableNextColumn(); text(row.varcStatus)
                        }
                    }
                }
                if (diagnostics) text(VARC_LEGEND)
            }

            if (showItems.value) {
                separator()
                text("ITEMS ON YOUR ACTION BARS - food, potions, bombs (${state.items.size})")
                imageTable("items##aiobozo", listOf("item", "id", "bar/slot", "key")) {
                    for (row in state.items) {
                        tableNextRow()
                        tableNextColumn(); text(row.name)
                        tableNextColumn(); text(row.itemId.toString())
                        tableNextColumn(); text(row.location)
                        tableNextColumn(); text(row.keybind ?: "-")
                    }
                }
            }

            if (showFeed.value) {
                separator()
                text("WHAT ACTUALLY FIRED - newest first, any source including revolution")
                imageTable("feed##aiobozo", listOf("tick", "event", "detail")) {
                    for (event in state.events.asReversed().take(MAX_FEED_ROWS)) {
                        tableNextRow()
                        tableNextColumn(); text(event.tick.toString())
                        tableNextColumn(); text(event.label)
                        tableNextColumn(); text(event.detail)
                    }
                }
            }
            popStyleVar(3)
        }
    }
}

private class Snapshot(
    val cycle: Int,
    val adrenaline: Double,
    val bloodlustStacks: Int,
    val bloodlustEmpowered: Boolean,
    val abilities: List<AbilityRow>,
    val cooldowns: Map<Int, Double>,
    val queue: QueueModel,
    val items: List<ItemRow>,
    val events: List<FeedEvent>
)

private class ItemRow(
    val name: String,
    val itemId: Int,
    val keybind: String?,
    val location: String
)

private class AbilityRow(
    val name: String,
    val structId: Int,
    val tierName: String,
    val costPercent: Double,
    val generatedPercent: Double,
    val cooldownParamTicks: Int,
    val activeTicks: Int,
    val channelTicks: Int,
    val empowerable: Boolean,
    val keybind: String?,
    val varcStatus: String,
    val barLoc: Int,
    val slot: Int,
    val derivedStart: Int,
    val derivedEnd: Int,
    val hardcodedEnd: Int,
    val iconGraphic: Int,
    val paramGraphic: Int
) {
    fun toAbilityState(nowCycle: Int, readVarc: (Int) -> Int): AbilityState = AbilityState(
        structId = structId,
        name = name,
        keybind = keybind,
        iconGraphic = iconGraphic,
        tier = tierName,
        adrenalineRequired = costPercent,
        adrenalineCost = ObservedCosts.costOf(structId, costPercent),
        adrenalineGenerated = generatedPercent,
        cooldownTicks = ceil(liveCooldownTicks(nowCycle, readVarc)).toInt(),
        ready = liveCooldownTicks(nowCycle, readVarc) <= 0.0,
        buffTicks = activeTicks,
        channelTicks = channelTicks
    )

    fun liveCooldownTicks(nowCycle: Int, readVarc: (Int) -> Int): Double =
        CooldownVarcs.remainingTicks(structId, nowCycle, readVarc)

    fun adrenLabel(): String = when {
        costPercent > 0 -> "-%.0f%%".format(costPercent)
        generatedPercent > 0 -> "+%.0f%%".format(generatedPercent)
        else -> "-"
    }

    /**
     * A tick is indivisible, so a partial tick still has to elapse in full: the sub-tick remainder
     * rounds up to the tick the ability actually becomes available on. [remainingTicks] must come
     * from the fast poll - sampling it on the slow refresh makes the countdown skip whole ticks.
     */
    fun readyInLabel(remainingTicks: Double): String {
        val ticks = ceil(remainingTicks).toInt()
        if (ticks <= 0) return "ready"
        return "${ticks}t (%.1fs)".format(ticks * SECONDS_PER_TICK)
    }

    fun baseCooldownLabel(): String {
        if (cooldownParamTicks <= 0) return "-"
        val channel = if (channelTicks > 0) " ch ${channelTicks}t" else ""
        return "${cooldownParamTicks}t (%.1fs)".format(cooldownParamTicks * SECONDS_PER_TICK) + channel
    }

    fun buffLabel(): String =
        if (activeTicks > 0) "${activeTicks}t (%.1fs)".format(activeTicks * SECONDS_PER_TICK) else "-"

    fun logLine(): String =
        "%-20s struct=%-6d tier=%-11s req=%-5.0f gen=%-5.0f cdParam=%-4d active=%-4d chan=%-3d key=%-5s at=%-9s icon=%-6d param_icon=%-6d varc=%-9s start=%-6d end=%-6d table=%d".format(
            name, structId, tierName, costPercent, generatedPercent, cooldownParamTicks, activeTicks,
            channelTicks, keybind ?: "-", "$barLoc.$slot", iconGraphic, paramGraphic, varcStatus, derivedStart, derivedEnd, hardcodedEnd
        )

}

private fun AbilityType.toRow(keybind: String?, barLoc: Int, slot: Int, liveGraphic: Int): AbilityRow {
    val iterations = cacheParam(CHANNEL_ITERATIONS)
    val rate = cacheParam(CHANNEL_RATE)
    val varcs = CooldownVarcs.resolve(structId)
    return AbilityRow(
        name = name.ifBlank { "struct $structId" },
        structId = structId,
        tierName = adrenalineTierLabel(),
        costPercent = adrenalineReq / ADRENALINE_UNITS_PER_PERCENT,
        generatedPercent = adrenalineGenerated / ADRENALINE_UNITS_PER_PERCENT,
        cooldownParamTicks = cooldownParamTicks,
        activeTicks = ParamProbe.activeTicks(structId),
        channelTicks = if (isChannelled) iterations * rate.coerceAtLeast(1) else 0,
        empowerable = structId in BLOODLUST_BASE_ABILITIES,
        keybind = keybind,
        varcStatus = varcs.status,
        barLoc = barLoc,
        slot = slot,
        derivedStart = varcs.derivedStart,
        derivedEnd = varcs.derivedEnd,
        hardcodedEnd = varcs.hardcodedEnd,
        iconGraphic = if (liveGraphic > 0) liveGraphic else ParamProbe.iconGraphic(structId),
        paramGraphic = ParamProbe.iconGraphic(structId)
    )
}

/**
 * A table that can hold images. The DSL's own `table` builds a [TableScope], which has no image
 * support and does not extend [LayoutScope], so the begin/end commands are emitted directly instead.
 */
private inline fun LayoutScope.imageTable(id: String, columns: List<String>, body: () -> Unit) {
    commands.add(BeginTableCommand(id, columns.size, TABLE_FLAGS, AtomicReference(false)))
    for (column in columns) tableSetupColumn(column)
    tableHeadersRow()
    body()
    commands.add(EndTableCommand())
}

/** A prayer step asks for a state, so the tile has to say which one rather than just naming it. */
private fun prayerLabel(name: String, active: Boolean): String = if (active) name else "$name off"

/** Non-ASCII keybind labels came back as replacement characters, so surface codepoints to diagnose. */
private fun String.escapeNonPrintable(): String =
    if (all { it.code in 0x20..0x7E }) this
    else map { if (it.code in 0x20..0x7E) it.toString() else "\\u%04X".format(it.code) }.joinToString("")

/**
 * `adrenaline_type` is absent on prayers, teleports and other non-combat bar entries, and the param
 * reader defaults a missing value to 0 - which [AdrenalineType] maps to AUTO_ATTACK. Distinguish the
 * two so they are not mislabelled as auto-attacks.
 */
private fun AbilityType.adrenalineTierLabel(): String {
    val declared = runCatching {
        Cache.struct(structId)?.getValues()?.containsKey(CombatIds.ABILITY_ADRENALINE_TYPE)
    }.getOrNull() == true
    if (!declared) return "-"
    return adrenalineTier?.name?.lowercase() ?: "-"
}

private fun AbilityType.cacheParam(name: String): Int =
    Gameval.id(Gameval.PARAM, name)?.let { Cache.struct(structId)?.getIntValue(it, 0) } ?: 0

private const val CHANNEL_ITERATIONS = "combatv2_ability_channel_iterations"
private const val CHANNEL_RATE = "combatv2_ability_channel_rate"
private const val ADRENALINE_UNITS_PER_PERCENT = 10.0
private const val ITEM_GAP = 4f
private const val CELL_PAD = 3f
private const val FRAME_PAD = 3f
private const val METRONOME_ROUNDING = 3f
private val TICK_TEXT = hex("#6E6152")
private val HUD_FLAGS = WindowFlags.NoTitleBar + WindowFlags.NoScrollbar +
    WindowFlags.NoScrollWithMouse + WindowFlags.NoCollapse + WindowFlags.NoFocusOnAppearing +
    WindowFlags.AlwaysAutoResize
private const val METRONOME_MIN_WIDTH = 120f
private const val METRONOME_HEIGHT = 6f

/** ImGui draws its default "XX%" when the overlay is null, and an empty string reaches it as null. */
private const val BLANK_OVERLAY = " "
private val METRONOME_FILL = hex("#C9A227")
private val METRONOME_TRACK = hex("#241E19")
private const val SECONDS_PER_TICK = 0.6
private const val VARC_LEGEND =
    "varc: ok = derived cooldown varc matches :core's table · NEW = derived one :core is missing · " +
        "none = ability has no cooldown varc · CONFLICT = derivation disagrees, treat as a bug"
private const val ICON_SIZE = 26f
private const val TABLE_FLAGS = 1 or 64 or 1920
private const val MAX_FEED_ROWS = 25
private const val MAX_QUEUE_SIDE = 6
private const val CONFIDENCE_DECAY = 0.22f

/**
 * Set to a struct id to dump every param on it with its gameval name, then rebuild.
 *
 * Developer tooling, not a setting: it exists because a `:core` accessor pointed at the wrong param
 * returns 0 rather than failing, which is indistinguishable from the ability genuinely having no
 * value there. Zero disables it.
 */
private const val PARAM_DUMP_STRUCT = 0

/**
 * The countdown leads the game by a tick.
 *
 * An input issued during the tick before an ability frees up is queued and lands on that tick; wait
 * until the game would already accept it and the auto-attack or revolution has taken the slot. Every
 * missed activation costs a full global cooldown of damage, so the cue fires a tick early by design.
 */
private const val PRESS_LEAD_TICKS = 1
private const val SLOW_REFRESH_LOOPS = 12

private val CHANNEL_ITERATIONS_VARP = Gameval.id(Gameval.VAR_PLAYER, "combatv2_channel_iterations")
private val BLOODLUST_STACKS = Gameval.id(Gameval.VAR_PLAYER, "combatv2_buff_melee_bloodlust_stacks")
private val BLOODLUST_EMPOWERED =
    Gameval.id(Gameval.VAR_PLAYER, "combatv2_active_attack_buff_melee_bloodlust_empowered")

/**
 * The abilities that have a `_bloodlust` variant struct, marked with a `*` in the debug table.
 *
 * The variants are parameter-identical to their base abilities - same name, cooldown, adrenaline and
 * channel values - so the cache says which abilities are empowerable but nothing about what the
 * empowered version does.
 */
private val BLOODLUST_BASE_ABILITIES = setOf(MeleeIds.FLURRY, MeleeIds.HURRICANE, MeleeIds.ASSAULT)
