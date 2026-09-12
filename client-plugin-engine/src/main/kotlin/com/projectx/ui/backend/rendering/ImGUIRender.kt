package com.projectx.ui.backend.rendering

import com.projectx.game.hooks.Priority

/**
 * Annotation for methods that should be called during ImGui rendering.
 * Annotated methods will be automatically discovered and invoked during the EGLSwapBuffers hook.
 * 
 * Requirements:
 * - Method must be static (@JvmStatic for Kotlin objects)
 * - Method must have no parameters
 * - Method should contain ImGui DSL calls
 * 
 * Example usage:
 * ```kotlin
 * object MyUIComponent {
 *     @JvmStatic
 *     @RenderImGui(priority = Priority.NORMAL)
 *     fun render() {
 *         window("My Window") {
 *             text("Auto-rendered content")
 *         }
 *     }
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ImGUIRender(
    val priority: Priority = Priority.NORMAL,
)