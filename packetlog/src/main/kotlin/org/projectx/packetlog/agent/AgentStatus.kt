package org.projectx.packetlog.agent

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.projectx.packetlog.upload.UploadService

/**
 * What the agent is doing, for the engine's UI to read.
 *
 * A file rather than a socket: the reader is a different process that comes and goes on its own
 * schedule, and the only question it asks is "what happened last cycle". A file answers that whether
 * or not the agent is running at the moment it is asked, which a connection cannot.
 */
@Serializable
class AgentStatus(
    val lastCycleEpochMs: Long,
    val liveSessions: Int,
    val cycles: Long,
    val chunksSent: Long,
    val bytesSent: Long,
    val filesDeleted: Long,
    val blocked: Boolean,
    val error: String? = null,
) {

    /** One line, phrased for the settings row that shows it. */
    fun summary(): String = when {
        blocked -> "refused by the server: ${error ?: "no reason given"}"
        error != null -> "retrying after: $error"
        cycles == 0L -> "waiting for the first cycle"
        else -> "sent $chunksSent chunks (${bytesSent / 1024} KB), $liveSessions live, $filesDeleted cleared"
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun write(file: File, service: UploadService, liveSessions: Int) {
            val status = AgentStatus(
                lastCycleEpochMs = System.currentTimeMillis(),
                liveSessions = liveSessions,
                cycles = service.cycles,
                chunksSent = service.uploadedChunks,
                bytesSent = service.uploadedBytes,
                filesDeleted = service.deletedFiles,
                blocked = service.isBlocked,
                error = service.error,
            )
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(serializer(), status))
            }
        }

        fun read(file: File): AgentStatus? {
            if (!file.isFile) return null
            return runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()
        }
    }
}
