package com.projectx.game.platform

/**
 * The graphics API a client binary was built for. Jagex ships the same revision as separate binaries
 * per renderer, so a build number alone does not identify an offset table.
 */
enum class Renderer(val id: String) {
    OPENGL("opengl"),
    VULKAN("vulkan");

    companion object {
        /** The renderer an offset table declares; tables that predate the field are OpenGL builds. */
        fun ofTableValue(value: String?): Renderer =
            if (value == null) OPENGL else entries.firstOrNull { it.id == value } ?: error("Unknown renderer '$value' in offset table")
    }
}
