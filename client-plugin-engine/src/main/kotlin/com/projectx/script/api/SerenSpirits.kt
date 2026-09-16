package com.projectx.script.api

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.util.gaussian
import java.util.concurrent.ConcurrentHashMap

/**
 * Seren spirits appear at random while skilling in a Grace of the elves, and capturing one sends a reward to the bank.
 * Skilling scripts call [captureSerenSpirit] from their loop, and end long waits early on `findSerenSpirit() != null`.
 */
private const val SEREN_SPIRIT = "Seren spirit"
private const val DEFAULT_RANGE = 15
private const val RETRY_AFTER_MILLIS = 60_000L

/** Spirits a capture did not remove, most likely someone else's, by server index and when that happened. */
private val refusedSpirits = ConcurrentHashMap<Int, Long>()

/** The nearest Seren spirit within [range] tiles, skipping one that just refused a capture, or null. */
@JvmOverloads
fun findSerenSpirit(range: Int = DEFAULT_RANGE): NPC? {
    val now = System.currentTimeMillis()
    refusedSpirits.values.removeIf { now - it > RETRY_AFTER_MILLIS }
    return findClosestNPC(range) {
        !refusedSpirits.containsKey(it.serverIndex) && it.name() == SEREN_SPIRIT && it.hasOption("Capture")
    }
}

/**
 * Captures the nearest Seren spirit and waits for it to vanish. Returns true when one was captured, false when there
 * was none or it stayed; a spirit that stays is skipped for a minute. Java: `Wait.captureSerenSpirit`.
 */
suspend fun Script.captureSerenSpirit(range: Int = DEFAULT_RANGE): Boolean {
    val spirit = findSerenSpirit(range) ?: return false
    if (spirit.interact("Capture")) delayUntil(gaussian(9000L, 1500L)) { !spirit.exists() }
    val captured = !spirit.exists()
    if (!captured) refusedSpirits[spirit.serverIndex] = System.currentTimeMillis()
    delay(420, 160)
    return captured
}
