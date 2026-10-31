package com.projectx.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.BuildInfo
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * Sends one script's log from the last day, and its settings, to the Project X team through the site's problem
 * reports, which DM the admins and the script's author on Discord. Bank PINs, passwords and tokens are masked before
 * anything leaves the machine. Blocking: call it off the game and render threads.
 */
internal object ProblemReports {
    const val HOURS = 24L

    private const val GAME = "rs3"
    private const val INLINE_MAX = 200_000
    // The site takes 12 MB of base64.
    private const val GZIP_MAX = 8 * 1024 * 1024
    private const val NO_NOTE = "Logs sent from the overlay without a description."
    private val SECRET_KEY = Regex("(?i)(password|passwd|token|secret|pin|webhook|apikey|api_key|auth|cookie|session)")
    private val SECRET_VALUE = Regex("[A-Za-z0-9_\\-]{40,}|discord(app)?\\.com/api/webhooks/\\S+")
    private val HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val logs = File(System.getProperty("user.home"), ".projectx/logs")
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }

    sealed interface Outcome {
        data class Sent(val id: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun send(meta: ScriptMetadata, note: String): Outcome {
        val token = Entitlements.token()
            ?: return Outcome.Failed("Sign in with Discord in the launcher's Store tab first, so we know who sent it.")
        val bundle = runCatching { bundle(meta) }.getOrNull()
        val body = JsonObject().apply {
            addProperty("note", note.trim().ifBlank { NO_NOTE })
            addProperty("scriptInternalName", meta.scriptClass.simpleName)
            addProperty("scriptName", meta.name)
            addProperty("scriptVersion", meta.version)
            addProperty("clientVersion", "RS3 engine ${BuildInfo.VERSION}")
            settings(meta)?.let { addProperty("settings", it) }
            if (bundle != null) {
                addProperty("log", bundle.text.takeLast(INLINE_MAX))
                addProperty("fullLogGzip", Base64.getEncoder().encodeToString(bundle.gzip))
                addProperty("fullLogFiles", bundle.files)
                addProperty("fullLogBytes", bundle.bytes)
            }
        }
        val request = HttpRequest.newBuilder(URI.create("${Entitlements.storeBase()}/api/v1/reports?game=$GAME"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrElse {
            return Outcome.Failed("Couldn't reach xclient.dev. Check your connection and try again.")
        }
        val reply = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()
        return when (response.statusCode()) {
            in 200..299 -> Outcome.Sent(reply?.get("id")?.asString ?: "")
            401 -> Outcome.Failed("Your sign-in has expired. Sign in again in the launcher's Store tab.")
            else -> Outcome.Failed(reply?.get("message")?.asString ?: "The site didn't take it (HTTP ${response.statusCode()}). Try again in a minute.")
        }
    }

    internal class Bundle(val text: String, val gzip: ByteArray, val files: Int, val bytes: Int)

    internal fun bundle(meta: ScriptMetadata, now: LocalDateTime = LocalDateTime.now()): Bundle {
        val since = now.minusHours(HOURS)
        val cutoff = System.currentTimeMillis() - Duration.ofHours(HOURS).toMillis()
        val sessions = logs.listFiles { f -> f.name.startsWith("projectx-") && f.name.endsWith(".log") && f.lastModified() >= cutoff }
            .orEmpty().sortedBy { it.name }
        val out = StringBuilder()
        out.append("${meta.name} v${meta.version} (${meta.scriptClass.simpleName}), RS3 engine ${BuildInfo.VERSION}, ")
            .append("${System.getProperty("os.name")}, log since ${since.format(HEADER_TIME)}\n")
        var files = 0
        for (session in sessions) {
            val lines = ScriptLogExcerpt.excerpt(session.readText().lines(), meta.name, meta.scriptClass.simpleName, since)
            if (lines.isEmpty()) continue
            files++
            out.append("\n===== ${session.name} =====\n")
            lines.forEach { out.append(it).append('\n') }
        }
        if (files == 0) out.append("\nNo lines from this script in the last $HOURS hours.\n")
        File(logs, "last-crash.txt").takeIf { it.isFile && it.lastModified() >= cutoff }?.let {
            out.append("\n===== last-crash.txt =====\n").append(it.readText())
        }

        var text = scrub(out.toString(), secretValues())
        var gzip = gzip(text)
        while (gzip.size > GZIP_MAX) {
            text = text.substring(text.length / 2)
            gzip = gzip(text)
        }
        return Bundle(text, gzip, files, text.toByteArray().size)
    }

    internal fun scrub(text: String, secrets: Collection<String>): String =
        secrets.sortedByDescending { it.length }.fold(text) { masked, secret -> masked.replace(secret, "[hidden]") }
            .replace(SECRET_VALUE, "[redacted]")

    private fun settings(meta: ScriptMetadata): String? = savedSettings(meta.scriptClass.name)
        ?.joinToString("\n") { (key, value) -> "$key = ${if (SECRET_KEY.containsMatchIn(key)) "[hidden]" else value}" }

    // Every secret any script has saved, so a PIN a script printed is masked wherever it shows up.
    private fun secretValues(): Set<String> = ScriptConfigStore.directory.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .flatMap { savedSettings(it.name.removeSuffix(".json")).orEmpty() }
        .filter { (key, value) -> SECRET_KEY.containsMatchIn(key) && value.length >= 4 }
        .map { it.second }
        .toSet()

    private fun savedSettings(scriptClass: String): List<Pair<String, String>>? = runCatching {
        val file = File(ScriptConfigStore.directory, "$scriptClass.json").takeIf { it.isFile } ?: return null
        JsonParser.parseString(file.readText()).asJsonObject.entrySet()
            .filter { it.value.isJsonPrimitive }
            .map { it.key to it.value.asString }
    }.getOrNull()

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
    }.toByteArray()
}
