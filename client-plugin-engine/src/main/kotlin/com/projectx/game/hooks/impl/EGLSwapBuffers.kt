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

@SupportedOn(Platform.LINUX)
object EGLSwapBuffers {
    private val sdlGetCurrentWindow by lazy {
        NativeAccess.getLibraryFunction(
            NativeLibraries.path(NativeLibraries.WINDOWING),
            "SDL_GL_GetCurrentWindow"
        ) { FunctionDescriptor.of(ADDRESS) }
    }

    private val sdlGetCurrentContext by lazy {
        NativeAccess.getLibraryFunction(
            NativeLibraries.path(NativeLibraries.WINDOWING),
            "SDL_GL_GetCurrentContext"
        ) { FunctionDescriptor.of(ADDRESS) }
    }

    @JvmStatic
    @SymbolHook(library = NativeLibraries.GL, symbol = "eglSwapBuffers")
    fun eglSwapBuffersHook(display: MemorySegment, surface: MemorySegment): MemorySegment {
        // During teardown become a pure passthrough so the render thread never touches ImGui state
        // while it is being shut down. The native ImGui shutdown happens AFTER this is uninstalled.
        if (!Bootstrap.stopping) {
            OverlayFrameDriver.drawFrame(::initImGui)
        }
        return HookManager.trampoline(::eglSwapBuffersHook.name).invokeExact(display, surface) as MemorySegment
    }

    private fun initImGui() {
        try {
            val sdlWindow = sdlGetCurrentWindow.invokeExact() as MemorySegment
            val glContext = sdlGetCurrentContext.invokeExact() as MemorySegment

            if (sdlWindow.address() == 0L || glContext.address() == 0L) {
                println("[EGLSwapBuffers] Warning: SDL window or GL context is null, retrying with Bootstrap")
                val fallbackWindow = Bootstrap.client.sdlManager.sdlWindow.ptr
                NativeBridge.init(fallbackWindow, glContext)
            } else {
                NativeBridge.init(sdlWindow, glContext)
            }

            // Discover render methods; the actual per-frame build runs on the main-logic thread
            // (UiFrameProducer.build, driven by ClientMainLogic), not a background worker.
            ImGuiRenderManager.initialize()
            UiFrameProducer.enabled = true

            println("[EGLSwapBuffers] ImGui initialized successfully")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
