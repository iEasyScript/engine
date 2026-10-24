package com.projectx.ui

import java.io.File

/** Remembers that the experience card was closed, in a file of its own so the shared config's shape never changes. */
internal object XpOverlayPreference {
    private val file = File(System.getProperty("user.home"), ".projectx/xp-overlay-hidden")

    val hidden: Boolean get() = file.exists()

    fun save(hidden: Boolean) {
        if (hidden) file.apply { parentFile.mkdirs() }.createNewFile() else file.delete()
    }
}
