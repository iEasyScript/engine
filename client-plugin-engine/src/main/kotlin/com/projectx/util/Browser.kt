package com.projectx.util

import java.awt.Desktop
import java.net.URI

/** Opens a web page in the player's own browser. Only http and https addresses are ever handed on. */
object Browser {
    fun open(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        if (runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }.getOrDefault(false)) {
            if (runCatching { Desktop.getDesktop().browse(uri) }.isSuccess) return true
        }
        // The client runs headless as far as AWT is concerned, so the platform's own opener is the usual route.
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            os.contains("mac") -> listOf("open", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }
        return runCatching { ProcessBuilder(command).start() }.isSuccess
    }
}
