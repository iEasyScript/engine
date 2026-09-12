package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.AnimClip
import world.gregs.voidps.cache.type.data.AnimExpressionNode
import world.gregs.voidps.cache.type.data.AnimLayer
import world.gregs.voidps.cache.type.data.AnimReference
import world.gregs.voidps.cache.type.data.AnimStateMachineType
import world.gregs.voidps.cache.type.data.AnimTransition
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.CLIP_ANIMATION
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.CLIP_EXPRESSION
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.EXPRESSION_BRANCHED
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.EXPRESSION_LEAF
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.EXPRESSION_PAIR
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.EXPRESSION_WEIGHTED
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.LAYER_FORM
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder.Companion.STATES_FORM
import world.gregs.voidps.cache.type.writeRawString

class AnimStateMachineEncoder : TypeEncoder<AnimStateMachineType> {

    override fun Writer.encode(definition: AnimStateMachineType) {
        writeByte(definition.version)
        writeByte(definition.form)
        when (definition.form) {
            LAYER_FORM -> writeLayer(definition.layer!!)
            STATES_FORM -> {
                val states = definition.states!!
                writeInt(states.size)
                for (state in states) {
                    writeLayer(state.layer)
                    writeInt(state.value)
                    writeRawString(state.name)
                    writeLong(state.first)
                    writeLong(state.second)
                }
            }
        }
        skip(definition.padding)
    }

    private fun Writer.writeLayer(layer: AnimLayer) {
        writeRawString(layer.name)
        writeInt(layer.clips.size)
        for (clip in layer.clips) {
            writeClip(clip)
        }
        writeInt(layer.transitions.size)
        for (transition in layer.transitions) {
            writeTransition(transition)
        }
    }

    private fun Writer.writeClip(clip: AnimClip) {
        writeRawString(clip.name)
        writeInt(clip.kind)
        when (clip.kind) {
            CLIP_ANIMATION -> writeReference(clip.reference!!)
            CLIP_EXPRESSION -> writeNode(clip.expression!!, 0)
        }
    }

    private fun Writer.writeTransition(transition: AnimTransition) {
        writeRawString(transition.source)
        writeRawString(transition.target)
        writeLong(transition.duration)
        writeInt(transition.fieldA)
        writeInt(transition.fieldB)
        writeRawString(transition.variable)
        writeInt(transition.fieldC)
    }

    private fun Writer.writeReference(reference: AnimReference) {
        writeInt(reference.sequence)
        writeInt(reference.fieldA)
        writeInt(reference.fieldB)
        writeInt(reference.fieldC)
        writeByte(reference.flag)
        writeByte(reference.hasOverride)
        if (reference.hasOverride == 0) {
            return
        }
        writeInt(reference.discarded!!)
        writeRawString(reference.overrideName!!)
        writeInt(reference.overrideValue!!)
    }

    private fun Writer.writeNode(nodes: List<AnimExpressionNode>, index: Int): Int {
        val node = nodes[index]
        writeInt(node.kind)
        var next = index + 1
        when (node.kind) {
            EXPRESSION_LEAF -> writeReference(node.reference!!)
            EXPRESSION_PAIR -> {
                writeFloat(node.first!!)
                writeFloat(node.second!!)
                writeRawString(node.variable!!)
                next = writeNode(nodes, next)
                next = writeNode(nodes, next)
            }
            EXPRESSION_WEIGHTED -> {
                writeFloat(node.first!!)
                writeFloat(node.second!!)
                writeRawString(node.variable!!)
                val weights = node.weights!!
                writeInt(weights.size)
                for (weight in weights) {
                    writeFloat(weight)
                    next = writeNode(nodes, next)
                }
            }
            EXPRESSION_BRANCHED -> {
                writeRawString(node.source!!)
                writeRawString(node.target!!)
                val branches = node.branches!!
                writeInt(branches.size)
                for (branch in branches) {
                    writeFloat(branch.first)
                    writeFloat(branch.second)
                    next = writeNode(nodes, next)
                }
                val trailing = node.trailing!!
                writeInt(trailing.size)
                for (value in trailing) {
                    writeInt(value)
                }
            }
        }
        return next
    }
}
