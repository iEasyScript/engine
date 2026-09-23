package com.projectx.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.projectx.game.input.Key
import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.game.net.packetlog.PacketUploadRunner
import com.projectx.mcp.McpServer
import com.projectx.profiling.JfrManager
import com.projectx.ui.UIState
import com.projectx.ui.compose.ComposeOverlay
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.ToggleRow
import com.projectx.ui.compose.theme.Palette
import com.projectx.util.Configuration
import com.projectx.util.DiscordWebhook
import kotlin.concurrent.thread

data class SettingsSnapshot(val mcp: McpServer.State, val uploadStatus: String, val jfrRunning: Boolean, val lastJfr: String?)

/**
 * Keeps the engine in step with what the settings say.
 *
 * Runs every main-logic frame rather than only while Settings is showing, so turning MCP or capture on takes effect
 * wherever the panel is - and a setting changed from elsewhere is honoured too.
 */
object SettingsModel {
    /** Bump when the sharing explanation changes materially; uploads pause until re-consented. */
    private const val CONSENT_VERSION = 1

    var status by mutableStateOf<String?>(null)

    val feed = Feed(500) {
        SettingsSnapshot(McpServer.state, PacketUploadRunner.status, JfrManager.isRunning(), JfrManager.getLastDumpPath()?.toString())
    }

    fun reconcile() {
        reconcileMcp()
        val packetSettingsChanged = persistPacketLogSettings()
        reconcileRecorder()
        reconcileUpload(packetSettingsChanged)
    }

    fun setToggleKey(key: Key) {
        UIState.uiToggleKey.value = key.native
        Configuration.updateConfig(Configuration.config.copy(uiToggleKey = key.native))
    }

    fun saveDiscord() {
        Configuration.updateConfig(
            Configuration.config.copy(
                discordWebhookUrl = UIState.discordWebhookUrl.value,
                discordUsername = UIState.discordUsername.value,
                discordEnabled = UIState.discordEnabled.value,
            ),
        )
        status = "Discord settings saved"
    }

    fun testDiscord() {
        thread(name = "discord-test", isDaemon = true) {
            DiscordWebhook.sendDiscordNotification("Test notification", "This is a test notification from Project X.")
        }
        status = "Test notification sent"
    }

    fun toggleJfr() {
        status = if (JfrManager.isRunning()) {
            JfrManager.stopAndDump()?.let { "Recording saved to $it" } ?: "Could not stop the recording"
        } else {
            if (JfrManager.start()) "Recording started" else "Could not start a recording (already running or unsupported)"
        }
        feed.invalidate()
    }

    private fun reconcileMcp() {
        val desired = UIState.mcpEnabled.value
        when (McpServer.state) {
            is McpServer.State.Listening -> if (!desired) McpServer.stop()
            McpServer.State.Disabled -> if (desired) McpServer.start().onFailure { UIState.mcpEnabled.value = false }
            // Retrying an error on its own would spam; it waits for the switch to be flipped again.
            is McpServer.State.Error, McpServer.State.Starting, McpServer.State.Stopping -> Unit
        }
    }

    /** Written back only on an actual change. Reports whether one happened. */
    private fun persistPacketLogSettings(): Boolean {
        val config = Configuration.config
        val enabled = UIState.packetLogEnabled.value
        val upload = UIState.packetLogUploadEnabled.value
        val keepLocal = UIState.packetLogKeepLocalAfterUpload.value
        val text = UIState.packetLogTextDumpEnabled.value
        if (enabled == config.packetLogEnabled && upload == config.packetLogUploadEnabled &&
            keepLocal == config.packetLogKeepLocalAfterUpload && text == config.packetLogTextDumpEnabled
        ) return false
        Configuration.updateConfig(
            config.copy(
                packetLogEnabled = enabled,
                packetLogUploadEnabled = upload,
                packetLogKeepLocalAfterUpload = keepLocal,
                packetLogTextDumpEnabled = text,
                // Stamped when sharing is turned on and read back per session at export, so turning it on later
                // cannot make an older capture shareable.
                packetLogConsentVersion = if (upload) CONSENT_VERSION else config.packetLogConsentVersion,
            ),
        )
        return true
    }

    private fun reconcileRecorder() {
        val desired = UIState.packetLogEnabled.value
        if (desired == PacketRecorder.isArmed) return
        if (desired) PacketRecorder.requestStart() else PacketRecorder.requestStop()
    }

