package com.projectx.util

import java.awt.*
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread

private val sharedIcon: Image by lazy { loadIconFromResources() }
private val sharedTrayIcon: TrayIcon by lazy {
    TrayIcon(sharedIcon, "Project X Notifications").apply {
        isImageAutoSize = true
        toolTip = "Project X Notification"
    }
}

fun showNotification(title: String, message: String, playSound: Boolean = Configuration.config.soundEnabled) {
    val config = Configuration.config

    if (config.discordEnabled)
        DiscordWebhook.sendDiscordNotification(title, message)

    if (config.systemTrayEnabled && SystemTray.isSupported()) {
        thread(isDaemon = true) {
            try {
                val systemTray = SystemTray.getSystemTray()
                if (systemTray.trayIcons.isEmpty())
                    systemTray.add(sharedTrayIcon)
                sharedTrayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO)

                if (playSound && config.soundEnabled) {
                    playNotificationSound()
                }
            } catch (_: Exception) { }
        }
    }
}

private fun loadIconFromResources(): Image {
    return try {
        val iconURL: URL? = object {}.javaClass.getResource("/icons/logo.png")
        iconURL?.let { ImageIO.read(it) } ?: createDefaultIcon()
    } catch (e: Exception) {
        createDefaultIcon()
    }
}

private fun createDefaultIcon(): Image {
    val size = 16
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.BLUE
    g.fillOval(2, 2, size - 4, size - 4)
    g.color = Color.WHITE
    g.fillOval(6, 6, size - 12, size - 12)
    g.dispose()
    return image
}

private fun playNotificationSound() {
    thread(isDaemon = true) {
        try {
            val soundURL: URL? = object {}.javaClass.getResource("/sounds/notification.wav")
            if (soundURL != null) {
                val audioInputStream = AudioSystem.getAudioInputStream(soundURL)
                val clip = AudioSystem.getClip()
                clip.open(audioInputStream)
                clip.start()
            } else
                Toolkit.getDefaultToolkit().beep()
        } catch (_: Exception) {
            try { Toolkit.getDefaultToolkit().beep() } catch (_: Exception) { }
        }
    }
}