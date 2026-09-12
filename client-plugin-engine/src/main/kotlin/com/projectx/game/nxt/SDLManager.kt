package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess.deref
import java.lang.foreign.MemorySegment

class SDLManager(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OSDLManager.extent)
    val windowPtr
        get() = ptr.deref(OSDLManager.WINDOW, OSDLWindow.extent)
    val sdlWindow
        get() = SDLWindow(windowPtr.deref(OSDLManager.SDL_WINDOW, OSDLWindow.extent))
}

class SDLWindow(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OSDLWindow.extent)
}