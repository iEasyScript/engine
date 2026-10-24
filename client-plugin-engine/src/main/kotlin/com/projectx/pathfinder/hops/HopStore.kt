package com.projectx.pathfinder.hops

import com.google.gson.JsonParser
import com.projectx.pathfinder.HopModel
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A machine-local hop store: `knowledge.jsonl`, a header line then one evidence line per key, which every client on
 * the machine folds its evidence into under a file lock; and `observations/<day>-<pid>.jsonl`, every hop as recorded,
 * append-only and one file per client per day so no lock is needed, kept [KEEP_DAYS] days.
 */
internal class HopStore(val dir: Path) {
    val knowledgeFile: Path get() = dir.resolve("knowledge.jsonl")
    private val lockFile: Path get() = dir.resolve("knowledge.jsonl.lock")
    private val observationsDir: Path get() = dir.resolve("observations")

    fun load(): MutableMap<String, HopEvidence> {
        val out = sortedMapOf<String, HopEvidence>()
        if (Files.isRegularFile(knowledgeFile)) readLines(Files.readAllLines(knowledgeFile, StandardCharsets.UTF_8), out)
        return out
    }

    /** Folds [deltas] into the file and returns what it now holds, or null when it could not be written. */
    @Synchronized
    fun flush(deltas: Map<String, HopEvidence>): MutableMap<String, HopEvidence>? = try {
        Files.createDirectories(dir)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val stored = load()
                for ((key, delta) in deltas) stored.merge(key, delta.copy()) { a, b -> a.also { it.add(b) } }
                write(stored)
                stored
            }
        }
    } catch (e: IOException) {
        println("[hops] could not write $knowledgeFile: $e")
        null
    }

    private fun write(evidence: Map<String, HopEvidence>) {
        val temp = dir.resolve("knowledge.jsonl.${ProcessHandle.current().pid()}.tmp")
        val lines = ArrayList<String>(evidence.size + 1)
        lines += """{"meta":"hops","model":${HopModel.VERSION}}"""
        for ((key, e) in evidence) lines += e.toJson(key, null)
        Files.write(temp, lines, StandardCharsets.UTF_8)
        Files.move(temp, knowledgeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun append(observation: String) {
        try {
            Files.createDirectories(observationsDir)
            val file = observationsDir.resolve("${LocalDate.now(ZoneOffset.UTC)}-${ProcessHandle.current().pid()}.jsonl")
            Files.writeString(file, observation + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            println("[hops] could not record an observation: $e")
        }
    }

    fun rotate() {
        val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS)
        if (!Files.isDirectory(observationsDir)) return
        Files.list(observationsDir).use { files ->
            files.filter { file ->
                val day = file.fileName.toString().take(10)
                runCatching { LocalDate.parse(day) }.getOrNull()?.isBefore(cutoff) == true
            }.forEach { runCatching { Files.delete(it) } }
        }
    }

    companion object {
        const val KEEP_DAYS = 30L

        fun today(millis: Long = System.currentTimeMillis()): Long =
            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

        fun readLines(lines: List<String>, into: MutableMap<String, HopEvidence>) {
            for (line in lines) {
                if (line.isBlank()) continue
                val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                val (key, evidence) = HopEvidence.fromJson(o) ?: continue
                into.merge(key, evidence) { a, b -> a.also { it.add(b) } }
            }
        }
    }
}
