package com.projectx.script

import com.projectx.game.chat.MessageType
import com.projectx.game.input.Key
import com.projectx.game.interfaces.InstanceSystem
import com.projectx.game.interfaces.confirmInstanceDialogue
import com.projectx.game.interfaces.startOrRejoinInstance
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.webwalker.WebWalkResult
import com.projectx.webwalker.WebWalker
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.Effect
import org.projectx.core.game.skill.Skill
import java.util.concurrent.ThreadLocalRandom
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.math.roundToInt

/**
 * What a [JavaScript] asks the engine to wait for. Build one with the static factories —
 * prefer [until] over [ms] wherever an outcome can be observed, so the script reacts to the
 * game rather than to a guess.
 *
 * Every Kotlin helper that waits (a `suspend fun`) has a factory here of the same name, so Java reaches the whole API.
 * Those that report an outcome take an optional `onResult`, called with it once the helper finishes; a step after it
 * in a [sequence] can read what it stored. [JavaScript.shouldInterrupt] cuts any of them short, and then `onResult` is
 * not called.
 */
sealed class Wait {

    class Millis internal constructor(val mean: Int, val variance: Int) : Wait()

    class Until internal constructor(
        val predicate: BooleanSupplier,
        val timeoutMillis: Long,
        val pollMillis: Int,
    ) : Wait()

    class While internal constructor(
        val predicate: BooleanSupplier,
        val timeoutMillis: Long,
    ) : Wait()

    class XpDrop internal constructor(val skill: Skill?, val timeoutMillis: Long) : Wait()

    class Idle internal constructor(val maxTicks: Int, val idleChecks: Int, val countAnimation: Boolean) : Wait()

    class Sequence internal constructor(val steps: List<Step>) : Wait()

    class Loop internal constructor(val step: Step) : Wait()

    class WebWalk internal constructor(
        val x: Int,
        val y: Int,
        val plane: Int,
        val arriveDistance: Int,
        val onResult: Consumer<WebWalkResult>?,
        val useLodestones: Boolean,
    ) : Wait()

    /** Runs a Kotlin helper that waits, then hands its result to [onResult]. */
    class Call<T> internal constructor(
        internal val action: suspend Script.() -> T,
        internal val onResult: Consumer<in T>?,
    ) : Wait()

    object Abort : Wait()

    companion object {
        private fun <T> call(onResult: Consumer<in T>?, action: suspend Script.() -> T): Wait = Call(action, onResult)

        // ---- Timing ----

        /** A fixed pause. Prefer the randomised overload; a constant delay is a recognisable pattern. */
        @JvmStatic
        fun ms(millis: Int): Wait = Millis(millis, 0)

        /** A randomised pause around [mean], spread by [variance]. */
        @JvmStatic
        fun ms(mean: Int, variance: Int): Wait = Millis(mean, variance)

        /** A pause picked uniformly between [minMillis] and [maxMillis], inclusive. */
        @JvmStatic
        fun between(minMillis: Int, maxMillis: Int): Wait =
            Millis(ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1), 0)

        /** [ticks] game ticks of 600 ms (fractions allowed), plus 0..[jitterMillis] ms picked uniformly. */
        @JvmStatic
        @JvmOverloads
        fun ticks(ticks: Double, jitterMillis: Int = 0): Wait = ticks(ticks, 0, jitterMillis)

        /** [ticks] game ticks of 600 ms (fractions allowed), plus [minJitterMillis]..[maxJitterMillis] ms picked uniformly. */
        @JvmStatic
        fun ticks(ticks: Double, minJitterMillis: Int, maxJitterMillis: Int): Wait =
            Millis(
                (ticks * Script.TICK_MILLIS).roundToInt() +
                    ThreadLocalRandom.current().nextInt(minJitterMillis, maxJitterMillis + 1),
                0,
            )

        // ---- Conditions and events ----

        /** Wait until [predicate] holds, giving up after [timeoutMillis]. */
        @JvmStatic
        @JvmOverloads
        fun until(predicate: BooleanSupplier, timeoutMillis: Long, pollMillis: Int = 100): Wait =
            Until(predicate, timeoutMillis, pollMillis)

        /** Wait while [predicate] holds, giving up after [timeoutMillis]. */
        @JvmStatic
        fun whileTrue(predicate: BooleanSupplier, timeoutMillis: Long): Wait =
            While(predicate, timeoutMillis)

