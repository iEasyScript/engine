package world.gregs.voidps.cache.source.codec.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The glTF 2.0 binary container.
 *
 * ```
 * glb
 *   header      "glTF", version 2, total length
 *   JSON chunk  the glTF asset, compact, fixed key order, padded with spaces
 *   BIN chunk   the accessors' bytes, little endian, padded with zeroes
 * ```
 *
 * A file written through here is deterministic to the byte: the JSON is built by hand in a fixed
 * key order with no whitespace, the chunks are padded the way the specification requires, and
 * [GENERATOR] is a constant, so re-unpacking an unchanged cache rewrites nothing.
 */
internal object Glb {

    /** Fixed, because a generator string that moved would rewrite every `.glb` in the tree. */
    const val GENERATOR = "projectx cache-source"

    const val BYTE = 5120
    const val UNSIGNED_BYTE = 5121
    const val SHORT = 5122
    const val UNSIGNED_SHORT = 5123
    const val UNSIGNED_INT = 5125
    const val FLOAT = 5126

    const val ARRAY_BUFFER = 34962
    const val ELEMENT_ARRAY_BUFFER = 34963

    const val POINTS = 0
    const val TRIANGLES = 4

    private const val MAGIC = 0x46546C67

    private const val VERSION = 2

    private const val CHUNK_JSON = 0x4E4F534A

    private const val CHUNK_BIN = 0x004E4942

    private const val HEADER_BYTES = 12

    private const val CHUNK_HEADER_BYTES = 8

    private const val SPACE: Byte = 0x20

    private const val ZERO: Byte = 0

    private val parser = Json { ignoreUnknownKeys = true }

    fun assemble(asset: String, binary: ByteArray): ByteArray {
        val text = asset.toByteArray(Charsets.UTF_8)
        val jsonPadding = padding(text.size)
        val binaryPadding = if (binary.isEmpty()) 0 else padding(binary.size)
        var length = HEADER_BYTES + CHUNK_HEADER_BYTES + text.size + jsonPadding
        if (binary.isNotEmpty()) {
            length += CHUNK_HEADER_BYTES + binary.size + binaryPadding
        }
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        out.putInt(length)
        out.putInt(text.size + jsonPadding)
        out.putInt(CHUNK_JSON)
        out.put(text)
        repeat(jsonPadding) { out.put(SPACE) }
        if (binary.isNotEmpty()) {
            out.putInt(binary.size + binaryPadding)
            out.putInt(CHUNK_BIN)
            out.put(binary)
            repeat(binaryPadding) { out.put(ZERO) }
        }
        return out.array()
    }

    fun padding(size: Int): Int = (4 - (size and 3)) and 3

