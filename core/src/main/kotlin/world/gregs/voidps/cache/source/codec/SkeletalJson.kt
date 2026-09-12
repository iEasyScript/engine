package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.source.SourceJson
import world.gregs.voidps.cache.type.data.AnimBaseBone
import world.gregs.voidps.cache.type.data.AnimBaseExtra
import world.gregs.voidps.cache.type.data.AnimBaseTransform
import world.gregs.voidps.cache.type.data.AnimBaseType
import world.gregs.voidps.cache.type.data.Keyframe
import world.gregs.voidps.cache.type.data.KeyframeAnimType
import world.gregs.voidps.cache.type.data.KeyframeTrack
import world.gregs.voidps.cache.type.data.SkeletalAnimType
import world.gregs.voidps.cache.type.data.SkeletalFrameType

/**
 * The three skeletal animation formats as editable JSON, and back.
 *
 * Writing is deterministic to the byte so re-unpacking an unchanged cache leaves the tree alone; reading
 * is tolerant of key order, whitespace and unknown keys, and a missing key reads as the default the
 * decoder would have left.
 *
 * **A record is a line while it fits.** These indices are the cache's largest by record count - millions
 * of frames and of keys - so a frame, a bone, a track and a key are each one line where a line holds
 * them, and an array that does not fit is filled to the margin rather than broken one entry per line.
 * A key is a tuple, `[time, value, inX, inY, outX, outY]`, for the same reason.
 */
internal object SkeletalJson {

