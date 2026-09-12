package org.projectx.packetlog.agent

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.upload.PacketUploader
import org.projectx.packetlog.upload.RedactionPolicy
import org.projectx.packetlog.upload.UploadCredentials
import org.projectx.packetlog.upload.UploadService

/**
 * The capture agent: a separate process that finishes and uploads sessions the client can no longer
 * speak for.
 *
 * ⛔ This runs OUTSIDE the game client, and that is the entire point. Sealing a session used to be
 * the job of a JVM shutdown hook inside the injected engine, which cannot work: the client is killed
 * outright often enough that clean shutdown is the exception, and a process being killed cannot
 * report that it was killed. Neither can a signal handler or a held-open socket - SIGKILL takes both
 * with it.
 *
 * What survives every one of those is the kernel releasing the dead writer's file lock, which
 * [SessionFiles.abandoned] already reads. So the death signal is observed from here rather than
 * announced from there, and the client's exit path is no longer load-bearing for anything.
 *
 * The cycle itself is [UploadService]'s, unchanged - recover what is unowned, stream what is sealed,
 * complete what has ended. This adds only the things a daemon needs: exactly one of it, a status
 * file the engine UI can read, and an idle exit so it does not outlive its usefulness.
 */
object PacketAgent {

    /** Long enough to ride out a client restart between two play sessions. */
    private const val IDLE_EXIT_MS = 10 * 60 * 1000L

    private const val LOCK_NAME = "agent.lock"
    private const val STATUS_NAME = "agent-status.json"

    /** Written by the engine's sharing toggle; there is no other process to signal. */
    private const val STOP_NAME = "agent.stop"

    class Held(private val lock: FileLock, private val handle: RandomAccessFile) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }

    fun lockFile(): File = File(SessionFiles.root(), LOCK_NAME)

    fun statusFile(): File = File(SessionFiles.root(), STATUS_NAME)

    fun stopFile(): File = File(SessionFiles.root(), STOP_NAME)

    /**
     * Claims the right to be the only agent on this machine, or returns null if one is already
     * running. Spawning is therefore idempotent: the engine can ask for an agent on every injection
     * without checking, and a second one costs a lock attempt and an exit.
     */
    fun claim(): Held? {
        SessionFiles.root().mkdirs()
        val handle = RandomAccessFile(lockFile(), "rw")
        val lock = runCatching { handle.channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching { handle.close() }
            return null
        }
        return Held(lock, handle)
    }

    /**
     * One pass, then a verdict on whether there is any reason to still be here.
     *
     * A session file a live client still holds counts as a reason even when nothing was uploaded:
     * the client is mid-capture and this process is what will finish it.
     */
    fun runCycle(service: UploadService): Boolean {
        val sent = service.runOnce()
        val live = PacketSchema.ServerProfile.entries.sumOf { profile ->
            val directory = SessionFiles.directoryFor(profile)
            if (directory.isDirectory) SessionFiles.live(directory).size else 0
        }
        AgentStatus.write(statusFile(), service, live)
        return sent > 0 || live > 0
    }

    fun run(endpoint: String, policy: RedactionPolicy, once: Boolean, keepLocal: Boolean = false) {
        val held = claim()
        if (held == null) {
            println("[packetlog-agent] another agent already holds ${lockFile()}; nothing to do")
            return
        }
        stopFile().delete()
        held.use {
            val service = UploadService(endpoint, UploadCredentials::load, policy, deleteAfterUpload = !keepLocal)
            println("[packetlog-agent] watching ${SessionFiles.root()}, streaming to $endpoint")
            var lastBusyMs = System.currentTimeMillis()
            while (true) {
                if (runCatching { runCycle(service) }.getOrDefault(false)) {
                    lastBusyMs = System.currentTimeMillis()
                }
                if (once) break
                if (service.isBlocked) {
                    println("[packetlog-agent] refused by $endpoint: ${service.error}")
                    break
                }
                if (stopFile().exists()) {
                    println("[packetlog-agent] stop requested; outstanding work is flushed, exiting")
                    break
                }
                if (System.currentTimeMillis() - lastBusyMs > IDLE_EXIT_MS) {
                    println("[packetlog-agent] nothing to capture or send; exiting")
                    break
                }
                runCatching { Thread.sleep(UploadService.DEFAULT_INTERVAL_MS) }.onFailure { break }
            }
            statusFile().delete()
        }
    }
}

private const val USAGE = """
usage: agent [--endpoint <url>] [--policy prot-v1|prot-v1-strict] [--once] [--keep-local]

--keep-local  do not delete a session once it uploads; mark it instead so `packetlog purge` can
              remove it later. Without this flag a sealed session is deleted within one cycle.
"""

fun main(args: Array<String>) {
    if (args.contains("--help")) return println(USAGE.trim())
    val endpoint = args.value("--endpoint") ?: PacketUploader.DEFAULT_ENDPOINT
    val policyName = args.value("--policy") ?: RedactionPolicy.DEFAULT.version
    val policy = RedactionPolicy.byName(policyName) ?: return println("unknown policy: $policyName")
    PacketAgent.run(endpoint, policy, once = args.contains("--once"), keepLocal = args.contains("--keep-local"))
}

private fun Array<String>.value(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
