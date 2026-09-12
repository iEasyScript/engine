package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.AnimBranch
import world.gregs.voidps.cache.type.data.AnimClip
import world.gregs.voidps.cache.type.data.AnimExpressionNode
import world.gregs.voidps.cache.type.data.AnimLayer
import world.gregs.voidps.cache.type.data.AnimReference
import world.gregs.voidps.cache.type.data.AnimState
import world.gregs.voidps.cache.type.data.AnimStateMachineType
import world.gregs.voidps.cache.type.data.AnimTransition
import world.gregs.voidps.cache.type.readRawString

class AnimStateMachineDecoder : TypeDecoder<AnimStateMachineType>(INDEX) {

    override fun create(size: Int) = Array(size) { AnimStateMachineType(it) }

    override fun readLoop(definition: AnimStateMachineType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun AnimStateMachineType.read(opcode: Int, buffer: Reader) = Unit

    private fun AnimStateMachineType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        form = buffer.readUnsignedByte()
        when (form) {
            LAYER_FORM -> layer = readLayer(buffer)
            STATES_FORM -> states = List(buffer.readInt()) { readState(buffer) }
        }
        padding = buffer.readableBytes()
        buffer.skip(padding)
    }

    private fun readLayer(buffer: Reader) = AnimLayer(
        name = buffer.readRawString(),
        clips = List(buffer.readInt()) { readClip(buffer) },
        transitions = List(buffer.readInt()) { readTransition(buffer) },
    )

    private fun readState(buffer: Reader) = AnimState(
        layer = readLayer(buffer),
        value = buffer.readInt(),
        name = buffer.readRawString(),
        first = buffer.readLong(),
        second = buffer.readLong(),
    )

    private fun readClip(buffer: Reader): AnimClip {
        val name = buffer.readRawString()
        return when (val kind = buffer.readInt()) {
            CLIP_ANIMATION -> AnimClip(name, kind, readReference(buffer), null)
            CLIP_EXPRESSION -> AnimClip(name, kind, null, readExpression(buffer))
            else -> AnimClip(name, kind, null, null)
        }
    }

    private fun readTransition(buffer: Reader) = AnimTransition(
        source = buffer.readRawString(),
        target = buffer.readRawString(),
        duration = buffer.readLong(),
        fieldA = buffer.readInt(),
        fieldB = buffer.readInt(),
        variable = buffer.readRawString(),
        fieldC = buffer.readInt(),
    )

    private fun readReference(buffer: Reader): AnimReference {
        val sequence = buffer.readInt()
        val fieldA = buffer.readInt()
        val fieldB = buffer.readInt()
        val fieldC = buffer.readInt()
        val flag = buffer.readUnsignedByte()
        val hasOverride = buffer.readUnsignedByte()
        if (hasOverride == 0) {
            return AnimReference(sequence, fieldA, fieldB, fieldC, flag, hasOverride, null, null, null)
        }
        return AnimReference(
            sequence,
            fieldA,
            fieldB,
            fieldC,
            flag,
            hasOverride,
            buffer.readInt(),
            buffer.readRawString(),
            buffer.readInt(),
        )
    }

    private fun readExpression(buffer: Reader): List<AnimExpressionNode> {
        val nodes = ArrayList<AnimExpressionNode>()
        appendNode(nodes, buffer)
        return nodes
    }

    private fun appendNode(nodes: MutableList<AnimExpressionNode>, buffer: Reader) {
        val kind = buffer.readInt()
        val slot = nodes.size
        nodes.add(node(kind))
        when (kind) {
            EXPRESSION_LEAF -> nodes[slot] = node(kind, reference = readReference(buffer))
            EXPRESSION_PAIR -> {
                val node = node(kind, first = buffer.readFloat(), second = buffer.readFloat(), variable = buffer.readRawString())
                nodes[slot] = node
                appendNode(nodes, buffer)
                appendNode(nodes, buffer)
            }
            EXPRESSION_WEIGHTED -> {
                val first = buffer.readFloat()
                val second = buffer.readFloat()
                val variable = buffer.readRawString()
                val weights = ArrayList<Float>()
                repeat(buffer.readInt()) {
                    weights.add(buffer.readFloat())
                    appendNode(nodes, buffer)
                }
                nodes[slot] = node(kind, first = first, second = second, variable = variable, weights = weights)
            }
            EXPRESSION_BRANCHED -> {
                val source = buffer.readRawString()
                val target = buffer.readRawString()
                val branches = ArrayList<AnimBranch>()
                repeat(buffer.readInt()) {
                    branches.add(AnimBranch(buffer.readFloat(), buffer.readFloat()))
                    appendNode(nodes, buffer)
                }
                val trailing = List(buffer.readInt()) { buffer.readInt() }
                nodes[slot] = node(kind, source = source, target = target, branches = branches, trailing = trailing)
            }
        }
    }

    private fun node(
        kind: Int,
        reference: AnimReference? = null,
        first: Float? = null,
        second: Float? = null,
        variable: String? = null,
        source: String? = null,
        target: String? = null,
        weights: List<Float>? = null,
        branches: List<AnimBranch>? = null,
        trailing: List<Int>? = null,
    ) = AnimExpressionNode(kind, reference, first, second, variable, source, target, weights, branches, trailing)

    companion object {
        const val INDEX = 62

        const val LAYER_FORM = 0

        const val STATES_FORM = 1

        const val CLIP_ANIMATION = 1

        const val CLIP_EXPRESSION = 2

        const val EXPRESSION_LEAF = 0

        const val EXPRESSION_PAIR = 1

        const val EXPRESSION_WEIGHTED = 2

        const val EXPRESSION_BRANCHED = 3
    }
}
