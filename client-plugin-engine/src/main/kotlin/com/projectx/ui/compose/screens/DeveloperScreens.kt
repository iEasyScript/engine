package com.projectx.ui.compose.screens

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.cs2.CS2Trace
import com.projectx.game.input.InputArbiter
import com.projectx.game.input.InputRecorder
import com.projectx.game.input.ShadowInputBus
import com.projectx.game.input.SyntheticInputShadow
import com.projectx.game.input.wire.MouseRing
import com.projectx.game.input.wire.WireInput
import com.projectx.game.input.wire.WirePacketVariants
import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.game.net.packetlog.PacketUploadRunner
import com.projectx.game.nxt.MainState
import com.projectx.profiling.PlayerProfile
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import com.projectx.ui.UIState
import com.projectx.ui.VarcEntry
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Card
import com.projectx.ui.compose.components.ChipGroup
import com.projectx.ui.compose.components.DataTable
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.Readout
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.Segmented
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TableColumn
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.Toggle
import com.projectx.ui.compose.components.ToggleChip
import com.projectx.ui.compose.components.ToggleRow
import com.projectx.ui.compose.components.overlayScrollbarStyle
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import com.projectx.util.EngineLog
import java.io.File
import java.io.RandomAccessFile
import org.projectx.packetlog.store.PacketSchema.ServerProfile
import org.projectx.packetlog.store.SessionFiles
import world.gregs.voidps.gameval.Gameval

// ---------- Logs ----------

object LogsModel {
    private const val TAIL_BYTES = 64L * 1024
    private val errorMarkers = listOf("error", "exception", "severe", "fatal", "\tat ", "caused by")
    private val warnMarkers = listOf("warn", "deprecated")
    private var lastModified = 0L

    val errorsOnly = UIState.logErrorsOnly

    val feed = Feed(250) {
        syncFromFile()
        EngineLog.lines.toList()
    }

    fun isError(line: String) = errorMarkers.any { line.contains(it, ignoreCase = true) }
    fun isWarning(line: String) = warnMarkers.any { line.contains(it, ignoreCase = true) }

    fun clear() {
        EngineLog.lines.clear()
        feed.invalidate()
    }

    private fun syncFromFile() {
        val file = EngineLog.file?.takeIf { it.exists() } ?: return
        if (file.lastModified() <= lastModified) return
        lastModified = file.lastModified()
        val tail = readLastLines(file, EngineLog.MAX_LINES)
        EngineLog.lines.clear()
        EngineLog.lines.addAll(tail)
    }

    private fun readLastLines(file: File, count: Int): List<String> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val from = (length - TAIL_BYTES).coerceAtLeast(0)
        raf.seek(from)
        val window = ByteArray((length - from).toInt())
        raf.readFully(window)
        val lines = String(window, Charsets.UTF_8).lineSequence().toList()
        // A window that starts mid-file can start mid-line or mid-codepoint, which only damages its first line.
        (if (from > 0) lines.drop(1) else lines).filter { it.isNotEmpty() }.takeLast(count)
    }
}

@Composable
fun LogsScreen() {
    val all = LogsModel.feed.value.orEmpty()
    val query = UIState.logFilterText.value.trim()
    val lines = all.filter { (query.isEmpty() || it.contains(query, ignoreCase = true)) && (!LogsModel.errorsOnly.value || LogsModel.isError(it)) }
    val listState = rememberLazyListState()
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(lines.size, pinned) { if (pinned && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(UIState.logFilterText, "Filter lines", 260.dp)
            ToggleChip("Errors only", LogsModel.errorsOnly.value) { LogsModel.errorsOnly.value = it }
            ToggleChip("Follow new lines", pinned) { pinned = it }
            Spacer(Modifier.weight(1f))
            BasicText("${lines.size} / ${all.size} lines", style = LocalType.current.dataSmall)
            ActionButton("Clear", { LogsModel.clear() }, height = 32.dp)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0A0D12))
                .border(1.dp, Palette.line, RoundedCornerShape(10.dp)),
        ) {
            if (lines.isEmpty()) {
                EmptyState(if (all.isEmpty()) "No log output yet" else "No lines match", "Everything the engine and your scripts print lands here.")
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                items(lines) { line ->
                    val color = when {
                        LogsModel.isError(line) -> Palette.stop
                        LogsModel.isWarning(line) -> Palette.amber
                        else -> Color(0xFFC9CFDB)
                    }
                    BasicText(line, style = LocalType.current.dataSmall.copy(color = color), modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(3.dp))
            }
        }
        Hint(EngineLog.file?.absolutePath ?: "No log file")
    }
}

