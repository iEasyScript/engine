package org.projectx.tools.decodecheck

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DecodeReport
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.cache.type.data.PalettedGraphic
import world.gregs.voidps.cache.type.data.RawGraphic
import world.gregs.voidps.cache.type.data.GraphicType
import world.gregs.voidps.cache.type.decoder.QuickChatCatDecoder
import world.gregs.voidps.cache.type.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.type.decoder.GraphicDecoder
import java.nio.file.Paths

private const val SAMPLE_GRAPHIC = 13213

private fun DecodeReport.clean(type: String) = decoded(type) - trailing(type).size - failures(type).size

private fun graphics(cache: Cache, report: DecodeReport) {
    val decoder = GraphicDecoder()
    decoder.report = report
    var paletted = 0
    var raw = 0
    var frames = 0
    var outOfBounds = 0
    for (archive in cache.archives(Index.GRAPHICS)) {
        val data = cache.data(Index.GRAPHICS, archive, 0) ?: continue
        val definition = GraphicType(archive)
        decoder.readLoop(definition, BufferReader(data))
        val decoded = definition.frames ?: continue
        if (decoded.isEmpty()) continue
        if (decoded.first() is RawGraphic) raw++ else paletted++
        for (frame in decoded) {
            frames++
            if (frame.offsetX + frame.width > definition.maxWidth || frame.offsetY + frame.height > definition.maxHeight) {
                outOfBounds++
            }
        }
    }
    val name = "GraphicDecoder"
    println("$name: ${report.clean(name)}/${report.decoded(name)} groups end-of-buffer, $paletted paletted, $raw raw, $frames frames, $outOfBounds out of canvas bounds")
    for (record in report.records.filter { it.type == name }.take(10)) {
        println("  $record")
    }
}

private fun quickChat(cache: Cache, report: DecodeReport) {
    val categories = QuickChatCatDecoder()
    categories.report = report
    categories.load(cache)

    val phrases = QuickChatPhraseDecoder()
    phrases.report = report
    val decoded = phrases.load(cache)

    for (name in listOf("QuickChatCatDecoder", "QuickChatPhraseDecoder")) {
        println("$name: ${report.clean(name)}/${report.decoded(name)} end-of-buffer, ${report.unknowns(name).size} unknown opcodes")
        for (record in report.failures(name).take(10)) {
            println("  $record")
        }
    }

    var mismatched = 0
    var parameterised = 0
    for (phrase in decoded) {
        val parts = phrase.stringParts ?: continue
        val params = phrase.types?.size ?: 0
        if (params > 0) parameterised++
        if (parts.size != params + 1) mismatched++
    }
    println("stringParts == paramCount + 1: ${decoded.size - mismatched}/${decoded.size} ($parameterised parameterised)")
    for (id in intArrayOf(1712, 1714)) {
        val phrase = decoded[id]
        println("  phrase $id ${phrase.stringParts?.toList()} responses=${phrase.responses?.toList()} flag=${phrase.flag}")
    }
}

private fun sampleGraphic(cache: Cache) {
    val data = cache.data(Index.GRAPHICS, SAMPLE_GRAPHIC, 0) ?: return
    val definition = GraphicType(SAMPLE_GRAPHIC)
    GraphicDecoder().readLoop(definition, BufferReader(data))
    println("graphic $SAMPLE_GRAPHIC ${definition.maxWidth}x${definition.maxHeight} frames=${definition.frames?.size}")
    for (frame in definition.frames.orEmpty().take(3)) {
        val layout = if (frame is PalettedGraphic) "paletted palette=${frame.palette.size} alpha=${frame.alpha != null}" else "raw"
        println("  ${frame.width}x${frame.height} at ${frame.offsetX},${frame.offsetY} $layout")
    }
}

fun main(args: Array<String>) {
    val path = Paths.get(args.firstOrNull() ?: "./data/cache").toAbsolutePath().normalize()
    val cache = SQLiteCache.load(path, readOnly = true)
    val report = DecodeReport()
    graphics(cache, report)
    quickChat(cache, report)
    sampleGraphic(cache)
    cache.close()
}