        /** Wait for the next experience drop, optionally in one [skill]. */
        @JvmStatic
        @JvmOverloads
        fun xpDrop(skill: Skill? = null, timeoutMillis: Long = 15000): Wait =
            XpDrop(skill, timeoutMillis)

        /** Wait for the next event [predicate] accepts, giving up after [timeoutMillis]. Kotlin: `waitForEvent`. */
        @JvmStatic
        @JvmOverloads
        fun event(predicate: Predicate<Event>, timeoutMillis: Long = 15000): Wait =
            call(null) { waitForEvent(timeoutMillis, predicate) }

        /** Wait for a chat message of [type] containing [text], ignoring case. Kotlin: `waitForChatContaining`. */
        @JvmStatic
        @JvmOverloads
        fun chatContaining(type: MessageType, text: String, timeoutMillis: Long = 15000): Wait =
            call(null) { waitForChatContaining(type, text, timeoutMillis) }

        /**
         * Wait for the player to stop moving and animating: checked once a tick, finished once
         * [idleChecks] checks in a row see nothing going on, or after [maxTicks] ticks.
         */
        @JvmStatic
        fun untilIdle(maxTicks: Int, idleChecks: Int): Wait = Idle(maxTicks, idleChecks, countAnimation = true)

        /**
         * Wait for the player to stop moving, ignoring animation: checked once a tick, finished once
         * [stillChecks] checks in a row see no movement, or after [maxTicks] ticks. Use it after clicking
         * something you walk to and then keep working at, such as a rock or an altar, where [untilIdle]
         * would wait out the whole activity.
         */
        @JvmStatic
        fun untilStoppedMoving(maxTicks: Int, stillChecks: Int): Wait = Idle(maxTicks, stillChecks, countAnimation = false)

        /** 1.2 s, then until the player stops moving, giving up after [timeoutMillis]. Kotlin: `waitUntilNotMoving`. */
        @JvmStatic
        @JvmOverloads
        fun untilNotMoving(timeoutMillis: Long = 30000): Wait = call(null) { waitUntilNotMoving(timeoutMillis) }

        /** 1.2 s, then until the player stops moving and animating. Kotlin: `waitUntilNotAniMoving`. */
        @JvmStatic
        @JvmOverloads
        fun untilNotAniMoving(timeoutMillis: Long = 30000): Wait = call(null) { waitUntilNotAniMoving(timeoutMillis) }

        // ---- Composition ----

        /**
         * Run [steps] one after another, each performing its own wait before the next starts — the
         * non-blocking way to write "click, wait, click, wait". A step runs only when the one before
         * it has finished waiting, so it always sees the game as it is at that moment.
         */
        @JvmStatic
        fun sequence(vararg steps: Step): Wait = Sequence(steps.toList())

        /**
         * Run [step] again and again, performing the wait it returns each time, until it returns null.
         * The non-blocking form of a `while` loop with a sleep inside it.
         */
        @JvmStatic
        fun loop(step: Step): Wait = Loop(step)

        /**
         * Returned from a step: skip every remaining step of the sequences and loops it belongs to, and
         * go straight on to the next [JavaScript.onLoop].
         */
        @JvmStatic
        fun abort(): Wait = Abort

        /** Pause every other running script for [durationMillis] while this one waits. Kotlin: `pauseOthersFor`. */
        @JvmStatic
        fun pauseOthersFor(durationMillis: Long): Wait = call(null) { this.pauseOthersFor(durationMillis) }

        // ---- Walking and teleports ----

        /**
         * Walk to ([x], [y]) on [plane] from anywhere on the world map, planning the route from cache collision and
         * opening doors on the way; finished within [arriveDistance] tiles. A long walk teleports to an unlocked
         * lodestone first when that is quicker, unless [useLodestones] is false. [onResult], when given, receives how
         * it ended, so a step after this one can tell arriving from failing. See [WebWalker] for what routes cover.
         */
        @JvmStatic
        @JvmOverloads
        fun webWalk(
            x: Int,
            y: Int,
            plane: Int,
            arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
            onResult: Consumer<WebWalkResult>? = null,
            useLodestones: Boolean = true,
        ): Wait = WebWalk(x, y, plane, arriveDistance, onResult, useLodestones)

        /** Open the lodestone map if it is shut, otherwise teleport to [lodestone]. Kotlin: `useLodestone`. */
        @JvmStatic
        fun useLodestone(lodestone: Lodestone): Wait = call(null) { this.useLodestone(lodestone) }

