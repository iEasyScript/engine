package com.projectx.script

import androidx.compose.runtime.State
import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.XPDrop
import com.projectx.script.api.localPlayer
import com.projectx.util.gaussian
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ThreadLocalRandom
import com.projectx.script.api.awaitServerTick
import com.projectx.util.Logger
import java.util.function.Predicate
import kotlin.math.roundToInt
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

abstract class Script {
    companion object {
        /** The pause the engine takes after every [loop] pass, on top of anything the pass waited for. */
        const val LOOP_PASS_MILLIS = 40

        /** How often [shouldInterrupt] is checked while a script waits. */
        const val INTERRUPT_POLL_MILLIS = 50

        /** One game tick. */
        const val TICK_MILLIS = 600
    }

    private var loopPaceMillis = LOOP_PASS_MILLIS
    private var loopOnServerTick = false
    private var lastLogged: String? = null

    private var pendingEventWaitCompleted = false
    private var pendingEventPredicate: Predicate<Event>? = null
    private val parallelScripts = mutableListOf<Script>()

    var started = false
        private set

    var stopped = false
        private set

    private val dispatcher = ClientPulseDispatcher()

    fun tick() {
        if (stopped) return

        if (!started) {
            started = true
            startCoroutineLoop()
        } else
            dispatcher.tick()
    }

    private fun startCoroutineLoop() {
        val suspendLambda: suspend () -> Unit = {
            onStart()
            // A Java script checks shouldInterrupt between its own waits, so only Kotlin scripts that override it pay
            // for the watch.
            val interruptible = this !is JavaScript && overridesShouldInterrupt()
            while (!stopped) {
                if (interruptible) interruptWhen({ shouldInterrupt() }) { loop() } else loop()
                if (loopOnServerTick) awaitServerTick() else delay(loopPaceMillis)
            }
            onStop()
            stopParallelScripts()
        }
        suspendLambda.startCoroutine(object : Continuation<Unit> {
            override val context: CoroutineContext = dispatcher
            override fun resumeWith(result: Result<Unit>) {
                result.onFailure { it.printStackTrace() }
            }
        })
    }

    abstract suspend fun loop()

    /**
     * Sets the pause between [loop] passes, in milliseconds; [LOOP_PASS_MILLIS] by default. Call it from [onStart].
     *
     * A slower pace costs reaction time: whatever the script watches for is noticed up to this long after it happens,
     * so anything that has to react - a floor marker landing, a boss's animation - either keeps the default or uses
     * [shouldInterrupt], which is polled while the script waits. For a script that only needs to act once a game
     * tick, [setLoopOnServerTick] is the accurate way to ask for it: a fixed 600 ms pause drifts off the tick within
     * a few passes, because the pass's own work is added to it.
     */
    fun setLoop(millis: Int) {
        loopPaceMillis = millis.coerceAtLeast(0)
        loopOnServerTick = false
    }

    /**
     * Runs one [loop] pass per server tick, starting each as soon as the tick lands rather than on a local timer, so
     * the pass reads what the tick just changed. See [ServerTick]; the pass waits at most one tick's timeout when the
     * connection is idle, which stands still in the lobby.
     */
    fun setLoopOnServerTick() {
        loopOnServerTick = true
    }

    /**
     * Prints [message] to the console and the engine log, prefixed with the script's name. A message identical to the
     * one before it is dropped, so a line in a loop that runs many times a second reports a change instead of a wall
     * of the same text.
     */
    fun log(message: String) {
        if (message == lastLogged) return
        lastLogged = message
        Logger.log(scriptName(), message)
    }

    private fun scriptName(): String =
        javaClass.getAnnotation(ScriptDescription::class.java)?.name ?: javaClass.simpleName

    /**
     * Checked about every [INTERRUPT_POLL_MILLIS] ms while the script waits. Returning true abandons what it is waiting
     * for and starts the next pass straight away: the way to react to something urgent, such as standing in an attack's
     * floor marker, in the middle of a long action. In Kotlin the current [loop] pass is cancelled at its next
     * suspension; in Java the current wait and the sequences and loops around it end, and `onLoop` runs. Keep it cheap,
     * and make it false again once the script is handling the situation, or every wait is cut short.
     */
    protected open fun shouldInterrupt(): Boolean = false

    private fun overridesShouldInterrupt(): Boolean {
        var type: Class<*>? = javaClass
        while (type != null && type != Script::class.java) {
            if (type.declaredMethods.any { it.name == "shouldInterrupt" && it.parameterCount == 0 }) return true
            type = type.superclass
        }
        return false
    }

