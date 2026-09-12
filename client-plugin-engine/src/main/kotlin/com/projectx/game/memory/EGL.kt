package com.projectx.game.memory

import com.projectx.game.platform.NativeLibraries
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

private val EGL_LIBRARY_PATH: String get() = NativeLibraries.path(NativeLibraries.GL)

object EGL {
    fun eglGetCurrentContext(): MemorySegment {
        val funcHandle = NativeAccess.getLibraryFunction(EGL_LIBRARY_PATH, "eglGetCurrentContext") {
            FunctionDescriptor.of(ADDRESS)
        }
        return funcHandle.invokeExact() as MemorySegment
    }

    fun eglMakeCurrent(display: MemorySegment, draw: MemorySegment, read: MemorySegment, context: MemorySegment): Int {
        val funcHandle = NativeAccess.getLibraryFunction(EGL_LIBRARY_PATH, "eglMakeCurrent") {
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
        }
        return funcHandle.invokeExact(display, draw, read, context) as Int
    }
}