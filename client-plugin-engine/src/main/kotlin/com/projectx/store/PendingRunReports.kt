package com.projectx.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Run reports waiting for the site, one file each so several clients share the folder; claimed by rename so none is
 * sent twice. A client's unfinished runs sit in `open-<pid>.json` and become CLIENT_CLOSED reports once it has gone.
 */
internal class PendingRunReports(private val dir: Path) {
    class Claimed(val file: Path, val body: JsonObject)

    fun add(body: JsonObject): Path {
        Files.createDirectories(dir)
        val file = dir.resolve("$REPORT${UUID.randomUUID()}$PENDING")
        write(file, body.toString())
        return file
    }

    fun pending(): List<Path> = list().filter { it.name.startsWith(REPORT) && it.name.endsWith(PENDING) }

    fun claimAll(): List<Claimed> = pending().mapNotNull(::claim)

    fun claim(file: Path): Claimed? {
        val claimed = file.resolveSibling(file.name + SENDING)
        if (!move(file, claimed)) return null
        val body = runCatching { JsonParser.parseString(Files.readString(claimed)).asJsonObject }.getOrNull()
        if (body == null) {
            Files.deleteIfExists(claimed)
            return null
        }
        return Claimed(claimed, body)
    }

    fun done(claimed: Claimed) {
        Files.deleteIfExists(claimed.file)
    }

    fun giveBack(claimed: Claimed) {
        move(claimed.file, claimed.file.resolveSibling(claimed.file.name.removeSuffix(SENDING)))
    }

    fun recoverStaleClaims(now: Long) {
        list().filter { it.name.startsWith(REPORT) && it.name.endsWith(SENDING) && now - modified(it) > STALE_CLAIM.toMillis() }
            .forEach { move(it, it.resolveSibling(it.name.removeSuffix(SENDING))) }
    }

    fun checkpoint(pid: Long, runs: List<JsonObject>) {
        val file = openFile(pid)
        if (runs.isEmpty()) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(dir)
        write(file, JsonArray().also { array -> runs.forEach(array::add) }.toString())
    }

    /** This process's own runs are from an earlier engine load, so they count as abandoned too. */
    fun recoverOpenRuns(selfPid: Long, now: Long, alive: (Long) -> Boolean): Int {
        var recovered = 0
        for (file in list()) {
            val pid = OPEN.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val abandoned = pid == selfPid || !alive(pid) || now - modified(file) > STALE_OPEN.toMillis()
            if (!abandoned) continue
            val runs = runCatching { JsonParser.parseString(Files.readString(file)).asJsonArray }.getOrNull()
            runs?.forEach { run -> if (run.isJsonObject) add(run.asJsonObject).also { recovered++ } }
            Files.deleteIfExists(file)
        }
        return recovered
    }

    private fun openFile(pid: Long) = dir.resolve("open-$pid.json")

    private fun list(): List<Path> =
        if (Files.isDirectory(dir)) dir.listDirectoryEntries().filter { it.isRegularFile() } else emptyList()

    private fun modified(file: Path) = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)

    private fun write(file: Path, text: String) {
        val partial = file.resolveSibling(file.name + ".part")
        Files.writeString(partial, text)
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun move(from: Path, to: Path): Boolean = runCatching {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

    private companion object {
        const val REPORT = "run-"
        const val PENDING = ".json"
        const val SENDING = ".sending"
        val OPEN = Regex("""open-(\d+)\.json""")
        val STALE_CLAIM: Duration = Duration.ofMinutes(2)
        val STALE_OPEN: Duration = Duration.ofMinutes(5)
    }
}