// ---------- Packet log ----------

data class PacketLogSnapshot(
    val recording: Boolean,
    val armed: Boolean,
    val queued: Long,
    val rows: List<Pair<String, String>>,
    val unknownProfile: Boolean,
    val dropped: Long,
)

object PacketLogModel {
    var purgeMessage by mutableStateOf<String?>(null)

    val feed = Feed(500) {
        val recording = PacketRecorder.isRecording
        val rows = if (!recording) {
            listOf("Directory" to "~/.projectx/packetlog/")
        } else {
            val file = PacketRecorder.databaseFile
            listOf(
                "Profile" to "${PacketRecorder.profile.wireName} - ${PacketRecorder.profileDetail}",
                "Database" to "${file?.absolutePath ?: "?"}  (${(file?.length() ?: 0L) / 1024} KB)",
                "Packets" to PacketRecorder.packetsWritten.toString(),
                "Queue" to "${PacketRecorder.queueDepth} events / ${PacketRecorder.queueBytes / 1024} KB",
                "Open chunk" to "${PacketRecorder.openChunkEvents} events",
                "Sealing" to "${PacketRecorder.sealsInFlight} in flight",
                "Dropped" to PacketRecorder.droppedPackets.toString(),
                "Sharing" to if (PacketUploadRunner.isRunning) PacketUploadRunner.status else "off",
            )
        }
        PacketLogSnapshot(
            recording, PacketRecorder.isArmed, PacketRecorder.queueDepth, rows,
            recording && PacketRecorder.profile == ServerProfile.UNKNOWN, PacketRecorder.droppedPackets,
        )
    }

    fun start() {
        PacketRecorder.requestStart()
        UIState.packetLogEnabled.value = true
        feed.invalidate()
    }

    fun stop() {
        PacketRecorder.requestStop()
        UIState.packetLogEnabled.value = false
        feed.invalidate()
    }

    fun purgeUploaded() {
        var purged = 0
        var bytes = 0L
        var live = 0
        for (profile in ServerProfile.entries) {
            val result = SessionFiles.purgeUploaded(SessionFiles.directoryFor(profile))
            purged += result.purged
            bytes += result.bytesFreed
            live += result.stillLive
        }
        purgeMessage = "Purged $purged session(s), freed %.1f MB".format(bytes / 1_000_000.0) + if (live > 0) " ($live still recording, skipped)" else ""
    }
}

@Composable
fun PacketLogScreen() {
    val s = PacketLogModel.feed.value ?: return
    ScreenScroll {
        Section(
            "Capture",
            actions = {
                when {
                    s.recording -> Pill("Recording", Palette.running, dot = true)
                    s.armed -> Pill("Waiting for a connection", Palette.amber, dot = true)
                    else -> Pill("Off", Palette.faint)
                }
            },
        ) {
            Readout(s.rows)
            when {
                s.recording -> ActionButton("Stop recording", { PacketLogModel.stop() }, tone = ButtonTone.Danger, icon = Glyph.Stop)
                s.armed -> Hint(
                    "Capturing, but no session is open yet: the server the client connects to decides which database a " +
                        "session belongs to. ${s.queued} event(s) are held until then, not dropped.",
                )
                else -> {
                    Hint("Recording is off. Turn it on in Settings, or start it here for this session only. Live and local-server captures go to separate databases.")
                    ActionButton("Start recording", { PacketLogModel.start() }, tone = ButtonTone.Primary, icon = Glyph.Play)
                }
            }
            if (s.unknownProfile) {
                Hint(
                    "This session could not be attributed to the live or the local server, so it is filed to quarantine and " +
                        "will never be uploaded. Set PROJECTX_SERVER_PROFILE in the launcher to record it as one or the other.",
                    color = Palette.amber,
                )
            }
            if (s.dropped > 0) {
                Hint(
                    "Packets were dropped because the capture queue filled faster than the game thread drained it. Each is " +
                        "still recorded as an empty marker, so the sequence has no unexplained gaps, but the bodies are gone.",
                    color = Palette.amber,
                )
            }
        }
        Section("Storage", note = "Removes sessions the server has confirmed it holds. Only matters with \"Keep local copy\" on in Settings.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Purge uploaded logs", { PacketLogModel.purgeUploaded() }, icon = Glyph.Trash)
                PacketLogModel.purgeMessage?.let { Hint(it) }
            }
        }
    }
}

