package com.projectx.ui.backend.rendering

import io.github.classgraph.ClassGraph
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Optimized render manager using MethodHandles for faster invocation
 * and better caching strategies.
 */
object ImGuiRenderManager {
    
    private data class RenderMethod(
        val handle: MethodHandle,
        val priority: Int,
        val name: String
    )

    private var renderMethods: Array<RenderMethod> = emptyArray()
    private val lookup = MethodHandles.lookup()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        
        println("[OptimizedImGuiRenderManager] Discovering @ImGUIRender methods...")
        val methods = discoverAndCompileRenderMethods()
        renderMethods = methods.toTypedArray()
        initialized = true
        println("[OptimizedImGuiRenderManager] Compiled ${renderMethods.size} render methods")
    }
    
    fun getRenderMethods(): List<MethodHandle> {
        if (!initialized)
            initialize()
        return renderMethods.map(RenderMethod::handle)
    }

    fun clear() {
        renderMethods = emptyArray()
    }

    private fun discoverAndCompileRenderMethods(): List<RenderMethod> {
        val methods = mutableListOf<RenderMethod>()
        
        try {
            ClassGraph()
                .acceptPackages("com.projectx")
                .enableClassInfo()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .scan().use { scanResult ->
                    val classInfoList = scanResult.getClassesWithMethodAnnotation(ImGUIRender::class.java.name)
                    
                    for (classInfo in classInfoList) {
                        try {
                            // Load via THIS classloader, not ClassGraph's detected one. After a
                            // hot-reload the old (leaked) classloader is still reachable, and
                            // classInfo.loadClass() can bind the render method to it — then its DSL
                            // emissions go to the old CommandRenderer.captureSink instead of the new
                            // build()'s target, so nothing renders. Class.forName(name) uses the
                            // current engine loader (same fix HookManager already relies on).
                            val clazz = Class.forName(classInfo.name, true, javaClass.classLoader)
                            compileClassMethods(clazz, methods)
                        } catch (e: Exception) {
                            println("[OptimizedImGuiRenderManager] Failed to load class ${classInfo.name}: ${e.message}")
                        }
                    }
                }
        } catch (e: Exception) {
            println("[OptimizedImGuiRenderManager] Error scanning for @ImGUIRender methods: ${e.message}")
            e.printStackTrace()
        }
        
        methods.sortBy { it.priority }
        return methods
    }
    
    private fun compileClassMethods(clazz: Class<*>, methods: MutableList<RenderMethod>) {
        for (method in clazz.declaredMethods) {
            val annotation = method.getAnnotation(ImGUIRender::class.java) ?: continue
            
            if (!validateMethod(method, clazz)) continue
            
            try {
                method.isAccessible = true
                val handle = createMethodHandle(method)

                methods.add(RenderMethod(
                    handle = handle,
                    priority = annotation.priority.ordinal,
                    name = "${clazz.simpleName}.${method.name}"
                ))
            } catch (e: Exception) {
                println("[OptimizedImGuiRenderManager] Failed to compile method ${method.name}: ${e.message}")
            }
        }
    }
    
    private fun validateMethod(method: Method, clazz: Class<*>): Boolean {
        if (!Modifier.isStatic(method.modifiers)) {
            println("[OptimizedImGuiRenderManager] Warning: ${method.name} in ${clazz.simpleName} is not static, skipping")
            return false
        }
        
        if (method.parameterCount > 0) {
            println("[OptimizedImGuiRenderManager] Warning: ${method.name} in ${clazz.simpleName} has parameters, skipping")
            return false
        }
        
        return true
    }
    
    private fun createMethodHandle(method: Method): MethodHandle {
        return lookup.unreflect(method)
            .asType(MethodType.methodType(Void.TYPE))
    }

    fun getRegisteredMethods(): List<String> {
        return renderMethods.map { method ->
            "${method.name} [priority=${method.priority}]"
        }
    }

    fun getStats(): Stats {
        return Stats(
            methodCount = renderMethods.size,
            initialized = initialized
        )
    }
    
    data class Stats(
        val methodCount: Int,
        val initialized: Boolean
    )
}