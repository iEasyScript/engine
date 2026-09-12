package com.projectx.script.impl.devin.zuk

import java.util.concurrent.ConcurrentHashMap

/**
 * The game narrates every mechanic in chat, which makes these observed events rather than
 * inferences - a mechanic confirmed here has actually happened, unlike a varbit we polled.
 *
 * Zuk announces each special one tick (0.6s) before it lands, so these lines are the earliest
 * possible warning and the only one that arrives before the damage. Matching is on a distinctive
 * fragment rather than the whole line so a wording tweak or an embedded colour tag cannot
 * silently stop the coach reacting.
 */
enum class ZukMessage(
    val fragments: List<String>,
    val alert: String?,
    val holdMs: Long,
    val prayer: CombatStyle? = null,
    val abilities: List<String> = emptyList(),
    val exact: List<String> = emptyList()
) {
    GEOTHERMAL_BURN(
        listOf("You will break beneath me"), "FREEDOM - after the hit", 5000, CombatStyle.MELEE,
        listOf(Abilities.FREEDOM)
    ),
    SEAR(
        emptyList(), "DEFLECT MAGIC -> SURGE AWAY", 6000, CombatStyle.MAGIC,
        Abilities.MOVEMENT, exact = listOf("Sear!", "Suffer!", "Burn!")
    ),
    QUAKE(
        listOf("Tremble before me", "Fall before my might", "The earth yields to me"),
        "SURGE PAST ZUK - eruptions incoming", 6000, CombatStyle.MAGIC, Abilities.MOVEMENT
    ),
    EMPOWERED_MAGIC(
        listOf("Ful's flame burns within me"),
        "RESONANCE / BARRICADE - 5k typeless", 5000, null, Abilities.NEGATE,
        exact = listOf("Die!", "Begone!")
    ),
    IGNEOUS_RAIN(
        listOf("The skies burn", "Flames consume you", "Fall, and burn to ash"),
        "ANTICIPATION -> DEFLECT MAGIC", 8000, CombatStyle.MAGIC, listOf(Abilities.ANTICIPATION)
    ),
    INSTANT_KILL(
        listOf("Flames unending", "charging a powerful attack"),
        "IGNEOUS VENGEANCE NOW - instant kill", 10000
    ),
    INTERRUPTED(listOf("You interrupt the powerful attack", "Ungh. Not bad"), "STAGGERED - BURST NOW", 6000),

    /** Har-Aken's submerged-phase blob pattern targets your position - moving 3+ tiles dodges it all. */
    AKEN_BOMBARDMENT(listOf("is bombarding you"), "RUN 3+ TILES - LAVA BOMBARDMENT", 6000),

    /** HM challenge intro (observed 20:42 wave 5): the timed kill-set has spawned - burst it now. */
    CHALLENGE_STARTED(listOf("Die, and be reborn in flame"), "CHALLENGE - BURN THEM DOWN", 8000),

    /** The wave-15 perfection is gone - surviving the remaining attacks is all that matters now. */
    CHALLENGE_FAILED(listOf("You have failed, as expected"), "CHALLENGE FAILED - survive it out", 5000),

    /**
     * Hard mode only: the rain lines spoken during ordinary waves mean a moving wall of lava (up
     * to 3,000 typeless per tick per segment), not the Zuk-fight rain - [ZukAlert] reclassifies by
     * phase, so this entry has no fragments of its own.
     */
    LAVA_WALL(emptyList(), "LAVA WALL - DODGE THE SEGMENTS", 6000),

    RISE_HUR("Rise, warrior of flame", "STUN THE HUR", 8000),
    RISE_XIL("Rise, ashen ranger", "THRESHOLD THE XIL", 8000),
    RISE_MEJ("Rise, mage of embers", "GET UNDER THE DOME", 8000),

    ENERGY_FULL("Activating 'Igneous Vengeance'", "IGNEOUS VENGEANCE READY", 6000),
    ENERGY_UNLEASHED("weakening TzKal-Zuk. Strike now", "STRIKE NOW", 5000),
    ENERGY_ABSORBED("absorbs some igneous energy", null, 0),

    SEARING_APPLIED("searing pain that starts to reduce your maximum health", "RUN - 15 stacks to shed", 8000),
    KIH_DRAIN("steals some of your health", "KILL THE KIH", 4000),
    UNBREAKABLE_IMMUNE("seems unaffected by your attack", "BIGGER HITS - that did nothing", 3000),
    OUT_OF_PRAYER("run out of prayer points", "NO PRAYER - you are unprotected", 6000),

    IGNEOUS_HUR_BROKEN("Stunning the Igneous TzekHaar-Hur", "ARMOUR BROKEN - DPS IT", 3000),
    IGNEOUS_XIL_BROKEN("powerful ability on the Igneous TzekHaar-Xil", "ARMOUR BROKEN - DPS IT", 3000),
    IGNEOUS_MEJ_BROKEN("lowered the barrier surrounding Igneous TzekHaar-Mej", "ARMOUR BROKEN - DPS IT", 3000);

    constructor(fragment: String, alert: String?, holdMs: Long) : this(listOf(fragment), alert, holdMs)

    /**
     * Cache devnames rather than struct ids, so a build that renumbers structs still resolves.
     * These are the specific abilities the wiki prescribes per mechanic, not a heuristic.
     */
    object Abilities {
        const val FREEDOM = "combatv2_ability_defence_freedom"
        const val ANTICIPATION = "combatv2_ability_defence_anticipation"
        const val SURGE = "combatv2_ability_magic_surge"
        const val DIVE = "combatv2_ability_attack_dive"
        const val BLADED_DIVE = "combatv2_ability_attack_bladed_dive"
        const val ESCAPE = "combatv2_ability_ranged_escape"
        const val RESONANCE = "combatv2_ability_defence_resonance"
        const val BARRICADE = "combatv2_ability_defence_barricade"
        const val DEVOTION = "combatv2_ability_defence_devotion"
        const val DEBILITATE = "combatv2_ability_defence_debilitate"
        const val REFLECT = "combatv2_ability_defence_reflect"

        val MOVEMENT = listOf(SURGE, BLADED_DIVE, DIVE, ESCAPE)

        /** Negate outright first, then the halving options. */
        val NEGATE = listOf(RESONANCE, BARRICADE, DEVOTION, DEBILITATE, REFLECT)
    }

    companion object {
        /** Longest fragment first, so a short generic phrase never wins over a specific one. */
        private val ordered = entries.sortedByDescending { m -> m.fragments.maxOfOrNull { it.length } ?: 0 }

        private val SPEAKER = Regex("""^[^:<>]{1,32}:\s*""")
        private val TAGS = Regex("""<[^>]*>""")

        /**
         * Short exclamations are matched against the **whole** utterance, never as substrings. Zuk's
         * flavour line "Fight, worm! Or crawl and die!" contains "Die!", and matching it as a substring
         * put an Empowered Magic warning on screen eight seconds into wave 1.
         */
        fun match(message: String): ZukMessage? {
            val spoken = normalize(message)
            entries.firstOrNull { m -> m.exact.any { it.equals(spoken, ignoreCase = true) } }?.let { return it }
            return ordered.firstOrNull { m -> m.fragments.any { message.contains(it, ignoreCase = true) } }
        }

        /** Strips colour tags and any "Speaker: " prefix so an exact match sees only what was said. */
        fun normalize(message: String): String =
            TAGS.replace(message, "").trim().let { SPEAKER.replace(it, "") }.trim()
    }
}

