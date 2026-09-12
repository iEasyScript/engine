package com.projectx.game.invention

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.items.Item
import com.projectx.game.nxt.Client
import com.projectx.game.nxt.MainState
import com.projectx.ui.UIState
import com.projectx.util.hoursElapsed
import world.gregs.voidps.cache.Cache

/**
 * Estimates effective Invention XP/hour from the item experience that augmented gear
 * accrues while training, assuming the player siphons it.
 *
 * Item XP lives in object-varbit [ITEM_XP_VARBIT], read per stack from the item's var domain.
 * Item level is derived from XP with the thresholds the client itself uses (CS2 script12073).
 * Siphoning a tier-80 item yields a fixed Invention XP per level (wiki), scaled across tiers by
 * (1 + 0.015*(tier-80)); the conversion to an effective rate is simply
 *   itemXpGainedPerHour * (siphonInvXp / itemXpSpentReachingThatLevel).
 *
 * Rates are measured empirically (sampling 30212 over time) so dual-wield/2H/tool nuances need no
 * special casing — whatever item XP a stack actually gains is what gets converted.
 */
object InventionXpTracker {

    const val ITEM_XP_VARBIT = 30212

    private const val PARAM_ITEM_TIER = 5679
    private const val PARAM_AUGMENT_STATE = 5524
    private const val PARAM_UNAUGMENTED_ID = 5527
    private val WIELD_REQ_PARAMS = intArrayOf(750, 752, 754, 756, 758, 760)

    /** Below this Invention level assume level-10 siphons; at or above it assume level-12. */
    const val INV_LEVEL_FOR_L12 = 60

    private const val DEFAULT_TIER = 80

    private const val BACKPACK_INV = 93
    private const val EQUIPMENT_INV = 94
    private val SCANNED_INVENTORIES = intArrayOf(BACKPACK_INV, EQUIPMENT_INV)

    private const val SCAN_INTERVAL_MS = 500L
    private const val STALE_MS = 60_000L

    /** Cumulative item XP required to BE a given level (index == level). CS2 script12073/12074. */
    private val ITEM_XP_TO_REACH = intArrayOf(
        0, 0, 1_160, 2_607, 5_176, 8_285, 11_760, 15_835, 21_152, 28_761, 40_120,
        57_095, 81_960, 117_397, 166_496, 232_755, 320_080, 432_785, 575_592, 753_631, 972_440
    )

    /** Invention XP from siphoning a tier-80 item at a given level (RuneScape Wiki). */
    private val SIPHON_INV_XP_T80 = mapOf(10 to 270_000.0, 12 to 540_000.0)

    data class Row(
        val itemId: Int,
        val name: String,
        val tier: Int,
        val tierKnown: Boolean,
        val itemLevel: Int,
        val itemXp: Int,
        val itemXpPerHour: Int,
        val effectiveInvXpPerHour: Int
    )

    private data class Key(val invId: Int, val slot: Int, val itemId: Int)

    private class State(var lastItemXp: Int) {
        val firstSeenMs: Long = System.currentTimeMillis()
        var lastSeenMs: Long = firstSeenMs
        var itemXpGained: Long = 0
        var effInvXp: Double = 0.0
    }

    private val states = HashMap<Key, State>()
    private val tierCache = HashMap<Int, Pair<Int, Boolean>>()

    @Volatile var rows: List<Row> = emptyList()
        private set
    @Volatile var assumedSiphonLevel: Int = 10
        private set
    @Volatile var inventionLevel: Int = 1
        private set

    private var totalEffInvXp: Double = 0.0
    private var totalItemXpGained: Long = 0
    private var sessionStartMs: Long = 0L
    private var lastScanMs: Long = 0L

    val totalEffectiveInvXpPerHour: Int get() = perHour(totalEffInvXp, sessionStartMs)
    val totalItemXpPerHour: Int get() = perHour(totalItemXpGained.toDouble(), sessionStartMs)

    /** Augmented item level (1-20) for a given cumulative item XP, using the client's own thresholds. */
    fun levelForXp(xp: Int): Int = itemLevel(xp)

    /** Current item XP for a stack, read from object-varbit [ITEM_XP_VARBIT]. */
    fun itemXpOf(item: Item): Int = readItemXp(item)

    fun isAugmentedItem(item: Item): Boolean = isAugmented(item, readItemXp(item))

    fun reset() {
        states.clear()
        totalEffInvXp = 0.0
        totalItemXpGained = 0
        sessionStartMs = 0L
        rows = emptyList()
    }

