package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.math.min

data class TextCommand(val text: String) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.text(text)
    }
}

data class TextWrappedCommand(val text: String) : ImGuiCommand() {
    override fun execute() {
        try {
            NativeBridge.textWrapped(text)
        } catch (_: Throwable) {
            NativeBridge.text(text)
        }
    }
}

data class ButtonCommand(
    val label: String,
    val width: Float = 0f,
    val height: Float = 0f,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        if (NativeBridge.button(label, width, height)) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for '$label': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

data class SmallButtonCommand(
    val label: String,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        if (NativeBridge.button(label, 0f, 0f)) {
            try { onClick() } catch (e: Throwable) {
                System.err.println("onClick failed for '$label': ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

data class CheckboxCommand(
    val label: String,
    val state: ImGuiState<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
            buffer.set(ValueLayout.JAVA_BOOLEAN, 0, state.value)
            NativeBridge.checkbox(safeLabel, buffer)
            state.value = buffer.get(ValueLayout.JAVA_BOOLEAN, 0)
        }
    }
}

data class CheckboxActionValueCommand(
    val label: String,
    val currentValue: Boolean,
    val onChange: (Boolean) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
            buffer.set(ValueLayout.JAVA_BOOLEAN, 0, currentValue)
            NativeBridge.checkbox(safeLabel, buffer)
            val newValue = buffer.get(ValueLayout.JAVA_BOOLEAN, 0)
            if (newValue != currentValue) {
                try { onChange(newValue) } catch (e: Throwable) {
                    System.err.println("onChange failed for checkbox '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class InputTextCommand(
    val label: String,
    val state: ImGuiState<String>,
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val buffer = state.buffer
        if (buffer != null) {
            NativeBridge.inputText(label, buffer, buffer.byteSize().toInt(), flags)
            val newValue = buffer.getString(0).substringBefore('\u0000')
            state.value = newValue
        }
    }
}

data class InputTextActionValueCommand(
    val label: String,
    val currentValue: String,
    val maxLength: Int = 256,
    val flags: Int = 0,
    val onChange: (String) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(maxLength.toLong())
            try {
                val bytes = currentValue.take(maxLength - 1).toByteArray(Charsets.UTF_8)
                buffer.fill(0.toByte())
                if (bytes.isNotEmpty()) {
                    val slice = buffer.asSlice(0, bytes.size.toLong())
                    slice.copyFrom(MemorySegment.ofArray(bytes))
                }
                buffer.set(ValueLayout.JAVA_BYTE, (maxLength - 1).toLong(), 0.toByte())
            } catch (_: Throwable) {
                buffer.fill(0.toByte())
            }

            NativeBridge.inputText(safeLabel, buffer, maxLength, flags)
            val newValue = buffer.getString(0).substringBefore('\u0000')
            if (newValue != currentValue) {
                try { onChange(newValue) } catch (e: Throwable) {
                    System.err.println("onChange failed for inputText '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class InputTextMultilineCommand(
    val label: String,
    val state: ImGuiState<String>,
    val sizeX: Float = 0f,
    val sizeY: Float = 0f,
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val buffer = state.buffer
        if (buffer != null) {
            NativeBridge.inputTextMultiline(label, buffer, buffer.byteSize().toInt(), sizeX, sizeY, flags)
            val newValue = buffer.getString(0).substringBefore('\u0000')
            state.value = newValue
        }
    }
}

data class InputTextMultilineActionValueCommand(
    val label: String,
    val currentValue: String,
    val maxLength: Int = 1024,
    val sizeX: Float = 0f,
    val sizeY: Float = 0f,
    val flags: Int = 0,
    val onChange: (String) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(maxLength.toLong())
            try {
                val bytes = currentValue.take(maxLength - 1).toByteArray(Charsets.UTF_8)
                buffer.fill(0.toByte())
                if (bytes.isNotEmpty()) {
                    val slice = buffer.asSlice(0, bytes.size.toLong())
                    slice.copyFrom(MemorySegment.ofArray(bytes))
                }
                buffer.set(ValueLayout.JAVA_BYTE, (maxLength - 1).toLong(), 0.toByte())
            } catch (_: Throwable) {
                buffer.fill(0.toByte())
            }

            NativeBridge.inputTextMultiline(safeLabel, buffer, maxLength, sizeX, sizeY, flags)
            val newValue = buffer.getString(0).substringBefore('\u0000')
            if (newValue != currentValue) {
                try { onChange(newValue) } catch (e: Throwable) {
                    System.err.println("onChange failed for inputTextMultiline '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class InputIntCommand(
    val label: String,
    val state: ImGuiState<Int>,
    val step: Int = 1,
    val stepFast: Int = 10
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_INT)
            buffer.set(ValueLayout.JAVA_INT, 0, state.value)
            NativeBridge.inputInt(safeLabel, buffer, step, stepFast, 0)
            state.value = buffer.get(ValueLayout.JAVA_INT, 0)
        }
    }
}

data class InputIntActionValueCommand(
    val label: String,
    val currentValue: Int,
    val step: Int = 1,
    val stepFast: Int = 10,
    val onChange: (Int) -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_INT)
            buffer.set(ValueLayout.JAVA_INT, 0, currentValue)
            NativeBridge.inputInt(safeLabel, buffer, step, stepFast, 0)
            val newValue = buffer.get(ValueLayout.JAVA_INT, 0)
            if (newValue != currentValue) {
                try { onChange(newValue) } catch (e: Throwable) {
                    System.err.println("onChange failed for inputInt '$label': ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}

data class SliderFloatCommand(
    val label: String,
    val state: ImGuiState<Float>,
    val min: Float,
    val max: Float
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            buffer.set(ValueLayout.JAVA_FLOAT, 0, state.value)
            NativeBridge.sliderFloat(safeLabel, buffer, min, max)
            state.value = buffer.get(ValueLayout.JAVA_FLOAT, 0)
        }
    }
}

data class SliderIntCommand(
    val label: String,
    val state: ImGuiState<Int>,
    val min: Int,
    val max: Int
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_INT)
            buffer.set(ValueLayout.JAVA_INT, 0, state.value)
            NativeBridge.sliderInt(safeLabel, buffer, min, max)
            state.value = buffer.get(ValueLayout.JAVA_INT, 0)
        }
    }
}

data class DragFloatCommand(
    val label: String,
    val state: ImGuiState<Float>,
    val speed: Float = 1f,
    val min: Float = 0f,
    val max: Float = 0f,
    val format: String = "%.3f",
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            buffer.set(ValueLayout.JAVA_FLOAT, 0, state.value)
            NativeBridge.dragFloat(safeLabel, buffer, speed, min, max, format, flags)
            state.value = buffer.get(ValueLayout.JAVA_FLOAT, 0)
        }
    }
}

data class DragIntCommand(
    val label: String,
    val state: ImGuiState<Int>,
    val speed: Float = 1f,
    val min: Int = 0,
    val max: Int = 0,
    val format: String = "%d",
    val flags: Int = 0
) : ImGuiCommand() {
    override fun execute() {
        val safeLabel = label.take(256).filter { it.code <= 0x10FFFF }
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_INT)
            buffer.set(ValueLayout.JAVA_INT, 0, state.value)
            NativeBridge.dragInt(safeLabel, buffer, speed, min, max, format, flags)
            state.value = buffer.get(ValueLayout.JAVA_INT, 0)
        }
    }
}

data class ProgressBarCommand(
    val fraction: Float,
    val sizeX: Float = -1f,
    val sizeY: Float = 0f,
    val overlay: String? = null
) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.progressBar(fraction, sizeX, sizeY, overlay ?: "")
    }
}

