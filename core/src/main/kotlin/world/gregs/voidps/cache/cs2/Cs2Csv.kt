package world.gregs.voidps.cache.cs2

import java.io.File

/** Minimal RFC 4180 reader for the RE exports the toolchain consumes. */
object Cs2Csv {

    fun read(file: File): List<Map<String, String>> {
        val rows = parse(file.readText())
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1)
            .filter { row -> row.any { it.isNotEmpty() } }
            .map { row -> header.indices.associate { header[it] to row.getOrElse(it) { "" } } }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var at = 0
        while (at < text.length) {
            val char = text[at]
            when {
                quoted && char == '"' && text.getOrNull(at + 1) == '"' -> {
                    field.append('"')
                    at++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && text.getOrNull(at + 1) == '\n') at++
                    row.add(field.toString().trim())
                    field.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(char)
            }
            at++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            rows.add(row)
        }
        return rows
    }
}