        /** Teleport to [boss] through the group system. Kotlin: `teleportWithGroupSystem`. */
        @JvmStatic
        fun teleportWithGroupSystem(boss: GroupTeleports): Wait = call(null) { this.teleportWithGroupSystem(boss) }

        /** Hop to a random world, members only unless [membersOnly] is false. Kotlin: `randomizedWorldHop`. */
        @JvmStatic
        @JvmOverloads
        fun randomizedWorldHop(membersOnly: Boolean = true): Wait = call(null) { this.randomizedWorldHop(membersOnly) }

        /** Hop to the least populated world not used in the last [cooldownMinutes]. Kotlin: `randomizedWorldHopQuick`. */
        @JvmStatic
        @JvmOverloads
        fun randomizedWorldHopQuick(currentWorld: Int, membersOnly: Boolean = true, cooldownMinutes: Long = 10): Wait =
            call(null) { this.randomizedWorldHopQuick(membersOnly, currentWorld, cooldownMinutes) }

        /** Hop worlds when other players are nearby; [onResult] gets whether it hopped. Kotlin: `checkWorldPop`. */
        @JvmStatic
        @JvmOverloads
        fun checkWorldPop(onResult: Consumer<Boolean>? = null, cooldownMinutes: Long = 10): Wait =
            call(onResult) { this.checkWorldPop(cooldownMinutes) }

        // ---- Input, items and prayers ----

        /** Press and release [key], a native key code. Kotlin: `clickKey`. */
        @JvmStatic
        fun clickKey(key: Int): Wait = call(null) { this.clickKey(key) }

        /** Press and release the key that types [key]. Kotlin: `clickKey`. */
        @JvmStatic
        fun clickKey(key: Char): Wait = call(null) { this.clickKey(key) }

        /** Press and release [key]. Kotlin: `clickKey`. */
        @JvmStatic
        fun clickKey(key: Key): Wait = call(null) { this.clickKey(key) }

        /** Pick up the named ground items; [onResult] gets whether it picked any up. Kotlin: `findAndPickupItems`. */
        @JvmStatic
        fun findAndPickupItems(vararg items: String): Wait = findAndPickupItems(null, *items)

        /** Pick up the named ground items; [onResult] gets whether it picked any up. Kotlin: `findAndPickupItems`. */
        @JvmStatic
        fun findAndPickupItems(onResult: Consumer<Boolean>?, vararg items: String): Wait =
            call(onResult) { this.findAndPickupItems(*items) }

        /** Wear a Sign of the porter from the inventory when none is worn. Kotlin: `checkPorter`. */
        @JvmStatic
        fun checkPorter(): Wait = call(null) { this.checkPorter() }

        /**
         * Capture the nearest Seren spirit within [range] tiles; [onResult] gets whether one was captured.
         * Kotlin: `captureSerenSpirit`.
         */
        @JvmStatic
        @JvmOverloads
        fun captureSerenSpirit(range: Int = 15, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.captureSerenSpirit(range) }

        /** Turn [prayer] on or off, then wait for the change. Kotlin: `togglePrayer`. */
        @JvmStatic
        fun togglePrayer(prayer: Prayer, shouldBeActive: Boolean): Wait = call(null) { this.togglePrayer(prayer, shouldBeActive) }

        /** Turn quick prayers on or off, then wait for the change. Kotlin: `toggleQuickPrayers`. */
        @JvmStatic
        fun toggleQuickPrayers(shouldBeActive: Boolean): Wait = call(null) { this.toggleQuickPrayers(shouldBeActive) }

        // ---- Combat ----

        /** Cast [ability] when it is off cooldown, then wait for it to go on cooldown. Kotlin: `castAndWaitForCd`. */
        @JvmStatic
        @JvmOverloads
        fun castAndWaitForCd(ability: Ability, onResult: Consumer<Boolean>? = null, timeoutMillis: Long = 6200): Wait =
            call(onResult) { this.castAndWaitForCd(ability, timeoutMillis) }

        /**
         * Cast [ability] with at least [required] adrenaline, then wait for [waitCondition], or for the ability to go on
         * cooldown when it is null. Kotlin: `castWithAdren`.
         */
        @JvmStatic
        @JvmOverloads
        fun castWithAdren(
            ability: Ability,
            required: Int,
            onResult: Consumer<Boolean>? = null,
            waitCondition: BooleanSupplier? = null,
            timeoutMillis: Long = 6200,
        ): Wait = call(onResult) {
            if (waitCondition == null) this.castWithAdren(ability, required, timeout = timeoutMillis)
            else this.castWithAdren(ability, required, { waitCondition.asBoolean }, timeoutMillis)
        }

