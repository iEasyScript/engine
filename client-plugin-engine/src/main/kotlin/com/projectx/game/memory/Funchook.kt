package com.projectx.game.memory

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*

object Funchook {
    @JvmStatic
    private lateinit var funchook: MemorySegment

    fun init() {
        val handle = NativeAccess.getFunction("funchook_create") { FunctionDescriptor.of(ADDRESS) }
        funchook = handle.invokeExact() as MemorySegment
    }

    fun addHook(name: String, src: MemorySegment, dst: MemorySegment): MemorySegment {
        val targetFuncPtrSegment = NativeAccess.engineArena.allocate(0x8)
        targetFuncPtrSegment.setAtIndex(JAVA_LONG, 0, src.address())
        val addFuncHandle = NativeAccess.getFunction("funchook_prepare") { FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS) }
        val result = addFuncHandle.invokeExact(funchook, targetFuncPtrSegment, dst) as Int
        if (result != 0) throw Exception("Failed to prepare hook $name")
        return targetFuncPtrSegment
    }

    fun install() {
        val handle = NativeAccess.getFunction("funchook_install") { FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT) }
        val result = handle.invokeExact(funchook, 0) as Int
        if (result != 0) throw Exception("Failed to install hooks")
    }

    fun uninstall() : Int {
        val handle = NativeAccess.getFunction("funchook_uninstall") { FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT) }
        return handle.invokeExact(funchook, 0) as Int
    }

    fun destroy(hookInfo: MemorySegment) {
        val handle = NativeAccess.getFunction("funchook_destroy") { FunctionDescriptor.ofVoid(ADDRESS) }
        handle.invokeExact(funchook)
    }
}