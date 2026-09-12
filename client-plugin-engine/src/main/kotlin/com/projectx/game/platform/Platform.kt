package com.projectx.game.platform

enum class Platform(val id: String) {
    LINUX("linux"),
    WINDOWS("windows"),
    MACOS("macos");

    companion object {
        val current: Platform = detectPlatform()
        val arch: String = detectArch()

        /** Identifies the offset table and native artifacts for this host, e.g. `linux-x86_64`. */
        val key: String get() = "${current.id}-$arch"

        /** The platform named by an offset-table key such as `linux-x86_64`. */
        fun ofTableKey(key: String): Platform {
            val id = key.substringBefore('-')
            return entries.firstOrNull { it.id == id } ?: error("Unknown platform in offset table key '$key'")
        }

        private fun detectPlatform(): Platform {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("linux") -> LINUX
                name.contains("win") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MACOS
                else -> error("Project X does not support host platform '$name'")
            }
        }

        private fun detectArch(): String {
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            return when (arch) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> error("Project X does not support host architecture '$arch'")
            }
        }
    }
}
