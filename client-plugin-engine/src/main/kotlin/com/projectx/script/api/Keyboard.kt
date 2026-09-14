package com.projectx.script.api

import com.projectx.game.input.Key
import com.projectx.game.input.action.ActionInput
import com.projectx.game.platform.Platform
import com.projectx.script.Script
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.exp

/**
 * Presses [key] the way a physical keyboard reaches the client: key-down, the character the OS translates it
 * to, a human-length hold, key-up.
 */
suspend fun Script.pressKey(key: Key) {
    val stroke = Keystroke.of(key)
    strike(stroke)
}

/** True when every character of [text] can be typed with [typeText]: letters, digits, space, tab, newline and backspace. */
fun canTypeText(text: String): Boolean = text.all { Keystroke.of(it) != null }

/**
 * Types [text] one physical keystroke at a time, holding shift for capitals, with typing-speed gaps between
 * keys. Returns false without pressing anything when a character cannot be typed ([canTypeText]).
 */
suspend fun Script.typeText(text: String): Boolean {
    val strokes = text.map { Keystroke.of(it) ?: return false }
    for ((index, stroke) in strokes.withIndex()) {
        if (index > 0) delay(KeyTiming.gapMillis())
        strike(stroke)
    }
    return true
}

private suspend fun Script.strike(stroke: Keystroke) {
    if (stroke.shift) {
        ActionInput.keyDown(Keystroke.SHIFT)
        delay(KeyTiming.modifierLeadMillis())
    }
    ActionInput.keyDown(stroke.key)
    stroke.char?.let { ActionInput.keyChar(it) }
    delay(KeyTiming.holdMillis())
    ActionInput.keyUp(stroke.key)
    if (stroke.shift) {
        delay(KeyTiming.modifierTrailMillis())
        ActionInput.keyUp(Keystroke.SHIFT)
    }
}

internal class Keystroke(val key: Int, val char: Int?, val shift: Boolean) {
    companion object {
        /** Windows reports either shift key as the generic `VK_SHIFT` in the key message, not the side-specific code. */
        val SHIFT: Int
            get() = if (Platform.current == Platform.WINDOWS) 0x10 else Key.LSHIFT.native

        fun of(char: Char): Keystroke? {
            val key = Key.forChar(char) ?: return null
            return Keystroke(key.native, translatedChar(key, char), char.isUpperCase())
        }

        fun of(key: Key): Keystroke = Keystroke(key.native, translatedChar(key, null), false)

        /**
         * What the OS hands the client as the typed character. Windows translates the control keys too; SDL's
         * text input only ever carries printable characters.
         */
        private fun translatedChar(key: Key, typed: Char?): Int? {
            if (typed != null && typed != '\n' && typed != '\r' && typed != '\t' && typed != '\b') return typed.code
            val windows = Platform.current == Platform.WINDOWS
            return when (key) {
                Key.SPACE -> ' '.code
                Key.RETURN -> if (windows) '\r'.code else null
                Key.TAB -> if (windows) '\t'.code else null
                Key.BACKSPACE -> if (windows) '\b'.code else null
                Key.ESCAPE -> if (windows) 0x1B else null
                in Key.A..Key.Z -> key.name.lowercase().first().code
                in Key.NUM0..Key.NUM9 -> key.name.last().code
                else -> null
            }
        }
    }
}

/**
 * Keystroke timings drawn from right-skewed distributions, the shape real dwell and flight times have: most
 * presses are quick, a few are much slower.
 */
internal object KeyTiming {
    fun holdMillis(): Int = logNormal(median = 85.0, spread = 0.30, min = 38, max = 240)
    fun gapMillis(): Int {
        val random = ThreadLocalRandom.current()
        val hesitation = if (random.nextDouble() < 0.07) random.nextInt(180, 520) else 0
        return logNormal(median = 125.0, spread = 0.45, min = 45, max = 600) + hesitation
    }
    fun modifierLeadMillis(): Int = logNormal(median = 70.0, spread = 0.35, min = 25, max = 220)
    fun modifierTrailMillis(): Int = logNormal(median = 45.0, spread = 0.40, min = 10, max = 180)

    private fun logNormal(median: Double, spread: Double, min: Int, max: Int): Int {
        val sample = median * exp(ThreadLocalRandom.current().nextGaussian() * spread)
        return sample.toInt().coerceIn(min, max)
    }
}