    fun tick() {
        if (!UIState.inventionXpTrackerEnabled.value) return
        try {
            val client = runCatching { Bootstrap.client }.getOrNull() ?: return
            if (runCatching { client.mainState }.getOrNull() != MainState.LOGGED_IN) return
            val now = System.currentTimeMillis()
            if (now - lastScanMs < SCAN_INTERVAL_MS) return
            lastScanMs = now

            val level = readInventionLevel(client) ?: return
            inventionLevel = level
            val siphonLevel = if (level >= INV_LEVEL_FOR_L12) 12 else 10
            assumedSiphonLevel = siphonLevel

            val manager = client.inventoryManager
            if (manager.ptr.address() == 0L) return

            val seen = HashSet<Key>()
            val nextRows = ArrayList<Row>()

            for (invId in SCANNED_INVENTORIES) {
                val inv = runCatching { manager[invId] }.getOrNull() ?: continue
                if (inv.ptr.address() == 0L) continue
                for (item in inv) {
                    val domain = item.varDomain ?: continue
                    if (!domain.isValid) continue
                    val xp = readItemXp(item)
                    if (xp < 0 || !isAugmented(item, xp)) continue

                    val key = Key(invId, item.slot.slotId, item.id)
                    seen.add(key)
                    val (tier, tierKnown) = resolveTier(item.id)
                    val ratio = invXpPerItemXp(siphonLevel, tier)

                    val state = states.getOrPut(key) { State(xp) }
                    val delta = xp - state.lastItemXp
                    if (delta > 0) {
                        state.itemXpGained += delta
                        state.effInvXp += delta * ratio
                        totalItemXpGained += delta
                        totalEffInvXp += delta * ratio
                        if (sessionStartMs == 0L) sessionStartMs = now
                    }
                    state.lastItemXp = xp
                    state.lastSeenMs = now

                    nextRows.add(
                        Row(
                            itemId = item.id,
                            name = runCatching { item.name }.getOrDefault("item ${item.id}"),
                            tier = tier,
                            tierKnown = tierKnown,
                            itemLevel = itemLevel(xp),
                            itemXp = xp,
                            itemXpPerHour = perHour(state.itemXpGained.toDouble(), state.firstSeenMs),
                            effectiveInvXpPerHour = perHour(state.effInvXp, state.firstSeenMs)
                        )
                    )
                }
            }

            if (sessionStartMs == 0L && nextRows.isNotEmpty()) sessionStartMs = now
            states.entries.removeIf { (key, state) -> key !in seen && now - state.lastSeenMs > STALE_MS }
            rows = nextRows.sortedByDescending { it.effectiveInvXpPerHour }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun readInventionLevel(client: Client): Int? {
        val manager = client.mainLogicManager
        if (manager.ptr.address() == 0L) return null
        val statTable = manager.statTable
        if (statTable.ptr.address() == 0L) return null
        if (runCatching { statTable.size }.getOrDefault(0) <= Skill.INVENTION.ordinal) return null
        return runCatching { statTable[Skill.INVENTION].realLevel }.getOrNull()
    }

    private fun readItemXp(item: Item): Int {
        val domain = item.varDomain ?: return 0
        val viaBit = runCatching { domain.getVarBit(ITEM_XP_VARBIT) }.getOrDefault(0)
        if (viaBit > 0) return viaBit
        return runCatching { domain.getVar(ITEM_XP_VARBIT) }.getOrDefault(0)
    }

    private fun isAugmented(item: Item, itemXp: Int): Boolean {
        if (item.varDomain == null) return false
        if (itemXp > 0) return true
        return runCatching { (Cache.obj(item.id)?.params?.get(PARAM_AUGMENT_STATE) as? Int ?: 0) != 0 }.getOrDefault(false)
    }

    private fun itemLevel(xp: Int): Int {
        var level = 1
        for (l in 1..20) {
            if (xp >= ITEM_XP_TO_REACH[l]) level = l else break
        }
        return level
    }

    private fun invXpPerItemXp(siphonLevel: Int, tier: Int): Double {
        val base = SIPHON_INV_XP_T80[siphonLevel] ?: return 0.0
        val tierMultiplier = (1.0 + 0.015 * (tier - 80)).coerceAtLeast(0.1)
        val itemXpSpent = ITEM_XP_TO_REACH[siphonLevel].toDouble()
        if (itemXpSpent <= 0.0) return 0.0
        return base * tierMultiplier / itemXpSpent
    }

    private fun resolveTier(itemId: Int): Pair<Int, Boolean> = tierCache.getOrPut(itemId) { computeTier(itemId) }

    private fun computeTier(itemId: Int): Pair<Int, Boolean> {
        return runCatching {
            var tier = tierFromParams(itemId)
            if (tier <= 0) {
                val baseId = (Cache.obj(itemId)?.params?.get(PARAM_UNAUGMENTED_ID) as? Int ?: -1)
                if (baseId > 0) tier = tierFromParams(baseId)
            }
            if (tier > 0) tier to true else DEFAULT_TIER to false
        }.getOrDefault(DEFAULT_TIER to false)
    }

    private fun tierFromParams(itemId: Int): Int {
        val params = Cache.obj(itemId)?.params ?: emptyMap()
        val explicit = params[PARAM_ITEM_TIER] as? Int ?: -1
        if (explicit > 0) return explicit
        return WIELD_REQ_PARAMS.maxOf { params[it] as? Int ?: 0 }
    }

    private fun perHour(total: Double, startMs: Long): Int {
        if (startMs <= 0L) return 0
        val hours = hoursElapsed(startMs).toDouble()
        if (hours < 1.0 / 3600.0) return 0
        return (total / hours).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
    }
}
