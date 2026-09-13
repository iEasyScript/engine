package com.projectx.script

import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.XPDrop
import com.projectx.util.gaussian
import kotlinx.coroutines.withTimeoutOrNull
import java.util.function.Predicate
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

abstract class Script {
    companion object {
        /** The pause the engine takes after every [loop] pass, on top of anything the pass waited for. */
        const val LOOP_PASS_MILLIS = 40
    }

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
            while (!stopped) {
                loop()
                delay(LOOP_PASS_MILLIS)
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