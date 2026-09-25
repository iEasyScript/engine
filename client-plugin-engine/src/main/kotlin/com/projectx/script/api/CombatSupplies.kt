package com.projectx.script.api

import com.projectx.game.items.Item
import org.projectx.core.game.combat.EffectType
import java.util.function.BooleanSupplier

/** A health or prayer level at or below which something is used: a fixed amount, or a percentage of the maximum. */
class Threshold private constructor(val value: Int, val percent: Boolean) {
    internal fun reached(current: Int, max: Int): Boolean =
        if (percent) max > 0 && current * 100 <= value * max else current <= value

    companion object {
        @JvmStatic
        fun percent(value: Int) = Threshold(value, true)

        @JvmStatic
        fun fixed(value: Int) = Threshold(value, false)
    }
}

/**
 * A buff to keep up: [apply] is run (at most once a tick) whenever [effect] is missing or has [refreshAtMillis] or
 * less left. A [toggle] buff (a scripture, a switched-on aura) is also switched off again, by running [apply], once
 * it is no longer requested. After five failed applications in a row it stops trying.
 */
class BuffRequest @JvmOverloads constructor(
    val name: String,
    val effect: EffectType,
    val apply: BooleanSupplier,
    val refreshAtMillis: Long = 0,
    val toggle: Boolean = false,
    val priority: Int = 0,
    val canApply: BooleanSupplier? = null,
)

/**
 * Keeps the player fed, prayed and buffed through a fight. Call [update] every loop; it uses at most one item of
 * each kind per tick and never blocks.
 *
 * Health: an Enhanced Excalibur at [excalibur], then food at [food], blubber jellyfish at [jellyfish] and a brew at
 * [healingPotion], each of which can land in the same tick as the others. Prayer: an ancient elven ritual shard at
 * [elvenShard], a restore at [prayerPotion], and a restore after every [brewSipsPerRestore] brew sips to undo their
 * drain. With nothing left to eat or drink at those levels it teleports to War's Retreat.
 */
