package com.projectx.script.api

import com.projectx.script.Script
import com.projectx.util.gaussian

/**
 * The bank PIN screen. The keypad shuffles where each digit sits after every press, but each digit keeps its own
 * components: a label whose text is the digit, followed by the button that takes the click. The prompt beside the
 * keypad turns each entered digit's "?" into "*".
 */
object BankPin {
    private const val KEYPAD = 759
    private const val PROMPT = 13
    private const val FIRST_PROMPT_SLOT = 12
    private const val DIGITS = 4

    /** Whether the PIN screen is waiting for digits. */
    @JvmStatic
    val isOpen: Boolean get() = interfaces.isOpen(KEYPAD) && interfaces.isOpen(PROMPT)

    /** How many digits of the PIN have been entered so far. */
    @JvmStatic
    val digitsEntered: Int
        get() = (FIRST_PROMPT_SLOT until FIRST_PROMPT_SLOT + DIGITS).count { interfaces.getComponent(PROMPT, it)?.text == "*" }

    /** Clicks the keypad button for [digit]. */
    @JvmStatic
    fun press(digit: Int): Boolean {
        val keypad = interfaces[KEYPAD] ?: return false
        val label = (0 until keypad.size).firstOrNull { keypad[it]?.text == digit.toString() }
            ?: return false
        return interactComponent(1, KEYPAD, label + 1)
    }
}

/**
 * Enters [pin] on the bank PIN screen, a digit at a time, waiting for each one to register before the next. Returns
 * true once the screen has closed, or straight away when it is not open. [pin] must be four digits.
 */
suspend fun Script.enterBankPin(pin: String): Boolean {
    if (!BankPin.isOpen) return true
    require(pin.length == 4 && pin.all { it.isDigit() }) { "A bank PIN is four digits" }
    for (digit in pin.drop(BankPin.digitsEntered)) {
        val entered = BankPin.digitsEntered
        delay(gaussian(460, 190))
        if (!BankPin.press(digit.digitToInt())) return false
        delayUntil(gaussian(2400L, 600L)) { !BankPin.isOpen || BankPin.digitsEntered > entered }
        if (!BankPin.isOpen) break
    }
    delayUntil(gaussian(3000L, 700L)) { !BankPin.isOpen }
    return !BankPin.isOpen
}