    fun writeBase(base: AnimBaseType): ByteArray {
        val out = StringBuilder(base.transforms.size * 48 + base.bones.size * 256 + 64)
        out.append("{\n")
        out.append("  \"id\": ").append(base.id).append(",\n")
        out.append("  \"version\": ").append(base.version).append(",\n")
        out.append("  \"transforms\": ")
        records(out, base.transforms.map { transform(it) })
        out.append(",\n  \"matrixSetCount\": ").append(base.matrixSetCount)
        out.append(",\n  \"bones\": ")
        records(out, base.bones.map { bone(it) })
        out.append(",\n  \"remapCount\": ").append(base.remapCount)
        if (base.remaps.isNotEmpty()) {
            out.append(",\n  \"remaps\": ")
            numbers(out, TOP_FIELD_INDENT, base.remaps.map { it.toString() })
        }
        out.append(",\n  \"extraCount\": ").append(base.extraCount)
        if (base.extras.isNotEmpty()) {
            out.append(",\n  \"extras\": ")
            records(out, base.extras.map { extra(it) })
        }
        out.append("\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readBase(bytes: ByteArray): AnimBaseType {
        val root = parse(bytes)
        val base = AnimBaseType(root["id"]?.jsonPrimitive?.int ?: -1)
        base.version = root["version"]?.jsonPrimitive?.int ?: AnimBaseType.DEFAULT_VERSION
        for (element in root["transforms"].array()) {
            val transform = element.jsonObject
            base.transforms.add(
                AnimBaseTransform(
                    type = transform.int("type"),
                    unusedA = transform.int("unusedA"),
                    unusedB = transform.int("unusedB"),
                    labels = transform["labels"].ints()
                )
            )
        }
        base.matrixSetCount = root.int("matrixSetCount")
        for (element in root["bones"].array()) {
            val bone = element.jsonObject
            base.bones.add(AnimBaseBone(bone.int("label"), bone["matrices"].floats()))
        }
        base.remapCount = root.int("remapCount")
        base.remaps = root["remaps"].ints()
        base.extraCount = root.int("extraCount")
        for (element in root["extras"].array()) {
            val extra = element.jsonObject
            base.extras.add(
                AnimBaseExtra(
                    name = extra["name"]?.jsonPrimitive?.content ?: "",
                    key = extra.int("key"),
                    b = extra.int("b"),
                    values = extra["values"].floats()
                )
            )
        }
        return base
    }

    fun writeAnim(anim: SkeletalAnimType): ByteArray {
        val out = StringBuilder(anim.frames.size * 256 + 64)
        out.append("{\n")
        out.append("  \"id\": ").append(anim.id).append(",\n")
        out.append("  \"frames\": ")
        records(out, anim.frames.map { frame(it) })
        out.append("\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readAnim(bytes: ByteArray): SkeletalAnimType {
        val root = parse(bytes)
        val anim = SkeletalAnimType(root["id"]?.jsonPrimitive?.int ?: -1)
        for (element in root["frames"].array()) {
            val frame = element.jsonObject
            anim.frames.add(
                SkeletalFrameType(
                    id = frame.int("time"),
                    version = frame.int("version"),
                    frameBaseId = frame.int("base"),
                    masks = frame["masks"].ints(),
                    values = frame["values"].ints(),
                    wide = frame["wide"].ints()
                )
            )
        }
        return anim
    }

    fun writeKeyframes(anim: KeyframeAnimType): ByteArray {
        val out = StringBuilder(anim.tracks.size * 256 + 128)
        out.append("{\n")
        out.append("  \"id\": ").append(anim.id).append(",\n")
        out.append("  \"version\": ").append(anim.version).append(",\n")
        out.append("  \"base\": ").append(anim.frameBaseId).append(",\n")
        out.append("  \"unknownA\": ").append(anim.unknownA).append(",\n")
        out.append("  \"duration\": ").append(anim.duration).append(",\n")
        out.append("  \"unknownC\": ").append(anim.unknownC).append(",\n")
        out.append("  \"tracks\": ")
        records(out, anim.tracks.map { track(it) })
        out.append("\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readKeyframes(bytes: ByteArray): KeyframeAnimType {
        val root = parse(bytes)
        val anim = KeyframeAnimType(root["id"]?.jsonPrimitive?.int ?: -1)
        anim.version = root.int("version")
        anim.frameBaseId = root.int("base")
        anim.unknownA = root.int("unknownA")
        anim.duration = root.int("duration")
        anim.unknownC = root.int("unknownC")
        for (element in root["tracks"].array()) {
            val track = element.jsonObject
            anim.tracks.add(
                KeyframeTrack(
                    kind = track.int("kind"),
                    channelIndex = track.int("channel"),
                    curveType = track.int("curveType"),
                    curveKind = track.int("curveKind"),
                    unusedD = track.int("unusedD"),
                    unusedE = track.int("unusedE"),
                    flag = track.int("flag"),
                    keys = track["keys"].array().map { key(it.jsonArray) },
                    wideChannel = track["wideChannel"]?.jsonPrimitive?.content == "true"
                )
            )
        }
        return anim
    }

    private fun key(tuple: JsonArray): Keyframe = Keyframe(
        time = tuple[0].jsonPrimitive.int,
        value = float(tuple[1]),
        inTangentX = float(tuple[2]),
        inTangentY = float(tuple[3]),
        outTangentX = float(tuple[4]),
        outTangentY = float(tuple[5])
    )

    /** One record, as the fields it holds; the caller decides whether they fit on a line. */
    private fun transform(transform: AnimBaseTransform): List<Field> = listOf(
        Field("type", transform.type),
        Field("unusedA", transform.unusedA),
        Field("unusedB", transform.unusedB),
        Field("labels", transform.labels.map { it.toString() })
    )

    private fun bone(bone: AnimBaseBone): List<Field> = listOf(
        Field("label", bone.label),
        Field("matrices", bone.matrices.map { number(it) })
    )

    private fun extra(extra: AnimBaseExtra): List<Field> = listOf(
        Field("name", SourceJson.quote(extra.name)),
        Field("key", extra.key),
        Field("b", extra.b),
        Field("values", extra.values.map { number(it) })
    )

    private fun frame(frame: SkeletalFrameType): List<Field> {
        val fields = ArrayList<Field>(6)
        fields.add(Field("time", frame.id))
        fields.add(Field("version", frame.version))
        fields.add(Field("base", frame.frameBaseId))
        fields.add(Field("masks", frame.masks.map { it.toString() }))
        fields.add(Field("values", frame.values.map { it.toString() }))
        if (frame.wide.isNotEmpty()) {
            fields.add(Field("wide", frame.wide.map { it.toString() }))
        }
        return fields
    }

    private fun track(track: KeyframeTrack): List<Field> {
        val fields = ArrayList<Field>(9)
        fields.add(Field("kind", track.kind))
        fields.add(Field("channel", track.channelIndex))
        fields.add(Field("curveType", track.curveType))
        fields.add(Field("curveKind", track.curveKind))
        fields.add(Field("unusedD", track.unusedD))
        fields.add(Field("unusedE", track.unusedE))
        fields.add(Field("flag", track.flag))
        if (track.wideChannel) {
            fields.add(Field("wideChannel", "true"))
        }
        fields.add(Field("keys", track.keys.map { tuple(it) }))
        return fields
    }

    private fun tuple(key: Keyframe): String = buildString {
        append('[').append(key.time)
        append(", ").append(number(key.value))
        append(", ").append(number(key.inTangentX))
        append(", ").append(number(key.inTangentY))
        append(", ").append(number(key.outTangentX))
        append(", ").append(number(key.outTangentY))
        append(']')
    }

    /**
     * One field of a record, written out; [items] are the entries of an array field, kept so an array
     * too long for a line can be filled to the margin instead.
     */
    private class Field(val name: String, val text: String, val items: List<String>? = null) {

        constructor(name: String, value: Int) : this(name, value.toString())

        constructor(name: String, items: List<String>) : this(name, items.joinToString(", ", "[", "]"), items)
    }

    /** A list of records: one per line where a line holds it, expanded field by field where it does not. */
    private fun records(out: StringBuilder, records: List<List<Field>>) {
        if (records.isEmpty()) {
            out.append("[]")
            return
        }
        out.append("[\n")
        for ((position, record) in records.withIndex()) {
            out.append(RECORD_PAD)
            val line = record.joinToString(", ", "{", "}") { "${SourceJson.quote(it.name)}: ${it.text}" }
            if (RECORD_INDENT + line.length <= WIDTH) {
                out.append(line)
            } else {
                expand(out, record)
            }
            out.append(if (position == records.size - 1) "\n" else ",\n")
        }
        out.append("  ]")
    }

    private fun expand(out: StringBuilder, record: List<Field>) {
        out.append("{\n")
        for ((position, field) in record.withIndex()) {
            out.append(FIELD_PAD).append(SourceJson.quote(field.name)).append(": ")
            if (field.items == null) {
                out.append(field.text)
            } else {
                numbers(out, FIELD_INDENT, field.items)
            }
            out.append(if (position == record.size - 1) "\n" else ",\n")
        }
        out.append(RECORD_PAD).append('}')
    }

    /** Values filled to the margin: an array too long for a line is still not one entry per line. */
    private fun numbers(out: StringBuilder, indent: Int, values: List<String>) {
        if (values.isEmpty()) {
            out.append("[]")
            return
        }
        val line = values.joinToString(", ", "[", "]")
        if (indent + line.length <= WIDTH) {
            out.append(line)
            return
        }
        out.append("[\n")
        val margin = indent + INDENT
        var column = margin
        pad(out, margin)
        for ((position, value) in values.withIndex()) {
            val text = if (position == values.size - 1) value else "$value,"
            if (column != margin && column + text.length > WIDTH) {
                out.append('\n')
                pad(out, margin)
                column = margin
            } else if (column != margin) {
                out.append(' ')
                column++
            }
            out.append(text)
            column += text.length
        }
        out.append('\n')
        pad(out, indent)
        out.append(']')
    }

    /**
     * A float as its shortest round tripping decimal, or as its raw bits when it has no JSON spelling -
     * a NaN or an infinity, which the format allows and the served cache does not carry.
     */
    private fun number(value: Float): String =
        if (value.isFinite()) value.toString() else SourceJson.quote("0x${value.toRawBits().toUInt().toString(16)}")

    private fun float(element: JsonElement): Float {
        val text = element.jsonPrimitive.content
        return if (text.startsWith("0x")) Float.fromBits(text.substring(2).toUInt(16).toInt()) else text.toFloat()
    }

    private fun pad(out: StringBuilder, columns: Int) {
        for (column in 0 until columns) {
            out.append(' ')
        }
    }

    private fun parse(bytes: ByteArray): JsonObject = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.int ?: 0

    private fun JsonElement?.array(): List<JsonElement> = this?.jsonArray ?: emptyList()

    private fun JsonElement?.ints(): IntArray {
        val items = this?.jsonArray ?: return IntArray(0)
        return IntArray(items.size) { items[it].jsonPrimitive.int }
    }

    private fun JsonElement?.floats(): FloatArray {
        val items = this?.jsonArray ?: return FloatArray(0)
        return FloatArray(items.size) { float(items[it]) }
    }

    private const val WIDTH = 110

    private const val INDENT = 2

    private const val TOP_FIELD_INDENT = 2

    private const val RECORD_INDENT = 4

    private const val FIELD_INDENT = 6

    private const val RECORD_PAD = "    "

    private const val FIELD_PAD = "      "

    private val json = Json { ignoreUnknownKeys = true }
}