// ---------- Input recording ----------

data class InputRecordingSnapshot(
    val player: String,
    val recording: Boolean,
    val session: List<Pair<String, String>>,
    val feed: List<String>,
    val synthEnabled: Boolean,
    val synthShadow: Boolean,
    val synthVisualizer: Boolean,
    val synthSend: Boolean,
    val synthModelPlayer: String,
    val model: List<Pair<String, String>>,
    val wire: List<String>,
    val trainingData: String,
)

object InputRecordingModel {
    val feed = Feed(500) {
        val player = runCatching {
            if (Bootstrap.client.mainState == MainState.LOGGED_IN) Bootstrap.client.loggedInPlayer.getPlayerName() ?: "" else ""
        }.getOrDefault("")
        val home = System.getProperty("user.home")
        val recording = InputRecorder.isRecording
        val session = if (recording) {
            val elapsed = InputRecorder.sessionDurationMs / 1000
            listOf(
                "Player" to InputRecorder.playerName,
                "Duration" to "${elapsed / 60}m ${elapsed % 60}s",
                "Events" to InputRecorder.eventCount.toString(),
                "Breakdown" to "move ${InputRecorder.motionEvents} · buttons ${InputRecorder.buttonEvents} · scroll ${InputRecorder.scrollEvents} · keys ${InputRecorder.keyEvents}",
                "Database" to "${(InputRecorder.databaseFile?.length() ?: 0L) / 1024} KB · dropped ${InputRecorder.droppedEvents}",
            )
        } else {
            listOf("Database" to "~/.projectx/training/$player/input.db")
        }
        val profile = player.takeIf { it.isNotBlank() }?.let { PlayerProfiles.getByName(it) }
        val modelKey = profile?.synthModelPlayer?.ifBlank { player } ?: player
        val modelFile = File(home, ".projectx/models/$modelKey/motor_model.onnx")
        val clicks = MouseRing.clicks()
        val moves = MouseRing.movement()
        val trainingFile = File(home, ".projectx/training/$player/input.db")
        InputRecordingSnapshot(
            player = player,
            recording = recording,
            session = session,
            feed = if (recording && UIState.showTrainingEventFeed.value) InputRecorder.feedSnapshot(UIState.trainingEventFeedMotion.value) else emptyList(),
            synthEnabled = profile?.synthInputEnabled ?: false,
            synthShadow = profile?.synthShadowDoActions ?: false,
            synthVisualizer = profile?.synthVisualizerEnabled ?: false,
            synthSend = profile?.synthSendToServer ?: false,
            synthModelPlayer = profile?.synthModelPlayer.orEmpty(),
            model = listOf(
                "File" to if (modelFile.exists()) "${modelFile.length() / 1024} KB" else "not trained",
                "Loaded" to if (SyntheticInputShadow.isModelLoaded()) SyntheticInputShadow.currentModelKey else "none",
                "Reaches" to "${SyntheticInputShadow.reachesGenerated} (${SyntheticInputShadow.reachesDiscarded} discarded as not arriving)",
                "Samples" to "${SyntheticInputShadow.samplesGenerated} generated, ${SyntheticInputShadow.pendingSamples} buffered",
                "Last synth XY" to "%.0f, %.0f".format(SyntheticInputShadow.lastSynthX, SyntheticInputShadow.lastSynthY),
                "Queue" to ShadowInputBus.size().toString(),
                "Rings" to "press ${clicks?.pending() ?: -1} pending, movement ${moves?.pending() ?: -1} pending",
                "Last rollout" to "%.2f ms".format(SyntheticInputShadow.lastRolloutMillis),
            ),
            wire = listOf(
                WirePacketVariants.describe(),
                "Trail ${WireInput.movementPacketsSent} packets / ${WireInput.movementSamplesSent} samples (${WireInput.trailQueued} queued), " +
                    "clicks ${WireInput.clickPacketsSent}, keys ${WireInput.keyPacketsSent} packets / ${WireInput.keyPressesSent} presses (${WireInput.keysQueued} queued)",
                "Skipped: real input queued ${WireInput.skippedRingBusy}, script action ${InputArbiter.wireSendsDeniedByAction}, " +
                    "incomplete variants ${WireInput.skippedIncompleteVariants}, off game thread ${WireInput.skippedOffGameThread}",
            ),
            trainingData = if (trainingFile.exists()) "${trainingFile.length() / 1024} KB" else "No recordings yet",
        )
    }

