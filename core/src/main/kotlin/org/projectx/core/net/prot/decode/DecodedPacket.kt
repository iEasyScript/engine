package org.projectx.core.net.prot.decode

import java.security.MessageDigest

/**
 * What a decoded field refers to, so a consumer can resolve an id to a name without the decoder
 * knowing anything about gamevals. Keeping resolution out of the decoder is what lets a cache
 * re-dump rename things without invalidating a single stored row.
 */
enum class RefDomain {
    NONE, VARP, VARBIT, VARC, VARCBIT, INTERFACE, COMPONENT, NPC, LOC, OBJ, SEQ, SPOTANIM,
    GRAPHIC, STRUCT, ENUM, SKILL, INV, SCRIPT, PLAYER, COORD, TEXT,
}

sealed interface FieldValue {
    @JvmInline value class I(val v: Long) : FieldValue
    @JvmInline value class S(val v: String) : FieldValue
    @JvmInline value class B(val v: Boolean) : FieldValue
    class Bytes(val v: ByteArray) : FieldValue
    class L(val items: List<FieldValue>) : FieldValue
    class Obj(val fields: List<Field>) : FieldValue
}

class Field(val name: String, val value: FieldValue, val ref: RefDomain = RefDomain.NONE) {
    val long: Long? get() = (value as? FieldValue.I)?.v
    val text: String? get() = (value as? FieldValue.S)?.v
    val bool: Boolean? get() = (value as? FieldValue.B)?.v
}

enum class DecodeStatus {
    OK,

    /** Read something, but not all of it. The fields present are still trustworthy. */
    PARTIAL,
    FAILED,

    /** Needs prior-packet state the caller did not supply. Never guessed at. */
    NEEDS_CONTEXT,
    NO_DECODER,
}

/**
 * The decoded form of one packet.
 *
 * [children] carries nested records - zone sub-packets, per-entity blocks, script arguments - so a
 * decoder never has to flatten structure into invented field names.
 *
 * [trailingBytes] above zero is a defect signal, not a warning: it proves the decoder did not
 * consume its packet and therefore does not fully describe it.
 */
class DecodedPacket(
    val opcodeName: String,
    val opcode: Int,
    val fields: List<Field> = emptyList(),
    val children: List<DecodedPacket> = emptyList(),
    val trailingBytes: Int = 0,
    val status: DecodeStatus = DecodeStatus.OK,
    val error: String? = null,
) {
    operator fun get(name: String): Field? = fields.firstOrNull { it.name == name }

    /** Flattens to `path -> value` pairs, which is the shape an index and a JSON row both want. */
    fun flatten(prefix: String = ""): List<Pair<String, FieldValue>> = buildList {
        for (field in fields) collect(prefix + field.name, field.value, this)
        for ((i, child) in children.withIndex()) {
            addAll(child.flatten("$prefix${child.opcodeName.lowercase()}[$i]."))
        }
    }

    private fun collect(path: String, value: FieldValue, into: MutableList<Pair<String, FieldValue>>) {
        when (value) {
            is FieldValue.Obj -> for (field in value.fields) collect("$path.${field.name}", field.value, into)
            is FieldValue.L -> for ((i, item) in value.items.withIndex()) collect("$path[$i]", item, into)
            else -> into.add(path to value)
        }
    }
}

/**
 * A decoder's declared shape, independent of the bytes it reads.
 *
 * [version] is bumped by hand whenever a decoder's field semantics change; [fieldsHash] is derived
 * from the declaration and catches the case where someone forgets. Together they give each prot its
 * own revision, which is what makes a targeted re-decode of one packet type possible instead of
 * re-running everything.
 */
class ProtSchema(
    val opcodeName: String,
    val version: Int,
    val stateful: Boolean = false,
    val fields: List<SchemaField> = emptyList(),
) {
    val fieldsHash: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(opcodeName.toByteArray())
        for (field in fields) digest.update("${field.name}:${field.kind}:${field.ref}".toByteArray())
        digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}

enum class FieldKind { INT, STRING, BOOL, BYTES, LIST, OBJECT }

/**
 * [indexed] marks a field worth its own index. Everything is *filterable*; this decides what is
 * filterable in constant time rather than by scanning that prot's events, so it is set for the ids
 * an investigation starts from rather than for every field.
 */
class SchemaField(
    val name: String,
    val kind: FieldKind,
    val ref: RefDomain = RefDomain.NONE,
    val indexed: Boolean = false,
)
