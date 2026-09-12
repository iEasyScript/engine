package org.projectx.tools.cacheunpack

import world.gregs.voidps.cache.cs2.Cs2Codec
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import world.gregs.voidps.cache.cs2.Cs2PushTag
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.type.data.ClientScriptSwitchCase
import world.gregs.voidps.cache.type.data.ClientScriptType

/**
 * A clientscript with its instruction stream split into instructions, which the cache library's
 * container decoder deliberately leaves as bytes because the split needs the per-build operand
 * width table the cs2 toolchain owns.
 *
 * Arrays run parallel to [instructions]: `intOperands[i]` is meaningful for a BYTE, TRIBYTE, INT or
 * VAR operand and for a tagged push whose `pushTags[i]` is [Cs2PushTag.INT], `longOperands[i]` for a
 * LONG operand or a [Cs2PushTag.LONG] push, `stringOperands[i]` for a STRING operand or a
 * [Cs2PushTag.STRING] push; `pushTags[i]` is [Cs2PushTag.NONE] for every non-tagged opcode.
 *
 * [nameHash] is what index 12's reference table stores for the archive: the Java string hash of
 * Jagex's own `[category,name]` spelling, and 0 when the table carries none.
 */
class ClientScriptRecord(
    val name: String?,
    val nameHash: Int,
    val instructionCount: Int,
    val intLocalCount: Int,
    val stringLocalCount: Int,
    val longLocalCount: Int,
    val intArgumentCount: Int,
    val stringArgumentCount: Int,
    val longArgumentCount: Int,
    val instructions: IntArray,
    val intOperands: IntArray,
    val stringOperands: Array<String?>,
    val longOperands: LongArray,
    val pushTags: IntArray,
    val switchTables: List<List<ClientScriptSwitchCase>>,
) {
    companion object {
        /**
         * The projection [TypeUnpacker] applies, or null when no solved opcode table exists for the
         * cache's index-12 CRC — the byte blob is then written as it is rather than mis-split with a
         * table from another build.
         */
        fun projection(cache: Cache): ((CacheType) -> Any)? {
            val entries = Cs2OpcodeTable.load(cache) ?: return null
            Cs2OpcodeTable.install(entries)
            val hashes = DecompressionContext().use { context ->
                cache.sector(255, Index.CLIENT_SCRIPTS)?.let { parseRefTable(context, it) }
            }?.entries.orEmpty().associate { it.id to it.nameHash }
            return { definition ->
                if (definition is ClientScriptType) of(definition, hashes[definition.id] ?: 0) else definition
            }
        }

        fun of(container: ClientScriptType, nameHash: Int): ClientScriptRecord {
            val script = Cs2Codec.decode(container)
            val size = script.instructions.size
            val opcodes = IntArray(size)
            val ints = IntArray(size)
            val strings = arrayOfNulls<String>(size)
            val longs = LongArray(size)
            val tags = IntArray(size)
            for (index in 0 until size) {
                val instruction = script.instructions[index]
                opcodes[index] = instruction.op.id
                ints[index] = instruction.intOperand
                strings[index] = instruction.strOperand
                longs[index] = instruction.longOperand
                tags[index] = instruction.pushTag
            }
            return ClientScriptRecord(
                name = container.name,
                nameHash = nameHash,
                instructionCount = container.instructionCount,
                intLocalCount = container.intLocalCount,
                stringLocalCount = container.stringLocalCount,
                longLocalCount = container.longLocalCount,
                intArgumentCount = container.intArgumentCount,
                stringArgumentCount = container.stringArgumentCount,
                longArgumentCount = container.longArgumentCount,
                instructions = opcodes,
                intOperands = ints,
                stringOperands = strings,
                longOperands = longs,
                pushTags = tags,
                switchTables = container.switchTables,
            )
        }
    }
}
