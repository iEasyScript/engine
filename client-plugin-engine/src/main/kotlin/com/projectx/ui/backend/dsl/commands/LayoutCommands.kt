package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

data class BeginWindowCommand(
    val title: String,
    val flags: Int = 0,
    val open: ImGuiState<Boolean>? = null
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeTitle = title.take(256).filter { it.code <= 0x10FFFF }
        if (open != null && open.buffer != null) {
            val openThisFrame = try {
                NativeBridge.begin(safeTitle, open.buffer, flags)
            } catch (_: Throwable) { true }
            ImGuiExecState.beginAlwaysEnded(openThisFrame)
        } else {
            val openThisFrame = try {
                NativeBridge.begin(safeTitle, null, flags)
            } catch (_: Throwable) { true }
            ImGuiExecState.beginAlwaysEnded(openThisFrame)
        }
    }
}

data class BeginWindowActionValueCommand(
    val title: String,
    val flags: Int = 0,
    val currentOpen: Boolean,
    val onOpenChange: (Boolean) -> Unit
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        val safeTitle = title.take(256).filter { it.code <= 0x10FFFF }
        val openThisFrame = try {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                buffer.set(ValueLayout.JAVA_BOOLEAN, 0, currentOpen)
                val openNow = NativeBridge.begin(safeTitle, buffer, flags)
                val updated = buffer.get(ValueLayout.JAVA_BOOLEAN, 0)
                if (updated != currentOpen) {
                    try { onOpenChange(updated) } catch (e: Throwable) {
                        System.err.println("onOpenChange failed for window '$title': ${e.message}")
                        e.printStackTrace()
                    }
                }
                openNow
            }
        } catch (_: Throwable) { true }
        ImGuiExecState.beginAlwaysEnded(openThisFrame)
    }
}

object EndWindowCommand : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.end()
        }
    }
}

data class SetNextWindowPosCommand(
    val x: Float, 
    val y: Float, 
    val cond: Int = 0, 
    val pivotX: Float = 0f, 
    val pivotY: Float = 0f,
    val useSafeBounds: Boolean = true
) : ImGuiCommand() {
    override fun execute() {
        if (useSafeBounds) {
            NativeBridge.setNextWindowPosSafe(x, y, cond, pivotX, pivotY)
        } else {
            NativeBridge.setNextWindowPos(x, y, cond, pivotX, pivotY)
        }
    }
}

data class SetNextWindowSizeCommand(
    val width: Float, 
    val height: Float, 
    val cond: Int = 0,
    val useSafeBounds: Boolean = true
) : ImGuiCommand() {
    override fun execute() {
        if (useSafeBounds) {
            NativeBridge.setNextWindowSizeSafe(width, height, cond)
        } else {
            NativeBridge.setNextWindowSize(width, height, cond)
        }
    }
}

object SeparatorCommand : ImGuiCommand() {
    override fun execute() {
        NativeBridge.separator()
    }
}

object SameLineCommand : ImGuiCommand() {
    override fun execute() {
        NativeBridge.sameLine()
    }
}

object NewLineCommand : ImGuiCommand() {
    override fun execute() {
        NativeBridge.newLine()
    }
}

object SpacingCommand : ImGuiCommand() {
    override fun execute() {
        NativeBridge.spacing()
    }
}

data class BeginGroupCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.beginGroup()
    }
}

data class EndGroupCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.endGroup()
    }
}

data class IndentCommand(val width: Float = 0f) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.indent(width)
    }
}

data class UnindentCommand(val width: Float = 0f) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.unindent(width)
    }
}

data class SetCursorPosCommand(val x: Float, val y: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setCursorPos(x, y)
    }
}

data class SetCursorPosXCommand(val x: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setCursorPosX(x)
    }
}

data class SetCursorPosYCommand(val y: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setCursorPosY(y)
    }
}

data class AlignTextToFramePaddingCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.alignTextToFramePadding()
    }
}

data class SetNextItemWidthCommand(val width: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setNextItemWidth(width)
    }
}

data class PushItemWidthCommand(val width: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.pushItemWidth(width)
    }
}

data class PopItemWidthCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.popItemWidth()
    }
}

data class ColumnsCommand(
    val count: Int = 1,
    val id: String? = null,
    val border: Boolean = true
) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.columns(count, id, border)
    }
}

data class NextColumnCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.nextColumn()
    }
}

data class SetColumnWidthCommand(val columnIndex: Int, val width: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.setColumnWidth(columnIndex, width)
    }
}

data class PushStyleVarFloatCommand(val styleVar: ImGuiStyleVar, val value: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.pushStyleVarFloat(styleVar, value)
    }
}

data class PushStyleVarVec2Command(val styleVar: ImGuiStyleVar, val x: Float, val y: Float) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.pushStyleVarVec2(styleVar, x, y)
    }
}

data class PopStyleVarCommand(val count: Int = 1) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.popStyleVar(count)
    }
}

data class PushStyleColorCommand(val colorIndex: ImGuiCol, val color: Int) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.pushStyleColor(colorIndex, color)
    }
}

data class PopStyleColorCommand(val count: Int = 1) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.popStyleColor(count)
    }
}