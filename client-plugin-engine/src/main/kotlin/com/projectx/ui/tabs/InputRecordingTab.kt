package com.projectx.ui.tabs

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.InputArbiter
import com.projectx.game.input.InputRecorder
import com.projectx.game.input.ShadowInputBus
import com.projectx.game.input.SyntheticInputShadow
import com.projectx.game.input.wire.MouseRing
import com.projectx.game.input.wire.WireInput
import com.projectx.game.input.wire.WirePacketVariants
import com.projectx.game.nxt.MainState
import com.projectx.profiling.PlayerProfiles
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import java.io.File

object InputRecordingTab {

    fun ChildScope.render() {
        try {
            renderInner()
        } catch (e: Throwable) {
            text("Input Recording tab error: ${e.message}")
        }
    }

    private fun ChildScope.renderInner() {
        val playerName = resolveCurrentPlayer()
        val loggedIn = playerName.isNotBlank()

        section("Session")
        properties("recording-session") {
            row("Player profile") { text(playerName.ifBlank { "(not logged in)" }) }
        }

        section("Input Recording")
        if (!InputRecorder.isRecording) {
            if (loggedIn) {
                properties("recording-idle") {
                    row("Database") { text("~/.projectx/training/$playerName/input.db") }
                }
                button("Start Recording") {
                    InputRecorder.requestStart()
                }
            } else {
                text("Log in to record input.")
            }
        } else {
            val databaseKb = (InputRecorder.databaseFile?.length() ?: 0L) / 1024
            val elapsed = InputRecorder.sessionDurationMs / 1000
            properties("recording-active") {
                row("Player") { text(InputRecorder.playerName) }
                row("Duration") { text("${elapsed / 60}m ${elapsed % 60}s") }
                row("Events") { text(InputRecorder.eventCount.toString()) }
                row("Breakdown") {
                    text(
                        "move ${InputRecorder.motionEvents}   " +
                            "buttons ${InputRecorder.buttonEvents}   " +
                            "scroll ${InputRecorder.scrollEvents}   " +
                            "keys ${InputRecorder.keyEvents}"
                    )
                }
                row("Database") { text("$databaseKb KB   dropped: ${InputRecorder.droppedEvents}") }
            }
            button("Stop Recording") {
                InputRecorder.requestStop()
            }

            checkboxGrid(
                "recording-feed",
                listOf(
                    "Show event feed" to UIState.showTrainingEventFeed,
                    "Include motion" to UIState.trainingEventFeedMotion,
                )
            )
            if (UIState.showTrainingEventFeed.value) {
                child("TrainingEventFeed", height = 180f, childFlags = ImGuiChildFlags.Borders) {
                    val lines = InputRecorder.feedSnapshot(UIState.trainingEventFeedMotion.value)
                    if (lines.isEmpty()) text("Waiting for the next tick flush...")
                    else lines.forEach { text(it) }
                }
            }
        }

        section("Synthetic Input")

        if (!loggedIn) {
            text("Log in to configure synthetic input.")
        } else {
            val profile = PlayerProfiles.getByName(playerName)

            val modelKey = profile.synthModelPlayer.ifBlank { playerName }
            val modelFile = File(System.getProperty("user.home"), ".projectx/models/$modelKey/motor_model.onnx")
            val clickRing = MouseRing.clicks()
            val moveRing = MouseRing.movement()

            properties("synth-config") {
                row("Enabled") {
                    checkbox("##synthEnabled", profile.synthInputEnabled) { profile.synthInputEnabled = it }
                }
                row("Shadow doActions") {
                    checkbox("##synthShadow", profile.synthShadowDoActions) { profile.synthShadowDoActions = it }
                }
                row("Show visualizer") {
                    checkbox("##synthVis", profile.synthVisualizerEnabled) { profile.synthVisualizerEnabled = it }
                }
                row("Send to server") {
                    checkbox("##synthSend", profile.synthSendToServer) { profile.synthSendToServer = it }
                }
                row("Model player") {
                    inputText("##synthModelPlayer", profile.synthModelPlayer, maxLength = 64) {
                        profile.synthModelPlayer = it.trim()
                    }
                }
            }

            section("Model")
            properties("synth-model") {
                row("File") {
                    text(if (modelFile.exists()) "${modelFile.length() / 1024} KB" else "not trained")
                }
                row("Loaded") {
                    text(
                        if (SyntheticInputShadow.isModelLoaded()) SyntheticInputShadow.currentModelKey
                        else "(none)"
                    )
                }
                row("Reaches") {
                    text(
                        "${SyntheticInputShadow.reachesGenerated} " +
                            "(${SyntheticInputShadow.reachesDiscarded} discarded as not arriving)"
                    )
                }
                row("Samples") {
                    text(
                        "${SyntheticInputShadow.samplesGenerated} generated, " +
                            "${SyntheticInputShadow.pendingSamples} buffered"
                    )
                }
                row("Last synth XY") {
                    text("%.0f, %.0f".format(SyntheticInputShadow.lastSynthX, SyntheticInputShadow.lastSynthY))
                }
                row("Queue") { text(ShadowInputBus.size().toString()) }
                row("Rings") {
                    text(
                        "press ${clickRing?.pending() ?: -1} pending, " +
                            "movement ${moveRing?.pending() ?: -1} pending"
                    )
                }
                row("Last rollout") { text("%.2f ms".format(SyntheticInputShadow.lastRolloutMillis)) }
            }

            section("Wire")
            textWrapped(WirePacketVariants.describe())
            text(
                "Wire - trail ${WireInput.movementPacketsSent} packets / ${WireInput.movementSamplesSent} " +
                    "samples (${WireInput.trailQueued} queued), clicks ${WireInput.clickPacketsSent}, " +
                    "keys ${WireInput.keyPacketsSent} packets / ${WireInput.keyPressesSent} presses " +
                    "(${WireInput.keysQueued} queued)"
            )
            text(
                "Skipped - real input queued ${WireInput.skippedRingBusy}, " +
                    "script action ${InputArbiter.wireSendsDeniedByAction}, " +
                    "incomplete variants ${WireInput.skippedIncompleteVariants}, " +
                    "off game thread ${WireInput.skippedOffGameThread}"
            )
        }

        if (loggedIn) {
            section("Training Data")
            val databaseFile = File(System.getProperty("user.home"), ".projectx/training/$playerName/input.db")
            properties("recording-data") {
                row("Recordings") {
                    text(
                        if (databaseFile.exists()) "${databaseFile.length() / 1024} KB"
                        else "No recordings yet."
                    )
                }
            }
        }
    }

    private fun resolveCurrentPlayer(): String {
        return try {
            if (Bootstrap.client.mainState == MainState.LOGGED_IN) {
                Bootstrap.client.loggedInPlayer.getPlayerName() ?: ""
            } else ""
        } catch (_: Throwable) { "" }
    }
}
