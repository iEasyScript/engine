package com.projectx.script.impl.trent

import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.nxt.entity.HitType
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.Hitsplat
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.util.gaussian

private const val MIRRORBACK_SPIDER = 19468
private const val MIRRORBACK_SPIDER_SPEC = 19487

/** The acid-path spider with no "Lure" option — it walks the player down rather than being pulled
 *  off by a lure, unlike the "Highly acidic spider" (19471) that carries one. */
private const val UNLURABLE_ACIDIC_SPIDER = 19470

private val RAX_THREATS = setOf(MIRRORBACK_SPIDER, MIRRORBACK_SPIDER_SPEC, UNLURABLE_ACIDIC_SPIDER)

// cast animations, from the seq gameval names:
//   zamorak_attack_ranged[_fast|_faster] / zamorak_attack_magic[_fast|_faster]
private val ZAMORAK_RANGED_CASTS = setOf(34896, 34897, 34898)
private val ZAMORAK_MAGIC_CASTS = setOf(34899, 34900, 34901)
// "This world will burn" ground slam, both model variants:
//   egwd_zamorak_boss_flames_of_zamorak[_short] / zamorak_attack_special_flames_of_zamorak
private val ZAMORAK_FLAMES_SLAMS = setOf(34840, 34841, 34910)
// "Feel the rage of a god" release: zamorak_attack_special_chaos_blast_end
private val ZAMORAK_BLAST_RELEASES = setOf(34909)

private const val ZAMORAK_FLAMES_WINDOW_MS = 6_000L
private const val ZAMORAK_BLAST_WINDOW_MS = 12_000L
private const val ZAMORAK_CAGE_WINDOW_MS = 10_000L

private const val THREAT_RANGE = 25
private const val THREAT_GLOW = 0xFF0000
private const val THREAT_GLOW_SCALE = 8
private val THREAT_TILE = ImGuiColors.rgba(255, 0, 0, 220)

private val BOSS_NAMES_BY_PRIORITY = listOf(
    "Arch-Glacor",
    "Raksha, the Shadow Colossus",
    "Kalphite King",
    "Zamorak, Lord of Chaos",
)
private const val BOSS_RANGE = 20
private const val BOSS_RESCAN_MS = 500L
private const val BOSS_RESCAN_VARIANCE = 150L

enum class PrayerConstraint(val prayer: Prayer, val soulSplitAfterHit: Boolean, val condition: AutoPrayerSwitcher.() -> Boolean) {
    RAX_MAGIC(Prayer.PROTECT_MAGIC, false, {
        projectileIncoming(4979, 2)
    }),

    RAX_RANGE(Prayer.PROTECT_RANGED, false, {
        projectileIncoming(4997, 2)
    }),

    SANCTUM_MAGE(Prayer.PROTECT_MAGIC, true, {
        projectileIncoming(8182, 5)
    }),

    SANCTUM_RANGE(Prayer.PROTECT_RANGED, true, {
        projectileIncoming(8185, 5)
    }),

    ARCH_GLACOR_MELEE(Prayer.PROTECT_MELEE, true, melee@{
        val anim = boss?.animationId ?: return@melee false
        return@melee anim == 34276 || anim == 34277
    }),

    ARCH_GLACOR_RANGE(Prayer.PROTECT_RANGED, true, range@{
        val anim = boss?.animationId ?: return@range false
        return@range anim == 34274 || anim == 34275
    }),

    ARCH_GLACOR_MAGIC(Prayer.PROTECT_MAGIC, true, mage@{
        val anim = boss?.animationId ?: return@mage false
        return@mage anim == 34272 || anim == 34273 || anim == 34278 || anim == 34282
    }),

    KK_MELEE(Prayer.PROTECT_MELEE, true, melee@{
        val id = boss?.id ?: return@melee false
        return@melee id == 16697 && boss?.interactingWith(localPlayer) == true
    }),

