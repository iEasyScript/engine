package com.projectx.ui.backend.dsl

import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.scopes.WindowScope
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.CommandRenderer

/**
 * Safe ImGui DSL that builds command queues for execution in render context.
 *
 * Commands are executed later during the EGLSwapBuffers hook when a valid OpenGL
 * context is available, preventing crashes and undefined behavior.
 */
object ImGuiDsl {
    @JvmStatic
    fun <T> persistentState(key: String, factory: () -> ImGuiState<T>): ImGuiState<T> {
        return CommandRenderer.getSharedState(key, factory)
    }

    @DslMarker
    annotation class ImGuiDsl

    inline fun window(
        title: String,
        flags: WindowFlags = WindowFlags.None,
        open: ImGuiState<Boolean>? = null,
        block: WindowScope.() -> Unit
    ) {
        val commands = mutableListOf<ImGuiCommand>()
        commands.addAll(flushNextWindowCommands())
        commands.add(BeginWindowCommand(title, flags.value, open))

        val scope = WindowScope()
        scope.block()
        commands.addAll(scope.commands)

        commands.add(EndWindowCommand)
        CommandRenderer.submitCommands(commands)
    }

    inline fun window(
        title: String,
        flags: WindowFlags = WindowFlags.None,
        open: Boolean,
        noinline onOpenChange: (Boolean) -> Unit,
        block: WindowScope.() -> Unit
    ) {
        val commands = mutableListOf<ImGuiCommand>()

        commands.addAll(flushNextWindowCommands())
        commands.add(BeginWindowActionValueCommand(title, flags.value, open, onOpenChange))

        val scope = WindowScope()
        scope.block()
        commands.addAll(scope.commands)

        commands.add(EndWindowCommand)
        CommandRenderer.submitCommands(commands)
    }

    private val nextWindowCommands = mutableListOf<ImGuiCommand>()

    fun setNextWindowPos(x: Float, y: Float, cond: ImGuiCond = ImGuiCond.None, pivotX: Float = 0f, pivotY: Float = 0f) {
        nextWindowCommands.add(SetNextWindowPosCommand(x, y, cond.value, pivotX, pivotY, useSafeBounds = true))
    }
    
    fun setNextWindowPosUnsafe(x: Float, y: Float, cond: ImGuiCond = ImGuiCond.None, pivotX: Float = 0f, pivotY: Float = 0f) {
        nextWindowCommands.add(SetNextWindowPosCommand(x, y, cond.value, pivotX, pivotY, useSafeBounds = false))
    }
    
    fun setNextWindowSize(width: Float, height: Float, cond: ImGuiCond = ImGuiCond.None) {
        nextWindowCommands.add(SetNextWindowSizeCommand(width, height, cond.value, useSafeBounds = true))
    }
    
    fun setNextWindowSizeUnsafe(width: Float, height: Float, cond: ImGuiCond = ImGuiCond.None) {
        nextWindowCommands.add(SetNextWindowSizeCommand(width, height, cond.value, useSafeBounds = false))
    }
    
    @PublishedApi
    internal fun flushNextWindowCommands(): List<ImGuiCommand> {
        val commands = nextWindowCommands.toList()
        nextWindowCommands.clear()
        return commands
    }

    @JvmStatic
    fun framerate(): Float = 60.0f

    @JvmStatic
    fun getDisplaySize(): Pair<Float, Float> {
        return try {
            NativeBridge.getDisplaySize()
        } catch (e: Exception) {
            Pair(1920.0f, 1080.0f)
        }
    }
    
    @JvmStatic
    fun displayWidth(): Float = getDisplaySize().first
    @JvmStatic
    fun displayHeight(): Float = getDisplaySize().second

    @JvmOverloads
    @JvmStatic
    fun centerNextWindow(width: Float = 400f, height: Float = 300f) {
        val (displayWidth, displayHeight) = getDisplaySize()
        val x = (displayWidth - width) * 0.5f
        val y = (displayHeight - height) * 0.5f
        setNextWindowPos(x, y)
        setNextWindowSize(width, height)
    }
    
    @JvmOverloads
    @JvmStatic
    fun setNextWindowPosTopLeft(margin: Float = 20f) {
        setNextWindowPos(margin, margin)
    }
    
    @JvmOverloads
    @JvmStatic
    fun setNextWindowPosTopRight(width: Float = 400f, margin: Float = 20f) {
        val displayWidth = displayWidth()
        setNextWindowPos(displayWidth - width - margin, margin)
    }
    
    @JvmStatic
    inline fun backgroundDrawList(block: BackgroundDrawListScope.() -> Unit) {
        val scope = BackgroundDrawListScope()
        scope.block()
        if (scope.drawCommands.isNotEmpty()) {
            val command = BackgroundDrawListScopeCommand(scope.drawCommands.toList())
            CommandRenderer.submitCommands(listOf(command))
        }
    }
}