package org.projectx.core.net.prot.decode

import kotlin.reflect.full.memberProperties
import org.projectx.core.net.prot.ClientProt

/**
 * Projects an already-decoded [ClientProt] into queryable fields.
 *
 * ⛔ Deliberately reflective rather than hand-written per prot. The client-to-server decoders are
 * already verified against the binary and produce typed values; writing a second decoder for each
 * would mean re-deriving byte layouts that are known, which is exactly how a wrong layout gets
 * introduced. This reads what those decoders already produced, so it cannot disagree with them and
 * a newly added prot becomes queryable with no extra work.
 *
 * The reference domains are inferred from property names. That only affects how an id is *labelled*
 * for a reader - never how a byte is read - so a wrong guess costs a nicer name, not correctness.
 */
object ClientProtProjection {

    private val REF_BY_SUFFIX = listOf(
        "interfacehash" to RefDomain.COMPONENT,
        "componenthash" to RefDomain.COMPONENT,
        "component" to RefDomain.COMPONENT,
        "interface" to RefDomain.INTERFACE,
        "itemid" to RefDomain.OBJ,
        "objid" to RefDomain.OBJ,
        "npcindex" to RefDomain.NPC,
        "npcid" to RefDomain.NPC,
        "locid" to RefDomain.LOC,
        "playerindex" to RefDomain.PLAYER,
        "scriptid" to RefDomain.SCRIPT,
        "varp" to RefDomain.VARP,
        "varbit" to RefDomain.VARBIT,
        "varc" to RefDomain.VARC,
        "destx" to RefDomain.COORD,
        "desty" to RefDomain.COORD,
        "x" to RefDomain.COORD,
        "y" to RefDomain.COORD,
    )

    /** Ids an investigation typically starts from, so they are worth an index of their own. */
    private val INDEXED_REFS = setOf(
        RefDomain.COMPONENT, RefDomain.INTERFACE, RefDomain.OBJ, RefDomain.NPC,
        RefDomain.LOC, RefDomain.PLAYER, RefDomain.SCRIPT,
        RefDomain.VARP, RefDomain.VARBIT, RefDomain.VARC,
    )

    fun project(name: String, opcode: Int, prot: ClientProt): DecodedPacket {
        val fields = prot::class.memberProperties.mapNotNull { property ->
            val value = runCatching { property.getter.call(prot) }.getOrNull() ?: return@mapNotNull null
            toFieldValue(value)?.let { Field(property.name, it, refFor(property.name)) }
        }.sortedBy { it.name }
        return DecodedPacket(name, opcode, fields)
    }

    fun schemaFor(name: String, prot: ClientProt, version: Int = 1): ProtSchema {
        val fields = prot::class.memberProperties.mapNotNull { property ->
            val value = runCatching { property.getter.call(prot) }.getOrNull() ?: return@mapNotNull null
            val kind = kindOf(value) ?: return@mapNotNull null
            val ref = refFor(property.name)
            SchemaField(property.name, kind, ref, indexed = ref in INDEXED_REFS)
        }.sortedBy { it.name }
        return ProtSchema(name, version, fields = fields)
    }

    private fun refFor(propertyName: String): RefDomain {
        val lowered = propertyName.lowercase()
        return REF_BY_SUFFIX.firstOrNull { lowered == it.first || lowered.endsWith(it.first) }?.second
            ?: RefDomain.NONE
    }

    private fun kindOf(value: Any): FieldKind? = when (value) {
        is Int, is Long, is Short, is Byte -> FieldKind.INT
        is Boolean -> FieldKind.BOOL
        is String -> FieldKind.STRING
        is ByteArray -> FieldKind.BYTES
        is List<*> -> FieldKind.LIST
        else -> null
    }

    private fun toFieldValue(value: Any): FieldValue? = when (value) {
        is Int -> FieldValue.I(value.toLong())
        is Long -> FieldValue.I(value)
        is Short -> FieldValue.I(value.toLong())
        is Byte -> FieldValue.I(value.toLong())
        is Boolean -> FieldValue.B(value)
        is String -> FieldValue.S(value)
        is ByteArray -> FieldValue.Bytes(value)
        is List<*> -> FieldValue.L(value.mapNotNull { it?.let(::toFieldValue) })
        else -> null
    }
}
