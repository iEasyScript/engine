package org.projectx.packetlog.query

import java.io.File
import org.projectx.core.net.prot.ProtRevisions
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketRecovery
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.upload.ArchiveReader

private const val USAGE = """
usage:
  recover  <session.db|dir>                        finish sessions a killed client left open
  purge    <dir> [--older-than Nd]                 delete sessions already confirmed uploaded
  index    <query.db> <archive.db>...              build or refresh the investigation index
  sessions <query.db>
  fields   <query.db> [--prot NAME] [--ref DOMAIN] what is queryable
  coverage <query.db>                              per-prot decode coverage
  conform  <session.db>  [--all]                   check our codecs against real captured bytes
  bytes    <session.db>  [--prot A,B] [--limit N]  captured bodies as hex, in arrival order
  find     <query.db> --path P [--value V] [--prot NAME] [--session N] [--limit N]
  text     <query.db> <query> [--prot NAME]
  window   <query.db> --session N --from T --to T [--prot NAME]
  around   <query.db> --path P --value V [--before N] [--after N] [--prot NAME]
  timeline <query.db> [--session N]                what the player was doing, and when
  invs     <query.db> [--session N]                containers this session described
  inv      <query.db> --inv N --tick T [--session N]   a container's contents at a tick
"""

fun main(args: Array<String>) {
    if (args.size < 2) return println(USAGE.trim())
    val database = File(args[1])

    when (args[0]) {
        "recover" -> {
            val targets = if (database.isDirectory) SessionFiles.abandoned(database) else listOf(database)
            if (targets.isEmpty()) return println("nothing to recover (a live client still owns them)")
            for (target in targets) {
                val result = PacketRecovery.recover(target)
                println("${target.name}: closed ${result.sessions} session(s), re-sealed ${result.resealed} packets")
            }
        }

        "purge" -> {
            val olderThanMs = args.option("--older-than")?.let { parseDays(it) }
            val result = SessionFiles.purgeUploaded(database, olderThanMs)
            println(
                "purged ${result.purged} session(s), freed ${result.bytesFreed / 1_000_000.0} MB" +
                    if (result.stillLive > 0) " (${result.stillLive} still owned by a live client, skipped)" else ""
            )
        }

        "index" -> {
            val archives = args.drop(2).filterNot { it.startsWith("--") }.map(::File).flatMap { path ->
                if (path.isDirectory) path.walkTopDown().filter { it.isFile && it.name.endsWith(".db") }.toList()
                else listOf(path)
            }
            if (archives.isEmpty()) return println("index needs at least one archive")
            val started = System.nanoTime()
            val report = QueryBuilder(database).use { it.build(archives) }
            val seconds = (System.nanoTime() - started) / 1e9
            println(
                "indexed %d sessions, %d events (%d decoded, %d without a decoder, %d failed), %d indexed values in %.1fs"
                    .format(report.sessions, report.events, report.decoded, report.noDecoder,
                        report.failed, report.indexedValues, seconds)
            )
            if (report.missingCodecs.isNotEmpty()) {
                println("  skipped sessions for revisions with no codec in this build: ${report.missingCodecs}")
            }
            println("  ${database.length() / 1_000_000.0} MB at ${database.absolutePath}")
        }

        "sessions" -> PacketQuery(database).use { it.sessions().forEach(::println) }

        "timeline" -> SessionTools(database).use { tools ->
            val session = args.option("--session")?.toLongOrNull() ?: 1L
            for (moment in tools.timeline(session)) {
                println("%7d  %-10s %s".format(moment.tick, moment.kind, moment.detail))
            }
        }

        "invs" -> SessionTools(database).use { tools ->
            tools.containers(args.option("--session")?.toLongOrNull() ?: 1L).forEach(::println)
        }

        "inv" -> SessionTools(database).use { tools ->
            val container = args.option("--inv")?.toIntOrNull()
                ?: return println("inv needs --inv <container id>")
            val tick = args.option("--tick")?.toIntOrNull() ?: Int.MAX_VALUE
            val slots = tools.containerAt(args.option("--session")?.toLongOrNull() ?: 1L, container, tick)
            if (slots.isEmpty()) return println("no snapshot of container $container at or before tick $tick")
            println("${slots.size} occupied slot(s)")
            slots.forEach { println("  " + it.render()) }
        }

        "conform" -> {
            val codec = ProtRevisions.currentCodec()
            val reports = CodecConformance(codec).check(database)
            val showAll = args.contains("--all")

            println("== decoders exercised against real bytes ==")
            println("%-32s %7s %7s %7s %8s %s".format("prot", "seen", "ok", "threw", "trailing", "round-trip"))
            for (report in reports.filter { it.hasDecoder }) {
                if (!showAll && report.threw == 0 && report.trailing == 0 && report.roundTripFailed == 0) continue
                val roundTrip = when {
                    report.roundTripped + report.roundTripFailed == 0 -> "-"
                    report.roundTripFailed == 0 -> "${report.roundTripped} exact"
                    else -> "${report.roundTripFailed} MISMATCH"
                }
                println("%-32s %7d %7d %7d %8d %s".format(
                    report.name, report.seen, report.decoded, report.threw, report.trailing, roundTrip))
                report.sampleMismatch?.let { println("    $it") }
            }
            if (!showAll) println("(clean decoders hidden; --all to show them)")

            println()
            println("== no decoder: what the capture can still teach us ==")
            println("%-32s %7s  %s".format("prot", "seen", "payload lengths"))
            for (report in reports.filter { it.opaque }.take(20)) {
                val lengths = report.lengths.sorted()
                val shape = if (lengths.size == 1) "fixed ${lengths.single()}"
                else "${lengths.size} sizes ${lengths.first()}..${lengths.last()}"
                println("%-32s %7d  %s".format(report.name, report.seen, shape))
            }
        }

        // What conform deliberately omits: the bodies themselves, in arrival order, so a decoder
        // that throws can be worked out against the bytes that broke it.
        "bytes" -> {
            val codec = ProtRevisions.currentCodec()
            val wanted = args.option("--prot")?.split(',')?.toSet()
            val limit = args.option("--limit")?.toIntOrNull() ?: 20
            var shown = 0
            ArchiveReader(database).use { reader ->
                for (session in reader.sessions()) {
                    for (chunk in reader.chunks(session.id)) {
                        for (event in ChunkFrame.open(chunk.frame, chunk.firstSeq)) {
                            val name = if (event.dir == 0) codec.serverProtName(event.opcode)
                            else codec.clientProtName(event.opcode)
                            if (wanted != null && name !in wanted) continue
                            if (shown++ >= limit) return
                            println("%-32s %s op=%-3d len=%3d  %s".format(
                                name, if (event.dir == 0) "S>" else "C>", event.opcode,
                                event.body.size, event.body.joinToString(" ") { "%02x".format(it) }))
                        }
                    }
                }
            }
        }

        "coverage" -> PacketQuery(database).use { it.coverage().forEach(::println) }

        "fields" -> PacketQuery(database).use { query ->
            query.fields(args.option("--prot"), args.option("--ref")).forEach {
                println("%-32s %-24s %-12s %s".format(it.protName, it.path, it.refDomain,
                    if (it.indexed) "indexed" else ""))
            }
        }

        "find" -> PacketQuery(database).use { query ->
            val events = query.find(
                path = args.option("--path"),
                value = args.option("--value")?.toLongOrNull(),
                prot = args.option("--prot"),
                sessionId = args.option("--session")?.toLongOrNull(),
                limit = args.option("--limit")?.toIntOrNull() ?: 50,
            )
            println("${events.size} match(es)")
            events.forEach { println("  session=${it.sessionId} seq=${it.seq} $it") }
        }

        "text" -> PacketQuery(database).use { query ->
            val events = query.findText(args[2], args.option("--prot"))
            println("${events.size} match(es)")
            events.forEach { println("  session=${it.sessionId} $it") }
        }

        "window" -> PacketQuery(database).use { query ->
            val events = query.window(
                sessionId = args.option("--session")?.toLongOrNull() ?: 1L,
                fromTick = args.option("--from")?.toIntOrNull() ?: 0,
                toTick = args.option("--to")?.toIntOrNull() ?: 0,
                prots = args.option("--prot")?.split(',')?.toSet() ?: emptySet(),
            )
            events.forEach(::println)
        }

        "around" -> PacketQuery(database).use { query ->
            val anchors = query.find(
                path = args.option("--path"),
                value = args.option("--value")?.toLongOrNull(),
                prot = args.option("--prot"),
                limit = 1,
            )
            val anchor = anchors.firstOrNull() ?: return println("no event matched that anchor")
            val investigation = query.investigate(
                anchor,
                ticksBefore = args.option("--before")?.toIntOrNull() ?: 0,
                ticksAfter = args.option("--after")?.toIntOrNull() ?: 3,
            )
            println("anchor: $anchor")
            for (event in investigation.before) println("  -   $event")
            println("  >>> ${investigation.anchor}")
            for (event in investigation.after) println("  +   $event")
        }

        else -> println(USAGE.trim())
    }
}

private fun Array<String>.option(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

private fun parseDays(raw: String): Long =
    raw.removeSuffix("d").toLong() * 24 * 60 * 60 * 1000
