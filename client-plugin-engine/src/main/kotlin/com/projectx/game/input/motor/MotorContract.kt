package com.projectx.game.input.motor

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The step layout a motor model was exported with, read from the `motor_contract.json` written beside it.
 *
 * Nothing falls back to a default. Feeding a recurrent model a step vector in the wrong order does not fail
 * loudly - it produces plausible-looking movement that is subtly not this player's, which is the one failure
 * mode the whole pipeline exists to avoid.
 */
class MotorContract private constructor(
    val contractVersion: Int,
    val stepFeatures: List<String>,
    val hiddenLayers: Int,
    val hiddenDim: Int,
    val numMixtures: Int,
    /** Pacing the elapsed-time feature was scaled by in training; the engine must reproduce it. */
    val nominalReachMs: Float,
    /** Cadence one step represents. Emitting at a different rate makes the model's pacing meaningless. */
    val stepIntervalMs: Float,
    val maxSteps: Int,
) {
    val stepDim: Int get() = stepFeatures.size

    fun indexOf(feature: String): Int {
        val index = stepFeatures.indexOf(feature)
        if (index < 0) throw IllegalStateException("step feature '$feature' is not in motor contract v$contractVersion")
        return index
    }

    companion object {
        const val FILENAME = "motor_contract.json"

        fun besideModel(modelPath: String): MotorContract {
            val file = File(File(modelPath).parentFile, FILENAME)
            if (!file.isFile) {
                throw IllegalStateException(
                    "no $FILENAME beside ${File(modelPath).name} - re-export the model so it ships its step " +
                        "layout instead of relying on constants compiled into the engine"
                )
            }
            return load(file)
        }

        fun load(file: File): MotorContract {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            return MotorContract(
                contractVersion = root.require("contract_version").asInt,
                stepFeatures = root.require("step_features").asJsonArray.map { it.asString },
                hiddenLayers = root.require("hidden_layers").asInt,
                hiddenDim = root.require("hidden_dim").asInt,
                numMixtures = root.require("num_mixtures").asInt,
                nominalReachMs = root.require("nominal_reach_ms").asFloat,
                stepIntervalMs = root.require("step_interval_ms").asFloat,
                maxSteps = root.require("max_steps").asInt,
            )
        }

        private fun JsonObject.require(key: String) =
            get(key) ?: throw IllegalStateException("$FILENAME is missing required key '$key'")
    }
}