    fun updateProfile(change: (PlayerProfile) -> Unit) {
        val player = feed.value?.player?.takeIf { it.isNotBlank() } ?: return
        change(PlayerProfiles.getByName(player))
        feed.invalidate()
    }
}

@Composable
fun InputRecordingScreen() {
    val s = InputRecordingModel.feed.value ?: return
    val loggedIn = s.player.isNotBlank()
    ScreenScroll {
        Section(
            "Input recording",
            note = if (loggedIn) "Recording into ${s.player}'s training profile." else "Log in to record input.",
            actions = { if (s.recording) Pill("Recording", Palette.running, dot = true) },
        ) {
            Readout(s.session)
            if (s.recording) {
                ActionButton("Stop recording", { InputRecorder.requestStop(); InputRecordingModel.feed.invalidate() }, tone = ButtonTone.Danger, icon = Glyph.Stop)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip("Show event feed", UIState.showTrainingEventFeed.value) { UIState.showTrainingEventFeed.value = it }
                    ToggleChip("Include motion", UIState.trainingEventFeedMotion.value) { UIState.trainingEventFeedMotion.value = it }
                }
                if (UIState.showTrainingEventFeed.value) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0D12)).padding(10.dp),
                    ) {
                        if (s.feed.isEmpty()) Hint("Waiting for the next tick flush...")
                        s.feed.takeLast(12).forEach { BasicText(it, style = LocalType.current.dataSmall) }
                    }
                }
            } else if (loggedIn) {
                ActionButton("Start recording", { InputRecorder.requestStart(); InputRecordingModel.feed.invalidate() }, tone = ButtonTone.Primary, icon = Glyph.Play)
            }
        }
        if (!loggedIn) return@ScreenScroll
        Section("Synthetic input") {
            SettingRow("Enabled") { Toggle(s.synthEnabled) { v -> InputRecordingModel.updateProfile { it.synthInputEnabled = v } } }
            SettingRow("Shadow script actions") { Toggle(s.synthShadow) { v -> InputRecordingModel.updateProfile { it.synthShadowDoActions = v } } }
            SettingRow("Show visualiser") { Toggle(s.synthVisualizer) { v -> InputRecordingModel.updateProfile { it.synthVisualizerEnabled = v } } }
            SettingRow("Send to server") { Toggle(s.synthSend) { v -> InputRecordingModel.updateProfile { it.synthSendToServer = v } } }
            SettingRow("Model player", "Whose motor model to use. Blank uses your own.") {
                val field = remember(s.player) {
                    OverlayText(
                        read = { InputRecordingModel.feed.value?.synthModelPlayer.orEmpty() },
                        write = { text -> InputRecordingModel.updateProfile { it.synthModelPlayer = text.trim() } },
                        maxLength = 64,
                    )
                }
                TextInput(field, s.player, 200.dp)
            }
        }
        Section("Model") { Readout(s.model) }
        Section("Wire") { s.wire.forEach { Hint(it) } }
        Section("Training data") { Readout(listOf("Recordings" to s.trainingData)) }
    }
}

