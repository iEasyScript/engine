package com.projectx.script.impl.devin.aiobozocombat

/**
 * One thing the player should do next.
 *
 * A boss is not survived by pressing abilities alone — the prayer, the brew, the step out of the gas
 * and the ability all belong on one timeline, because to a player learning the fight they are the same
 * decision. Abilities are only the kind that happens to have a cooldown.
 */
sealed interface RotationAction {

    /** Press an ability on the action bar. */
    class Ability(val structId: Int) : RotationAction

    /**
     * Bring a prayer to a state, rather than press it.
     *
     * A prayer has no cooldown and no cost, so it is permanently pressable and a press-shaped rule
     * would win its slot forever. Suggesting a *state* is what makes it self-limiting: once the
     * prayer is on, the step is satisfied and the queue moves on.
     */
    class Prayer(val structId: Int, val active: Boolean = true) : RotationAction

    /** Eat, drink, or throw one specific item. Read from the backpack, not just the bar. */
    class Item(val itemId: Int) : RotationAction

    /**
     * A family of items rather than one id — "antipoison", "brew", "sailfish".
     *
     * Consumables are dose-split and tier-split: antipoison alone is three ids before super and
     * extreme variants. A step naming one id is right for the dose the player happens to hold and
     * silently wrong for every other, so families match on the name the player reads.
     */
    class Consumable(val label: String, val nameFragment: String) : RotationAction

    /**
     * Something to do that is not a press — move out of the gas, get behind the pillar, stop
     * attacking. The mechanics that kill new players are mostly this kind, and a coach that can only
     * name abilities cannot teach them.
     */
    class Cue(val label: String) : RotationAction
}

enum class ActionKind { ABILITY, PRAYER, ITEM, CUE }

val RotationAction.kind: ActionKind
    get() = when (this) {
        is RotationAction.Ability -> ActionKind.ABILITY
        is RotationAction.Prayer -> ActionKind.PRAYER
        is RotationAction.Item -> ActionKind.ITEM
        is RotationAction.Consumable -> ActionKind.ITEM
        is RotationAction.Cue -> ActionKind.CUE
    }

/** Identity for "is this the same action", used both by the projection and by the display's hold logic. */
val RotationAction.projectionKey: String
    get() = when (this) {
        is RotationAction.Ability -> "ability:$structId"
        is RotationAction.Prayer -> "prayer:$structId:$active"
        is RotationAction.Item -> "item:$itemId"
        is RotationAction.Consumable -> "consumable:$nameFragment"
        is RotationAction.Cue -> "cue:$label"
    }

/**
 * A read-only view of everything a rotation needs to decide what to do next. Handed to a
 * [RotationProvider] so providers stay pure — they may not click, suspend or mutate, which is what
 * makes running one against a predicted future possible at all.
 */
class CombatSnapshot(
    val tick: Int,
    val adrenaline: Double,
    val bloodlustStacks: Int,
    val bloodlustEmpowered: Boolean,
    val inCombat: Boolean,
    val targetHealthPercent: Double?,
    /** Ticks left on the global cooldown; nothing can be pressed until this reaches zero. */
    val globalCooldownTicks: Int,
    /** A channel is running. Pressing now truncates it and loses the remaining hits. */
    val channelling: Boolean,
    /** Ticks left in that channel; zero when none is running. */
    val channelTicksRemaining: Int,
    private val abilities: Map<Int, AbilityState>,
    /** Struct ids of the prayers currently lit on the bar. Only barred prayers are visible. */
    val activePrayers: Set<Int> = emptySet(),
    private val itemCounts: Map<Int, Int> = emptyMap(),
    /** Lowercased names of everything in the backpack, for matching dose- and tier-split families. */
    private val heldNames: Set<String> = emptySet()
) {
    fun ability(structId: Int): AbilityState? = abilities[structId]

    fun barred(structId: Int): Boolean = structId in abilities

    fun ready(structId: Int): Boolean = abilities[structId]?.ready == true

    fun cooldownTicks(structId: Int): Int = abilities[structId]?.cooldownTicks ?: Int.MAX_VALUE

    /**
     * Gates on the *requirement*, not the spend. An ultimate can require 100% while only removing
     * 80%, and checking against the spend says it is castable at 80 where the game rejects the press.
     */
    fun affordable(structId: Int): Boolean {
        val ability = abilities[structId] ?: return false
        return adrenaline >= ability.adrenalineRequired
    }

    /** Ready, affordable, and on a bar — the baseline every rule needs before anything style-specific. */
    fun usable(structId: Int): Boolean = ready(structId) && affordable(structId)

    /** Usable *and* the game will actually accept the press this instant. */
    fun pressableNow(structId: Int): Boolean =
        usable(structId) && globalCooldownTicks <= 0 && channelTicksRemaining <= 0

    fun prayerActive(structId: Int): Boolean = structId in activePrayers

    fun itemCount(itemId: Int): Int = itemCounts[itemId] ?: 0

    fun hasItem(itemId: Int): Boolean = itemCount(itemId) > 0

    fun holdsNamed(fragment: String): Boolean {
        val needle = fragment.lowercase()
        return heldNames.any { it.contains(needle) }
    }

    /**
     * The action still needs doing. Everything except a prayer always does — a prayer already in the
     * requested state is the one case where the right advice is to say nothing.
     */
    fun outstanding(action: RotationAction): Boolean = when (action) {
        is RotationAction.Prayer -> prayerActive(action.structId) != action.active
        else -> true
    }

    /** The player can act on it right now: barred and affordable, or in the backpack. */
    fun performable(action: RotationAction): Boolean = when (action) {
        is RotationAction.Ability -> usable(action.structId)
        is RotationAction.Prayer -> barred(action.structId)
        is RotationAction.Item -> hasItem(action.itemId)
        is RotationAction.Consumable -> holdsNamed(action.nameFragment)
        is RotationAction.Cue -> true
    }

    val all: Collection<AbilityState> get() = abilities.values
}

