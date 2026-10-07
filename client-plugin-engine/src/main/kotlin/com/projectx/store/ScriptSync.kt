package com.projectx.store

import com.projectx.script.ScriptExecutor
import com.projectx.ui.compose.GameThread
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

internal data class StoreJar(
    val internalName: String,
    val version: String,
    val url: String,
    val sha256: String?,
    val fileName: String?,
)

/** Puts the current build of every paid script the account owns into the scripts folder. */
internal object ScriptSync {

    // Must match the launcher's naming, so whichever of the two gets there first, the other sees it done.
    fun jarName(internalName: String, version: String): String =
        "${safe(internalName)}-${safe(version).trimStart('.')}.jar"

    private fun safe(text: String): String =
        text.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._+-") it else '-' }.joinToString("")

    // A version starts with a digit, so `Agility-` does not claim `Agility-Course-1.0.jar`.
    fun isBuildOf(file: String, jar: StoreJar): Boolean {
        if (!file.endsWith(".jar")) return false
        if (jar.fileName != null && file == jar.fileName) return true
        val rest = file.removePrefix("${jar.internalName}-")
        return rest != file && rest.firstOrNull()?.isDigit() == true
    }

    fun missing(owned: Set<String>, catalogue: List<StoreJar>, present: Set<String>): List<StoreJar> =
        catalogue.filter { it.internalName in owned && jarName(it.internalName, it.version) !in present }

    fun sync(
        owned: Set<String>,
        catalogue: List<StoreJar>,
        token: String,
        http: HttpClient,
        dir: Path,
    ): List<StoreJar> {
        Files.createDirectories(dir)
        val present = listed(dir)
        val installed = missing(owned, catalogue, present).filter { install(it, token, http, dir, present) }

        // Also catches a build the launcher installed: on disk, owned, but not yet in the script list.
        val onDisk = ownedOnDisk(owned, catalogue, listed(dir))
        GameThread.post {
            val registered = ScriptExecutor.scripts.values.mapTo(HashSet()) { it.scriptClass.simpleName }
            // A rescan only rebuilds the list of available scripts; running ones are untouched.
            if (needsRescan(onDisk, registered)) ScriptExecutor.loadScripts()
        }
        return installed
    }

    fun ownedOnDisk(owned: Set<String>, catalogue: List<StoreJar>, present: Set<String>): Set<String> =
        catalogue
            .filter { it.internalName in owned && jarName(it.internalName, it.version) in present }
            .mapTo(HashSet()) { it.internalName }

    fun needsRescan(ownedOnDisk: Set<String>, registered: Set<String>): Boolean = !registered.containsAll(ownedOnDisk)

    private fun listed(dir: Path): Set<String> =
        Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun install(jar: StoreJar, token: String, http: HttpClient, dir: Path, present: Set<String>): Boolean =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(jar.url))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                println("[store] could not download ${jar.internalName} ${jar.version} (${response.statusCode()}).")
                return false
            }
            val bytes = response.body()
            if (jar.sha256 != null && !jar.sha256.equals(hex(bytes), ignoreCase = true)) {
                println("[store] ${jar.internalName} ${jar.version} did not match its published checksum; not installed.")
                return false
            }

            val name = jarName(jar.internalName, jar.version)
            val temp = dir.resolve(".$name.engine.part")
            Files.write(temp, bytes)
            Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            // Two jars for one script put every class on the scan path twice.
            present.filter { it != name && isBuildOf(it, jar) }.forEach { stale ->
                runCatching { Files.deleteIfExists(dir.resolve(stale)) }
            }
            println("[store] installed ${jar.internalName} ${jar.version}; it is in your script list.")
            true
        }.getOrElse {
            println("[store] could not install ${jar.internalName} ${jar.version}: ${it.message}")
            false
        }

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
