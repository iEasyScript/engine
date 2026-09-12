package com.projectx.game.hooks

import com.projectx.game.memory.Funchook
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.toDescriptor
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.memory.NativeAccess.toEngineUpcallStub
import com.projectx.game.nxt.OffsetTable
import com.projectx.game.platform.NativeLibraries
import com.projectx.game.platform.Platform
import io.github.classgraph.ClassGraph
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object HookManager {
    @JvmStatic
    private val trampolines = mutableMapOf<String, MethodHandle>()

    fun trampoline(key: String): MethodHandle = trampolines[key] ?: throw Exception("No original handle found for $key")

    fun parseAndApplyHooks(base: MemorySegment) {
        val classesWithHooks = findClassesWithHooks()
        val hookMethods = mutableListOf<Pair<Method, Annotation>>()

        for (clazz in classesWithHooks) {
            for (method in clazz.declaredMethods) {
                val hookAnnotation = method.annotations.firstOrNull { it is Hook || it is SymbolHook } ?: continue
                if (!Modifier.isStatic(method.modifiers)) continue

                val supported = method.getAnnotation(SupportedOn::class.java)
                if (supported != null && Platform.current !in supported.platforms) {
                    println("[HookManager] Skipping ${method.name}: not supported on ${Platform.current}")
                    continue
                }
                hookMethods.add(method to hookAnnotation)
            }
        }

        hookMethods.sortBy {
            return@sortBy when(it.second) {
                is Hook -> (it.second as Hook).priority
                is SymbolHook -> (it.second as SymbolHook).priority
                else -> error("Unexpected hook annotation: ${it.second.javaClass.name}")
            }
        }

        println("[HookManager] ${OffsetTable.summary()}")

        //Native calls that must remain in this order. Funchook uses its own data structure
        //to keep track of all hooks.
        Funchook.init()
        for ((method, hookAnnotation) in hookMethods) {
            try {
                when (hookAnnotation) {
                    is Hook -> {
                        val offset = OffsetTable.function(hookAnnotation.value)
                        if (offset == null) {
                            println(
                                "[HookManager] Skipping ${method.name}: ${hookAnnotation.value} is not in the " +
                                    "${OffsetTable.platform} build ${OffsetTable.build} offset table"
                            )
                        } else {
                            hookFunctionAtOffset(base, method, offset)
                        }
                    }
                    is SymbolHook -> {
                        NativeAccess.addPathLookup(NativeLibraries.path(hookAnnotation.library))
                        hookFunction(NativeAccess.getSymbol(hookAnnotation.symbol), method)
                    }
                }
            } catch (e: Exception) {
                println("Failed to hook ${method.name}: ${e.message}")
                e.printStackTrace()
            }
        }
        Funchook.install()
    }

    @JvmStatic
    fun hookFunctionAtOffset(base: MemorySegment, hookMethod: Method, offset: Long) {
        hookFunction(base.asSlice(offset, ADDRESS), hookMethod)
    }

    @JvmStatic
    fun hookFunction(originalFunction: MemorySegment, hookMethod: Method) {
        val hookHandle = getFunctionHandle(hookMethod)
        val jptr = hookHandle.toEngineUpcallStub()
        val targetFuncPtrSegment = Funchook.addHook(hookMethod.name, originalFunction, jptr)
        val trampolineAddress = targetFuncPtrSegment.getAtIndex(ADDRESS, 0).reinterpret(8)
        val trampolineFunctionSegment = trampolineAddress.pointerAtOffset(0x0L, 0x0L)
        trampolines[hookMethod.name] = trampolineFunctionSegment.toFunctionHandle(hookHandle.type().toDescriptor())
        println("[HookManager] Successfully hooked ${hookMethod.name}")
        println("\t hookAddr: 0x${jptr.address().toString(16)} trampoline: ${trampolineFunctionSegment.address().toString(16)}")
    }

    fun getFunctionHandle(method: Method): MethodHandle {
        val methodType = if (method.parameterTypes.isEmpty())
            MethodType.methodType(method.returnType)
        else {
            MethodType.methodType(
                method.returnType,
                method.parameterTypes[0],
                *method.parameterTypes.copyOfRange(1, method.parameterTypes.size)
            )
        }

        return when {
            Modifier.isStatic(method.modifiers) -> MethodHandles.lookup().findStatic(method.declaringClass, method.name, methodType)
            else -> MethodHandles.lookup().findVirtual(method.declaringClass, method.name, methodType)
        }
    }

    private fun findClassesWithHooks(): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()
            .acceptPackages("com.projectx.game.hooks.impl")
            .scan().use { scanResult ->
                val classesWithHookMethods = scanResult
                    .allClasses
                    .filter { classInfo ->
                        classInfo.methodInfo
                            .filter { it.hasAnnotation(Hook::class.java.name) || it.hasAnnotation(SymbolHook::class.java.name) }
                            .any { it.isStatic }
                    }

                for (classInfo in classesWithHookMethods) {
                    try {
                        val clazz = Class.forName(classInfo.name)
                        val supported = clazz.getAnnotation(SupportedOn::class.java)
                        if (supported != null && Platform.current !in supported.platforms) {
                            println("[HookManager] Skipping ${classInfo.simpleName}: not supported on ${Platform.current}")
                            continue
                        }
                        result.add(clazz)
                    } catch (e: ClassNotFoundException) {
                        println("Could not load class ${classInfo.name}: ${e.message}")
                    }
                }
            }
        return result
    }
}

/** TODO would be nice to be able to call this but it doesn't work
 * Example:
 * trampolineAfter<Unit> {
 *      ThreadSyncedScriptRunner.tickMainLogic()
 * }
 */
inline fun <reified R> trampolineAfter(
    hookLogic: () -> Unit
): R {
    val stackTrace = Thread.currentThread().stackTrace
    val hookName = stackTrace[2].methodName
    val args = stackTrace.drop(3).map { it }.toTypedArray()

    return try {
        hookLogic()
    } catch (e: Exception) {
        e.printStackTrace()
        throw RuntimeException("Hook execution failed: ${e.message}")
    }.let {
        val trampoline = HookManager.trampoline(hookName)
        trampoline.invokeExact(args) as R
    }
}