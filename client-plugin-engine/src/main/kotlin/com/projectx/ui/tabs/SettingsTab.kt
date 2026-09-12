package com.projectx.ui.tabs

import com.projectx.game.input.Key
import com.projectx.mcp.McpServer
import com.projectx.profiling.JfrManager
import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.game.net.packetlog.PacketUploadRunner
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.util.Configuration
import com.projectx.util.DiscordWebhook
import world.gregs.voidps.gameval.Gameval

object SettingsTab {

    /** Bump when the sharing explanation changes materially; uploads pause until re-consented. */
    private const val CONSENT_VERSION = 1

    fun ChildScope.render() {
        section("Interface")
        val keyItems = Key.entries.map { it.name }
        val currentKeyIndex = Key.entries.indexOfFirst { it.native == UIState.uiToggleKey.value }
            .let { if (it >= 0) it else 0 }
        properties("settings-interface") {
            row("UI toggle key") {
                combo(
                    label = "##uiToggleKey",
                    currentIndex = currentKeyIndex,
                    items = keyItems,
                    maxItemsShown = 20
                ) { newIndex ->
                    val selected = Key.entries[newIndex]
                    UIState.uiToggleKey.value = selected.native
                    Configuration.updateConfig(Configuration.config.copy(uiToggleKey = selected.native))
                }
            }
        }

        section("MCP Server")
        textWrapped("Exposes the client to an LLM agent over localhost.")
        reconcileMcpServerState()
        properties("settings-mcp") {
            row("Enabled") { checkbox("##mcpEnabled", UIState.mcpEnabled) }
            row("Status") {
                val state = McpServer.state
                text(
                    if (state is McpServer.State.Listening) "Listening on 127.0.0.1:${state.port}"
                    else state.toString()
                )
            }
        }

        section("Variable Debug")
        checkboxGrid(
            "settings-vardebug",
            listOf(
                "Varp Debug" to UIState.varpDebugEnabled,
                "Varc Debug" to UIState.varcDebugEnabled,
            )
        )

        section("Packet Dump")
        properties("settings-packetdump") {
            row("Record to database") { checkbox("##packetLogEnabled", UIState.packetLogEnabled) }
            row("Raw login/RSA dump") { checkbox("##rawLoginDump", UIState.rawLoginDumpEnabled) }
            row("Legacy text dump") { checkbox("##packetLogText", UIState.packetLogTextDumpEnabled) }
            row("Share captures") { checkbox("##packetLogUpload", UIState.packetLogUploadEnabled) }
            if (UIState.packetLogUploadEnabled.value) {
                row("Push to") { text(Configuration.config.packetLogEndpoint) }
                row("Upload") { text(PacketUploadRunner.status) }
                row("Keep local copy") { checkbox("##packetLogKeepLocal", UIState.packetLogKeepLocalAfterUpload) }
            }
        }
        textWrapped(
            "Recording writes every packet to ~/.projectx/packetlog/, keeping live and local-server " +
                "sessions in separate databases and appending across sessions. It is off by default. " +
                "The raw login dump adds the handshake bytes the decoded hooks can't see - enable it before " +
                "logging in. The legacy text dump is the old ~/.projectx/logs/packets-<date>.log and costs " +
                "roughly ten bytes on disk per payload byte; leave it off unless reading a capture by eye."
        )
        if (UIState.packetLogUploadEnabled.value) {
            textWrapped(
                "Sharing uploads sealed captures so the server can be checked against what the live game " +
                    "actually sends. Chat, private messages and friend/clan lists are stripped before " +
                    "anything leaves this machine, and a session recorded before sharing was enabled is " +
                    "never eligible. Nearby players' appearance still travels inside PLAYER_INFO - " +
                    "stripping only the names needs the field decoder, which does not exist yet. " +
                    "Uploading runs in a separate process that outlives the client, so closing or " +
                    "killing the game still finishes the capture; once the server confirms it holds a " +
                    "session, the local copy is deleted by default. A session it will not take is kept " +
                    "either way. \"Keep local copy\" leaves it on disk instead - marked, not deleted - " +
                    "for querying against the local codecs; use the Purge button in the Packet Log tab " +
                    "to reclaim that space once you no longer need it. Changing this cycles the upload " +
                    "process, so it applies once the running one finishes what it is sending."
            )
        }
        val packetLogSettingsChanged = persistPacketLogSettings()
        reconcilePacketRecorderState()
        reconcileUploadState(packetLogSettingsChanged)

        section("Discord Notifications")
        properties("settings-discord") {
            row("Enabled") { checkbox("##discordEnabled", UIState.discordEnabled) }
            if (UIState.discordEnabled.value) {
                row("Webhook URL") { inputText("##webhook", UIState.discordWebhookUrl) }
                row("Bot username") { inputText("##username", UIState.discordUsername) }
            }
        }

        if (UIState.discordEnabled.value) {
            button("Save") {
                Configuration.updateConfig(
                    Configuration.config.copy(
                        discordWebhookUrl = UIState.discordWebhookUrl.value,
                        discordUsername = UIState.discordUsername.value,
                        discordEnabled = UIState.discordEnabled.value
                    )
                )
            }
            sameLine()
            button("Send Test") {
                DiscordWebhook.sendDiscordNotification(
                    "Test Notification",
                    "This is a test notification from Project X ImGui!"
                )
            }
        }

        section("Script Windows")
        button("Close All Config Windows") {
            UIState.openConfigWindows.values.forEach { windowState ->
                windowState.value = false
            }
        }

        section("Java Flight Recorder")
        // Reported through the log rather than inline: these run inside the button's click callback, which
        // fires while the recorded frame is being executed, long after this scope's commands were collected.
        if (JfrManager.isRunning()) {
            button("Stop && Dump") {
                val path = JfrManager.stopAndDump()
                println(path?.let { "[JFR] dumped to $it" } ?: "[JFR] failed to stop/dump")
            }
        } else {
            button("Start JFR") {
                if (!JfrManager.start()) println("[JFR] failed to start (already running or unsupported)")
            }
        }
        JfrManager.getLastDumpPath()?.let { text("Last JFR: $it") }

        if (UIState.varcDebugEnabled.value || UIState.varpDebugEnabled.value) {
            section("Variable Changes")
            inputText("Search", UIState.varcSearchText)
            sameLine()
            button("Clear Table") {
                UIState.varTableData.clear()
            }

            table(
                id = "VarcTable",
                columns = 4,
                flags = ImGuiTableFlags.SizingStretchSame or ImGuiTableFlags.BordersInnerH
            ) {
                setupColumn("Type")
                setupColumn("ID")
                setupColumn("Previous")
                setupColumn("New")
                headersRow()

                val filteredData = if (UIState.varcSearchText.value.isEmpty()) {
                    UIState.varTableData
                } else {
                    UIState.varTableData.filter { entry ->
                        entry.type.contains(UIState.varcSearchText.value, ignoreCase = true) ||
                                entry.id.toString().contains(UIState.varcSearchText.value)
                    }
                }

                filteredData.forEach { entry ->
                    nextRow()
                    nextColumn()
                    text(entry.type)
                    nextColumn()
                    text(when (entry.type) {
                        "varp" -> Gameval.varpLabel(entry.id)
                        "varpbit" -> Gameval.varbitLabel(entry.id)
                        "varc" -> Gameval.varcLabel(entry.id)
                        else -> entry.id.toString()
                    })
                    nextColumn()
                    text(entry.prevValue.toString())
                    nextColumn()
                    text(entry.newValue.toString())
                }
            }
        }
    }

