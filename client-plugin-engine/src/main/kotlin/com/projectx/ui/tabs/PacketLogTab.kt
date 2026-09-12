package com.projectx.ui.tabs

import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.game.net.packetlog.PacketUploadRunner
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import org.projectx.packetlog.store.PacketSchema.ServerProfile
import org.projectx.packetlog.store.SessionFiles

object PacketLogTab {

    private var lastPurgeMessage: String? = null

    fun ChildScope.render() {
        try {
            renderInner()
        } catch (e: Throwable) {
            text("Packet Log tab error: ${e.message}")
        }
    }

    private fun ChildScope.renderInner() {
        section("Capture")
        if (!PacketRecorder.isRecording) {
            val waiting = PacketRecorder.isArmed
            properties("packetlog-idle") {
                row("State") { text(if (waiting) "armed - waiting for a connection" else "off") }
                row("Queued") { text("${PacketRecorder.queueDepth} events held") }
                row("Directory") { text("~/.projectx/packetlog/") }
            }
            textWrapped(
                if (waiting) {
                    "Capturing, but no session is open yet: which database a session belongs to is decided " +
                        "by the server the client connects to, so the session opens on connection. Packets " +
                        "captured before then are held, not dropped."
                } else {
                    "Recording is off. Enable it in Settings, or start it here for this session only. " +
                        "Live and local-server captures are kept in separate databases and appended across sessions."
                }
            )
            if (!waiting) button("Start Recording") {
                PacketRecorder.requestStart()
                UIState.packetLogEnabled.value = true
            }
            renderStorage()
            return
        }

        val file = PacketRecorder.databaseFile
        val sizeKb = (file?.length() ?: 0L) / 1024
        properties("packetlog-active") {
            row("State") { text("recording") }
            row("Profile") { text("${PacketRecorder.profile.wireName} - ${PacketRecorder.profileDetail}") }
            row("Database") { text("${file?.absolutePath ?: "?"}  ($sizeKb KB)") }
            row("Packets") { text(PacketRecorder.packetsWritten.toString()) }
            row("Queue") {
                text("${PacketRecorder.queueDepth} events / ${PacketRecorder.queueBytes / 1024} KB")
            }
            row("Open chunk") { text("${PacketRecorder.openChunkEvents} events") }
            row("Sealing") { text("${PacketRecorder.sealsInFlight} in flight") }
            row("Dropped") { text(PacketRecorder.droppedPackets.toString()) }
            row("Sharing") { text(if (PacketUploadRunner.isRunning) PacketUploadRunner.status else "off") }
        }

        if (PacketRecorder.profile == ServerProfile.UNKNOWN) {
            textWrapped(
                "This session could not be attributed to the live or the local server, so it is being " +
                    "filed to quarantine and will never be uploaded. Set PROJECTX_SERVER_PROFILE in the " +
                    "launcher to record it as one or the other."
            )
        }
        if (PacketRecorder.droppedPackets > 0) {
            textWrapped(
                "Packets were dropped because the capture queue filled faster than the game thread " +
                    "drained it. Each one is still recorded as an empty marker, so the sequence has no " +
                    "unexplained gaps, but their bodies are gone."
            )
        }

        button("Stop Recording") {
            PacketRecorder.requestStop()
            UIState.packetLogEnabled.value = false
        }
        renderStorage()
    }

    private fun ChildScope.renderStorage() {
        section("Storage")
        button("Purge Uploaded Logs") {
            var purged = 0
            var bytes = 0L
            var live = 0
            for (profile in ServerProfile.entries) {
                val result = SessionFiles.purgeUploaded(SessionFiles.directoryFor(profile))
                purged += result.purged
                bytes += result.bytesFreed
                live += result.stillLive
            }
            lastPurgeMessage = "purged $purged session(s), freed %.1f MB".format(bytes / 1_000_000.0) +
                if (live > 0) " ($live still recording, skipped)" else ""
        }
        textWrapped(
            "Removes sessions the server has already confirmed it holds and sealed. Only relevant when " +
                "\"Keep local copy\" is on in Settings - without it nothing is left behind to purge."
        )
        lastPurgeMessage?.let { text(it) }
    }
}
