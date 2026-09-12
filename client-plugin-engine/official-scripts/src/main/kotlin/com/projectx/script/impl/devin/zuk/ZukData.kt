package com.projectx.script.impl.devin.zuk

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.Prayer
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/**
 * The three protectable damage types. Colours are cosmetic (RS convention: blue magic / green
 * ranged / red melee). Prayers cover both the standard and curses (deflect) books.
 * [statIconKey] indexes the `stat2icon_medium` cache enum (stat id + 1: Attack/Ranged/Magic).
 */
enum class CombatStyle(
    val displayName: String,
    val rgb: Int,
    val statIconKey: Int,
    val protect: Prayer,
    val deflect: Prayer
) {
    MAGIC("Magic", 0x33AAFF, 7, Prayer.PROTECT_MAGIC, Prayer.DEFLECT_MAGIC),
    RANGED("Ranged", 0x33CC55, 5, Prayer.PROTECT_RANGED, Prayer.DEFLECT_RANGE),
    MELEE("Melee", 0xE23C3C, 1, Prayer.PROTECT_MELEE, Prayer.DEFLECT_MELEE);

    fun protectionPrayer(onCurses: Boolean): Prayer = if (onCurses) deflect else protect

    val drawColor: Int
        get() = ImGuiColors.rgba((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF)
}

enum class MinionRole {
    /** Single-style attacker we outline/mark and pray against. */
    ADD,

    /** Har-Aken tentacle - single style, part of the final wave. */
    TENTACLE,

    /** Multi-style; prayer resolved live by projectile/animation, not a static outline. */
    JAD,

    /** Har-Aken head. */
    AKEN,

    /** Challenge-wave minion with a special mechanic - excluded from outlines per design. */
    CHALLENGE,

    /** Debuff/healer support - not a protect-prayer target. */
    MECHANIC,

    /** TzKal-Zuk himself; his prayer comes from attack animations, not a fixed style. */
    ZUK,

    /** Environmental damage that occupies tiles - marked to stand clear of, never attacked. */
    HAZARD,
}

/**
 * The full TzKal-Zuk (EGWD) encounter roster. id + display name are cross-verified between the
 * gameval devname (egwd_ful_boss_minion_*, rev 947) and the live cache npc names; the style is
 * taken from the gameval devname (..._melee/_ranged/_magic) and agrees with the TzHaar naming
 * convention (Hur=melee, Xil=ranged, Mej/Ket-Zek=magic). High confidence.
 */
private const val FATAL_MECHANIC = "IMMUNE - survive 4 attacks (Barricade)"

enum class ZukMinion(
    val id: Int,
    val displayName: String,
    val style: CombatStyle?,
    val role: MinionRole,
    val mechanic: String? = null
) {
    HUR(28535, "TzekHaar-Hur", CombatStyle.MELEE, MinionRole.ADD),
    YT_MEJKOT(28536, "TzekHaar-Yt-Mejkot", CombatStyle.MELEE, MinionRole.ADD),
    IGNEOUS_HUR(28537, "Igneous TzekHaar-Hur", CombatStyle.MELEE, MinionRole.ADD, "STUN to break 50% DR"),
    XIL(28538, "TzekHaar-Xil", CombatStyle.RANGED, MinionRole.ADD),
    TOK_XIL(28539, "TzekHaar-Tok-Xil", CombatStyle.RANGED, MinionRole.ADD),
    IGNEOUS_XIL(28540, "Igneous TzekHaar-Xil", CombatStyle.RANGED, MinionRole.ADD, "ADRENALINE ability to break 50% DR"),
    MEJ_DISCIPLE(28541, "TzekHaar-Mej Disciple", CombatStyle.MAGIC, MinionRole.ADD),
    MEJ(28542, "TzekHaar-Mej", CombatStyle.MAGIC, MinionRole.ADD),
    KET_ZEK(28543, "TzekHaar-Ket-Zek", CombatStyle.MAGIC, MinionRole.ADD),
    IGNEOUS_MEJ(28544, "Igneous TzekHaar-Mej", CombatStyle.MAGIC, MinionRole.ADD, "GET UNDER dome, hit 2-3x"),

    JAD(28534, "TzekHaar-Jad", null, MinionRole.JAD),
    AKEN(28529, "TzekHaar-Aken", null, MinionRole.AKEN),
    TENTACLE_RANGED(28530, "Ranged Tentacle", CombatStyle.RANGED, MinionRole.TENTACLE),
    TENTACLE_MAGIC(28531, "Magic Tentacle", CombatStyle.MAGIC, MinionRole.TENTACLE),
    TENTACLE_RANGED_STRONG(28532, "Piercing Ranged Tentacle", CombatStyle.RANGED, MinionRole.TENTACLE),
    TENTACLE_MAGIC_STRONG(28533, "Warding Magic Tentacle", CombatStyle.MAGIC, MinionRole.TENTACLE),

    KIH(28545, "TzekHaar-Kih", null, MinionRole.MECHANIC),
    HEALER(28551, "TzekHaar-Yt-HurKot", null, MinionRole.MECHANIC),

    ZUK(28527, "TzKal-Zuk", null, MinionRole.ZUK),
    GEYSER(28552, "Lava geyser", null, MinionRole.HAZARD, "MOVE OFF"),

    SLAYER_MELEE(28587, "TzekHaar-Hur", CombatStyle.MELEE, MinionRole.ADD),
    SLAYER_RANGED(28588, "TzekHaar-Xil", CombatStyle.RANGED, MinionRole.ADD),
    SLAYER_MAGIC(28590, "TzekHaar-Mej", CombatStyle.MAGIC, MinionRole.ADD),
    SLAYER_DEBUFF(28591, "TzekHaar-Kih", null, MinionRole.MECHANIC),

    VOLATILE_HUR(28546, "Volatile TzekHaar-Hur", CombatStyle.MELEE, MinionRole.CHALLENGE, "BURST DOWN before it explodes (6k each)"),
    UNBREAKABLE_KET(28547, "Unbreakable TzekHaar-Ket", CombatStyle.MELEE, MinionRole.CHALLENGE, "HITS >3000 ONLY - burst before explode"),
    // 28548's challenge orbs are soft typeless, so it deliberately has no protect-prayer style even
    // though the cache templates all three damage types onto it; 28549/28550 are single-style.
    FATAL_GENERIC(28548, "Fatal TzekHaar-Yt-HurKot", null, MinionRole.CHALLENGE, FATAL_MECHANIC),
    FATAL_RANGED(28549, "Fatal TzekHaar-Yt-HurKot", CombatStyle.RANGED, MinionRole.CHALLENGE, FATAL_MECHANIC),
    FATAL_MAGIC(28550, "Fatal TzekHaar-Yt-HurKot", CombatStyle.MAGIC, MinionRole.CHALLENGE, FATAL_MECHANIC);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun byId(id: Int): ZukMinion? = byId[id]
    }
}

