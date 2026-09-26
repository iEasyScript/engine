package com.projectx.script.api

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.util.Logger
import java.util.WeakHashMap
import java.util.Collections

private val lastLogged: MutableMap<Script, String> = Collections.synchronizedMap(WeakHashMap())

/**
 * Prints [message] to the console and the engine log, under the script's own name. A message identical to the one
 * before it is dropped, so a line in a loop that runs many times a second reports a change instead of a wall of the
 * same text.
 *
 * An extension rather than a member of [Script], because a script that already declares a `log` of its own has to
 * keep compiling: its own wins, and nothing it wrote has to change.
 */
fun Script.log(message: String) {
    if (lastLogged.put(this, message) == message) return
    Logger.log(javaClass.getAnnotation(ScriptDescription::class.java)?.name ?: javaClass.simpleName, message)
}