    KK_RANGE(Prayer.PROTECT_RANGED, true, range@{
        val id = boss?.id ?: return@range false
        return@range id == 16699
    }),

    KK_MAGIC(Prayer.PROTECT_MAGIC, true, mage@{
        val id = boss?.id ?: return@mage false
        return@mage id == 16698 || (id == 16697 && boss?.interactingWith(localPlayer) == false)
    }),

    RAKSHA_MELEE(Prayer.PROTECT_MELEE, true, melee@{
        val anim = boss?.animation ?: return@melee false
        return@melee anim.id == 33702 && anim.currentFrame < 30
    }),

    RAKSHA_RANGE(Prayer.PROTECT_RANGED, true, {
        projectileIncoming(7392, 2)
    }),

    RAKSHA_MAGIC(Prayer.PROTECT_MAGIC, true, {
        projectileIncoming(7390, 2)
    }),

    ZAMORAK_MELEE(Prayer.PROTECT_MELEE, true, {
        System.currentTimeMillis() < flamesOfZamorakUntil || boss?.animationId in ZAMORAK_FLAMES_SLAMS
    }),

    ZAMORAK_RANGE(Prayer.PROTECT_RANGED, true, {
        boss?.animationId in ZAMORAK_RANGED_CASTS
    }),

    ZAMORAK_MAGIC(Prayer.PROTECT_MAGIC, true, {
        boss?.animationId in ZAMORAK_MAGIC_CASTS ||
            boss?.animationId in ZAMORAK_BLAST_RELEASES ||
            System.currentTimeMillis() < adrenalineCageUntil ||
            System.currentTimeMillis() < chaosBlastUntil
    }),

    ;

    suspend fun check(context: AutoPrayerSwitcher): Boolean {
        var actualPrayer = prayer
        if (onCursesPrayers) {
            when(prayer) {
                Prayer.PROTECT_MELEE -> actualPrayer = Prayer.DEFLECT_MELEE
                Prayer.PROTECT_MAGIC -> actualPrayer = Prayer.DEFLECT_MAGIC
                Prayer.PROTECT_RANGED -> actualPrayer = Prayer.DEFLECT_RANGE
                Prayer.PROTECT_NECROMANCY -> actualPrayer = Prayer.DEFLECT_NECROMANCY
                Prayer.ECLIPSED_SOUL -> actualPrayer = Prayer.SOUL_SPLIT
                else -> prayer
            }
        }
        if (!actualPrayer.active && context.condition() && actualPrayer.click()) {
            if (soulSplitAfterHit && context.ssFlick.value) {
                val hitType = when(prayer) {
                    Prayer.PROTECT_MELEE -> HitType.MELEE
                    Prayer.PROTECT_MAGIC -> HitType.MAGIC
                    Prayer.PROTECT_RANGED -> HitType.RANGED
                    else -> HitType.NECROMANCY
                }
                context.waitForEvent(gaussian(2500L, 1210L)) { it is Hitsplat && (it.type == hitType || it.type == HitType.MISS) }
                context.delay(gaussian(210, 110))
                val ss = if (onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL
                if (ss.click())
                    context.delayUntil(gaussian(1059L, 200L)) { ss.active }
            } else
                context.delayUntil(gaussian(1059L, 200L)) { actualPrayer.active }
            return true
        }
        return false
    }
}

@ScriptDescription(
    name = "Auto Prayer Switcher",
    version = "1.0.0",
    author = "Trent",
    description = "Auto switches prayers at various bosses"
)
class AutoPrayerSwitcher : Script(), ConfigurableScript {
    val ssFlick = BooleanConfigItem(name = "Soul split flick", description = "Should we soul split flick?", initialValue = false)
    val highlightRaxThreats = BooleanConfigItem(
        name = "Highlight Araxxor threats",
        description = "Mark mirrorback and unlurable acidic spiders in red",
        initialValue = true
    )

