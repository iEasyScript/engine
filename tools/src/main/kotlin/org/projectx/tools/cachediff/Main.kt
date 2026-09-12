package org.projectx.tools.cachediff

import org.projectx.tools.util.IndexLabels
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """
Cache diff — what changed between two cache snapshots.

  --from <label|dir>   baseline snapshot
  --to <label|dir>     newer snapshot
  --root <dir>         snapshot root when labels are used   (default ./data/cache-unpacked)
  --only <sel>         restrict to indices (comma list and/or a-b ranges)
  --detail <n>         sample ids to print per bucket       (default 12)
  --out <file>         also write the report as markdown
  --help

  Decoded comparison (needs snapshots written by cacheUnpack):
  --decoded            also diff decoded definitions field by field
  --decoded-only       skip the archive-identity section
  --types <sel>        restrict the decoded section to these type names
  --changed-detail <n> changed ids given full field detail per type   (default 25)
  --field-detail <n>   fields shown per changed id                    (default 12)
  --value-chars <n>    characters shown per before/after value        (default 120)

Archive-level identity comes from each index's reference table, so indices with no definition
decoder are diffed just as precisely as decoded ones. Every cap above is stated in the report
wherever it bites, so a truncated section never reads as a complete one.
"""

private fun sample(ids: List<Int>, limit: Int): String {
    if (ids.isEmpty()) return ""
    val shown = ids.take(limit).joinToString(",")
    return if (ids.size > limit) "$shown … (+${ids.size - limit})" else shown
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(USAGE.trimIndent())
        if (args.isEmpty()) exitProcess(64)
        return
    }

    var from: String? = null
    var to: String? = null
    var root = "./data/cache-unpacked"
    var only: String? = null
    var detail = 12
    var out: String? = null
    var decoded = false
    var decodedOnly = false
    var typeSelector: String? = null
    var changedDetail = 25
    var fieldDetail = 12
    var valueChars = 120

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--from" -> from = args[++i]
            "--to" -> to = args[++i]
            "--root" -> root = args[++i]
            "--only" -> only = args[++i]
            "--detail" -> detail = args[++i].toInt()
            "--out" -> out = args[++i]
            "--decoded" -> decoded = true
            "--decoded-only" -> {
                decoded = true
                decodedOnly = true
            }
            "--types" -> typeSelector = args[++i]
            "--changed-detail" -> changedDetail = args[++i].toInt()
            "--field-detail" -> fieldDetail = args[++i].toInt()
            "--value-chars" -> valueChars = args[++i].toInt()
            else -> {
                System.err.println("Unknown arg: $a")
                exitProcess(64)
            }
        }
        i++
    }
    if (from == null || to == null) {
        System.err.println("--from and --to are required")
        exitProcess(64)
    }

    val rootPath = Paths.get(root).toAbsolutePath().normalize()
    fun resolve(label: String) = Paths.get(label).let { if (it.isAbsolute || it.exists()) it else rootPath.resolve(label) }
    val beforePath = resolve(from)
    val afterPath = resolve(to)
    val archives = !decodedOnly
    for (p in listOf(beforePath, afterPath)) {
        if (archives && !p.resolve("indexes").exists()) {
            System.err.println("Not a snapshot (no indexes/ dir): $p — pass --decoded-only for an unpack-only snapshot")
            exitProcess(1)
        }
        if (decoded && !p.resolve(DecodedDiff.TYPES).exists()) {
            System.err.println("Not an unpacked snapshot (no ${DecodedDiff.TYPES}/ dir): $p — run :tools:cacheUnpack first")
            exitProcess(1)
        }
    }

    val wanted = only?.let { sel ->
        val set = LinkedHashSet<Int>()
        for (part in sel.split(",")) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val dash = t.indexOf('-')
            if (dash > 0) {
                val a = t.substring(0, dash).trim().toInt()
                val b = t.substring(dash + 1).trim().toInt()
                for (v in minOf(a, b)..maxOf(a, b)) set.add(v)
            } else set.add(t.toInt())
        }
        set
    }

    val report = StringBuilder()
    fun line(s: String = "") {
        println(s)
        report.append(s).append('\n')
    }

    line("# Cache diff")
    line()
    line("`${beforePath.fileName}` → `${afterPath.fileName}`")

    if (archives) archiveSection(beforePath, afterPath, wanted, detail, ::line)
    if (decoded) {
        decodedSection(
            beforePath, afterPath,
            typeSelector?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet(),
            DecodedDiff.Limits(changedDetail, fieldDetail, valueChars, detail),
            ::line,
        )
    }

    if (out != null) {
        val target = Paths.get(out).toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        Files.writeString(target, report.toString())
        println()
        println("wrote $target")
    }
}

