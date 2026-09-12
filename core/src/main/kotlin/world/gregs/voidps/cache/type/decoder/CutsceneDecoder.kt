package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.CutsceneAction
import world.gregs.voidps.cache.type.data.CutsceneActor
import world.gregs.voidps.cache.type.data.CutsceneArea
import world.gregs.voidps.cache.type.data.CutsceneCameraPath
import world.gregs.voidps.cache.type.data.CutsceneCameraStep
import world.gregs.voidps.cache.type.data.CutsceneObject
import world.gregs.voidps.cache.type.data.CutscenePath
import world.gregs.voidps.cache.type.data.CutsceneType
import world.gregs.voidps.cache.type.data.CutsceneWaypoint

class CutsceneDecoder : TypeDecoder<CutsceneType>(Index.CUTSCENES) {

    override fun create(size: Int) = Array(size) { CutsceneType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: CutsceneType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun CutsceneType.read(opcode: Int, buffer: Reader) = unknown(opcode, buffer)

    private fun CutsceneType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        aspectWidth = buffer.readUnsignedShort()
        aspectHeight = buffer.readUnsignedShort()
        unknown5 = buffer.readUnsignedByte()
        areas = Array(buffer.readUnsignedByte()) {
            val packed = buffer.readInt()
            CutsceneArea(
                packed ushr PLANE_SHIFT,
                (packed ushr COORD_BITS) and COORD_MASK,
                packed and COORD_MASK,
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte()
            )
        }
        cameraPaths = Array(buffer.readUnsignedByte()) {
            CutsceneCameraPath(
                List(buffer.readUnsignedByte()) {
                    CutsceneCameraStep(
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedShort()
                    )
                }
            )
        }
        actors = Array(buffer.readUnsignedByte()) {
            val kind = buffer.readUnsignedByte()
            val npc = if (kind == ACTOR_NPC) buffer.readUnsignedShort() else -1
            CutsceneActor(kind, npc, buffer.readString())
        }
        objects = Array(buffer.readUnsignedByte()) {
            CutsceneObject(buffer.readBigSmart(), buffer.readUnsignedByte())
        }
        paths = Array(buffer.readUnsignedByte()) {
            CutscenePath(
                List(buffer.readUnsignedByte()) {
                    CutsceneWaypoint(buffer.readUnsignedByte(), buffer.readUnsignedShort(), buffer.readUnsignedShort())
                }
            )
        }
        actions = Array(buffer.readSmart()) { readAction(buffer) }
    }

    private fun readAction(buffer: Reader): CutsceneAction {
        val type = buffer.readUnsignedByte()
        val time = buffer.readUnsignedShort()
        return when (type) {
            1 -> CutsceneAction(type, time, shorts = IntArray(6) { buffer.readUnsignedShort() })
            10 -> CutsceneAction(
                type,
                time,
                actor = buffer.readUnsignedShort(),
                x = buffer.readUnsignedShort(),
                z = buffer.readUnsignedShort(),
                level = buffer.readUnsignedByte(),
                angle = buffer.readSignedSmart()
            )
            11, 21 -> CutsceneAction(type, time, shorts = IntArray(1) { buffer.readUnsignedShort() })
            12 -> CutsceneAction(
                type,
                time,
                shorts = IntArray(2) { buffer.readUnsignedShort() },
                bytes = IntArray(1) { buffer.readUnsignedByte() }
            )
            14 -> CutsceneAction(
                type,
                time,
                actor = buffer.readUnsignedShort(),
                sequence = buffer.readUnsignedShort(),
                ints = IntArray(1) { buffer.readInt() }
            )
            17 -> CutsceneAction(
                type,
                time,
                shorts = IntArray(1) { buffer.readUnsignedShort() },
                ints = IntArray(2) { buffer.readInt() }
            )
            20 -> CutsceneAction(
                type,
                time,
                actor = buffer.readUnsignedShort(),
                x = buffer.readUnsignedShort(),
                z = buffer.readUnsignedShort(),
                shorts = IntArray(1) { buffer.readUnsignedShort() }
            )
            22 -> CutsceneAction(
                type,
                time,
                actor = buffer.readUnsignedShort(),
                sequence = buffer.readUnsignedShort()
            )
            30 -> CutsceneAction(type, time, bytes = IntArray(3) { buffer.readUnsignedByte() })
            33 -> CutsceneAction(type, time, bytes = IntArray(5) { buffer.readUnsignedByte() })
            40 -> CutsceneAction(
                type,
                time,
                shorts = IntArray(1) { buffer.readUnsignedShort() },
                bytes = IntArray(4) { buffer.readUnsignedByte() }
            )
            70 -> CutsceneAction(type, time, text = buffer.readString(), duration = buffer.readUnsignedShort())
            255 -> CutsceneAction(type, time)
            else -> error("Unhandled cutscene action $type at ${buffer.position()}")
        }
    }

    companion object {
        const val COORD_BITS = 14
        const val COORD_MASK = 0x3fff
        const val PLANE_SHIFT = 28
        const val ACTOR_NPC = 0
    }
}