        /** Cast [ability] when [condition] is true, then wait for [waitCondition]. Kotlin: `castIf`. */
        @JvmStatic
        @JvmOverloads
        fun castIf(
            condition: Boolean,
            ability: Ability,
            waitCondition: BooleanSupplier,
            onResult: Consumer<Boolean>? = null,
            timeoutMillis: Long = 6200,
        ): Wait = call(onResult) { this.castIf(condition, ability, { waitCondition.asBoolean }, timeoutMillis) }

        /**
         * Cast [ability] when [condition] holds and there is [resourceRequired] adrenaline, then wait for
         * [waitCondition], or for the cooldown when it is null. Kotlin: `smartCast`.
         */
        @JvmStatic
        @JvmOverloads
        fun smartCast(
            ability: Ability,
            onResult: Consumer<Boolean>? = null,
            resourceRequired: Int = 0,
            condition: BooleanSupplier? = null,
            waitCondition: BooleanSupplier? = null,
            timeoutMillis: Long = 6200,
            checkCooldown: Boolean = true,
        ): Wait = call(onResult) {
            this.smartCast(
                ability = ability,
                resourceRequired = resourceRequired,
                condition = { condition?.asBoolean ?: true },
                waitCondition = { waitCondition?.asBoolean ?: !ability.offCdIgnoreGCD },
                timeout = timeoutMillis,
                checkCooldown = checkCooldown,
            )
        }

        /** Cast [ability] once [effect] has at least [minStacks] stacks. Kotlin: `castWithEffectStacks`. */
        @JvmStatic
        @JvmOverloads
        fun castWithEffectStacks(
            ability: Ability,
            effect: Effect,
            minStacks: Int,
            onResult: Consumer<Boolean>? = null,
            waitCondition: BooleanSupplier? = null,
            timeoutMillis: Long = 6200,
        ): Wait = call(onResult) {
            this.castWithEffectStacks(ability, effect, minStacks, waitCondition?.let { { it.asBoolean } }, timeoutMillis)
        }

        // ---- Make-X and smithing ----

        /** The pause a player takes before reacting to the Make-X window. Kotlin: `makeXReaction`. */
        @JvmStatic
        @JvmOverloads
        fun makeXReaction(mean: Int = 820, variance: Int = 520): Wait = call(null) { this.makeXReaction(mean, variance) }

        /** Press the Make-X window's confirm button. Kotlin: `makeXConfirm`. */
        @JvmStatic
        @JvmOverloads
        fun makeXConfirm(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.makeXConfirm() }

        /** Select the Make-X category whose name [match] accepts. Kotlin: `selectMakeCategory`. */
        @JvmStatic
        @JvmOverloads
        fun selectMakeCategory(match: Predicate<String>, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.selectMakeCategory { match.test(it) } }

        /** Select the item [itemMatch] accepts, switching to the category [categoryMatch] accepts first. Kotlin: `makeXSelect`. */
        @JvmStatic
        @JvmOverloads
        fun makeXSelect(
            itemMatch: Predicate<String>,
            categoryMatch: Predicate<String>? = null,
            onResult: Consumer<Boolean>? = null,
        ): Wait = call(onResult) { this.makeXSelect({ itemMatch.test(it) }, categoryMatch?.let { m -> { m.test(it) } }) }

        /** Select the item [itemMatch] accepts and confirm. Kotlin: `makeX`. */
        @JvmStatic
        @JvmOverloads
        fun makeX(
            itemMatch: Predicate<String>,
            categoryMatch: Predicate<String>? = null,
            onResult: Consumer<Boolean>? = null,
        ): Wait = call(onResult) { this.makeX({ itemMatch.test(it) }, categoryMatch?.let { m -> { m.test(it) } }) }

        /** Set the smithing quantity to [target]; [onResult] gets the quantity set. Kotlin: `smithSetQuantity`. */
        @JvmStatic
        @JvmOverloads
        fun smithSetQuantity(target: Int, onResult: Consumer<Int>? = null): Wait = call(onResult) { this.smithSetQuantity(target) }

