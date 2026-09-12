package com.projectx.util

import world.gregs.voidps.type.Tile
import com.projectx.profiling.PlayerProfiles
import java.security.SecureRandom
import java.text.NumberFormat
import java.util.*
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

private val RANDOM = SecureRandom()

fun random(max: Int): Int = random(0, max)
fun random(min: Int, max: Int): Int = min + RANDOM.nextInt(max - min)
fun random(max: Long): Long = random(0L, max)
fun random(min: Long, max: Long): Long = min + RANDOM.nextLong(max - min)
fun randomInclusive(max: Int): Int = randomInclusive(0, max)
fun randomInclusive(min: Int, max: Int): Int = random(min, max + 1)
fun randomD(max: Double): Double = randomD(0.0, max)
fun randomD(min: Double, max: Double): Double = min + (max - min) * RANDOM.nextDouble()
fun randomD(): Double = RANDOM.nextDouble()

fun gaussian(mean: Int, variance: Int) = (RANDOM.nextGaussian() * sqrt(variance * PlayerProfiles.get().gaussVariance) + mean).toInt()
fun gaussian(mean: Long, variance: Long) = (RANDOM.nextGaussian() * sqrt(variance * PlayerProfiles.get().gaussVariance) + mean).toLong()
fun nonProfiledGaussian(mean: Int, variance: Int) = (RANDOM.nextGaussian() * sqrt(variance.toDouble()) + mean).toInt()

val ANGLE_DIRECTION_DELTA = arrayOf(0 to -1, -1 to -1, -1 to 0, -1 to 1, 0 to 1, 1 to 1, 1 to 0, 1 to -1)
fun getDirDelta(angle: Int): Pair<Int, Int>? = ANGLE_DIRECTION_DELTA.getOrNull(angle shr 11)

fun getAngleTo(from: Tile, to: Tile) = getAngleTo(to.x - from.x, to.y - from.y)
fun getAngleTo(xOffset: Int, yOffset: Int) = (atan2(-xOffset.toDouble(), -yOffset.toDouble()) * 2607.5945876176133).toInt() and 0x3fff

fun hashFromInterface(interfaceId: Int, componentId: Int) = (interfaceId shl 16) + componentId
fun interfaceIdFromHash(hash: Int) = hash shr 16
fun componentIdFromHash(hash: Int) = hash - ((hash shr 16) shl 16)

fun millisElapsed(startTime: Long) = System.currentTimeMillis() - startTime
fun secondsElapsed(startTime: Long) = millisElapsed(startTime) / 1000f
fun minutesElapsed(startTime: Long) = secondsElapsed(startTime) / 60f
fun hoursElapsed(startTime: Long) = minutesElapsed(startTime) / 60f

fun getXpPerHour(startingXp: Int, currentXp: Int, startTime: Long) = ((currentXp - startingXp) / hoursElapsed(startTime)).toInt()
fun getFormattedXpPerHour(startingXp: Int, currentXp: Int, startTime: Long) = format(getXpPerHour(startingXp, currentXp, startTime))

fun getUnitsPerHour(unitsGained: Int, startTime: Long) = (unitsGained / hoursElapsed(startTime)).toInt()
fun getFormattedUnitsPerHour(unitsGained: Int, startTime: Long) = format(getUnitsPerHour(unitsGained, startTime))

fun format(number: Int) = NumberFormat.getNumberInstance(Locale.US).format(number)
fun format(number: Float, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", number)
fun format(number: Double, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", number)

fun formatElapsedTime(currTime: Long, startTime: Long): String {
    val totalSecs = (currTime - startTime) / 1000
    val hours = totalSecs / 3600
    val mins = (totalSecs / 60) % 60
    val secs = totalSecs % 60

    return "%02d:%02d:%02d".format(hours, mins, secs)
}

fun Byte.toBitString() = (7 downTo 0).joinToString("") { if (toInt() and (1 shl it) != 0) "1" else "0" }.chunked(4).joinToString(" ")
fun Int.toBitString() = (31 downTo 0).joinToString("") { if (this and (1 shl it) != 0) "1" else "0" }.chunked(4).joinToString(" ")

fun getXpForLevel(level: Int): Int {
    var points = 0
    var output = 0
    for (lvl in 1..level) {
        points = (points + floor(lvl + 300.0 * 2.0.pow(lvl / 7.0))).toInt()
        if (lvl >= level) return output
        output = (points.toDouble() / 4).toInt()
    }
    return 0
}

fun getLevelForXp(xp: Int): Int {
    var points = 0
    var output = 0
    for (lvl in 1..120) {
        points = (points + floor(lvl + 300.0 * 2.0.pow(lvl / 7.0))).toInt()
        output = (points.toDouble() / 4).toInt()
        if ((output - 1) >= xp) return lvl
    }
    return 120
}