package com.projectx.script.api

import com.projectx.script.Script
import com.projectx.util.Logger

/**
 * Prints [message] to the console and the engine log, under the script's own name. A message identical to the one
 * before it is dropped, so a line in a loop that runs many times a second reports a change instead of a wall of the
 * same text.
 *
 * An extension rather than a member of [Script], because a script that already declares a `log` of its own has to
 * keep compiling: its own wins, and nothing it wrote has to change.
 */
fun Script.log(message: String) {
    if (message == lastLoggedMessage) return
    lastLoggedMessage = message
    Logger.log(scriptName(), message)
}
