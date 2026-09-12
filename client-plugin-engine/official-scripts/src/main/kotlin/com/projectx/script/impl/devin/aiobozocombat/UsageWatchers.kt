package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.inventory
import org.projectx.core.game.combat.AbilityType
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/**
 * Detects the actions that never move a cooldown varc.
 *
 * Prayers, food and thrown items are all pressed from the action bar but none carries a
 * `combatv2_cooldown_*` varc, so the varc watcher driving the rest of the feed cannot see them.
 */
object PrayerWatcher {

    private class Barred(val structId: Int, val name: String, val onGraphic: Int, val slot: IFSlot)

    private var barred: List<Barred> = emptyList()
    private val wasOn = HashMap<Int, Boolean>()

    /**
     * Finds every prayer on the bar by its `prayer_graphic_on` param and remembers the graphic that
     * marks it active.
     *
     * Detection keys off that graphic rather than a varbit: the cache lists 102 prayers while the
     * engine's hand-authored `Prayer` table covers 21 overheads and curses, and gamevals names no
     * prayer varbits at all — so a varbit-based watcher can only ever see a fraction of them.
     */
    fun bind(bar: Map<AbilityType, IFSlot>) {
        barred = bar.mapNotNull { (ability, slot) ->
            val on = param(ability.structId, PRAYER_GRAPHIC_ON)
            if (on <= 0) return@mapNotNull null
            Barred(ability.structId, ability.name.ifBlank { "struct ${ability.structId}" }, on, slot)
        }
        wasOn.clear()
        println("[BozoCap] PRAYERBIND ${barred.size} prayers on bar")
        for (p in barred) println("[BozoCap] PRAYERBIND  ${p.name} struct=${p.structId} onGraphic=${p.onGraphic}")
    }

    /** Fires once per activation, not per tick, so holding a prayer on does not spam the queue. */
    fun poll(onActivated: (structId: Int, name: String) -> Unit) {
        val lit = HashSet<Int>()
        for (prayer in barred) {
            val graphic = BarLayout.iconGraphic(prayer.slot.interfaceId, prayer.slot.componentId)
            val on = graphic == prayer.onGraphic
            if (on) lit += prayer.structId
            val previous = wasOn.put(prayer.structId, on) ?: false
            if (!on || previous) continue
            println("[BozoCap] PRAYER ${prayer.name} on struct=${prayer.structId} graphic=$graphic")
            onActivated(prayer.structId, prayer.name)
        }
        active = lit
    }

    /**
     * Which barred prayers are lit right now.
     *
     * The queue needs the *state*, not just the activation edge: a prayer has no cooldown, so a rule
     * asking for one can only stop repeating once it can see that the prayer is already on.
     */
    @Volatile
    var active: Set<Int> = emptySet()
        private set

    fun reset() {
        wasOn.clear()
        barred = emptyList()
        active = emptySet()
    }

    private fun param(structId: Int, name: String): Int = runCatching {
        Gameval.id(Gameval.PARAM, name)?.let { Cache.struct(structId)?.getIntValue(it, 0) } ?: 0
    }.getOrDefault(0)

    private const val PRAYER_GRAPHIC_ON = "prayer_graphic_on"
}

/**
 * Watches the whole backpack for counts dropping.
 *
 * Consumables have no cooldown varc and no ability struct, and restricting this to action-bar slots
 * missed anything eaten or drunk from the backpack itself — which is most of it.
 */
object ItemWatcher {
    private var counts: Map<Int, Int> = emptyMap()
    private var primed = false

    /** Backpack contents as of the last poll, so a rule can ask whether the player still has brews. */
    @Volatile
    var held: Map<Int, Int> = emptyMap()
        private set

    fun poll(onUsed: (itemId: Int, name: String, remaining: Int) -> Unit) {
        val now = runCatching {
            inventory.filter { it.id > 0 }.groupBy { it.id }.mapValues { (_, items) -> items.sumOf { it.amount } }
        }.getOrNull() ?: return

        held = now
        if (!primed) {
            counts = now
            primed = true
            return
        }
        for ((id, before) in counts) {
            val after = now[id] ?: 0
            if (after >= before) continue
            val name = runCatching { Cache.obj(id)?.name }.getOrNull() ?: "item $id"
            println("[BozoCap] ITEMUSE $name id=$id $before -> $after")
            onUsed(id, name, after)
        }
        counts = now
    }

    fun reset() {
        counts = emptyMap()
        held = emptyMap()
        primed = false
    }
}