// ---------- Variables ----------

enum class VarDomain(val label: String) { Player("Player var"), Client("Client var") }
enum class VarRead(val label: String) { Whole("Whole var"), Bit("Varbit") }

data class WatchRow(val index: Int, val domain: VarDomain, val read: VarRead, val id: Int, val label: String, val live: Boolean, val value: Int)

data class VariablesSnapshot(val preview: Int, val watches: List<WatchRow>, val changes: List<VarcEntry>)

object VariablesModel {
    var domain by mutableStateOf(VarDomain.Player)
    var read by mutableStateOf(VarRead.Whole)
    val search = UIState.varChangeSearchText

    private val shownTypes = mapOf(
        "varp" to UIState.varChangeTrackVarp,
        "varpbit" to UIState.varChangeTrackVarpbit,
        "varc" to UIState.varChangeTrackVarc,
        "varcbit" to UIState.varChangeTrackVarcbit,
    )

    val feed = Feed(200) {
        val watches = UIState.varDebugWatches.mapIndexed { index, w ->
            val d = VarDomain.entries[w.domainIndex]
            val r = VarRead.entries[w.readModeIndex]
            WatchRow(index, d, r, w.id, label(d, r, w.id), w.live, if (w.live) readValue(d, r, w.id) else w.cachedValue)
        }
        VariablesSnapshot(readValue(domain, read, UIState.varDebugId.value), watches, UIState.varTableData.toList())
    }

    fun typeFilters() = shownTypes.toList()

    fun visibleChanges(changes: List<VarcEntry>): List<VarcEntry> {
        val q = search.value.trim()
        return changes.filter { (shownTypes[it.type]?.value ?: true) && (q.isEmpty() || it.type.contains(q, true) || it.id.toString().contains(q) || changeLabel(it).contains(q, true)) }
    }

    fun addWatch(live: Boolean) {
        val d = domain
        val r = read
        val id = UIState.varDebugId.value
        GameThread.post {
            UIState.varDebugWatches.add(UIState.VarDebugWatch(d.ordinal, r.ordinal, id, live, if (live) 0 else readValue(d, r, id)))
            feed.invalidate()
        }
    }

    fun refreshWatch(index: Int) = GameThread.post {
        UIState.varDebugWatches.getOrNull(index)?.let { w ->
            w.cachedValue = readValue(VarDomain.entries[w.domainIndex], VarRead.entries[w.readModeIndex], w.id)
        }
        feed.invalidate()
    }

    fun removeWatch(index: Int) = GameThread.post {
        if (index in UIState.varDebugWatches.indices) UIState.varDebugWatches.removeAt(index)
        feed.invalidate()
    }

    fun clearWatches() = GameThread.post { UIState.varDebugWatches.clear(); feed.invalidate() }

    fun clearChanges() = GameThread.post { UIState.varTableData.clear(); feed.invalidate() }

    fun label(domain: VarDomain, read: VarRead, id: Int): String = when {
        domain == VarDomain.Player && read == VarRead.Bit -> Gameval.varbitLabel(id)
        domain == VarDomain.Player -> Gameval.varpLabel(id)
        read == VarRead.Bit -> id.toString()
        else -> Gameval.varcLabel(id)
    }

    fun changeLabel(entry: VarcEntry): String = when (entry.type) {
        "varp" -> Gameval.varpLabel(entry.id)
        "varpbit" -> Gameval.varbitLabel(entry.id)
        "varc" -> Gameval.varcLabel(entry.id)
        else -> entry.id.toString()
    }