/**
 * Latest chat-driven alert, cleared once it goes stale so a prompt never outlives its mechanic.
 * [zukPhase] disambiguates the rain lines: spoken during ordinary waves they are hard mode's
 * moving lava wall, not the Zuk-fight Igneous Rain.
 */
class ZukAlert(private val zukPhase: () -> Boolean = { false }) {

    @Volatile
    private var text: String? = null

    @Volatile
    private var expiresAt = 0L

    @Volatile
    var prayerOverride: CombatStyle? = null
        private set

    @Volatile
    private var active: ZukMessage? = null

    private val lastSeen = ConcurrentHashMap<ZukMessage, Long>()

    fun accept(message: String): ZukMessage? {
        var matched = ZukMessage.match(message) ?: return null
        if (matched == ZukMessage.IGNEOUS_RAIN && !runCatching(zukPhase).getOrDefault(true)) {
            matched = ZukMessage.LAVA_WALL
        }
        // "Burn!" is both a Sear variant and the Igneous Rain follow-up line (wiki; seen in the
        // 17:45 capture right after "Fall, and burn to ash!") - inside a fresh rain window it must
        // not raise the Sear movement alert.
        if (matched == ZukMessage.SEAR &&
            ZukMessage.normalize(message).equals("Burn!", ignoreCase = true) &&
            sawRecently(ZukMessage.IGNEOUS_RAIN, BURN_AMBIGUITY_MS)
        ) {
            matched = ZukMessage.IGNEOUS_RAIN
        }
        lastSeen[matched] = System.currentTimeMillis()
        matched.alert?.let {
            text = it
            expiresAt = System.currentTimeMillis() + matched.holdMs
            prayerOverride = matched.prayer
            active = matched
        }
        return matched
    }

    fun lastSeenAt(message: ZukMessage): Long? = lastSeen[message]

    fun sawRecently(message: ZukMessage, withinMs: Long): Boolean =
        lastSeenAt(message)?.let { System.currentTimeMillis() - it <= withinMs } ?: false

    /** Abilities prescribed by whichever telegraph is still live. */
    fun prescribedAbilities(): List<String> {
        if (current() == null) return emptyList()
        return active?.abilities.orEmpty()
    }

    fun current(): String? {
        if (System.currentTimeMillis() >= expiresAt) {
            text = null
            prayerOverride = null
            active = null
        }
        return text
    }

    fun clear() {
        text = null
        expiresAt = 0
        prayerOverride = null
        active = null
        lastSeen.clear()
    }

    private companion object {
        const val BURN_AMBIGUITY_MS = 5_000L
    }
}
