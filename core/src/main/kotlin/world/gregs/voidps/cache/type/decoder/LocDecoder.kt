package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.OBJECTS
import world.gregs.voidps.cache.type.data.LocLight
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.cache.type.encoder.LocEncoder

open class LocDecoder(
    val members: Boolean = true,
    val lowDetail: Boolean = false
) : TypeDecoder<LocType>(OBJECTS) {

    override fun create(size: Int) = Array(size) { LocType(it) }

    private val encoder = LocEncoder()

    override fun canonicalOpcodes(definition: LocType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun LocType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1, 5 -> {
                if (opcode == 5 && lowDetail) {
                    skip(buffer)
                }
                val length = buffer.readUnsignedByte()
                modelTypes = ByteArray(length)
                this.modelIds = Array(length) { count ->
                    modelTypes!![count] = buffer.readByte().toByte()
                    val size = buffer.readUnsignedByte()
                    IntArray(size) { buffer.readBigSmart() }
                }
                if (opcode == 5 && !lowDetail) {
                    skip(buffer)
                }
            }
            2 -> name = buffer.readString()
            14 -> sizeX = buffer.readUnsignedByte()
            15 -> sizeY = buffer.readUnsignedByte()
            17 -> {
                blocksSky = false
                solid = 0
                block = block and LocType.PROJECTILE.inv()
            }
            18 -> {
                blocksSky = false
                block = block and LocType.PROJECTILE.inv()
            }
            19 -> interactive = buffer.readUnsignedByte()
            21 -> contouredGround = 1
            22 -> delayShading = true
            23 -> culling = 1
            24 -> {
                singleAnimation = buffer.readBigSmart()
                if (singleAnimation != -1) {
                    animations = intArrayOf(singleAnimation)
                }
            }
            27 -> solid = 1
            28 -> offsetMultiplier = buffer.readUnsignedByte() shl 2
            29 -> ambient = buffer.readByte() + 64
            in 30..34 -> {
                if (options == null) {
                    options = arrayOf(null, null, null, null, null, "Examine")
                }
                options!![opcode - 30] = buffer.readString()
            }
            39 -> contrast = buffer.readByte() * 5
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            42 -> readColourPalette(buffer)
            44, 45 -> keep(opcode, buffer.capture { readShort() })
            62 -> mirrored = true
            64 -> castsShadow = false
            65 -> modelSizeX = buffer.readShort()
            66 -> modelSizeZ = buffer.readShort()
            67 -> modelSizeY = buffer.readShort()
            69 -> blockFlag = buffer.readUnsignedByte()
            70 -> offsetX = buffer.readUnsignedShort() shl 2
            71 -> offsetZ = buffer.readUnsignedShort() shl 2
            72 -> offsetY = buffer.readUnsignedShort() shl 2
            73 -> blocksLand = true
            74 -> {
                ignoreOnRoute = true
                block = block and LocType.ROUTE.inv()
            }
            75 -> supportItems = buffer.readUnsignedByte()
            77, 92 -> readTransforms(buffer, opcode == 92, wideVarbit = false)
            78 -> {
                unknown78a = buffer.readShort()
                unknown78b = buffer.readUnsignedByte()
            }
            79 -> {
                unknown79a = buffer.readShort()
                unknown79b = buffer.readShort()
                unknown79d = buffer.readUnsignedByte()
                val length = buffer.readUnsignedByte()
                unknown79c = IntArray(length) { buffer.readShort() }
            }
            81 -> {
                contouredGround = 2.toByte()
                contouredGroundValue = buffer.readUnsignedByte() * 256
            }
            82 -> hideMinimap = true
            88 -> unknown88 = false
            89 -> animateImmediately = false
            91 -> isMembers = true
            93 -> {
                contouredGround = 3
                contouredGroundValue = buffer.readShort()
            }
            94 -> contouredGround = 4
            95 -> {
                contouredGround = 5
                contouredGroundValue = buffer.readUnsignedShort()
            }
            97 -> unknown97 = true
            98 -> unknown98 = true
            101 -> unknown101 = buffer.readUnsignedByte()
            102 -> mapscene = buffer.readShort()
            103 -> culling = 0
            104 -> unknown104 = buffer.readUnsignedByte()
            105 -> invertMapScene = true
            106 -> {
                val length = buffer.readUnsignedByte()
                var total = 0
                animations = IntArray(length)
                percents = IntArray(length)
                for (index in 0 until length) {
                    animations!![index] = buffer.readBigSmart()
                    percents!![index] = buffer.readUnsignedByte()
                    total += percents!![index]
                }
                rawPercents = percents!!.copyOf()
                for (count in 0 until length) {
                    percents!![count] = 65535 * percents!![count] / total
                }
            }
            107 -> mapDefinitionId = buffer.readShort()
            108, 109, 110, 111 -> keep(opcode, buffer.capture { })
            in 150..154 -> {
                if (options == null) {
                    options = arrayOf(null, null, null, null, null, "Examine")
                }
                options!![opcode - 150] = buffer.readString()
                if (!members) {
                    options!![opcode - 150] = null
                }
            }
            160 -> unknown160 = IntArray(buffer.readUnsignedByte()) { buffer.readShort() }
            162 -> {
                contouredGround = 3
                contouredGroundValue = buffer.readInt()
            }
            163 -> {
                unknown163a = buffer.readByte().toByte()
                unknown163b = buffer.readByte().toByte()
                unknown163c = buffer.readByte().toByte()
                unknown163d = buffer.readByte().toByte()
            }
            164 -> unknown164 = buffer.readUnsignedShort()
            165 -> unknown165 = buffer.readUnsignedShort()
            166 -> unknown166 = buffer.readUnsignedShort()
            167 -> unknown167 = buffer.readShort()
            170, 171 -> keep(opcode, buffer.capture { readSignedSmart() })
            173 -> {
                unknown173a = buffer.readShort()
                unknown173b = buffer.readShort()
            }
            177 -> keep(opcode, buffer.capture { })
            178 -> unknown178 = buffer.readUnsignedByte()
            186 -> keep(opcode, buffer.capture { skip(1) })
            188 -> keep(opcode, buffer.capture { })
            189 -> dynamicTint = true
            in 190..195 -> keep(opcode, buffer.capture { skip(2) })
            196 -> keep(opcode, buffer.capture { skip(1) })
            197 -> keep(opcode, buffer.capture { skip(1) })
            198, 199, 200, 203 -> keep(opcode, buffer.capture { })
            201 -> keep(opcode, buffer.capture { repeat(6) { readSmart() } })
            202 -> keep(opcode, buffer.capture { skip(1) })
            204 -> keep(opcode, buffer.capture {
                repeat(readSmart()) {
                    readUnsignedShort()
                    skip(25)
                }
            })
            205, 209 -> keep(opcode, buffer.capture {
                readUnsignedShort()
                if (opcode == 209) readUnsignedMedium() else readUnsignedShort()
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
            206 -> {
                keep(opcode, buffer.capture { skip(2) })
                lights = List(buffer.readUnsignedByte()) { readLight(buffer) }
            }
            207, 208 -> readTransforms(buffer, opcode == 208, wideVarbit = true)
            249 -> readParameters(buffer)
            250 -> keep(opcode, buffer.capture { skip(1) })
            251 -> keep(opcode, buffer.capture { skip(1) })
            252 -> keep(opcode, buffer.capture { skip(6) })
            253 -> keep(opcode, buffer.capture { skip(1) })
            254 -> keep(opcode, buffer.capture { skip(1) })
            255 -> keep(opcode, buffer.capture { skip(6) })
            else -> unknown(opcode, buffer)
        }
    }

    companion object {
        private fun readLight(buffer: Reader): LocLight {
            val flags = buffer.readUnsignedByte()
            return LocLight(
                flag0 = flags and 0x1 != 0,
                flag1 = flags and 0x2 != 0,
                flag2 = flags and 0x4 != 0,
                x = buffer.readFloat(),
                y = -buffer.readFloat(),
                z = buffer.readFloat(),
                type = buffer.readUnsignedByte(),
                intensity = buffer.readFloat(),
                colour = buffer.readUnsignedMedium(),
                unknownShort = buffer.readUnsignedShort(),
                unknownSigned = buffer.readShort(),
                unknownX = buffer.readFloat(),
                unknownY = buffer.readFloat(),
                unknownZ = buffer.readFloat(),
            )
        }

        private fun skip(buffer: Reader) {
            val length = buffer.readUnsignedByte()
            for (i in 0 until length) {
                buffer.skip(1)
                val amount = buffer.readUnsignedByte()
                buffer.skip(amount * 2)
            }
        }
    }
}