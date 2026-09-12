package com.projectx.game.platform

/**
 * Logical names for the host libraries the engine binds against, so hook declarations and FFI
 * lookups name a role rather than a platform-specific path. Aliases are annotation-usable constants;
 * [path] maps them to the current host's library.
 */
object NativeLibraries {
    const val GL = "gl"
    const val GL_COMMANDS = "glCommands"
    const val WINDOWING = "windowing"

    /** Holder of the buffer-swap entry the overlay hooks to draw a frame — not always the GL library. */
    const val SWAP = "swap"

    private val byPlatform = mapOf(
        Platform.LINUX to mapOf(
            GL to "/usr/lib/libEGL.so.1",
            GL_COMMANDS to "/usr/lib/libOpenGL.so.0",
            WINDOWING to "/usr/lib/libSDL2-2.0.so.0",
            SWAP to "/usr/lib/libEGL.so.1",
        ),
        Platform.WINDOWS to mapOf(
            GL to "opengl32.dll",
            GL_COMMANDS to "opengl32.dll",
            WINDOWING to "user32.dll",
            SWAP to "gdi32.dll",
        ),
    )

    fun path(alias: String): String {
        val forPlatform = byPlatform[Platform.current]
            ?: error("No native library mapping for ${Platform.current}")
        return forPlatform[alias]
            ?: error("No '$alias' library mapped for ${Platform.current}")
    }
}
