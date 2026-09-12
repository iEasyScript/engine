package com.projectx.game.input.motor

import com.projectx.game.input.wire.TrailSample
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns the motor model and turns targets into a stream of per-tick trail samples.
 *
 * A whole reach is rolled out at once and buffered, then drained a few samples per tick. That keeps every ONNX
 * call off the moment the game is waiting on us, and it matches how the samples are consumed: the wire carries
 * a run of positions in one history packet, not one packet per position.
 */
class MotorDriver(modelPath: String) : AutoCloseable {
    private val model = MotorModel(modelPath)
    private val buffered = ConcurrentLinkedQueue<TrailSample>()

    val contract get() = model.contract

    @Volatile var reachesGenerated: Long = 0
        private set
    @Volatile var lastReachSamples: Int = 0
        private set
    @Volatile var lastReachReachedTarget: Boolean = false
        private set
    @Volatile var lastRolloutMillis: Double = 0.0
        private set

    val pending: Int get() = buffered.size
    val isIdle: Boolean get() = buffered.isEmpty()

    /**
     * Roll out a reach from [startX], [startY] to the target and buffer it.
     *
     * @return whether the rollout finished at the target; a false here means the model is undertrained, and the
     *         caller should decide whether to use the samples rather than being handed a silently bad trail.
     */
    fun beginReach(
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        clockMs: Long,
        temperature: Float = 1.0f,
    ): Boolean {
        val started = System.nanoTime()
        val result = MotorReach.generate(
            model = model,
            startX = startX, startY = startY,
            targetX = targetX, targetY = targetY,
            viewportWidth = viewportWidth, viewportHeight = viewportHeight,
            temperature = temperature,
        )
        lastRolloutMillis = (System.nanoTime() - started) / 1_000_000.0
        lastReachSamples = result.samples.size
        lastReachReachedTarget = result.reachedTarget
        reachesGenerated++

        for (sample in result.samples) {
            buffered.add(
                TrailSample(
                    x = sample.x.toInt().coerceIn(0, viewportWidth.toInt() - 1),
                    y = sample.y.toInt().coerceIn(0, viewportHeight.toInt() - 1),
                    timestampMs = clockMs + sample.offsetMs,
                )
            )
        }
        return result.reachedTarget
    }

    /** Take everything buffered so far; the wire path batches it into one history packet. */
    fun drain(limit: Int = Int.MAX_VALUE): List<TrailSample> {
        if (buffered.isEmpty()) return emptyList()
        val out = ArrayList<TrailSample>(minOf(limit, buffered.size))
        while (out.size < limit) {
            out.add(buffered.poll() ?: break)
        }
        return out
    }

    fun clear() = buffered.clear()

    override fun close() {
        buffered.clear()
        model.close()
    }

    companion object {
        fun forPlayer(player: String): MotorDriver? {
            if (player.isBlank()) return null
            val file = File(System.getProperty("user.home"), ".projectx/models/$player/motor_model.onnx")
            if (!file.isFile) return null
            return try {
                MotorDriver(file.absolutePath)
            } catch (e: Throwable) {
                println("[MotorDriver] Failed to load ${file.absolutePath}: ${e.message}")
                null
            }
        }
    }
}