object ZukIds {
    const val UNKNOWN = -1

    /** The fightable TzKal-Zuk (egwd_ful_boss_showdown). */
    const val ZUK_SHOWDOWN = 28527

    /** Present for the whole encounter, so it anchors the arena even before Zuk is fightable. */
    const val ZUK_ANCHOR = 28526
    const val ZUK_END = 28528
    const val JAD = 28534
    const val TOTAL_WAVES = 17

    /** Zero-based while wave 1 is live, so the displayed wave number is this plus one. */
    const val WAVE_INDEX_VARBIT = 50345
    const val ENCOUNTER_MODE_VARBIT = 50343

    /** Mode varbit values: 1 = normal (17:45 capture), 2 = hard (20:42 capture). */
    const val MODE_HARD = 2
    const val PRACTICE_MODE_VARBIT = 50344

    const val TARGET_STUNNED_VARBIT = 1910
    const val TARGET_BOUND_VARBIT = 1911
    const val TARGET_VULNERABLE_VARBIT = 1939

    /**
     * The Tok-Xil gains +1% Attack/Strength per stack while standing still (observed reaching 130
     * in the 17:31 run); the counter is forcing it to move.
     */
    const val TOK_XIL_GROUNDED_STACKS_VARBIT = 50330
    const val UNGROUND_AT_STACKS = 50

    /** Builds 0..3 as Zuk's attacks are absorbed; at [IGNEOUS_ENERGY_FULL] the extra-action button arms. */
    const val IGNEOUS_ENERGY_VARBIT = 10254
    const val IGNEOUS_ENERGY_FULL = 3
    const val CHALLENGERS_VENGEANCE_VARBIT = 10255
    const val INFERNAL_BURN_VARBIT = 10256

    /** Sear applies 15 stacks that drain Constitution; one stack clears per tile moved. */
    const val SEARING_PAIN_VARP = 10259
    const val SEARING_PAIN_APPLIED = 15

    /** At this LP Zuk blocks a tick and restarts his rotation from Geothermal Burn (wiki). */
    const val ZUK_FINAL_ROTATION_LP = 100_000

    /** Advances by 8 per wave and reaches [ENCOUNTER_PROGRESS_ZUK] for the boss fight itself. */
    const val ENCOUNTER_PROGRESS_VARP = 10253
    const val ENCOUNTER_PROGRESS_PER_WAVE = 8
    const val ENCOUNTER_PROGRESS_ZUK = 137

    /**
     * Har-Aken's falling lava impact marker. Gfx 8978 is NOT it - on the wire it is a spot anim
     * attached to erupting tentacle/Tok-Xil npcs, so it never surfaces in the world spot-anim
     * list. Gfx 7585 appeared on the exact tile of a 1,891 typeless lava hit 3.50s before it
     * landed (17:45 Aken window, n=1 - unproven, validate next run).
     */
    const val LAVA_RAIN_SPOTANIM = 7585

    /**
     * HM lava wall: every wall announcement in the 20:42 HM capture was followed ~2s later by a
     * ~25–30-tile burst of gfx 7593 - the tiles the sweep burns.
     */
    const val LAVA_WALL_SPOTANIM = 7593
    val LAVA_HAZARD_SPOTANIMS = setOf(LAVA_RAIN_SPOTANIM, LAVA_WALL_SPOTANIM)
    const val LAVA_RAIN_HAZARD_MS = 5_000L

