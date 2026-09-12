package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.NPCS
import world.gregs.voidps.cache.type.Transforms.Companion.readVarbit
import world.gregs.voidps.cache.type.data.NpcType
import world.gregs.voidps.cache.type.encoder.NpcEncoder

class NpcDecoder(val members: Boolean = true) : TypeDecoder<NpcType>(NPCS) {

    override fun create(size: Int) = Array(size) { NpcType(it) }

    private val encoder = NpcEncoder()

    override fun canonicalOpcodes(definition: NpcType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0x7f

    override fun getArchive(id: Int) = id ushr 7

    override  fun NpcType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                val length = buffer.readUnsignedByte()
                modelIds = IntArray(length)
                for (count in 0 until length) {
                    modelIds!![count] = buffer.readBigSmart()
                }
            }
            2 -> name = buffer.readString()
            12 -> size = buffer.readUnsignedByte()
            in 30..34 -> options[opcode - 30] = buffer.readString()
            39, 101 -> contrast = 5 * buffer.readByte()
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            42 -> readColourPalette(buffer)
            44, 45 -> keep(opcode, buffer.capture { readShort() })
            60 -> dialogueModels = IntArray(buffer.readUnsignedByte()) { buffer.readBigSmart() }
            93 -> drawMinimapDot = false
            95 -> combat = buffer.readUnsignedShort()
            97 -> scaleXY = buffer.readUnsignedShort()
            98 -> scaleZ = buffer.readUnsignedShort()
            99 -> priorityRender = true
            100 -> ambient = buffer.readByte() + 64
            102 -> keep(opcode, buffer.capture {
                var mask = readUnsignedByte()
                while (mask > 0) {
                    if (mask and 0x1 == 1) {
                        readBigSmart()
                        readSmart()
                    }
                    mask = mask ushr 1
                }
            })
            103 -> rotation = buffer.readUnsignedShort()
            106, 118, 187, 188 -> {
                varbit = buffer.readVarbit(wide = opcode >= 187)
                varp = buffer.readUnsignedShort()
                if (varp == 65535) varp = -1
                var last = -1
                if (opcode == 118 || opcode == 188) {
                    last = buffer.readUnsignedShort()
                    if (last == 65535) last = -1
                }
                val length = buffer.readSmart()
                transforms = IntArray(length + 2)
                for (count in 0..length) {
                    transforms!![count] = buffer.readUnsignedShort()
                    if (transforms!![count] == 65535) transforms!![count] = -1
                }
                transforms!![length + 1] = last
            }
            107 -> clickable = false
            109 -> keep(opcode, buffer.capture { })
            111 -> animateIdle = false
            113 -> {
                primaryShadowColour = buffer.readUnsignedShort().toShort()
                secondaryShadowColour = buffer.readUnsignedShort().toShort()
            }
            114 -> {
                primaryShadowModifier = buffer.readByte().toByte()
                secondaryShadowModifier = buffer.readByte().toByte()
            }
            119 -> walkMask = buffer.readByte().toByte()
            121 -> {
                val count = buffer.readUnsignedByte()
                translations = Array<IntArray?>(count) { IntArray(4) { buffer.readByte() } }
            }
            122 -> hitbarGraphic = buffer.readUnsignedShort()
            123 -> height = buffer.readUnsignedShort()
            125 -> respawnDirection = buffer.readByte().toByte()
            127 -> renderEmote = buffer.readUnsignedShort()
            128 -> keep(opcode, buffer.capture { skip(1) })
            134 -> {
                walkSound = buffer.readUnsignedShort()
                if (walkSound == 65535) {
                    walkSound = -1
                }
                crawlSound = buffer.readUnsignedShort()
                if (crawlSound == 65535) {
                    crawlSound = -1
                }
                idleSound = buffer.readUnsignedShort()
                if (idleSound == 65535) {
                    idleSound = -1
                }
                runSound = buffer.readUnsignedShort()
                if (runSound == 65535) {
                    runSound = -1
                }
                soundDistance = buffer.readUnsignedByte()
            }
            135 -> {
                primaryCursorOp = buffer.readUnsignedByte()
                primaryCursor = buffer.readUnsignedShort()
            }
            136 -> {
                secondaryCursorOp = buffer.readUnsignedByte()
                secondaryCursor = buffer.readUnsignedShort()
            }
            137 -> attackCursor = buffer.readUnsignedShort()
            138 -> armyIcon = buffer.readBigSmart()
            139 -> graphicId = buffer.readBigSmart()
            140 -> ambientSoundVolume = buffer.readUnsignedByte()
            141 -> visiblePriority = true
            142 -> mapFunction = buffer.readUnsignedShort()
            143 -> invisiblePriority = true
            in 150..154 -> {
                options[opcode - 150] = buffer.readString()
                if (!members) {
                    options[opcode - 150] = null
                }
            }
            155 -> {
                hue = buffer.readByte().toByte()
                saturation = buffer.readByte().toByte()
                lightness = buffer.readByte().toByte()
                opacity = buffer.readByte().toByte()
            }
            158 -> mainOptionIndex = 1.toByte()
            159 -> mainOptionIndex = 0.toByte()
            160 -> {
                val length = buffer.readUnsignedByte()
                unknown160 = IntArray(length) { buffer.readUnsignedShort() }
            }
            163 -> unknown163 = buffer.readUnsignedByte()
            164 -> {
                unknown164a = buffer.readUnsignedShort()
                unknown164b = buffer.readUnsignedShort()
            }
            165 -> unknown165 = buffer.readUnsignedByte()
            168 -> unknown168 = buffer.readUnsignedByte()
            169 -> keep(opcode, buffer.capture { })
            in 170..175 -> keep(opcode, buffer.capture { skip(2) })
            176 -> keep(opcode, buffer.capture { repeat(6) { readSmart() } })
            178 -> keep(opcode, buffer.capture { })
            179 -> keep(opcode, buffer.capture { repeat(6) { readSmart() } })
            180 -> keep(opcode, buffer.capture { skip(1) })
            181 -> keep(opcode, buffer.capture { skip(3) })
            182, 185 -> keep(opcode, buffer.capture { })
            183, 184 -> keep(opcode, buffer.capture { skip(1) })
            186, 189 -> keep(opcode, buffer.capture {
                readUnsignedShort()
                if (opcode == 189) readUnsignedMedium() else readUnsignedShort()
                readUnsignedShort()
                val flags = readUnsignedByte()
                if (flags and 0x1 != 0) {
                    repeat(readUnsignedByte()) {
                        skip(1)
                        repeat(readUnsignedByte()) {
                            readUnsignedShort()
                            readUnsignedShort()
                            readBigSmart()
                            skip(minOf(readUnsignedByte(), 3))
                        }
                    }
                }
                if (flags and 0x2 != 0) {
                    repeat(readUnsignedByte()) {
                        skip(1)
                        repeat(readUnsignedByte()) {
                            readUnsignedShort()
                            readUnsignedShort()
                            readBigSmart()
                        }
                    }
                }
                if (flags and 0x4 != 0) {
                    repeat(readUnsignedByte()) {
                        skip(1)
                        skip(readUnsignedByte() * 8)
                    }
                }
                if (flags and 0x8 != 0) {
                    repeat(readUnsignedByte()) {
                        skip(1)
                        skip(readUnsignedByte() * 8)
                    }
                }
                if (flags and 0x10 != 0) {
                    skip(readUnsignedByte() * 8)
                }
                readUnsignedShort()
            })
            249 -> readParameters(buffer)
            252 -> keep(opcode, buffer.capture { skip(2) })
            253 -> keep(opcode, buffer.capture { skip(1) })
            else -> unknown(opcode, buffer)
        }
    }
}