class CombatSupplies @JvmOverloads constructor(
    var food: Threshold = Threshold.fixed(100),
    var jellyfish: Threshold = Threshold.percent(70),
    var healingPotion: Threshold = Threshold.percent(30),
    var excalibur: Threshold = Threshold.percent(40),
    var prayerPotion: Threshold = Threshold.fixed(200),
    var criticalPrayer: Threshold = Threshold.percent(10),
    var elvenShard: Threshold = Threshold.fixed(600),
    var brewSipsPerRestore: Int = 3,
    var comboBrewWithJellyfish: Boolean = false,
    var preferJellyfishAboveAdrenaline: Int? = null,
    var emergencyTeleport: String? = WARS_RETREAT_TELEPORT,
    var debug: Boolean = false,
) {
    private enum class Kind { PRAYER, BREW, JELLYFISH, FOOD }

    internal var currentTick: () -> Long = { ServerTick.count }

    private var lastEat = Long.MIN_VALUE / 2
    private var lastDrink = Long.MIN_VALUE / 2
    private var lastTeleport = Long.MIN_VALUE / 2
    private var brewSips = 0

    private var requested: List<BuffRequest> = emptyList()
    private val toggledOn = mutableListOf<BuffRequest>()
    private val failures = HashMap<EffectType, Int>()
    private val lastAttempt = HashMap<EffectType, Long>()

    /** Buffs that stopped being retried after five failures in a row. */
    val failedBuffs: List<String> get() = requested.filter { (failures[it.effect] ?: 0) >= MAX_BUFF_FAILURES }.map { it.name }

    /** The buffs to keep up from now on; toggle buffs left out of the list are switched off. */
    fun keepUp(buffs: List<BuffRequest>) {
        requested = buffs.sortedByDescending { it.priority }
    }

    /** Switches off every toggle buff this manager switched on, and stops keeping any buff up. */
    fun releaseAllBuffs() {
        requested = emptyList()
        toggledOn.removeAll { !it.effect.active() || it.apply.asBoolean }
    }

    /** Postpones drinking for [ticks] ticks, e.g. while a potion's effect must not be overwritten. */
    fun holdDrinks(ticks: Int) {
        lastDrink = currentTick() + ticks
    }

    /** Runs one pass of health, prayer and buff management. Returns true when anything was used. */
    fun update(): Boolean {
        val health = manageHealth()
        val prayer = managePrayer()
        val buffs = manageBuffs()
        return health || prayer || buffs
    }

    private fun manageHealth(): Boolean {
        val current = healthCurrent
        val max = healthMax
        val wantsFood = food.reached(current, max)
        val wantsJellyfish = jellyfish.reached(current, max)
        val wantsBrew = healingPotion.reached(current, max)
        var used = excalibur.reached(current, max) && activateExcalibur()

        if (!(wantsFood || wantsJellyfish || wantsBrew)) return used
        if (stock(Kind.FOOD) + stock(Kind.JELLYFISH) + stock(Kind.BREW) == 0) return teleportOut("health") || used
        if (!ready(lastEat) && !ready(lastDrink)) return used

        var solid = wantsFood
        val jellyFirst = preferJellyfishAboveAdrenaline?.let { adrenaline >= it } == true
        if (ready(lastEat)) {
            if (jellyFirst && wantsJellyfish && eat(Kind.JELLYFISH)) {
                used = true
                solid = false
                comboBrew()
            }
            if (solid && eat(Kind.FOOD)) used = true
            if (!jellyFirst && wantsJellyfish && eat(Kind.JELLYFISH)) {
                used = true
                comboBrew()
            }
        }
        if (wantsBrew && ready(lastDrink)) {
            val brew = first(Kind.BREW)
            if (brew != null && brew.click(1)) {
                lastDrink = currentTick()
                if (brew.name.contains(SARADOMIN_BREW)) brewSips++
                used = true
            }
        }
        return used
    }

    private fun managePrayer(): Boolean {
        val current = prayerPoints
        val max = prayerMax.toInt()
        var used = elvenShard.reached(current, max) && activateElvenShard()

        val restores = stock(Kind.PRAYER)
        if (brewSipsPerRestore > 0 && brewSips >= brewSipsPerRestore && restores > 0) {
            if (drinkRestore()) {
                brewSips = 0
                used = true
            }
            return used
        }
        if (prayerPotion.reached(current, max) && restores > 0) return drinkRestore() || used
        if (criticalPrayer.reached(current, max) && restores == 0) return teleportOut("prayer") || used
        return used
    }

    private fun manageBuffs(): Boolean {
        var used = false
        for (buff in requested) {
            val remaining = buff.effect.timeRemainingMs()
            val up = buff.effect.active() && (remaining == 0L || remaining > buff.refreshAtMillis)
            if (up) {
                if (buff.toggle && toggledOn.none { it.effect == buff.effect }) toggledOn += buff
                continue
            }
            used = applyBuff(buff) || used
        }
        toggledOn.removeAll { toggle ->
            requested.none { it.effect == toggle.effect } && (!toggle.effect.active() || toggle.apply.asBoolean)
        }
        return used
    }

    private fun applyBuff(buff: BuffRequest): Boolean {
        val tick = currentTick()
        if (tick - (lastAttempt[buff.effect] ?: Long.MIN_VALUE / 2) <= INTERVAL_TICKS) return false
        val failed = failures[buff.effect] ?: 0
        if (failed >= MAX_BUFF_FAILURES || buff.canApply?.asBoolean == false) return false
        lastAttempt[buff.effect] = tick
        return if (buff.apply.asBoolean) {
            failures[buff.effect] = 0
            if (buff.toggle && toggledOn.none { it.effect == buff.effect }) toggledOn += buff
            log("applied ${buff.name}")
            true
        } else {
            failures[buff.effect] = failed + 1
            if (failed + 1 >= MAX_BUFF_FAILURES) log("giving up on ${buff.name}")
            false
        }
    }

    private fun eat(kind: Kind): Boolean {
        val item = first(kind) ?: return false
        if (!item.click(1)) return false
        lastEat = currentTick()
        log("ate ${item.name}")
        return true
    }

    private fun comboBrew() {
        if (!comboBrewWithJellyfish || !ready(lastDrink)) return
        val brew = supplies(Kind.BREW).firstOrNull { it.name.contains(SARADOMIN_BREW) } ?: return
        if (brew.click(1)) {
            lastDrink = currentTick()
            brewSips++
        }
    }

    private fun drinkRestore(): Boolean {
        if (!ready(lastDrink)) return false
        val restore = first(Kind.PRAYER) ?: return false
        if (!restore.click(1)) return false
        lastDrink = currentTick()
        log("drank ${restore.name}")
        return true
    }

    private fun teleportOut(reason: String): Boolean {
        val teleport = emergencyTeleport ?: return false
        val tick = currentTick()
        if (tick - lastTeleport <= TELEPORT_RETRY_TICKS) return false
        if (!(abilityUsable(teleport) && castAbility(teleport))) return false
        lastTeleport = tick
        log("out of $reason supplies, teleporting")
        return true
    }

    private fun ready(last: Long) = currentTick() - last > INTERVAL_TICKS

    private fun first(kind: Kind): Item? = supplies(kind).firstOrNull()

    private fun stock(kind: Kind): Int = supplies(kind).size

    private fun supplies(kind: Kind): List<Item> = inventory.filter { it.amount == 1 && kindOf(it.name) == kind }

    private fun log(message: String) {
        if (debug) println("[CombatSupplies] $message")
    }

    companion object {
        private const val INTERVAL_TICKS = 1
        private const val TELEPORT_RETRY_TICKS = 10
        private const val MAX_BUFF_FAILURES = 5
        private const val WARS_RETREAT_TELEPORT = "War's Retreat Teleport"
        private const val SARADOMIN_BREW = "Saradomin brew"
        private val COLOUR_TAG = Regex("<col=[0-9a-fA-F]+>")

        private val PRAYER = listOf(
            "Prayer", "Super restore", "Sanfew", "Super prayer", "Spiritual prayer", "Extreme prayer", "Blessed flask",
        )
        private val BREW = listOf("Guthix rest", "Super Guthix brew", SARADOMIN_BREW)
        private val JELLYFISH = listOf("blue blubber jellyfish", "green blubber jellyfish")
        private val FOOD = listOf(
            "Kebab", "Bread", "Doughnut", "Roll", "Square sandwich", "Crayfish", "Shrimps", "Sardine", "Herring",
            "Mackerel", "Anchovies", "Cooked chicken", "Cooked meat", "Trout", "Cod", "Pike", "Salmon", "Tuna", "Bass",
            "Lobster", "Swordfish", "Desert sole", "Catfish", "Monkfish", "Beltfish", "Ghostly sole",
            "Cooked eeligator", "Shark", "Sea turtle", "Great white shark", "Cavefish", "Manta ray", "Rocktail",
            "Tiger shark", "Sailfish", "Baron shark", "Potato with cheese", "Tuna potato", "Great maki",
            "Great gunkan", "Rocktail soup", "Sailfish soup", "Fury shark", "Primal feast",
        )

        private fun kindOf(rawName: String): Kind? {
            val name = rawName.replace(COLOUR_TAG, "")
            return when {
                PRAYER.any { name.contains(it) } && !name.contains("renewal", ignoreCase = true) -> Kind.PRAYER
                BREW.any { name.contains(it) } -> Kind.BREW
                JELLYFISH.any { name.contains(it, ignoreCase = true) } -> Kind.JELLYFISH
                FOOD.any { name.contains(it) } -> Kind.FOOD
                else -> null
            }
        }
    }
}
