package com.projectx.game.input.motor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Runs the goal-conditioned motor model one step at a time.
 *
 * The graph is a single timestep with the recurrent state passed out and back in, because the caller owns the
 * cursor: it applies each movement, recomputes what remains to the target, and asks for the next step. Output
 * movement is a fraction of the viewport, so the caller scales it by the live window.
 *
 * Not thread-safe. Roll a whole reach out on one thread and hand the samples to whoever needs them.
 */
class MotorModel(modelPath: String) : AutoCloseable {
    val contract: MotorContract = MotorContract.besideModel(modelPath)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())

    private val remainingX = contract.indexOf("remaining_x")
    private val remainingY = contract.indexOf("remaining_y")
    private val remainingDist = contract.indexOf("remaining_dist")
    private val velocityX = contract.indexOf("velocity_x")
    private val velocityY = contract.indexOf("velocity_y")
    private val elapsedFrac = contract.indexOf("elapsed_frac")
    private val totalDist = contract.indexOf("total_dist")

    private var hidden = FloatArray(contract.hiddenLayers * contract.hiddenDim)

    /** One generated sample: movement as a fraction of the viewport, plus what the model wants to do next. */
    data class Step(val dx: Float, val dy: Float, val terminate: Boolean, val click: Boolean)

    fun reset() {
        hidden = FloatArray(contract.hiddenLayers * contract.hiddenDim)
    }

    /**
     * @param remaining  target minus current position, as a fraction of the viewport
     * @param velocity   the movement applied on the previous step, same units
     * @param elapsedFraction time spent so far over the nominal reach duration the model was trained against
     * @param totalDistance the whole reach's length, same units
     */
    fun step(
        remaining: Pair<Float, Float>,
        velocity: Pair<Float, Float>,
        elapsedFraction: Float,
        totalDistance: Float,
        temperature: Float = 1.0f,
    ): Step {
        val features = FloatArray(contract.stepDim)
        features[remainingX] = remaining.first
        features[remainingY] = remaining.second
        features[remainingDist] = hypot(remaining.first.toDouble(), remaining.second.toDouble()).toFloat()
        features[velocityX] = velocity.first
        features[velocityY] = velocity.second
        features[elapsedFrac] = elapsedFraction
        features[totalDist] = totalDistance

        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, contract.stepDim.toLong()))
            .use { stepTensor ->
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(hidden),
                    longArrayOf(contract.hiddenLayers.toLong(), 1, contract.hiddenDim.toLong())
                ).use { hiddenTensor ->
                    session.run(mapOf("step" to stepTensor, "hidden_in" to hiddenTensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val logWeights = (result.get("log_weights").get().value as Array<FloatArray>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val means = (result.get("means").get().value as Array<Array<FloatArray>>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val logStds = (result.get("log_stds").get().value as Array<Array<FloatArray>>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val terminate = (result.get("terminate_logit").get().value as FloatArray)[0]
                        @Suppress("UNCHECKED_CAST")
                        val click = (result.get("click_logit").get().value as FloatArray)[0]
                        @Suppress("UNCHECKED_CAST")
                        val nextHidden = result.get("hidden_out").get().value as Array<Array<FloatArray>>

                        var i = 0
                        for (layer in nextHidden) for (row in layer) for (v in row) hidden[i++] = v

                        val (dx, dy) = sample(logWeights, means, logStds, temperature)
                        return Step(
                            dx = dx,
                            dy = dy,
                            terminate = sigmoid(terminate) > 0.5f,
                            click = sigmoid(click) > 0.5f,
                        )
                    }
                }
            }
    }

    private fun sample(
        logWeights: FloatArray,
        means: Array<FloatArray>,
        logStds: Array<FloatArray>,
        temperature: Float,
    ): Pair<Float, Float> {
        val scaled = FloatArray(logWeights.size) { exp(logWeights[it] / temperature.coerceAtLeast(1e-6f)) }
        val sum = scaled.sum().coerceAtLeast(1e-12f)
        val pick = ThreadLocalRandom.current().nextFloat() * sum
        var accumulated = 0f
        var component = scaled.size - 1
        for (k in scaled.indices) {
            accumulated += scaled[k]
            if (accumulated >= pick) {
                component = k
                break
            }
        }

        val rng = ThreadLocalRandom.current()
        val dx = means[component][0] + exp(logStds[component][0]) * temperature * rng.nextGaussian().toFloat()
        val dy = means[component][1] + exp(logStds[component][1]) * temperature * rng.nextGaussian().toFloat()
        return dx to dy
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    override fun close() {
        runCatching { session.close() }
    }
}
