package org.projectx.packetlog.upload

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Uploads sealed chunks to the ingest server.
 *
 * Resume is structural rather than bookkept: registering a session returns the ordinals the server
 * already holds, so a client that died mid-upload simply sends the difference. Re-sending a chunk
 * the server has is a no-op on both sides, which is what lets this retry freely without tracking
 * what it managed to send before it was interrupted.
 */
class PacketUploader(
    private val endpoint: String,
    private val credentials: UploadCredentials,
    private val policy: RedactionPolicy = RedactionPolicy.DEFAULT,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {

    class Report(
        val sessions: Int,
        val chunksSent: Int,
        val chunksAlreadyHeld: Int,
        val bytesSent: Long,
        val redactedChunks: Int,
        val skipped: List<String>,
        /** Sessions the server itself reported as sealed - its word, not an inference from a 2xx. */
        val sealedOnServer: Int = 0,
    ) {

        /**
         * Every session in the archive is on the server and sealed there, so the local copy is no
         * longer the only one.
         *
         * ⛔ The only safe basis for deleting a capture. A skipped session means bytes that never
         * left this machine - one of those in the file makes the whole file worth keeping, however
         * much of the rest got through.
         */
        val safeToDelete: Boolean get() = sessions > 0 && skipped.isEmpty() && sealedOnServer == sessions
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null
    private var streaming = false

    /**
     * Pushes a session's sealed chunks while it is still being written.
     *
     * The server keeps a session open until told otherwise, and a chunk is immutable once sealed, so
     * there is nothing to wait for - streaming is just uploading early. The session is only
     * completed once it has actually ended.
     */
    fun stream(archive: File): Report = upload(archive, dryRun = false, streaming = true)

    fun upload(archive: File, dryRun: Boolean = false, streaming: Boolean = false): Report {
        this.streaming = streaming
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://localhost")) {
            "refusing to upload over plaintext to $endpoint"
        }
        var sessions = 0
        var sent = 0
        var held = 0
        var bytes = 0L
        var redacted = 0
        var sealed = 0
        val skipped = ArrayList<String>()

        ArchiveReader(archive).use { reader ->
            for (session in reader.sessions()) {
                val reason = notUploadable(session)
                if (reason != null) {
                    skipped.add("${session.uuid}: $reason")
                    continue
                }
                sessions++

                val redactedOpcodes = reader.opcodesFor(session.revision, policy.droppedProts)
                val have = if (dryRun) emptySet() else registerSession(session).toSet()

                for (chunk in reader.chunks(session.id)) {
                    if (chunk.ordinal in have) {
                        held++
                        continue
                    }
                    val names = reader.protNamesIn(chunk.ordinal, session.id)
                    val needsRedaction = names.any { policy.drops(it) }
                    val frame = if (needsRedaction) {
                        redacted++
                        ArchiveReader.redact(chunk, redactedOpcodes)
                    } else {
                        chunk.frame
                    }
                    bytes += frame.size
                    sent++
                    if (!dryRun) putChunk(session.uuid, chunk.ordinal, frame)
                }
                // Completing seals it; a session still being written must stay open - but it has to
                // say so, or the server's lease expires and reaps it out from under a live capture.
                if (!dryRun) {
                    if (session.endedEpochMs != null) {
                        if (completeSession(session.uuid) == SEALED) sealed++
                    } else if (streaming) {
                        heartbeat(session.uuid, reader.heartbeatFor(session))
                    }
                }
            }
        }
        return Report(sessions, sent, held, bytes, redacted, skipped, sealed)
    }

    /**
     * Consent is checked against the session, not the current setting: a capture taken before the
     * user agreed to share anything can never become shareable by flipping a switch afterwards.
     */
    private fun notUploadable(session: ArchiveReader.Session): String? = when {
        !session.uploadOptIn -> "recorded without upload consent"
        session.consentVersion <= 0 -> "no consent version recorded"
        session.kind == "unknown" -> "server could not be attributed"
        session.endedEpochMs == null && !streaming -> "session is still open"
        else -> null
    }

    private fun registerSession(session: ArchiveReader.Session): List<Long> {
        // Serialized from the shared contract rather than assembled field by field, so the receiver
        // and the sender cannot disagree about what a field is called.
        val manifest = SessionManifest(
            uuid = session.uuid,
            kind = session.kind,
            revision = session.revision,
            protTableHash = session.protTableHash,
            clientBuild = session.clientBuild,
            engineBuild = session.engineBuild,
            platform = session.platform,
            arch = session.arch,
            startedEpochMs = session.startedEpochMs,
            consentVersion = session.consentVersion,
            redactionPolicy = policy.version,
        )
        val response = send(
            request("/v1/sessions")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(SessionManifest.serializer(), manifest)))
                .header("Content-Type", "application/json")
        )
        val body = json.parseToJsonElement(response.body()) as JsonObject
        return body["haveOrdinals"]?.jsonArray?.map { it.jsonPrimitive.long } ?: emptyList()
    }

    private fun putChunk(sessionUuid: String, ordinal: Long, frame: ByteArray) {
        send(
            request("/v1/sessions/$sessionUuid/chunks/$ordinal")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(frame))
                .header("Content-Type", "application/octet-stream")
                .header("X-Plain-Sha256", sha256Hex(frame))
                .header("Idempotency-Key", "$sessionUuid/$ordinal")
        )
    }

    private fun heartbeat(sessionUuid: String, progress: SessionHeartbeat) {
        send(
            request("/v1/sessions/$sessionUuid/heartbeat")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(SessionHeartbeat.serializer(), progress)))
                .header("Content-Type", "application/json")
        )
    }

    /** The state the server ended up in, which is what decides whether the local copy is redundant. */
    private fun completeSession(sessionUuid: String): String {
        val response = send(
            request("/v1/sessions/$sessionUuid/complete")
                .POST(HttpRequest.BodyPublishers.noBody())
        )
        return runCatching {
            (json.parseToJsonElement(response.body()) as JsonObject).getValue("state").jsonPrimitive.content
        }.getOrDefault("")
    }

    /**
     * Retries only what a retry can fix. A rejected chunk is rejected for a reason that will still
     * hold in an hour, so hammering it would only delay surfacing the problem.
     */
    private fun send(builder: HttpRequest.Builder): HttpResponse<String> {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_ATTEMPTS) {
            val response = client.send(
                builder.header("Authorization", "Bearer ${token()}").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            when {
                response.statusCode() in 200..299 -> return response
                response.statusCode() == 401 -> token = null
                response.statusCode() == 429 || response.statusCode() >= 500 -> Unit
                else -> error("upload rejected (${response.statusCode()}): ${response.body()}")
            }
            lastError = "${response.statusCode()}: ${response.body()}"
            attempt++
            Thread.sleep(backoffMs(attempt))
        }
        error("upload failed after $MAX_ATTEMPTS attempts - $lastError")
    }

    private fun token(): String = token ?: exchangeToken().also { token = it }

    private fun exchangeToken(): String {
        check(credentials.enrolled) { "not registered with $endpoint yet" }
        val timestamp = System.currentTimeMillis()
        val nonce = randomNonce()
        val mac = hmacHex(
            sha256Hex(credentials.deviceSecret.toByteArray()),
            "${credentials.deviceId}:$timestamp:$nonce",
        )
        val body = buildJsonObject {
            put("deviceId", credentials.deviceId)
            put("timestamp", timestamp)
            put("nonce", nonce)
            put("mac", mac)
        }
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$endpoint/v1/token"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "token exchange failed: ${response.body()}" }
        return (json.parseToJsonElement(response.body()) as JsonObject)
            .getValue("token").jsonPrimitive.content
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$endpoint$path")).timeout(Duration.ofMinutes(2))

    private fun randomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://rs3-data.projectx.org"

        /** The ingest server's own name for a finished session; it owns this vocabulary. */
        private const val SEALED = "sealed"

        private const val MAX_ATTEMPTS = 10

        /** Exponential with full jitter, so a fleet coming back after an outage does not synchronise. */
        private fun backoffMs(attempt: Int): Long {
            val capped = minOf(BASE_BACKOFF_MS shl minOf(attempt, 10), MAX_BACKOFF_MS)
            return (Math.random() * capped).toLong().coerceAtLeast(BASE_BACKOFF_MS)
        }

        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 15 * 60 * 1000L

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Hex(value: String): String = sha256Hex(value.toByteArray())

        fun hmacHex(key: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