    /** A changed setting cycles the upload agent, which takes its settings as arguments; a handover takes several passes. */
    private fun reconcileUpload(settingsChanged: Boolean) {
        if (!UIState.packetLogUploadEnabled.value) {
            if (PacketUploadRunner.isRunning) PacketUploadRunner.stop()
            return
        }
        if (settingsChanged || !PacketUploadRunner.isRunning || PacketUploadRunner.isRestarting) PacketUploadRunner.start()
    }
}

@Composable
fun SettingsScreen() {
    val s = SettingsModel.feed.value
    ScreenScroll {
        SettingsModel.status?.let { Hint(it, color = Palette.amber) }
        Section("Panel") {
            SettingRow("Show or hide key", "The key that toggles this panel in game.") {
                val current = Key.entries.firstOrNull { it.native == UIState.uiToggleKey.value } ?: Key.F12
                Dropdown(current, Key.entries, { SettingsModel.setToggleKey(it) }, width = 160.dp) { it.name }
            }
            SettingRow("Position and size", "Drag the header to move the panel and the corner to resize it.") {
                ActionButton("Reset", { ComposeOverlay.resetBounds() }, height = 32.dp)
            }
        }
        Section(
            "MCP server",
            note = "Lets an LLM agent inspect and drive the client over localhost.",
            actions = {
                when (val state = s?.mcp) {
                    is McpServer.State.Listening -> Pill("Listening on 127.0.0.1:${state.port}", Palette.running, dot = true)
                    is McpServer.State.Error -> Pill("Error", Palette.stop, dot = true)
                    McpServer.State.Starting, McpServer.State.Stopping -> Pill("${state}", Palette.amber, dot = true)
                    else -> Pill("Off", Palette.faint)
                }
            },
        ) {
            ToggleRow("Enabled", UIState.mcpEnabled)
            (s?.mcp as? McpServer.State.Error)?.let { Hint(it.message, color = Palette.stop) }
        }
        Section("Packet capture", note = "Recording writes every packet to ~/.projectx/packetlog/, live and local-server sessions in separate databases.") {
            ToggleRow("Record to database", UIState.packetLogEnabled)
            ToggleRow("Raw login dump", UIState.rawLoginDumpEnabled, "Adds the handshake bytes the decoded hooks can't see. Turn on before logging in.")
            ToggleRow("Legacy text dump", UIState.packetLogTextDumpEnabled, "The old packets-<date>.log. Costs roughly ten bytes per payload byte.")
            ToggleRow("Share captures", UIState.packetLogUploadEnabled, "Uploads sealed captures so the server can be checked against the live game.")
            if (UIState.packetLogUploadEnabled.value) {
                ToggleRow("Keep local copy", UIState.packetLogKeepLocalAfterUpload, "Keep uploaded sessions on disk, marked, for querying locally.")
                SettingRow("Pushing to") { Hint(Configuration.config.packetLogEndpoint) }
                SettingRow("Upload") { Hint(s?.uploadStatus ?: "-") }
                Hint(
                    "Chat, private messages and friend/clan lists are stripped before anything leaves this machine, and a session " +
                        "recorded before sharing was on is never eligible. Nearby players' appearance still travels inside PLAYER_INFO. " +
                        "Uploading runs in its own process, so closing the game still finishes a capture.",
                )
            }
        }
        Section("Discord notifications") {
            ToggleRow("Enabled", UIState.discordEnabled)
            if (UIState.discordEnabled.value) {
                val url = remember { OverlayText.of(UIState.discordWebhookUrl, 256) }
                val name = remember { OverlayText.of(UIState.discordUsername, 64) }
                SettingRow("Webhook URL") { TextInput(url, "https://discord.com/api/webhooks/...", 360.dp) }
                SettingRow("Bot name") { TextInput(name, "Project X", 200.dp) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Save", { SettingsModel.saveDiscord() }, tone = ButtonTone.Primary)
                    ActionButton("Send a test", { SettingsModel.testDiscord() })
                }
            }
        }
        Section("Java Flight Recorder", note = "Profiles the engine; the recording is written when you stop it.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s?.jfrRunning == true) ActionButton("Stop and save", { SettingsModel.toggleJfr() }, tone = ButtonTone.Danger, icon = Glyph.Stop)
                else ActionButton("Start recording", { SettingsModel.toggleJfr() }, icon = Glyph.Play)
                s?.lastJfr?.let { Hint("Last: $it") }
            }
        }
    }
}