        /** Select smithing tier [target]. Kotlin: `smithSelectTier`. */
        @JvmStatic
        @JvmOverloads
        fun smithSelectTier(target: Int, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.smithSelectTier(target) }

        /** Select the smithing item whose name [match] accepts. Kotlin: `smithSelectItem`. */
        @JvmStatic
        @JvmOverloads
        fun smithSelectItem(match: Predicate<String>, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.smithSelectItem { match.test(it) } }

        /** Press the smithing window's make button. Kotlin: `smithMake`. */
        @JvmStatic
        @JvmOverloads
        fun smithMake(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.smithMake() }

        // ---- Keyboard ----

        /** Press [key] the way a physical keyboard does: down, its character, a human hold, up. Kotlin: `pressKey`. */
        @JvmStatic
        fun pressKey(key: Key): Wait = call(null) { this.pressKey(key) }

        /** Type [text] one physical keystroke at a time; [onResult] gets false if a character cannot be typed. Kotlin: `typeText`. */
        @JvmStatic
        @JvmOverloads
        fun typeText(text: String, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.typeText(text) }

        // ---- Grand Exchange ----

        /** Open the exchange through the nearest clerk or banker. Kotlin: `geOpen`. */
        @JvmStatic
        @JvmOverloads
        fun geOpen(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.geOpen() }

        /** Buy [quantity] of [itemId] at [price] each in the first empty slot. Kotlin: `geBuy`. */
        @JvmStatic
        @JvmOverloads
        fun geBuy(itemId: Int, quantity: Int, price: Long, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.geBuy(itemId, quantity, price) }

        /** Buy [quantity] of [itemId] at [price] each in [slot]. Kotlin: `geBuy`. */
        @JvmStatic
        fun geBuyInSlot(itemId: Int, quantity: Int, price: Long, slot: Int, onResult: Consumer<Boolean>?): Wait =
            call(onResult) { this.geBuy(itemId, quantity, price, slot) }

        /** Sell [quantity] of [itemId] from the backpack at [price] each in the first empty slot. Kotlin: `geSell`. */
        @JvmStatic
        @JvmOverloads
        fun geSell(itemId: Int, quantity: Int, price: Long, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.geSell(itemId, quantity, price) }

        /** Sell [quantity] of [itemId] from the backpack at [price] each in [slot]. Kotlin: `geSell`. */
        @JvmStatic
        fun geSellInSlot(itemId: Int, quantity: Int, price: Long, slot: Int, onResult: Consumer<Boolean>?): Wait =
            call(onResult) { this.geSell(itemId, quantity, price, slot) }

        /** Abort the offer in [slot]. Kotlin: `geAbort`. */
        @JvmStatic
        @JvmOverloads
        fun geAbort(slot: Int, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.geAbort(slot) }

        /** Collect everything waiting in every slot. Kotlin: `geCollectAll`. */
        @JvmStatic
        @JvmOverloads
        fun geCollectAll(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.geCollectAll() }

        /** Refresh the wiki prices and wait for them; [onResult] gets whether prices are loaded. Kotlin: `awaitGrandExchangePrices`. */
        @JvmStatic
        @JvmOverloads
        fun awaitGrandExchangePrices(timeoutMillis: Long = 20_000, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.awaitGrandExchangePrices(timeoutMillis) }

        // ---- Summoning ----

        /** Withdraw [pouch] from the bank if needed and summon it. Kotlin: `familiarRenewFromBank`. */
        @JvmStatic
        @JvmOverloads
        fun familiarRenewFromBank(pouch: Familiar, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.familiarRenewFromBank(pouch) }

        /** Summon [pouch] from the inventory. Kotlin: `familiarSummonFamiliar`. */
        @JvmStatic
        @JvmOverloads
        fun familiarSummonFamiliar(pouch: Familiar, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.familiarSummonFamiliar(pouch) }

        /** Renew [pouch] from the summoning interface. Kotlin: `familiarRenewFromInterface`. */
        @JvmStatic
        @JvmOverloads
        fun familiarRenewFromInterface(pouch: Familiar, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.familiarRenewFromInterface(pouch) }

        /** Call the familiar back. Kotlin: `familiarRecall`. */
        @JvmStatic
        @JvmOverloads
        fun familiarRecall(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.familiarRecall() }

        /** Dismiss the familiar. Kotlin: `familiarDismiss`. */
        @JvmStatic
        @JvmOverloads
        fun familiarDismiss(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.familiarDismiss() }

