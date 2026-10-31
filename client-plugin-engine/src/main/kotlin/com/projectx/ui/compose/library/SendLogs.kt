package com.projectx.ui.compose.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectx.script.ScriptMetadata
import com.projectx.store.ProblemReports
import com.projectx.store.ProblemReports.Outcome
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.TextArea
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import kotlin.concurrent.thread

private const val NOTE_MAX = 2000

/** Sends this script's log from the last day and its settings to the Project X team. */
@Composable
fun SendLogs(meta: ScriptMetadata, onClose: () -> Unit) {
    val type = LocalType.current
    val draft = remember(meta) { mutableStateOf("") }
    val note = remember(meta) { OverlayText({ draft.value }, { draft.value = it }, NOTE_MAX, multiline = true) }
    var sending by remember(meta) { mutableStateOf(false) }
    var outcome by remember(meta) { mutableStateOf<Outcome?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("SEND LOGS", style = type.eyebrow)
        BasicText(
            "Sends ${meta.name}'s log from the last ${ProblemReports.HOURS} hours and its settings to the Project X team, " +
                "so we can see what went wrong and fix it. Bank PINs, passwords and tokens are hidden before anything " +
                "leaves your computer.",
            style = type.body,
            modifier = Modifier.fillMaxWidth(0.94f),
        )
        TextArea(note, "What went wrong? When did it happen? (optional)", Modifier.fillMaxWidth(0.94f), minHeight = 96.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val sent = outcome is Outcome.Sent
            ActionButton(
                when {
                    sending -> "Sending..."
                    sent -> "Sent"
                    else -> "Send logs"
                },
                {
                    if (!sending && !sent) {
                        sending = true
                        outcome = null
                        val text = note.text
                        thread(isDaemon = true, name = "projectx-send-logs") {
                            outcome = ProblemReports.send(meta, text)
                            sending = false
                        }
                    }
                },
                tone = ButtonTone.Primary,
                height = 36.dp,
            )
            ActionButton(if (sent) "Done" else "Cancel", onClose, height = 36.dp)
        }
        when (val result = outcome) {
            is Outcome.Sent -> Hint("Sent, thank you. We'll look into it and message you on Discord.", color = Palette.running)
            is Outcome.Failed -> Hint(result.message, color = Palette.stop)
            null -> {}
        }
    }
}