    /**
     * Runs [block], cancelling it once [condition] holds; checked about every [pollMillis] ms. Returns what [block]
     * returned, or null when it was cancelled. The block stops at its next suspension, so a click already sent is not
     * undone. Java scripts get the same through `shouldInterrupt`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> interruptWhen(
        condition: () -> Boolean,
        pollMillis: Int = INTERRUPT_POLL_MILLIS,
        block: suspend () -> T,
    ): T? = coroutineScope {
        val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
        while (work.isActive) {
            if (runCatching(condition).getOrDefault(false)) {
                work.cancel()
                break
            }
            select<Unit> {
                work.onJoin {}
                onTimeout(pollMillis.toLong()) {}
            }
        }
        if (work.isCancelled && !work.isCompleted) work.join()
        if (work.isCancelled) {
            // A block that failed rather than being interrupted rethrows here, as it would outside interruptWhen.
            work.getCompletionExceptionOrNull()?.takeUnless { it is CancellationException }?.let { throw it }
            null
        } else {
            work.await()
        }
    }

    fun stop() {
        stopped = true
        ScriptExecutor.deactivate(this)
        parallelScripts.forEach { ScriptExecutor.deactivate(it) }
    }

    fun addParallelScript(script: Script) {
        parallelScripts.add(script)
        ScriptExecutor.activate(script)
    }

    fun stopParallelScripts() = parallelScripts.forEach { ScriptExecutor.deactivate(it) }

    fun removeParallelScript(script: Script, deactivate: Boolean = true) {
        parallelScripts.remove(script)
        if (deactivate) {
            ScriptExecutor.deactivate(script)
        }
    }

    open fun onStart() = println("${this.javaClass.simpleName} started.")
    open fun onStop() = println("${this.javaClass.simpleName} stopped.")

    open fun onEvent(event: Event) {}

    open fun render() {}

    /**
     * A value read from the game on the game thread every [everyMillis] while this script runs, for a
     * [ComposePanel] to display. A panel draws on the render thread and must never read the game itself:
     * `val xp by live(0) { getXp(Skill.MINING) }` reads on the right thread and updates the panel when it changes.
     */
    @JvmOverloads
    fun <T> live(initial: T, everyMillis: Long = 250, read: () -> T): State<T> =
        LiveValues.register(this, initial, everyMillis, read)

    fun _processEvent(event: Event) {
        onEvent(event)
        if (pendingEventPredicate?.test(event) == true)
            pendingEventWaitCompleted = true
    }

    private suspend fun waitForCondition(
        predicate: () -> Boolean,
        timeoutMillis: Long? = null,
        shouldWaitWhile: Boolean = true,
        pollingDelayMillis: Int = 100
    ) {
        val wrappedPredicate = {
            if (shouldWaitWhile) predicate() else !predicate()
        }

        if (timeoutMillis != null) {
            withTimeoutOrNull(timeoutMillis) {
                while (wrappedPredicate()) {
                    delay(pollingDelayMillis)
                }
            }
        } else {
            while (wrappedPredicate()) {
                delay(pollingDelayMillis)
            }
        }
    }

    suspend fun delayWhile(timeoutMillis: Long? = null, predicate: () -> Boolean) = waitForCondition(predicate, timeoutMillis, shouldWaitWhile = true)

    suspend fun waitThenDelayWhile(waitFor: Long, timeoutMillis: Long? = null, predicate: () -> Boolean) {
        delay(waitFor.toInt(), 1000)
        waitForCondition(predicate, timeoutMillis, shouldWaitWhile = true)
    }

    suspend fun delayUntil(timeoutMillis: Long? = null, pollingDelayMillis: Int = 100, predicate: () -> Boolean) = waitForCondition(predicate, timeoutMillis, pollingDelayMillis = pollingDelayMillis, shouldWaitWhile = false)

    suspend fun waitThenDelayUntil(waitFor: Long, timeoutMillis: Long? = null, pollingDelayMillis: Int = 100, predicate: () -> Boolean) {
        delay(waitFor.toInt(), 1000)
        waitForCondition(predicate, timeoutMillis, shouldWaitWhile = false, pollingDelayMillis = pollingDelayMillis)
    }

    suspend fun waitForEvent(timeoutMillis: Long = 15000, predicate: Predicate<Event>) {
        pendingEventPredicate = predicate
        pendingEventWaitCompleted = false
        delayUntil(timeoutMillis) { pendingEventWaitCompleted }
        pendingEventPredicate = null
    }

