package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.CutsceneAction
import world.gregs.voidps.cache.type.data.CutsceneType
import world.gregs.voidps.cache.type.decoder.CutsceneDecoder

class CutsceneEncoder : TypeEncoder<CutsceneType> {

    override fun Writer.encode(definition: CutsceneType) {
        writeByte(definition.version)
        writeShort(definition.aspectWidth)
        writeShort(definition.aspectHeight)
        writeByte(definition.unknown5)
        val areas = definition.areas ?: emptyArray()
        writeByte(areas.size)
        for (area in areas) {
            writeInt(
                (area.plane shl CutsceneDecoder.PLANE_SHIFT) or
                    (area.x shl CutsceneDecoder.COORD_BITS) or
                    area.z
            )
            writeByte(area.chunkWidth)
            writeByte(area.chunkHeight)
            writeByte(area.level)
            writeByte(area.chunkX)
            writeByte(area.chunkZ)
            writeByte(area.unknown9)
        }
        val cameraPaths = definition.cameraPaths ?: emptyArray()
        writeByte(cameraPaths.size)
        for (path in cameraPaths) {
            writeByte(path.steps.size)
            for (step in path.steps) {
                writeShort(step.eyeX)
                writeShort(step.eyeZ)
                writeShort(step.eyeHeight)
                writeShort(step.lookX)
                writeShort(step.lookZ)
                writeShort(step.lookHeight)
                writeShort(step.duration)
            }
        }
        val actors = definition.actors ?: emptyArray()
        writeByte(actors.size)
        for (actor in actors) {
            writeByte(actor.kind)
            if (actor.kind == CutsceneDecoder.ACTOR_NPC) {
                writeShort(actor.npc)
            }
            writeString(actor.name)
        }
        val objects = definition.objects ?: emptyArray()
        writeByte(objects.size)
        for (loc in objects) {
            writeBigSmart(loc.loc)
            writeByte(loc.unknown)
        }
        val paths = definition.paths ?: emptyArray()
        writeByte(paths.size)
        for (path in paths) {
            writeByte(path.steps.size)
            for (step in path.steps) {
                writeByte(step.level)
                writeShort(step.x)
                writeShort(step.z)
            }
        }
        val actions = definition.actions ?: emptyArray()
        writeSmart(actions.size)
        for (action in actions) {
            writeAction(action, definition.id)
        }
    }

    private fun Writer.writeAction(action: CutsceneAction, id: Int) {
        writeByte(action.type)
        writeShort(action.time)
        when (action.type) {
            1, 11, 21 -> writeShorts(action)
            10 -> {
                writeShort(action.actor!!)
                writeShort(action.x!!)
                writeShort(action.z!!)
                writeByte(action.level!!)
                writeSignedSmart(action.angle!!)
            }
            12, 40 -> {
                writeShorts(action)
                writeBytes(action)
            }
            14 -> {
                writeShort(action.actor!!)
                writeShort(action.sequence!!)
                writeInts(action)
            }
            17 -> {
                writeShorts(action)
                writeInts(action)
            }
            20 -> {
                writeShort(action.actor!!)
                writeShort(action.x!!)
                writeShort(action.z!!)
                writeShorts(action)
            }
            22 -> {
                writeShort(action.actor!!)
                writeShort(action.sequence!!)
            }
            30, 33 -> writeBytes(action)
            70 -> {
                writeString(action.text)
                writeShort(action.duration!!)
            }
            255 -> Unit
            else -> error("Unhandled cutscene action ${action.type} in $id")
        }
    }

    private fun Writer.writeShorts(action: CutsceneAction) {
        for (value in action.shorts ?: return) {
            writeShort(value)
        }
    }

    private fun Writer.writeBytes(action: CutsceneAction) {
        for (value in action.bytes ?: return) {
            writeByte(value)
        }
    }

    private fun Writer.writeInts(action: CutsceneAction) {
        for (value in action.ints ?: return) {
            writeInt(value)
        }
    }
}
