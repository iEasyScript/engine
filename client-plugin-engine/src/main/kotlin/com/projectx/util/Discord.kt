package com.projectx.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.concurrent.thread

object DiscordWebhook {
    fun sendDiscordNotification(title: String, message: String) {
        val config = Configuration.config

        if (!config.discordEnabled || config.discordWebhookUrl.isBlank())
            return

        thread(isDaemon = true) {
            try {
                sendWebhookMessage(config.discordWebhookUrl, title, message, config)
            } catch (e: Exception) {
                println("Failed to send Discord notification: ${e.message}")
            }
        }
    }

    private fun sendWebhookMessage(webhookUrl: String, title: String, message: String, config: PersistentConfig) {
        val url = URI(webhookUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Project X-Notifications/1.0")
            connection.doOutput = true

            val embed = JsonObject().apply {
                addProperty("title", title)
                addProperty("description", message)
                addProperty("color", 0x3498DB)
                addProperty("timestamp", Instant.now().toString())

                val footer = JsonObject().apply {
                    addProperty("text", "Project X Notification")
                }
                add("footer", footer)
            }

            val payload = JsonObject().apply {
                addProperty("username", config.discordUsername)
                if (!config.discordAvatarUrl.isBlank())
                    addProperty("avatar_url", config.discordAvatarUrl)

                val embeds = JsonArray().apply {
                    add(embed)
                }
                add("embeds", embeds)
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMessage = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                println("Discord webhook failed with code $responseCode: $errorMessage")
            }

        } finally {
            connection.disconnect()
        }
    }
}