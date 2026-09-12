package com.projectx.script.impl.qb.bosses.gaytescript

import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.script.event.impl.Chat
import com.projectx.util.DiscordWebhook
import java.text.Normalizer

object GayteScriptDiscordHelper {
    fun handleChatEvent(event: Chat) {
        println(event.message)
        val cleanMessage = event.message.replace(Regex("<col=[^>]*>|</col>"), "")
        val normalizedPlayerName = Normalizer.normalize(localPlayer.name, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        println(normalizedPlayerName)
        println(cleanMessage)
        val killCount = varps.getVarBit(55987)

        val isCompletion = cleanMessage.contains("Completion time", ignoreCase = true)
        val isDrop = (cleanMessage.contains("$normalizedPlayerName has received", ignoreCase = true) ||
                cleanMessage.contains("New collection log entry", ignoreCase = true))
        println(isDrop)
        println(isCompletion)

        when {
            isDrop && isCompletion -> {
                DiscordWebhook.sendDiscordNotification(
                    "🎉💎 Boss Defeated + Rare Drop!",
                    "**$normalizedPlayerName** has defeated the Gates boss AND received a rare drop!\n\n" +
                            "**Fight Details:**\n" +
                            "- $cleanMessage\n" +
                            "- Total Kill Count: $killCount\n" +
                            "- BONUS: Rare drop obtained!"
                )
            }
            isDrop -> {
                DiscordWebhook.sendDiscordNotification(
                    "💎 Unique Drop Received!",
                    "**Rare Drop Alert!**\n\n" +
                            "$cleanMessage\n\n" +
                            "**Kill Count:** $killCount\n" +
                            "Congratulations on the unique drop!"
                )
            }
            isCompletion -> {
                DiscordWebhook.sendDiscordNotification(
                    "🎉 Gates Boss Defeated!",
                    "**$normalizedPlayerName** has successfully defeated the Gates boss!\n\n" +
                            "**Fight Details:**\n" +
                            "- $cleanMessage\n" +
                            "- Total Kill Count: $killCount"
                )
            }
        }
    }
}