    val ZUK_FIGHT_START_ANIMS = setOf(34518, 34494)

    /**
     * Attack tells, correlated against damage landing within 1.5s while Zuk was the only npc alive,
     * so nothing else could have produced the hitsplats: 34496 and 34497 were 100% melee, 34499 100%
     * magic, and 34498 72% melee of the 23k it accounted for.
     */
    val ZUK_ANIM_STYLE: Map<Int, CombatStyle> = mapOf(
        34496 to CombatStyle.MELEE,
        34497 to CombatStyle.MELEE,
        34498 to CombatStyle.MELEE,
        34499 to CombatStyle.MAGIC,
    )

    /**
     * The wave-15 challenge attacks, seen in both captures: 7604 carries attacks 1 and 4 (the soft
     * typeless ones), 7603 attacks 2 and 3. Zuk's own 2263 also locks onto the player mid-challenge,
     * so the attack has to be identified by id and never by "something is incoming".
     */
    val HURKOT_PROJECTILES = setOf(7603, 7604)

    const val ZUK_TELL_HOLD_MS = 2400L

    /**
     * Jad's anims, settled by per-index wire attribution from the 17:45 packet capture: 16195
     * pairs with projectile 2996 and lands exclusively magic; 16202 pairs with 8044, whose lone
     * launches land only ranged. 16201 fires every 0.6-1.2s - far below the 2.4s attack speed -
     * so it is the movement/chase loop and must never be a tell (it poisoned every correlation
     * that included it). 19057 is the spawn roar, 16188 the death (exactly one per Jad in wave
     * 16). Melee has no anim mapped: kiting keeps it out of range, where it cannot matter.
     */
    val JAD_ANIM_STYLE: Map<Int, CombatStyle> = mapOf(
        16195 to CombatStyle.MAGIC,
        16202 to CombatStyle.RANGED,
    )

    const val JAD_TELL_HOLD_MS = 2400L

    /** Beyond this he throws a magic fireball instead of swinging. */
    const val ZUK_MELEE_RANGE = 4

    /**
     * Correlated against the hitsplat each produced: 2985 ranged (n=192), 2984 magic (n=25).
     *
     * ⚠️ These are *minion* projectiles, not Jad's - neither id fires once during any Jad wave.
     * Jad's own projectile ids are still unidentified, which is half of why Jad never flicked.
     */
    val PROJECTILE_STYLE: Map<Int, CombatStyle> = mapOf(
        2985 to CombatStyle.RANGED,
        2984 to CombatStyle.MAGIC,
    )


    val REGULAR_WAVES = setOf(1, 2, 3, 7, 8, 12, 13)
    val IGNEOUS_WAVES = setOf(4, 9, 14)
    val CHALLENGE_WAVES = setOf(5, 10, 15)
    val JAD_WAVES = setOf(6, 11, 16)
}

private fun NPC.zukKey(): Int = if (typeId != -1) typeId else id

fun NPC.zukMinion(): ZukMinion? = ZukMinion.byId(zukKey())
fun NPC.combatStyle(): CombatStyle? = zukMinion()?.style
fun NPC.isZuk(): Boolean = zukKey() == ZukIds.ZUK_SHOWDOWN

fun NPC.anchorsArena(): Boolean = zukKey() == ZukIds.ZUK_ANCHOR || zukKey() == ZukIds.ZUK_SHOWDOWN

/** Every recognised encounter NPC gets a marker; only ones with a fixed style get a style colour. */
fun NPC.isOverlayTarget(): Boolean = exists() && zukMinion() != null

fun NPC.isHazard(): Boolean = zukMinion()?.role == MinionRole.HAZARD

/**
 * Bombs are a limited consumable and only pay off on something that lives long enough to benefit.
 * Trash dies well inside the debuff's duration, so bombing it is pure waste. NM: Zuk, Jads and
 * Har-Aken only; HM adds the igneous minions of waves 4/9/14 (user rule 2026-07-20 - the wave-10
 * Unbreakable was dropped from the list by the same call).
 */
fun NPC.worthVulnBomb(): Boolean = zukMinion()?.let {
    it.role in VULN_BOMB_ROLES || (ZukWaves.hardMode() && it in HM_VULN_BOMB_MINIONS)
} == true

private val VULN_BOMB_ROLES = setOf(MinionRole.ZUK, MinionRole.JAD, MinionRole.AKEN)

private val HM_VULN_BOMB_MINIONS = setOf(
    ZukMinion.IGNEOUS_HUR, ZukMinion.IGNEOUS_XIL, ZukMinion.IGNEOUS_MEJ
)

/** Hazards are ground to avoid and Zuk is handled by phase, so neither identifies a wave. */
fun ZukMinion.countsTowardWave(): Boolean = role != MinionRole.HAZARD && role != MinionRole.ZUK


/** Carries a break/survive condition worth captioning on its tile, whatever its role. */
fun NPC.mechanicHint(): String? = if (exists()) zukMinion()?.mechanic else null

