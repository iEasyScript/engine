package com.projectx.game.input

/**
 * Builds the model's input tensor from recent events. Mirrors `ai-input/encode.py`, and
 * `InputPathContractTest` pins the two together against `ai-input/spec/encoder_fixture.json` — the encoding
 * is where training and inference have silently disagreed before, so it is asserted rather than reviewed.
 *
 * Kept independent of any ONNX session so it can be tested directly.
 */
class InputFeatureEncoder(private val contract: InputContract) {
    private val typeIndex = contract.indexOf("event_type")
    private val deltaIndex = contract.indexOf("delta_seconds")
    private val xIndex = contract.indexOf("x")
    private val yIndex = contract.indexOf("y")
    private val buttonIndex = contract.indexOf("button_id")
    private val pressedIndex = contract.indexOf("pressed")
    private val scrollIndex = contract.indexOf("scroll_delta")
    private val keyIndex = contract.indexOf("key_low_byte")
    private val tickIndex = contract.indexOf("game_tick")
    private val tileXIndex = contract.indexOf("player_tile_x")
    private val tileYIndex = contract.indexOf("player_tile_y")
    private val mainStateIndex = contract.indexOf("main_state")

    private val motionAction = contract.actionIndex("mouse_motion").toFloat()
    private val buttonAction = contract.actionIndex("mouse_button").toFloat()
    private val scrollAction = contract.actionIndex("mouse_scroll").toFloat()
    private val keyboardAction = contract.actionIndex("keyboard").toFloat()

    private val stride = contract.featureDim

    fun encodeWindow(recentEvents: List<InputEvent>, context: GameContext): FloatArray {
        val out = FloatArray(contract.windowSize * stride)
        val window = recentEvents.takeLast(contract.windowSize)
        val pad = contract.windowSize - window.size

        var previousNanos = if (window.isNotEmpty()) window.first().timestampNanos else 0L
        for (i in window.indices) {
            val event = window[i]
            val deltaUs = ((event.timestampNanos - previousNanos) / 1_000L).coerceAtLeast(0)
            previousNanos = event.timestampNanos
            encodeEvent(event, deltaUs, context, out, (pad + i) * stride)
        }

        applyClips(out)
        return out
    }

    fun contextVector(window: FloatArray): FloatArray {
        val out = FloatArray(contract.contextDim)
        val base = (contract.windowSize - 1) * stride + contract.contextFirstFeatureIndex
        val available = minOf(contract.contextDim, stride - contract.contextFirstFeatureIndex)
        for (i in 0 until available) out[i] = window[base + i]
        return out
    }

    private fun encodeEvent(
        event: InputEvent,
        deltaUs: Long,
        context: GameContext,
        out: FloatArray,
        base: Int,
    ) {
        out[base + deltaIndex] = deltaUs / contract.normalizer("delta_seconds")
        out[base + tickIndex] = event.gameTick / contract.normalizer("game_tick")
        out[base + tileXIndex] = context.playerX / contract.normalizer("player_tile_x")
        out[base + tileYIndex] = context.playerY / contract.normalizer("player_tile_y")
        out[base + mainStateIndex] = context.mainState / contract.normalizer("main_state")

        when (event) {
            is MouseMotionEvent -> {
                out[base + typeIndex] = motionAction
                writePosition(out, base, event.x, event.y)
            }
            is MouseButtonEvent -> {
                out[base + typeIndex] = buttonAction
                writePosition(out, base, event.x, event.y)
                out[base + buttonIndex] = event.button.id.toFloat()
                out[base + pressedIndex] = if (event.pressed) 1f else 0f
            }
            is MouseScrollEvent -> {
                out[base + typeIndex] = scrollAction
                writePosition(out, base, event.x, event.y)
                out[base + scrollIndex] = event.scrollDelta / contract.normalizer("scroll_delta")
            }
            is KeyboardEvent -> {
                out[base + typeIndex] = keyboardAction
                out[base + pressedIndex] = if (event.pressed) 1f else 0f
                out[base + keyIndex] = (event.keyCode and 0xFF) / contract.normalizer("key_low_byte")
            }
            // Typed characters are recorded but are not part of the model's action vocabulary, so a caller
            // that lets one reach here has filtered wrongly — encoding it as some other action would put a
            // row in the window that the model was never trained to see.
            is KeyCharEvent -> throw IllegalArgumentException(
                "KeyCharEvent has no action in contract v${contract.contractVersion}; filter it before encoding"
            )
        }
    }

    private fun writePosition(out: FloatArray, base: Int, x: Int, y: Int) {
        out[base + xIndex] = x / contract.normalizer("x")
        out[base + yIndex] = y / contract.normalizer("y")
    }

    /** The engine used to skip this while training clipped, so any long pause fed the model an unseen value. */
    private fun applyClips(out: FloatArray) {
        for ((column, feature) in contract.features.withIndex()) {
            val low = feature.clipLow ?: continue
            val high = feature.clipHigh ?: continue
            var index = column
            while (index < out.size) {
                out[index] = out[index].coerceIn(low, high)
                index += stride
            }
        }
    }
}
