package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.hooks.SymbolHook
import com.projectx.game.memory.NativeAccess
import com.projectx.game.platform.NativeLibraries
import com.projectx.game.platform.Platform
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGuiRenderManager
import com.projectx.ui.backend.rendering.OverlayFrameDriver
import com.projectx.ui.backend.rendering.UiFrameProducer
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS

/**
 * Windows counterpart of [EGLSwapBuffers]. The client presents through `GDI32!SwapBuffers` rather
 * than `wglSwapBuffers`, so that is the frame boundary the overlay draws on.
 */
@SupportedOn(Platform.WINDOWS)
object GdiSwapBuffers {
    private val windowFromDeviceContext by lazy {
        NativeAccess.getLibraryFunction(
            NativeLibraries.path(NativeLibraries.WINDOWING),
            "WindowFromDC"
        ) { FunctionDescriptor.of(ADDRESS, ADDRESS) }
    }

    private val currentGlContext by lazy {
        NativeAccess.getLibraryFunction(
            NativeLibraries.path(NativeLibraries.GL),
            "wglGetCurrentContext"
        ) { FunctionDescriptor.of(ADDRESS) }
    }

    @JvmStatic
    @SymbolHook(library = NativeLibraries.SWAP, symbol = "SwapBuffers")
    fun swapBuffersHook(deviceContext: MemorySegment): Int {
        // During teardown become a pure passthrough so the render thread never touches ImGui state
        // while it is being shut down. The native ImGui shutdown happens AFTER this is uninstalled.
        if (!Bootstrap.stopping) {
            OverlayFrameDriver.drawFrame { initImGui(deviceContext) }
        }
        return HookManager.trampoline(::swapBuffersHook.name).invokeExact(deviceContext) as Int
    }

    /**
     * The window and GL context are taken from the presenting device context rather than stored at
     * load time: the client creates them long after injection, and this runs on the render thread
     * with the context already current.
     */
    private fun initImGui(deviceContext: MemorySegment) {
        try {
            val window = windowFromDeviceContext.invokeExact(deviceContext) as MemorySegment
            val glContext = currentGlContext.invokeExact() as MemorySegment

            if (window.address() == 0L) {
                println("[GdiSwapBuffers] WindowFromDC returned null; overlay input will not bind")
            }

            NativeBridge.init(window, glContext)
            ImGuiRenderManager.initialize()
            UiFrameProducer.enabled = true

            println("[GdiSwapBuffers] ImGui initialized successfully")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

}
