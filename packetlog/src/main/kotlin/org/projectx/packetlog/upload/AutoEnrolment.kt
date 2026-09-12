package org.projectx.packetlog.upload

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Registers this machine with the ingest server the first time it has something to send.
 *
 * Registration is open and automatic: a development corpus only accumulates if every machine that
 * captures also uploads, and anything a person has to remember to run is a thing that does not
 * happen. The credential exists to give an operator something to revoke - not to gate who may
 * contribute.
 *
 * Several clients can start at once on one machine and they share one credential file, so
 * registration is done under a file lock and the file is re-read after acquiring it. Otherwise two
 * clients racing at login would mint two credentials and one would overwrite the other.
 */
object AutoEnrolment {

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        class Ready(val credentials: UploadCredentials) : Result

        /** The server refused this machine. Remembered, so it does not simply register again. */
        class Blocked(val reason: String) : Result

        /** Try again later - the server is down, or does not support open registration yet. */
        class Deferred(val reason: String) : Result
    }

    fun ensure(
        endpoint: String,
        file: File = UploadCredentials.defaultFile(),
        client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    ): Result {
        val existing = UploadCredentials.load(file)
        if (existing.blocked) return Result.Blocked("this machine was refused by $endpoint")
        if (existing.enrolled) return Result.Ready(existing)

        file.parentFile?.mkdirs()
        val lockFile = File(file.parentFile, "${file.name}.lock")
        RandomAccessFile(lockFile, "rw").use { handle ->
            val lock = runCatching { handle.channel.lock() }.getOrNull()
                ?: return Result.Deferred("another client is registering")
            try {
                // Another client may have finished while this one waited for the lock.
                val current = UploadCredentials.load(file)
                if (current.blocked) return Result.Blocked("this machine was refused by $endpoint")
                if (current.enrolled) return Result.Ready(current)
                return register(endpoint, current, file, client)
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    private fun register(
        endpoint: String,
        current: UploadCredentials,
        file: File,
        client: HttpClient,
    ): Result {
        val body = buildJsonObject {
            put("deviceName", deviceName())
            put("installId", current.installId)
        }
        val response = runCatching {
            client.send(
                HttpRequest.newBuilder(URI.create("$endpoint/v1/enroll"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrElse { return Result.Deferred("could not reach $endpoint: ${it.message}") }

        return when (response.statusCode()) {
            in 200..299 -> {
                val parsed = runCatching { json.parseToJsonElement(response.body()) as JsonObject }
                    .getOrElse { return Result.Deferred("unreadable registration reply") }
                val deviceId = parsed["deviceId"]?.jsonPrimitive?.content
                val secret = parsed["deviceSecret"]?.jsonPrimitive?.content
                if (deviceId.isNullOrBlank() || secret.isNullOrBlank()) {
                    return Result.Deferred("registration reply had no credential")
                }
                val updated = UploadCredentials.copyOf(current, deviceId = deviceId, deviceSecret = secret)
                UploadCredentials.save(updated, file)
                Result.Ready(updated)
            }

            // Refused on purpose. Remember it rather than registering again on the next cycle.
            403, 410 -> {
                UploadCredentials.save(UploadCredentials.copyOf(current, blocked = true), file)
                Result.Blocked(response.body().take(200))
            }

            else -> Result.Deferred("registration returned ${response.statusCode()}: ${response.body().take(200)}")
        }
    }

    /** Recognisable to an operator looking at a list of devices, and not personally identifying. */
    private fun deviceName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
        val platform = System.getProperty("os.name")?.lowercase()?.replace(' ', '-') ?: "unknown"
        return "$host/$platform"
    }
}
