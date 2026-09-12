package org.projectx.packetlog.upload

import java.io.File
import org.projectx.packetlog.agent.PacketAgent

private const val USAGE = """
usage:
  enrol  [--endpoint <url>]                register this machine (also happens automatically)
  upload <archive.db|dir> [--endpoint <url>] [--policy prot-v1|prot-v1-strict] [--dry-run] [--stream]
  agent  [--endpoint <url>] [--policy prot-v1|prot-v1-strict] [--once]
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE.trim())
        return
    }
    val endpoint = args.optionValue("--endpoint") ?: PacketUploader.DEFAULT_ENDPOINT
    when (args[0]) {
        "enrol", "enroll" -> enrol(endpoint)
        "upload" -> upload(args, endpoint)
        "agent" -> agent(args, endpoint)
        else -> println(USAGE.trim())
    }
}

/**
 * Registration is automatic, so this exists only to do it now rather than on the next upload cycle
 * - and it goes through the same path, because a second way to register is a second thing to keep
 * in step with the server.
 */
private fun enrol(endpoint: String) {
    when (val result = AutoEnrolment.ensure(endpoint)) {
        is AutoEnrolment.Result.Ready ->
            println("registered with $endpoint; credential in ${UploadCredentials.defaultFile()}")
        is AutoEnrolment.Result.Blocked -> println("refused by $endpoint: ${result.reason}")
        is AutoEnrolment.Result.Deferred -> println("could not register yet: ${result.reason}")
    }
}

/**
 * Runs the capture agent in the foreground. The engine spawns this same entry point detached; this
 * is how to watch it work.
 */
private fun agent(args: Array<String>, endpoint: String) {
    val policy = policyOf(args) ?: return
    PacketAgent.run(endpoint, policy, once = args.contains("--once"))
}

private fun upload(args: Array<String>, endpoint: String) {
    val target = args.getOrNull(1)?.let(::File) ?: return println("upload needs an archive or directory")
    if (!target.exists()) return println("no such path: $target")
    val archives = if (target.isDirectory) {
        target.walkTopDown().filter { it.isFile && it.name.endsWith(".db") }.toList()
    } else {
        listOf(target)
    }
    if (archives.isEmpty()) return println("no session databases under $target")

    val policy = policyOf(args) ?: return
    val dryRun = args.contains("--dry-run")

    val uploader = PacketUploader(endpoint, UploadCredentials.load(), policy)
    val streaming = args.contains("--stream")
    var sessions = 0
    var sent = 0
    var held = 0
    var bytes = 0L
    var redacted = 0
    val skipped = ArrayList<String>()
    for (archive in archives) {
        val report = if (streaming) uploader.stream(archive) else uploader.upload(archive, dryRun)
        sessions += report.sessions
        sent += report.chunksSent
        held += report.chunksAlreadyHeld
        bytes += report.bytesSent
        redacted += report.redactedChunks
        skipped += report.skipped
    }
    println(
        buildString {
            append(if (dryRun) "would upload" else "uploaded")
            append(" $sent chunks (${"%.2f".format(bytes / 1e6)} MB)")
            append(" from $sessions session(s) to $endpoint")
            append("; $held already held")
            append("; $redacted chunks redacted under ${policy.version}")
        }
    )
    for (skip in skipped) println("  skipped $skip")
}

private fun policyOf(args: Array<String>): RedactionPolicy? {
    val name = args.optionValue("--policy") ?: RedactionPolicy.DEFAULT.version
    return RedactionPolicy.byName(name) ?: null.also { println("unknown policy: $name") }
}

private fun Array<String>.optionValue(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