class AbilityState(
    val structId: Int,
    val name: String,
    val keybind: String?,
    val iconGraphic: Int,
    val tier: String,
    /** What the game demands before it will accept the press — the cache's declared requirement. */
    val adrenalineRequired: Double,
    /** What the bar actually loses, learned by observation. Ultimates spend less than they require. */
    val adrenalineCost: Double,
    val adrenalineGenerated: Double,
    val cooldownTicks: Int,
    val ready: Boolean,
    val buffTicks: Int,
    /** Ticks a channel occupies; the queue must advance by this, not by one global cooldown. */
    val channelTicks: Int
)

/** How the next press should be signalled: press it, or wait and why. */
enum class Timing { NOW, GLOBAL_COOLDOWN, CHANNELLING, ON_COOLDOWN, NO_ADRENALINE }

enum class QueueKind { USED, NEXT, UPCOMING }

/**
 * Why a step is being suggested, which decides how disruptively it may change the display.
 *
 * [ROTATION] steps are the planned sequence and are held steady — a rotation that reshuffles on
 * transient state is unreadable mid-fight. [INTERRUPT] steps preempt that immediately and announce
 * themselves, because a mechanic that needs a stun cannot wait for the display to settle.
 *
 * Reserve [INTERRUPT] for mechanics that punish a missed press — a stun that stops a channelled
 * attack, a defensive for an unavoidable hit, a forced move. Never for a damage gain: an interrupt
 * that fires for DPS trains the player to ignore interrupts.
 */
enum class Urgency { ROTATION, INTERRUPT }

/** One suggested action. [reason] is shown to the player for interrupts, so a deviation teaches. */
class RotationStep(
    val action: RotationAction,
    val urgency: Urgency = Urgency.ROTATION,
    val reason: String? = null
) {
    constructor(structId: Int, urgency: Urgency = Urgency.ROTATION, reason: String? = null) :
        this(RotationAction.Ability(structId), urgency, reason)

    /** The ability this step presses, or null when it is a prayer, an item or a cue. */
    val abilityStruct: Int? get() = (action as? RotationAction.Ability)?.structId

    /** Identity for "is this still the same suggestion", which drives the display's hold logic. */
    val key: String get() = action.projectionKey
}

data class QueueEntry(
    val structId: Int,
    val name: String,
    val keybind: String?,
    val iconGraphic: Int,
    val kind: QueueKind,
    /** Drives how strongly the entry renders; simulated steps fade as they get further out. */
    val confidence: Float = 1f,
    val urgency: Urgency = Urgency.ROTATION,
    val reason: String? = null,
    val timing: Timing = Timing.NOW,
    val readyInTicks: Int = 0,
    val actionKind: ActionKind = ActionKind.ABILITY
)

/**
 * Fixed-length queue sides. Nulls are empty slots the renderer still allocates space for, so the
 * rail keeps constant geometry as entries come and go.
 */
class QueueModel(
    val used: List<QueueEntry?>,
    val next: QueueEntry?,
    val upcoming: List<QueueEntry?>
)

/**
 * Supplies what a player should do next.
 *
 * Boss scripts provide their own rather than the overlay hardcoding one — an encounter knows things
 * a generic rotation cannot, such as when to hold an ultimate through a phase transition.
 *
 * Implementations must be pure: [upcoming] may read game state, but must not click, suspend, sleep,
 * mutate shared state or throw. That purity is what makes running the policy against a *predicted*
 * future possible at all, and it is called on every ~40ms poll.
 *
 * **Degrade to silence rather than to a guess.** An ability the player has not barred, a var that
 * will not resolve, a phase nothing has identified — return fewer steps, or none. A confidently
 * wrong queue is worse than no queue, because a wrong suggestion the player follows costs the kill.
 * The exception is when there *is* a reason worth teaching: a boss absorbing damage wants a cue
 * saying so, since a blank rail reads as the overlay having lost the fight.
 */
fun interface RotationProvider {
    /** Steps in order, most immediate first. Empty means "no opinion". */
    fun upcoming(state: CombatSnapshot, depth: Int): List<RotationStep>
}

/**
 * The single provider the overlay renders. Boss scripts install one on start and clear it on stop;
 * with none installed the overlay shows observed history only and leaves the prediction side blank.
 */
object Rotation {
    @Volatile
    private var provider: RotationProvider? = null

    @Volatile
    var label: String = "none"
        private set

    fun install(name: String, provider: RotationProvider) {
        this.provider = provider
        label = name
        println("[BozoCap] ROTATION installed: $name")
    }

    fun clear() {
        provider = null
        label = "none"
        println("[BozoCap] ROTATION cleared")
    }

    val installed: Boolean get() = provider != null

    /**
     * The live provider, so an encounter script can drive one it did not install itself — the overlay
     * builds a rotation from its own picker, and its threat channel is unreachable without this.
     */
    val current: RotationProvider? get() = provider

    fun upcoming(state: CombatSnapshot, depth: Int): List<RotationStep> =
        runCatching { provider?.upcoming(state, depth) ?: emptyList() }.getOrDefault(emptyList())
}