    private fun readValue(domain: VarDomain, read: VarRead, id: Int): Int {
        if (id < 0) return 0
        return runCatching {
            when (domain) {
                VarDomain.Player -> if (read == VarRead.Whole) varps.getVar(id) else varps.getVarBit(id)
                VarDomain.Client -> if (read == VarRead.Whole) varcs.getVar(id) else varcs.getVarBit(id)
            }
        }.getOrDefault(0)
    }
}

@Composable
fun VariablesScreen() {
    val s = VariablesModel.feed.value
    val m = VariablesModel
    ScreenScroll {
        Section("Watch a variable") {
            SettingRow("Kind") {
                Segmented(VarDomain.entries, m.domain, { m.domain = it; m.feed.invalidate() }) { it.label }
                Segmented(VarRead.entries, m.read, { m.read = it; m.feed.invalidate() }) { it.label }
            }
            SettingRow("Id", m.label(m.domain, m.read, UIState.varDebugId.value)) {
                NumberInput(UIState.varDebugId.value, { UIState.varDebugId.value = it; m.feed.invalidate() }, 0..1_000_000, width = 160.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText("Value now", style = LocalType.current.label.copy(color = Palette.muted))
                BasicText("${s?.preview ?: "-"}", style = LocalType.current.data.copy(color = Palette.text, fontSize = LocalType.current.heading.fontSize))
                Spacer(Modifier.weight(1f))
                ActionButton("Watch live", { m.addWatch(live = true) }, tone = ButtonTone.Primary, icon = Glyph.Plus)
                ActionButton("Snapshot", { m.addWatch(live = false) })
            }
        }
        Section(
            "Watches",
            actions = { if (s?.watches?.isNotEmpty() == true) ActionButton("Clear all", { m.clearWatches() }, height = 30.dp) },
        ) {
            DataTable(
                listOf(TableColumn("Kind", width = 150.dp), TableColumn("Variable", 2f), TableColumn("Mode", width = 90.dp), TableColumn("Value", mono = true), TableColumn("", width = 110.dp)),
                s?.watches.orEmpty(),
                emptyText = "Nothing watched yet. Pick a variable above.",
            ) { w ->
                text("${w.domain.label} · ${if (w.read == VarRead.Bit) "bit" else "whole"}")
                text(w.label)
                cell { Pill(if (w.live) "Live" else "Snapshot", if (w.live) Palette.running else Palette.muted) }
                text(w.value.toString(), Palette.text)
                cell {
                    if (!w.live) IconButton(Glyph.Refresh, { m.refreshWatch(w.index) })
                    IconButton(Glyph.Trash, { m.removeWatch(w.index) }, activeTint = Palette.stop)
                }
            }
        }
        Section(
            "Change log",
            note = "The most recent change to each variable, newest first.",
            actions = { ActionButton("Clear", { m.clearChanges() }, height = 30.dp) },
        ) {
            ToggleRow("Record player var changes", UIState.varpDebugEnabled)
            ToggleRow("Record client var changes", UIState.varcDebugEnabled)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipGroup(m.typeFilters())
                Spacer(Modifier.weight(1f))
                SearchField(m.search, "Name or id", 190.dp)
            }
            DataTable(
                listOf(TableColumn("Type", width = 90.dp), TableColumn("Variable", 2f), TableColumn("Was", mono = true), TableColumn("Now", mono = true)),
                m.visibleChanges(s?.changes.orEmpty()),
                emptyText = if (!UIState.varpDebugEnabled.value && !UIState.varcDebugEnabled.value) "Turn on recording above." else "No changes yet.",
            ) { e ->
                text(e.type)
                text(m.changeLabel(e))
                text(e.prevValue.toString())
                text(e.newValue.toString(), Palette.text)
            }
        }
    }
}

// ---------- CS2 trace ----------

data class Cs2Row(val seq: Long, val scriptId: Int, val args: String, val returns: String)

data class Cs2Snapshot(val captured: Int, val rows: List<Cs2Row>, val blacklist: List<Int>)

object Cs2Model {
    private const val MAX_ROWS = 500
    val search = UIState.cs2TraceSearch

