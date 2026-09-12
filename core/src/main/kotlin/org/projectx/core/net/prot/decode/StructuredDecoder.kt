package org.projectx.core.net.prot.decode

import kotlinx.io.Source

/**
 * One prot's decoder plus the schema that describes what it emits.
 *
 * [reconstruct] rebuilds the typed prot from the decoded fields, where one exists. That is what lets
 * a captured packet be re-encoded with our own encoder and compared against what the game actually
 * sent - the strongest check available, because it exercises both directions at once and the
 * encoder under test is the same one the server uses to talk to a real client.
 */
class StructuredDecoder(
    val schema: ProtSchema,
    val decode: suspend Source.(size: Int) -> DecodedPacket,
    val reconstruct: ((DecodedPacket) -> Any?)? = null,
)

/**
 * Collects fields while reading, so a decoder body reads as a description of the packet rather than
 * as buffer bookkeeping.
 */
class FieldSink(val opcodeName: String, val opcode: Int) {
    private val fields = ArrayList<Field>()
    private val children = ArrayList<DecodedPacket>()
    private var status = DecodeStatus.OK
    private var error: String? = null

    fun int(name: String, value: Number, ref: RefDomain = RefDomain.NONE): FieldSink =
        apply { fields.add(Field(name, FieldValue.I(value.toLong()), ref)) }

    fun string(name: String, value: String, ref: RefDomain = RefDomain.TEXT): FieldSink =
        apply { fields.add(Field(name, FieldValue.S(value), ref)) }

    fun bool(name: String, value: Boolean): FieldSink =
        apply { fields.add(Field(name, FieldValue.B(value))) }

    fun bytes(name: String, value: ByteArray): FieldSink =
        apply { fields.add(Field(name, FieldValue.Bytes(value))) }

    fun ints(name: String, values: List<Number>, ref: RefDomain = RefDomain.NONE): FieldSink =
        apply { fields.add(Field(name, FieldValue.L(values.map { FieldValue.I(it.toLong()) }), ref)) }

    fun list(name: String, values: List<FieldValue>, ref: RefDomain = RefDomain.NONE): FieldSink =
        apply { fields.add(Field(name, FieldValue.L(values), ref)) }

    fun child(packet: DecodedPacket): FieldSink = apply { children.add(packet) }

    fun partial(reason: String): FieldSink = apply {
        status = DecodeStatus.PARTIAL
        error = reason
    }

    fun build(trailingBytes: Int = 0): DecodedPacket =
        DecodedPacket(opcodeName, opcode, fields, children, trailingBytes, status, error)
}

/**
 * Renders a decoded packet the way the text dumper always has, so the human-readable output stays a
 * derived view of the structured form rather than a second thing that can disagree with it.
 *
 * [resolve] turns an id into a label; callers that have gameval tables pass one in, and everything
 * else gets bare ids.
 */
fun DecodedPacket.render(resolve: (RefDomain, Long) -> String? = { _, _ -> null }): String {
    val parts = fields.map { field -> "${field.name}=${renderValue(field.value, field.ref, resolve)}" }
    val childText = children.joinToString(" ") { "${it.opcodeName}(${it.render(resolve)})" }
    val suffix = when {
        status == DecodeStatus.PARTIAL -> " [partial${error?.let { ": $it" } ?: ""}]"
        trailingBytes > 0 -> " [+${trailingBytes}B undecoded]"
        else -> ""
    }
    return (parts + childText).filter { it.isNotBlank() }.joinToString(" ") + suffix
}

private fun renderValue(value: FieldValue, ref: RefDomain, resolve: (RefDomain, Long) -> String?): String =
    when (value) {
        is FieldValue.I -> resolve(ref, value.v)?.let { "$it(${value.v})" } ?: value.v.toString()
        is FieldValue.S -> "\"${value.v}\""
        is FieldValue.B -> value.v.toString()
        is FieldValue.Bytes -> value.v.joinToString("") { "%02x".format(it) }
        is FieldValue.L -> value.items.joinToString(",", "[", "]") { renderValue(it, ref, resolve) }
        is FieldValue.Obj -> value.fields.joinToString(" ", "{", "}") {
            "${it.name}=${renderValue(it.value, it.ref, resolve)}"
        }
    }
