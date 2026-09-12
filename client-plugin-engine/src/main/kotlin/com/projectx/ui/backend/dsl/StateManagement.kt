package com.projectx.ui.backend.dsl

import com.projectx.game.memory.NativeAccess
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

fun boolState(initialValue: Boolean): ImGuiState<Boolean> = BooleanState(initialValue)
fun intState(initialValue: Int): ImGuiState<Int> = IntState(initialValue)

fun floatState(initialValue: Float): ImGuiState<Float> = FloatState(initialValue)
fun stringState(initialValue: String, maxLength: Int = 256): ImGuiState<String> = StringState(initialValue, maxLength)

data class FloatRange(val min: ImGuiState<Float>, val max: ImGuiState<Float>) : AutoCloseable {
    override fun close() {
        min.close()
        max.close()
    }
}

data class IntRange(val min: ImGuiState<Int>, val max: ImGuiState<Int>) : AutoCloseable {
    override fun close() {
        min.close()
        max.close()
    }
}

fun rangeStateFloat(minValue: Float, maxValue: Float): FloatRange {
    return FloatRange(floatState(minValue), floatState(maxValue))
}

fun rangeStateInt(minValue: Int, maxValue: Int): IntRange {
    return IntRange(intState(minValue), intState(maxValue))
}

/**
 * State management for ImGui values with proper memory handling and thread safety
 */
abstract class ImGuiState<T> : AutoCloseable {
    abstract var value: T
    open val buffer: MemorySegment? = null

    @Volatile
    private var closed = false

    protected fun checkNotClosed() {
        if (closed) {
            throw IllegalStateException("ImGuiState has been closed")
        }
    }

    override fun close() {
        closed = true
    }
}

class BooleanState(initialValue: Boolean) : ImGuiState<Boolean>() {
    override val buffer: MemorySegment = NativeAccess.engineArena.allocate(ValueLayout.JAVA_BOOLEAN)

    init {
        buffer.set(ValueLayout.JAVA_BOOLEAN, 0, initialValue)
    }

    override var value: Boolean
        get() {
            checkNotClosed()
            return buffer.get(ValueLayout.JAVA_BOOLEAN, 0)
        }
        set(newValue) {
            checkNotClosed()
            buffer.set(ValueLayout.JAVA_BOOLEAN, 0, newValue)
        }

    override fun close() {
        super.close()
        try {
            buffer.set(ValueLayout.JAVA_BOOLEAN, 0, false)
        } catch (e: Exception) {
        }
    }
}

class IntState(initialValue: Int) : ImGuiState<Int>() {
    override val buffer: MemorySegment = NativeAccess.engineArena.allocate(ValueLayout.JAVA_INT)

    init {
        buffer.set(ValueLayout.JAVA_INT, 0, initialValue)
    }

    override var value: Int
        get() {
            checkNotClosed()
            return buffer.get(ValueLayout.JAVA_INT, 0)
        }
        set(newValue) {
            checkNotClosed()
            buffer.set(ValueLayout.JAVA_INT, 0, newValue)
        }

    override fun close() {
        super.close()
        try {
            buffer.set(ValueLayout.JAVA_INT, 0, 0)
        } catch (e: Exception) {
        }
    }
}

class FloatState(initialValue: Float) : ImGuiState<Float>() {
    override var value: Float = initialValue
        get() {
            checkNotClosed()
            return field
        }
        set(newValue) {
            checkNotClosed()
            field = newValue
        }

    override fun close() {
        value = 0.0f
        super.close()
    }
}

class StringState(initialValue: String, private val maxLength: Int) : ImGuiState<String>() {
    override val buffer: MemorySegment = NativeAccess.engineArena.allocate(maxLength.toLong())

    override var value: String = initialValue
        get() {
            checkNotClosed()
            return field
        }
        set(newValue) {
            checkNotClosed()

            val sanitized = newValue
                .take(maxLength - 1)
                .filter { it.code <= 0x10FFFF && it.code >= 0x20 || it in "\t\n\r" }
                .replace("\u0000", "")

            field = sanitized

            try {
                val bytes = sanitized.toByteArray(Charsets.UTF_8)
                val maxBytes = (maxLength - 1).coerceAtMost(bytes.size)

                buffer.fill(0.toByte())

                if (maxBytes > 0) {
                    buffer.asSlice(0, maxBytes.toLong())
                        .copyFrom(MemorySegment.ofArray(bytes.take(maxBytes).toByteArray()))
                }
                buffer.set(ValueLayout.JAVA_BYTE, maxBytes.toLong(), 0.toByte())
            } catch (e: Exception) {
                field = ""
                buffer.fill(0.toByte())
            }
        }

    init {
        value = initialValue
    }

    override fun close() {
        try {
            value = ""
        } catch (_: Exception) {}

        try {
            buffer.fill(0.toByte())
        } catch (_: Exception) {
        }

        super.close()
    }
}