    /** The JSON chunk and the binary chunk, which is empty when the file has none. */
    fun chunks(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES && buffer.getInt(0) == MAGIC) { "Not a glb: bad magic." }
        require(buffer.getInt(4) == VERSION) { "glb version ${buffer.getInt(4)}, expected $VERSION." }
        var offset = HEADER_BYTES
        var text: ByteArray? = null
        var binary = ByteArray(0)
        val end = minOf(buffer.getInt(8), bytes.size)
        while (offset + CHUNK_HEADER_BYTES <= end) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + CHUNK_HEADER_BYTES
            require(start + length <= bytes.size) { "glb chunk at $offset runs past the file." }
            when (type) {
                CHUNK_JSON -> text = bytes.copyOfRange(start, start + length)
                CHUNK_BIN -> binary = bytes.copyOfRange(start, start + length)
            }
            offset = start + length
        }
        return (text ?: throw IllegalArgumentException("glb has no JSON chunk.")) to binary
    }

    fun root(text: ByteArray): JsonObject = parser.parseToJsonElement(String(text, Charsets.UTF_8)).jsonObject

    /** The `.glb`'s JSON chunk as text, for a report or a test that shows what a file says. */
    fun text(bytes: ByteArray): String = String(chunks(bytes).first, Charsets.UTF_8)

    /**
     * One accessor's values as the integers the file stores, [components] per element.
     *
     * Read through the buffer view's own offset and stride rather than this writer's layout, and
     * signed exactly as the component type is, so what the cache held comes back as itself.
     */
    fun integers(root: JsonObject, binary: ByteArray, index: Int, components: Int): IntArray {
        val reader = reader(root, binary, index, components)
        val values = IntArray(reader.count * components)
        reader.each { element, part, offset ->
            values[element * components + part] = when (reader.componentType) {
                BYTE -> reader.buffer.get(offset).toInt()
                UNSIGNED_BYTE -> reader.buffer.get(offset).toInt() and 0xff
                SHORT -> reader.buffer.getShort(offset).toInt()
                UNSIGNED_SHORT -> reader.buffer.getShort(offset).toInt() and 0xffff
                UNSIGNED_INT -> reader.buffer.getInt(offset)
                FLOAT -> Math.round(reader.buffer.getFloat(offset))
                else -> throw IllegalArgumentException("Unknown glTF component type ${reader.componentType}.")
            }
        }
        return values
    }

    /** One accessor's values as floats; a stored `FLOAT` keeps its exact bits, NaN payload and all. */
    fun floats(root: JsonObject, binary: ByteArray, index: Int, components: Int): FloatArray {
        val reader = reader(root, binary, index, components)
        val values = FloatArray(reader.count * components)
        reader.each { element, part, offset ->
            values[element * components + part] = when (reader.componentType) {
                FLOAT -> reader.buffer.getFloat(offset)
                BYTE -> reader.buffer.get(offset).toFloat()
                UNSIGNED_BYTE -> (reader.buffer.get(offset).toInt() and 0xff).toFloat()
                SHORT -> reader.buffer.getShort(offset).toFloat()
                UNSIGNED_SHORT -> (reader.buffer.getShort(offset).toInt() and 0xffff).toFloat()
                UNSIGNED_INT -> reader.buffer.getInt(offset).toFloat()
                else -> throw IllegalArgumentException("Unknown glTF component type ${reader.componentType}.")
            }
        }
        return values
    }

    private class AccessorReader(
        val buffer: ByteBuffer,
        val componentType: Int,
        val count: Int,
        val components: Int,
        val start: Int,
        val stride: Int,
        val size: Int
    ) {
        inline fun each(read: (element: Int, part: Int, offset: Int) -> Unit) {
            for (element in 0 until count) {
                val offset = start + element * stride
                for (part in 0 until components) {
                    read(element, part, offset + part * size)
                }
            }
        }
    }

    private fun reader(root: JsonObject, binary: ByteArray, index: Int, components: Int): AccessorReader {
        val accessor = root["accessors"]!!.jsonArray[index].jsonObject
        require(accessor["sparse"] == null) { "Sparse accessor $index is not supported." }
        val componentType = accessor["componentType"]!!.jsonPrimitive.int
        val size = componentSize(componentType)
        val view = root["bufferViews"]!!.jsonArray[accessor["bufferView"]!!.jsonPrimitive.int].jsonObject
        return AccessorReader(
            buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN),
            componentType = componentType,
            count = accessor["count"]!!.jsonPrimitive.int,
            components = components,
            start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0),
            stride = view["byteStride"]?.jsonPrimitive?.int ?: (size * components),
            size = size
        )
    }

    fun componentSize(componentType: Int): Int = when (componentType) {
        BYTE, UNSIGNED_BYTE -> 1
        SHORT, UNSIGNED_SHORT -> 2
        UNSIGNED_INT, FLOAT -> 4
        else -> throw IllegalArgumentException("Unknown glTF component type $componentType.")
    }

    private val digits = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xff
            out[index * 2] = digits[byte shr 4]
            out[index * 2 + 1] = digits[byte and 0xf]
        }
        return String(out)
    }

    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
