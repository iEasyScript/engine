package com.projectx.script.impl.devin.aiobozocombat

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.keyDown
import com.projectx.script.api.keyUp

/**
 * Presses the ability the queue is pointing at, when it is genuinely time to press it.
 *
 * Opt-in and off by default: a wrong suggestion that only renders is a bad lesson, while a wrong
 * suggestion that presses a key mid-fight loses the kill. Abilities only — a prayer flicked or a
 * brew drunk by mistake costs more than a missed ability, and a positional cue has nothing to press.
 *
 * Two independent gates stand between a decision and a click. [ZukInputGate]'s incident — 257
 * automated clicks against 206 real ones — came from an unguarded loop calling an api helper once per
 * iteration, so the global one-per-tick cap here is not a tuning knob.
 */
class RotationActivator {

    @Volatile
    private var lastPressedStruct = -1

    @Volatile
    private var lastPressedTick = Int.MIN_VALUE

    /**
     * @param pressable the game will accept the input this instant — own cooldown, global cooldown
     *   and any running channel all clear.
     * @return true when a click was issued.
     */
    fun tryPress(
        structId: Int,
        slot: IFSlot?,
        keybind: String?,
        tick: Int,
        inCombat: Boolean,
        pressable: Boolean,
        observedCast: (Int) -> Boolean
    ): Boolean {
        if (!inCombat || !pressable) return false
        if (slot == null && keybind == null) return false

        // One input per tick, matching the server's own rate. Nothing is gained by sending faster and
        // the flood risk is real.
        if (tick == lastPressedTick) return false

        // Re-pressing the same ability before its cast has been observed double-fires it: the varc
        // takes a tick to move, so the queue still names it on the next poll. But a press the game
        // refused never produces a cast, so this cannot wait forever — without the retry window a
        // single rejected click locks the activator out for the rest of the fight.
        if (structId == lastPressedStruct && !observedCast(structId) &&
            tick - lastPressedTick < RETRY_TICKS
        ) return false

        // Click the slot's real button; fall back to its keybind only if the button cannot be resolved.
        val key = keybind?.singleOrNull()?.uppercaseChar()
        val clicked = slot != null && runCatching { slot.click(1) }.getOrDefault(false)
        val fired = clicked || (key != null && runCatching { keyDown(key); keyUp(key); true }.getOrDefault(false))
        if (!fired) {
            println("[BozoCap] PRESSFAIL struct=$structId key=${keybind ?: "-"} slot=${slot?.componentId}")
            return false
        }

        lastPressedStruct = structId
        lastPressedTick = tick
        println("[BozoCap] PRESS struct=$structId via=${if (clicked) "click" else "key $key"} tick=$tick")
        return true
    }

    private companion object {
        /** Long enough for a real cast's varc to appear, short enough that a refusal retries soon. */
        const val RETRY_TICKS = 4
    }

    fun reset() {
        lastPressedStruct = -1
        lastPressedTick = Int.MIN_VALUE
    }
}