    /**
     * Driven by an actual settings change rather than by the frame: the agent takes its settings as
     * argv, so one that changed has to cycle the process. A handover needs several passes to wait out
     * the old agent, which is what [PacketUploadRunner.isRestarting] keeps alive - and it stops as
     * soon as the new agent is up, so an idle settings screen costs nothing.
     */
    private fun reconcileUploadState(settingsChanged: Boolean) {
        val desired = UIState.packetLogUploadEnabled.value
        if (!desired) {
            if (PacketUploadRunner.isRunning) PacketUploadRunner.stop()
            return
        }
        if (settingsChanged || !PacketUploadRunner.isRunning || PacketUploadRunner.isRestarting)
            PacketUploadRunner.start()
    }

    /** Written back only on an actual change - this runs every frame. Reports whether one happened. */
    private fun persistPacketLogSettings(): Boolean {
        val config = Configuration.config
        val enabled = UIState.packetLogEnabled.value
        val upload = UIState.packetLogUploadEnabled.value
        val keepLocal = UIState.packetLogKeepLocalAfterUpload.value
        val text = UIState.packetLogTextDumpEnabled.value
        if (enabled == config.packetLogEnabled &&
            upload == config.packetLogUploadEnabled &&
            keepLocal == config.packetLogKeepLocalAfterUpload &&
            text == config.packetLogTextDumpEnabled
        ) return false
        Configuration.updateConfig(
            config.copy(
                packetLogEnabled = enabled,
                packetLogUploadEnabled = upload,
                packetLogKeepLocalAfterUpload = keepLocal,
                packetLogTextDumpEnabled = text,
                // Stamped when sharing is turned on, and read back per session at export time, so
                // turning it on later cannot retroactively make an older capture shareable.
                packetLogConsentVersion = if (upload) CONSENT_VERSION else config.packetLogConsentVersion,
            )
        )
        return true
    }

    /**
     * Start and stop are requests: the database is only ever touched on the game thread, and this
     * runs on the render thread.
     */
    private fun reconcilePacketRecorderState() {
        val desired = UIState.packetLogEnabled.value
        if (desired == PacketRecorder.isArmed) return
        if (desired) PacketRecorder.requestStart() else PacketRecorder.requestStop()
    }

    private fun reconcileMcpServerState() {
        val desired = UIState.mcpEnabled.value
        when (val s = McpServer.state) {
            is McpServer.State.Listening -> if (!desired) McpServer.stop()
            McpServer.State.Disabled -> if (desired) {
                McpServer.start().onFailure {
                    UIState.mcpEnabled.value = false
                }
            }
            is McpServer.State.Error -> Unit // auto-retry would spam; wait for user interaction
            McpServer.State.Starting, McpServer.State.Stopping -> Unit // wait for transition to settle
        }
    }
}