        /** Take the familiar's scrolls. Kotlin: `familiarTakeScrolls`. */
        @JvmStatic
        @JvmOverloads
        fun familiarTakeScrolls(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.familiarTakeScrolls() }

        /** Cast the familiar's special move on the thing with [id]. Kotlin: `familiarCastSpecial`. */
        @JvmStatic
        @JvmOverloads
        fun familiarCastSpecial(id: Int, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.familiarCastSpecial(id) }

        /** Cast the familiar's special move on the thing named [name]. Kotlin: `familiarCastSpecial`. */
        @JvmStatic
        @JvmOverloads
        fun familiarCastSpecial(name: String, onResult: Consumer<Boolean>? = null): Wait =
            call(onResult) { this.familiarCastSpecial(name) }

        /** Give the beast of burden the whole inventory. Kotlin: `bobGiveAllItems`. */
        @JvmStatic
        @JvmOverloads
        fun bobGiveAllItems(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobGiveAllItems() }

        /** Take everything the beast of burden carries. Kotlin: `bobTakeAllItems`. */
        @JvmStatic
        @JvmOverloads
        fun bobTakeAllItems(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobTakeAllItems() }

        /** Give the beast of burden the item named [name]. Kotlin: `bobGiveItem`. */
        @JvmStatic
        @JvmOverloads
        fun bobGiveItem(name: String, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobGiveItem(name) }

        /** Give the beast of burden the item with [id]. Kotlin: `bobGiveItem`. */
        @JvmStatic
        @JvmOverloads
        fun bobGiveItem(id: Int, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobGiveItem(id) }

        /** Take the item named [name] from the beast of burden. Kotlin: `bobTake`. */
        @JvmStatic
        @JvmOverloads
        fun bobTake(name: String, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobTake(name) }

        /** Take the item with [id] from the beast of burden. Kotlin: `bobTake`. */
        @JvmStatic
        @JvmOverloads
        fun bobTake(id: Int, onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.bobTake(id) }

        /** Store items by id, each mapped to an amount (1, 5, 10 or all), in map order. Kotlin: `bobStore`. */
        @JvmStatic
        @JvmOverloads
        fun bobStoreById(amountsById: Map<Int, Int>, onResult: Consumer<Boolean?>? = null): Wait =
            call(onResult) { this.bobStore(*amountsById.map { it.key to it.value }.toTypedArray()) }

        /** Store items by name, each mapped to an amount (1, 5, 10 or all), in map order. Kotlin: `bobStore`. */
        @JvmStatic
        @JvmOverloads
        fun bobStoreByName(amountsByName: Map<String, Int>, onResult: Consumer<Boolean?>? = null): Wait =
            call(onResult) { this.bobStore(*amountsByName.map { it.key to it.value }.toTypedArray()) }

        /** Withdraw items by id, each mapped to an amount (1, 5, 10 or all), in map order. Kotlin: `bobWithdraw`. */
        @JvmStatic
        @JvmOverloads
        fun bobWithdrawById(amountsById: Map<Int, Int>, onResult: Consumer<Boolean?>? = null): Wait =
            call(onResult) { this.bobWithdraw(*amountsById.map { it.key to it.value }.toTypedArray()) }

        /** Withdraw items by name, each mapped to an amount (1, 5, 10 or all), in map order. Kotlin: `bobWithdraw`. */
        @JvmStatic
        @JvmOverloads
        fun bobWithdrawByName(amountsByName: Map<String, Int>, onResult: Consumer<Boolean?>? = null): Wait =
            call(onResult) { this.bobWithdraw(*amountsByName.map { it.key to it.value }.toTypedArray()) }

        // ---- Instances ----

        /** Join the instance through the instance interface. Kotlin: `InstanceSystem.joinInstance`. */
        @JvmStatic
        fun joinInstance(): Wait = call(null) { with(InstanceSystem) { this@call.joinInstance() } }

        /** Answer yes to the instance confirmation dialogue when it shows. Kotlin: `confirmInstanceDialogue`. */
        @JvmStatic
        @JvmOverloads
        fun confirmInstanceDialogue(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.confirmInstanceDialogue() }

        /** Rejoin the running instance, or start a new one. Kotlin: `startOrRejoinInstance`. */
        @JvmStatic
        @JvmOverloads
        fun startOrRejoinInstance(onResult: Consumer<Boolean>? = null): Wait = call(onResult) { this.startOrRejoinInstance() }
    }
}