    suspend fun waitForXPDrop(skill: Skill? = null, timeoutMillis: Long = 15000) {
        waitForEvent(timeoutMillis) { event ->
            event is XPDrop && (skill == null || event.skill == skill)
        }
    }

    suspend fun waitForChatContaining(type: MessageType, text: String, timeoutMillis: Long = 15000) {
        waitForEvent(timeoutMillis) { event ->
            event is Chat && event.messageType == type && event.message.contains(text, ignoreCase = true)
        }
    }

    suspend fun delay(time: Int) {
        kotlinx.coroutines.delay(time.toLong())
    }

    suspend fun delay(mean: Int, variance: Int) {
        delay(gaussian(mean, variance))
    }

    /** A pause picked uniformly between [minMillis] and [maxMillis], inclusive. Java: `Wait.between`. */
    suspend fun delayBetween(minMillis: Int, maxMillis: Int) {
        delay(ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1))
    }

    /** [ticks] game ticks of 600 ms (fractions allowed), plus 0..[jitterMillis] ms picked uniformly. Java: `Wait.ticks`. */
    suspend fun delayTicks(ticks: Double, jitterMillis: Int = 0) {
        delayTicks(ticks, 0, jitterMillis)
    }

    /** [ticks] game ticks of 600 ms, plus [minJitterMillis]..[maxJitterMillis] ms picked uniformly. */
    suspend fun delayTicks(ticks: Double, minJitterMillis: Int, maxJitterMillis: Int) {
        delay((ticks * TICK_MILLIS).roundToInt() + ThreadLocalRandom.current().nextInt(minJitterMillis, maxJitterMillis + 1))
    }

    /**
     * Waits for the player to stop moving and animating: checked once a tick, finished once [idleChecks] checks in a
     * row see nothing going on (true), or after [maxTicks] ticks (false). Java: `Wait.untilIdle`.
     */
    suspend fun waitUntilIdle(maxTicks: Int, idleChecks: Int): Boolean = waitForStillness(maxTicks, idleChecks, true)

    /**
     * Waits for the player to stop moving, ignoring animation: checked once a tick, finished once [stillChecks] checks
     * in a row see no movement (true), or after [maxTicks] ticks (false). Use it after clicking something you walk to
     * and then keep working at, such as a rock, where [waitUntilIdle] would wait out the whole activity. Java:
     * `Wait.untilStoppedMoving`.
     */
    suspend fun waitUntilStoppedMoving(maxTicks: Int, stillChecks: Int): Boolean = waitForStillness(maxTicks, stillChecks, false)

    private suspend fun waitForStillness(maxTicks: Int, checks: Int, countAnimation: Boolean): Boolean {
        var still = 0
        repeat(maxTicks) {
            delay(TICK_MILLIS)
            val busy = localPlayer.isMoving || (countAnimation && localPlayer.isAnimating)
            still = if (busy) 0 else still + 1
            if (still >= checks) return true
        }
        return false
    }

    fun pauseOthers(): Boolean = ScriptExecutor.pauseOthers(this)
    fun resumeOthers(): Boolean = ScriptExecutor.resumeOthers(this)
    fun isPaused(): Boolean = ScriptExecutor.isPaused()

    suspend fun pauseOthersFor(durationMs: Long) {
        if (pauseOthers()) {
            try {
                delay(durationMs.toInt())
            } finally {
                resumeOthers()
            }
        }
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScriptDescription(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val visible: Boolean = true,
    val category: ScriptCategory = ScriptCategory.OTHER
)

enum class ScriptCategory(val readableName: String) {
    COMBAT("Combat"),
    MAGIC("Magic"),
    PRAYER("Prayer"),
    SUMMONING("Summoning"),
    NECROMANCY("Necromancy"),

    MINING("Mining"),
    FISHING("Fishing"),
    WOODCUTTING("Woodcutting"),
    FARMING("Farming"),
    HUNTER("Hunter"),
    DIVINATION("Divination"),
    ARCHAEOLOGY("Archaeology"),

    SMITHING("Smithing"),
    HERBLORE("Herblore"),
    COOKING("Cooking"),
    CRAFTING("Crafting"),
    FIREMAKING("Firemaking"),
    FLETCHING("Fletching"),
    RUNECRAFTING("Runecrafting"),
    CONSTRUCTION("Construction"),

    AGILITY("Agility"),
    THIEVING("Thieving"),
    SLAYER("Slayer"),
    DUNGEONEERING("Dungeoneering"),

    INVENTION("Invention"),

    QUESTS("Quests"),
    BOSSES("Bosses"),
    OTHER("Other")
}