    var boss: NPC? = null

    var flamesOfZamorakUntil = 0L
    var chaosBlastUntil = 0L
    var adrenalineCageUntil = 0L

    /** Zamorak announces every special before it lands — latch a window from the chat so the
     *  protection is armed on the same tick as the yell, before the cast animation resolves. */
    override fun onEvent(event: Event) {
        if (event !is Chat) return
        val sender = event.cleanSenderName
        if (sender != null && !sender.contains("zamorak", true)) return
        val now = System.currentTimeMillis()
        val text = event.message
        when {
            text.contains("world will burn", true) -> flamesOfZamorakUntil = now + ZAMORAK_FLAMES_WINDOW_MS
            text.contains("rage of a god", true) -> chaosBlastUntil = now + ZAMORAK_BLAST_WINDOW_MS
            text.contains("unfettered", true) -> adrenalineCageUntil = now + ZAMORAK_CAGE_WINDOW_MS
        }
    }

    /**
     * Runs on the main-logic thread after the client's own per-frame UpdateHighlight, so the
     * render-model write survives into the frame. Only entities found in this tick's live iteration
     * are written to — a remembered address may since have been freed and its heap slot reused.
     */
    override fun render() {
        if (!highlightRaxThreats.value) return
        val threats = raxThreatsInRange()
        if (threats.isEmpty()) return

        threats.forEach {
            runCatching { EntityHighlight.apply(it, THREAT_GLOW, EntityHighlight.Mode.GLOW, THREAT_GLOW_SCALE) }
        }
        backgroundDrawList {
            threats.forEach { threat ->
                runCatching { tile(threat.tile, THREAT_TILE) }
            }
        }
    }

    override suspend fun loop() {
        if (prayerPoints <= 0) return
        if (boss?.exists() != true)
            boss = scanForBoss()
        PrayerConstraint.entries.firstOrNull { it.check(this) }
        delay(10)
    }

    fun projectileIncoming(id: Int, maxDistance: Int): Boolean {
        val player = localPlayer
        val playerTile by lazy(LazyThreadSafetyMode.NONE) { player.tile }
        return projectiles.any { it.id == id && it.lockedOnto(player) && it.tile.getDistance(playerTile) <= maxDistance }
    }

    private var nextBossScanAt = 0L

    private fun scanForBoss(): NPC? {
        val now = System.currentTimeMillis()
        if (now < nextBossScanAt) return null
        return closestBossByPriority().also {
            if (it == null) nextBossScanAt = now + gaussian(BOSS_RESCAN_MS, BOSS_RESCAN_VARIANCE)
        }
    }

    private fun closestBossByPriority(): NPC? {
        val closest = arrayOfNulls<NPC>(BOSS_NAMES_BY_PRIORITY.size)
        val closestDistance = IntArray(BOSS_NAMES_BY_PRIORITY.size) { Int.MAX_VALUE }
        val playerTile by lazy(LazyThreadSafetyMode.NONE) { localPlayer.tile }
        for (npc in npcs.values) {
            runCatching {
                val rank = BOSS_NAMES_BY_PRIORITY.indexOf(npc.name())
                if (rank < 0) return@runCatching
                val tile = npc.tile
                if (!tile.withinDistance(playerTile, BOSS_RANGE)) return@runCatching
                val distance = tile.getDistance(playerTile)
                if (distance < closestDistance[rank]) {
                    closestDistance[rank] = distance
                    closest[rank] = npc
                }
            }
        }
        return closest.firstOrNull { it != null }
    }

    private fun raxThreatsInRange(): List<NPC> {
        val playerTile by lazy(LazyThreadSafetyMode.NONE) { localPlayer.tile }
        return npcs.values.filter { npc ->
            runCatching {
                npc.id in RAX_THREATS && npc.exists() && npc.tile.withinDistance(playerTile, THREAT_RANGE)
            }.getOrDefault(false)
        }
    }
}