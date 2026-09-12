package world.gregs.voidps.cache.source.codec

import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.source.SourceJson
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.CHUNK_CELLS
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.MAP_SQUARE_CELLS
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.WHOLE_MAP_SQUARE
import world.gregs.voidps.cache.type.data.WorldMapAreaElement
import world.gregs.voidps.cache.type.data.WorldMapAreaElementsType
import world.gregs.voidps.cache.type.data.WorldMapAreaType
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.ESCAPE
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.INLINE_SHIFT
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LAYERED
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LAYER_MASK
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LOCATIONS
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.OVERLAY
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.SIMPLE_EXTRA_ID

/**
 * A world map area's two files as editable JSON, and back.
 *
 * **A cell is a token and a row is a line.** An area is up to five million tiles, which cannot be
 * an object each, so a cell is transcribed as its flag byte in hex followed by every value that
 * flag selects, comma separated and in the order the file stores them - the flag alone says how
 * many follow, so the line is a transcription rather than a shape somebody has to re-derive:
 *
 * | flag bit | what follows |
 * |---|---|
 * | `0x01` clear | a simple cell; `0xFC` is its inline palette index, `0x3F` meaning the id follows |
 * | `0x02` | one further floor underlay id, simple cells only |
 * | `0x06` | one less than the layer count, layered cells only |
 * | `0x08` | a floor overlay id and one packed byte, per layer |
 * | `0x10` | a location count and that many `{location id, packed shape and rotation}` pairs, per layer |
 *
 * A whole map square is 64 rows of 64 cells and a chunk is 8 rows of 8, in the order the file
 * stores them. Writing is deterministic to the byte; reading ignores whitespace and key order.
 */
internal object WorldMapAreaJson {

    fun writeArea(area: WorldMapAreaType): ByteArray {
        val out = StringBuilder(area.blocks.sumOf { it.cells.size } * 4 + 256)
        out.append("{\n")
        out.append("  \"underlays\": ").append(SourceJson.array(area.underlays)).append(",\n")
        out.append("  \"overlays\": ").append(SourceJson.array(area.overlays)).append(",\n")
        out.append("  \"blocks\": [")
        for ((position, block) in area.blocks.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            writeBlock(out, block)
        }
        if (area.blocks.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readArea(id: Int, bytes: ByteArray): WorldMapAreaType {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val area = WorldMapAreaType(id)
        area.underlays = ints(root, "underlays")
        area.overlays = ints(root, "overlays")
        area.blocks = root.getValue("blocks").jsonArray.map { readBlock(it.jsonObject) }
        return area
    }

    fun writeElements(elements: WorldMapAreaElementsType): ByteArray {
        val out = StringBuilder(elements.elements.size * 56 + 64)
        out.append("{\n  \"elements\": [")
        for ((position, element) in elements.elements.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"coord\": ").append(element.coord)
            out.append(", \"mapElementId\": ").append(element.mapElementId)
            out.append(", \"flag\": ").append(element.flag).append('}')
        }
        if (elements.elements.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readElements(id: Int, bytes: ByteArray): WorldMapAreaElementsType {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        return WorldMapAreaElementsType(
            id,
            root.getValue("elements").jsonArray.map { element ->
                val record = element.jsonObject
                WorldMapAreaElement(
                    record.getValue("coord").jsonPrimitive.int,
                    record.getValue("mapElementId").jsonPrimitive.int,
                    record.getValue("flag").jsonPrimitive.int
                )
            }
        )
    }

    private fun writeBlock(out: StringBuilder, block: WorldMapAreaBlock) {
        out.append("    {\"tag\": ").append(block.tag)
        out.append(", \"mapSquareX\": ").append(block.mapSquareX)
        out.append(", \"mapSquareZ\": ").append(block.mapSquareZ)
        val mask = block.chunkMask
        if (mask != null) {
            out.append(", \"chunkMask\": ").append(SourceJson.array(mask))
        } else {
            out.append(", \"chunkX\": ").append(block.chunkX)
            out.append(", \"chunkZ\": ").append(block.chunkZ)
            out.append(", \"value\": ").append(block.value)
        }
        out.append(", \"cells\": [")
        writeCells(out, block.cells, width(block))
        out.append("\n    ]}")
    }

    private fun writeCells(out: StringBuilder, cells: IntArray, width: Int) {
        var position = 0
        var column = width
        while (position < cells.size) {
            if (column == width) {
                out.append(if (position == 0) "\n" else "\",\n").append("      \"")
                column = 0
            } else {
                out.append(' ')
            }
            val flags = cells[position++]
            out.append(HEX[flags shr 4]).append(HEX[flags and 0xf])
            repeat(values(flags, cells, position)) {
                out.append(',').append(cells[position++])
            }
            column++
        }
        if (position > 0) {
            out.append('"')
        }
    }

    private fun readBlock(record: JsonObject): WorldMapAreaBlock {
        val block = WorldMapAreaBlock(record.getValue("tag").jsonPrimitive.int)
        block.mapSquareX = record.getValue("mapSquareX").jsonPrimitive.int
        block.mapSquareZ = record.getValue("mapSquareZ").jsonPrimitive.int
        if (block.tag == WHOLE_MAP_SQUARE) {
            block.chunkMask = ints(record, "chunkMask")
        } else {
            block.chunkX = record.getValue("chunkX").jsonPrimitive.int
            block.chunkZ = record.getValue("chunkZ").jsonPrimitive.int
            block.value = record.getValue("value").jsonPrimitive.int
        }
        val cells = IntArrayList()
        for (row in record.getValue("cells").jsonArray) {
            readRow(cells, row.jsonPrimitive.content)
        }
        block.cells = cells.toIntArray()
        return block
    }

    private fun readRow(cells: IntArrayList, row: String) {
        var position = 0
        while (position < row.length) {
            if (row[position] == ' ') {
                position++
                continue
            }
            val end = row.indexOf(' ', position).let { if (it < 0) row.length else it }
            var start = position
            var separator = row.indexOf(',', start)
            if (separator < 0 || separator > end) {
                separator = end
            }
            cells.add(row.substring(start, separator).toInt(16))
            while (separator < end) {
                start = separator + 1
                separator = row.indexOf(',', start).let { if (it < 0 || it > end) end else it }
                cells.add(row.substring(start, separator).toInt())
            }
            position = end
        }
    }

    /** How many values the cell whose flag is [flags] carries, its location counts included. */
    private fun values(flags: Int, cells: IntArray, start: Int): Int {
        if (flags and LAYERED == 0) {
            var count = 0
            if (flags ushr INLINE_SHIFT == ESCAPE) count++
            if (flags and SIMPLE_EXTRA_ID != 0) count++
            return count
        }
        var position = start
        repeat(((flags shr 1) and LAYER_MASK) + 1) {
            position++
            if (flags and OVERLAY != 0) {
                position += 2
            }
            if (flags and LOCATIONS != 0) {
                position += 1 + cells[position] * 2
            }
        }
        return position - start
    }

    private fun width(block: WorldMapAreaBlock): Int =
        if (block.tag == WHOLE_MAP_SQUARE) MAP_SQUARE_ROW else CHUNK_ROW

    private fun ints(root: JsonObject, key: String): IntArray {
        val array = root.getValue(key).jsonArray
        return IntArray(array.size) { array[it].jsonPrimitive.int }
    }

    private const val MAP_SQUARE_ROW = MAP_SQUARE_CELLS / 64

    private const val CHUNK_ROW = CHUNK_CELLS / 8

    private val HEX = "0123456789abcdef".toCharArray()
}
