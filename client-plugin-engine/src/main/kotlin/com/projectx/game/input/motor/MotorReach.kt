package com.projectx.game.input.motor

import kotlin.math.hypot

/**
 * Rolls the motor model out into a complete reach, in live-window pixels.
 *
 * This replaces the hand-written minimum-jerk curve the engine used to draw. That curve was a stand-in for a
 * model that never produced usable motion; the point of the goal-conditioned model is that the shape, the
 * pacing and the endpoint scatter are all learned from the player rather than parameterised by constants.
 *
 * A rollout is generated in one go, off the critical path, so the caller can hand the samples out one per tick
 * without an inference call inside the game loop.
 */
object MotorReach {
    /** One generated position and the offset from the reach's start at which it should be reported. */
    data class Sample(val x: Float, val y: Float, val offsetMs: Long)

    data class Result(val samples: List<Sample>, val endsWithClick: Boolean, val reachedTarget: Boolean)

    /**
     * @param acceptRadiusPx how close counts as arrived, so a model that stops slightly short still ends cleanly
     */
    fun generate(
        model: MotorModel,
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        temperature: Float = 1.0f,
        acceptRadiusPx: Float = 6f,
    ): Result {
        model.reset()

        val contract = model.contract
        val samples = ArrayList<Sample>()

        var x = startX
        var y = startY
        var velocityX = 0f
        var velocityY = 0f
        var elapsedMs = 0f
        var click = false
        var reached = false

        val totalDistance = normalizedDistance(
            targetX - startX, targetY - startY, viewportWidth, viewportHeight
        )
        if (totalDistance <= 0f) return Result(emptyList(), endsWithClick = false, reachedTarget = true)

        repeat(contract.maxSteps) {
            val remainingX = (targetX - x) / viewportWidth
            val remainingY = (targetY - y) / viewportHeight

            val step = model.step(
                remaining = remainingX to remainingY,
                velocity = velocityX to velocityY,
                elapsedFraction = elapsedMs / contract.nominalReachMs,
                totalDistance = totalDistance,
                temperature = temperature,
            )

            x += step.dx * viewportWidth
            y += step.dy * viewportHeight
            velocityX = step.dx
            velocityY = step.dy
            elapsedMs += contract.stepIntervalMs

            samples.add(Sample(x, y, elapsedMs.toLong()))

            val distance = hypot((targetX - x).toDouble(), (targetY - y).toDouble()).toFloat()
            if (distance <= acceptRadiusPx) {
                reached = true
                click = step.click
                return@repeat
            }
            if (step.terminate) {
                click = step.click
                reached = distance <= acceptRadiusPx
                return@repeat
            }
        }

        return Result(samples, endsWithClick = click, reachedTarget = reached)
    }

    private fun normalizedDistance(dx: Float, dy: Float, width: Float, height: Float): Float =
        hypot((dx / width).toDouble(), (dy / height).toDouble()).toFloat()
}