    val feed = Feed(250) {
        val all = CS2Trace.snapshot()
        val filterId = UIState.cs2TraceFilterId.value
        val q = search.value.trim()
        val rows = all.asReversed().asSequence()
            .filter { filterId < 0 || it.scriptId == filterId }
            .map { Cs2Row(it.seq, it.scriptId, stack(it.argInts, it.argLongs, it.argStrings), stack(it.retInts, it.retLongs, it.retStrings)) }
            .filter { q.isEmpty() || it.scriptId.toString().contains(q) || it.args.contains(q, true) || it.returns.contains(q, true) }
            .take(MAX_ROWS)
            .toList()
        Cs2Snapshot(all.size, rows, CS2Trace.blacklistedIds())
    }

    fun setEnabled(on: Boolean) {
        UIState.cs2TraceEnabled.value = on
        CS2Trace.enabled = on
    }

    private fun stack(ints: IntArray, longs: LongArray, strings: List<String>): String = buildString {
        if (ints.isNotEmpty()) append("i:").append(ints.joinToString(",")).append(' ')
        if (longs.isNotEmpty()) append("l:").append(longs.joinToString(",")).append(' ')
        if (strings.isNotEmpty()) append("s:[").append(strings.joinToString("|")).append(']')
    }.trim().ifEmpty { "-" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Cs2TraceScreen() {
    val s = Cs2Model.feed.value
    val showArgs = UIState.cs2TraceShowArgs.value
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Toggle(UIState.cs2TraceEnabled.value) { Cs2Model.setEnabled(it) }
                BasicText("Intercept CS2 calls", style = LocalType.current.bodyStrong)
                ToggleChip("Show arguments", showArgs) { UIState.cs2TraceShowArgs.value = it }
                Spacer(Modifier.weight(1f))
                BasicText("Script", style = LocalType.current.label.copy(color = Palette.muted))
                NumberInput(UIState.cs2TraceFilterId.value, { UIState.cs2TraceFilterId.value = it; Cs2Model.feed.invalidate() }, -1..100_000, width = 140.dp)
                SearchField(Cs2Model.search, "Id or value", 170.dp)
                ActionButton("Clear", { CS2Trace.clear(); Cs2Model.feed.invalidate() }, height = 34.dp)
            }
            val blacklist = s?.blacklist.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("Hidden", style = LocalType.current.label.copy(color = Palette.muted))
                if (blacklist.isEmpty()) Hint("Nothing. Click a script id below to hide it.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    blacklist.forEach { id -> ToggleChip("$id", true) { CS2Trace.removeBlacklist(id); Cs2Model.feed.invalidate() } }
                }
            }
        }
        Hint("Showing ${s?.rows?.size ?: 0} of ${s?.captured ?: 0} captured" + if (!UIState.cs2TraceEnabled.value) " · interception is off" else "")
        DataTable(
            buildList {
                add(TableColumn("#", width = 80.dp, mono = true))
                add(TableColumn("Script", width = 90.dp, mono = true))
                if (showArgs) add(TableColumn("Arguments", 2f, mono = true))
                add(TableColumn("Returns", 2f, mono = true))
            },
            s?.rows.orEmpty(),
            modifier = Modifier.weight(1f),
            fill = true,
            emptyText = if (UIState.cs2TraceEnabled.value) "No calls captured yet." else "Turn interception on to capture calls.",
            key = { it.seq },
        ) { row ->
            text(row.seq.toString())
            cell { ScriptIdChip(row.scriptId) }
            if (showArgs) text(row.args)
            text(row.returns)
        }
    }
}

@Composable
private fun ScriptIdChip(id: Int) {
    val hover = rememberHover()
    BasicText(
        "$id",
        style = LocalType.current.data.copy(color = if (hover.hovered) Palette.stop else Palette.amber),
        modifier = Modifier.press(hover) { CS2Trace.addBlacklist(id); Cs2Model.feed.invalidate() }.padding(vertical = 2.dp),
    )
}