private fun archiveSection(
    beforePath: Path,
    afterPath: Path,
    wanted: Set<Int>?,
    detail: Int,
    line: (String) -> Unit,
) {
    val differ = SnapshotDiff(beforePath, afterPath)
    val deltas = differ.indexes().filter { wanted == null || it in wanted }.map { differ.diff(it) }
    val changed = deltas.filter { !it.quiet }

    line("")
    line("## Archive identity")
    line("")
    line("| idx | label | archives | added | removed | changed | ver-only |")
    line("|----:|-------|---------:|------:|--------:|--------:|---------:|")
    for (d in changed) {
        val archives = if (d.beforeArchives == d.afterArchives) "${d.afterArchives}" else "${d.beforeArchives} → ${d.afterArchives}"
        line(
            "| %d | %s | %s | %d | %d | %d | %d |".format(
                d.index, IndexLabels.label(d.index), archives,
                d.added.size, d.removed.size, d.changed.size, d.versionOnly.size,
            )
        )
    }
    line("")
    line(
        "**${changed.size} of ${deltas.size} indices changed** — " +
            "${changed.sumOf { it.added.size }} archives added, " +
            "${changed.sumOf { it.removed.size }} removed, " +
            "${changed.sumOf { it.changed.size }} modified."
    )

    for (d in changed) {
        line("")
        line("## index ${d.index} — ${IndexLabels.label(d.index)}")
        if (d.added.isNotEmpty()) line("- added: ${sample(d.added, detail)}")
        if (d.removed.isNotEmpty()) line("- removed: ${sample(d.removed, detail)}")
        if (d.changed.isNotEmpty()) line("- changed: ${sample(d.changed, detail)}")
        if (d.versionOnly.isNotEmpty()) line("- version-only (content identical): ${sample(d.versionOnly, detail)}")
        if (d.fileCountChanged.isNotEmpty()) {
            val what = if (d.index == Index.INTERFACES) "component count" else "file count"
            line("- **$what changed** (${d.fileCountChanged.size}):")
            for ((id, change) in d.fileCountChanged.entries.take(detail)) {
                val (before, after) = change
                val arrow = if (after > before) "+${after - before}" else "${after - before}"
                line("  - $id: $before → $after ($arrow)")
            }
        }
        if (d.index == Index.INTERFACES && d.changed.isNotEmpty()) {
            line("- ⚠ **gameval component slots may have moved.** A changed component count proves a shift,")
            line("  but is not required for one: an interface can keep its count and still reorder slots.")
            line("  Every one of the ${d.changed.size} changed interfaces needs the shape alignment to rule drift in or out —")
            line("  re-run `gamevalExport --align` and read its summary, do not trust counts alone.")
        }
    }
}

private fun decodedSection(
    beforePath: Path,
    afterPath: Path,
    wantedTypes: Set<String>?,
    limits: DecodedDiff.Limits,
    line: (String) -> Unit,
) {
    val differ = DecodedDiff(beforePath, afterPath)
    val types = differ.types().filter { wantedTypes == null || it in wantedTypes }
    val deltas = types.map { differ.diff(it, limits) }
    val changed = deltas.filter { !it.quiet }

    line("")
    line("## Decoded definitions")
    line("")
    if (changed.isEmpty()) {
        line("**No decoded differences** across ${deltas.size} type(s).")
        return
    }
    line("| type | records | added | removed | changed |")
    line("|------|--------:|------:|--------:|--------:|")
    for (d in changed) {
        val records = if (d.beforeRecords == d.afterRecords) "${d.afterRecords}" else "${d.beforeRecords} → ${d.afterRecords}"
        line("| %s | %s | %d | %d | %d |".format(d.type, records, d.addedTotal, d.removedTotal, d.changedTotal))
    }
    line("")
    line(
        "**${changed.size} of ${deltas.size} types changed** — " +
            "${changed.sumOf { it.addedTotal }} ids added, " +
            "${changed.sumOf { it.removedTotal }} removed, " +
            "${changed.sumOf { it.changedTotal }} modified."
    )
    for (d in deltas) {
        if (!d.presentBefore) line("- ⚠ type `${d.type}` is absent from the baseline snapshot — every id counts as added.")
        if (!d.presentAfter) line("- ⚠ type `${d.type}` is absent from the newer snapshot — every id counts as removed.")
    }

    for (d in changed) {
        line("")
        line("### ${d.type}")
        if (d.addedTotal > 0) line("- added (${d.addedTotal}): ${sample(d.added, limits.sampleIds, d.addedTotal)}")
        if (d.removedTotal > 0) line("- removed (${d.removedTotal}): ${sample(d.removed, limits.sampleIds, d.removedTotal)}")
        if (d.changedTotal == 0) continue
        line("- changed (${d.changedTotal}):")
        for (change in d.changes) {
            line("  - `${d.type} ${change.id}`")
            for (field in change.fields) line("    - ${field.field}: `${field.before}` → `${field.after}`")
            if (change.fieldsOmitted > 0) {
                line("    - … ${change.fieldsOmitted} further changed field(s) not shown (raise --field-detail)")
            }
        }
        if (d.changesOmitted > 0) {
            line("- … ${d.changesOmitted} further changed id(s) not detailed (raise --changed-detail)")
        }
    }
}

private fun sample(ids: List<Int>, limit: Int, total: Int): String {
    if (ids.isEmpty()) return ""
    val shown = ids.take(limit).joinToString(",")
    return if (total > ids.size || ids.size > limit) "$shown … (+${total - minOf(ids.size, limit)})" else shown
}
