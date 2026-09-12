package org.projectx.core

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

val GAME_TICK: Duration = 600.milliseconds

val Duration.inGameTicks: Int get() = (this / GAME_TICK).roundToInt()