/**
 * A directional button drawn from ImGui's own vector geometry.
 *
 * Falls back to [fallbackLabel] on a bootstrap that predates the native export, so the control degrades to a
 * readable button instead of vanishing when the symbol is absent.
 */
data class ArrowButtonCommand(
    val id: String,
    val dir: Int,
    val fallbackLabel: String,
    val onClick: () -> Unit
) : ImGuiCommand() {
    override fun execute() {
        val safeId = id.take(256).filter { it.code <= 0x10FFFF }
        val clicked =
            if (NativeBridge.arrowButtonSupported) NativeBridge.arrowButton(safeId, dir)
            else NativeBridge.button("$fallbackLabel##$safeId")
        if (clicked) {
            try {
                onClick()
            } catch (e: Throwable) {
                System.err.println("onClick failed for arrow button '$id': ${e.message}")
            }
        }
    }
}

data class SetTooltipCommand(val text: String) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setTooltip(text.take(1024))
    }
}

data class ItemTooltipCommand(val text: String) : ImGuiCommand() {
    override fun execute() {
        if (!NativeBridge.isItemHovered()) return
        val shown = text.take(1024)
        val (textWidth, textHeight) = NativeBridge.calcTextSize(shown, wrapWidth = TOOLTIP_MAX_TEXT_WIDTH)
        NativeBridge.setNextWindowSize(
            min(textWidth, TOOLTIP_MAX_TEXT_WIDTH) + TOOLTIP_PADDING,
            textHeight + TOOLTIP_PADDING
        )
        NativeBridge.beginTooltip()
        NativeBridge.textWrapped(shown)
        NativeBridge.endTooltip()
    }

    private companion object {
        const val TOOLTIP_MAX_TEXT_WIDTH = 700f
        // Window padding both sides plus wrap slack; matched to the overlay style, not ImGui defaults.
        const val TOOLTIP_PADDING = 24f
    }
}

data class SetItemDefaultFocusCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        // Not yet implemented: requires native binding. Safe no-op for now.
    }
}