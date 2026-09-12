package com.projectx.game.input

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The tensor layout a model was built with, read from the `io_contract.json` the exporter writes beside the
 * `.onnx`. Normalizers, feature order and the action vocabulary used to be duplicated as constants here and
 * in the training code, and the two drifted - the action indices in particular disagreed outright.
 *
 * Nothing falls back to a default: a missing field throws, because feeding a model a tensor it was not
 * trained on fails silently and looks like a bad model rather than a bad encoding.
 */
class InputContract private constructor(
    val contractVersion: Int,
    val modelId: String,
    val windowSize: Int,
    val contextDim: Int,
    val features: List<Feature>,
    val actionLabels: List<String>,
    val actionHeadWidth: Int,
    val contextFirstFeatureIndex: Int,
    val positionMixtures: Int,
    val screenNormalizationMode: String,
) {
    class Feature(val name: String, val normalizer: Float, val clipLow: Float?, val clipHigh: Float?)

    val featureDim: Int get() = features.size

    private val indexByName: Map<String, Int> = features.withIndex().associate { (i, f) -> f.name to i }

    fun indexOf(featureName: String): Int = indexByName[featureName]
        ?: throw IllegalStateException("feature '$featureName' is not in contract v$contractVersion")

    fun normalizer(featureName: String): Float = features[indexOf(featureName)].normalizer

    fun actionIndex(label: String): Int {
        val index = actionLabels.indexOf(label)
        if (index < 0) throw IllegalStateException("action '$label' is not in contract v$contractVersion")
        return index
    }

    /** Null for an index the model can emit but was never trained on - never decode those as an action. */
    fun actionLabelOrNull(index: Int): String? = actionLabels.getOrNull(index)

    companion object {
        const val FILENAME = "io_contract.json"

        /** The contract belonging to a specific exported model, so a stale engine cannot mis-encode for it. */
        fun besideModel(modelPath: String): InputContract {
            val file = File(File(modelPath).parentFile, FILENAME)
            if (!file.isFile) {
                throw IllegalStateException(
                    "no $FILENAME beside ${File(modelPath).name} - re-export the model so it ships its " +
                        "tensor layout instead of relying on constants compiled into the engine"
                )
            }
            return load(file)
        }

        fun load(file: File): InputContract = parse(JsonParser.parseString(file.readText()).asJsonObject)

        private fun parse(root: JsonObject): InputContract {
            val features = root.requireArray("features").map { element ->
                val entry = element.asJsonObject
                val clip = entry.getAsJsonArray("clip")
                Feature(
                    name = entry.requireString("name"),
                    normalizer = entry.requireFloat("normalizer"),
                    clipLow = clip?.get(0)?.asFloat,
                    clipHigh = clip?.get(1)?.asFloat,
                )
            }

            val vocabulary = root.requireObject("action_vocabulary")
            val labels = vocabulary.requireArray("labels").map { it.asString }
            val headWidth = vocabulary.requireInt("head_width")
            if (headWidth < labels.size) {
                throw IllegalStateException("action head width $headWidth is narrower than its ${labels.size} labels")
            }

            return InputContract(
                contractVersion = root.requireInt("contract_version"),
                modelId = root.requireString("model_id"),
                windowSize = root.requireInt("window_size"),
                contextDim = root.requireInt("context_dim"),
                features = features,
                actionLabels = labels,
                actionHeadWidth = headWidth,
                contextFirstFeatureIndex = root.requireObject("context").requireInt("first_feature_index"),
                positionMixtures = root.requireInt("position_mixtures"),
                screenNormalizationMode = root.requireObject("screen_normalization").requireString("mode"),
            )
        }

        private fun JsonObject.require(key: String) =
            get(key) ?: throw IllegalStateException("$FILENAME is missing required key '$key'")

        private fun JsonObject.requireObject(key: String) = require(key).asJsonObject
        private fun JsonObject.requireArray(key: String) = require(key).asJsonArray
        private fun JsonObject.requireString(key: String) = require(key).asString
        private fun JsonObject.requireInt(key: String) = require(key).asInt
        private fun JsonObject.requireFloat(key: String) = require(key).asFloat
    }
}
