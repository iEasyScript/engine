package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.FLOOR_UNDERLAY
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.UnderlayType
import world.gregs.voidps.cache.type.encoder.UnderlayEncoder

class UnderlayDecoder : ConfigDecoder<UnderlayType>(FLOOR_UNDERLAY) {

    override fun create(size: Int) = Array(size) { UnderlayType(it) }

    private val encoder = UnderlayEncoder()

    override fun canonicalOpcodes(definition: UnderlayType): IntArray = encoder.opcodes(definition)

    override fun UnderlayType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                colour = buffer.readUnsignedMedium()
                computeHsl(colour)
            }
            2 -> {
                texture = buffer.readUnsignedShort()
                if (texture == 65535) {
                    texture = -1
                }
            }
            3 -> scale = buffer.readUnsignedShort() shl 2
            4 -> blockShadow = false
            5 -> Unit
            else -> unknown(opcode, buffer)
        }
    }

    private fun UnderlayType.computeHsl(rgb: Int) {
        val r = (rgb shr 16 and 0xff).toDouble() / 256.0
        val g = (rgb shr 8 and 0xff).toDouble() / 256.0
        val b = (rgb and 0xff).toDouble() / 256.0

        var minimum = r
        if (g < minimum) minimum = g
        if (b < minimum) minimum = b

        var maximum = r
        if (g > maximum) maximum = g
        if (b > maximum) maximum = b

        val l = (minimum + maximum) / 2.0

        var h = 0.0
        var s = 0.0
        if (minimum != maximum) {
            s = if (l < 0.5) {
                (maximum - minimum) / (maximum + minimum)
            } else {
                (maximum - minimum) / (2.0 - maximum - minimum)
            }

            h = when (maximum) {
                r -> (g - b) / (maximum - minimum)
                g -> 2.0 + (b - r) / (maximum - minimum)
                else -> 4.0 + (r - g) / (maximum - minimum)
            }
        }

        saturation = (s * 256.0).toInt()
        lightness = (l * 256.0).toInt()

        h /= 6.0

        chroma = if (l > 0.5) {
            (s * (1.0 - l) * 512.0).toInt()
        } else {
            (s * l * 512.0).toInt()
        }

        if (saturation < 0) saturation = 0
        else if (saturation > 255) saturation = 255

        if (lightness < 0) lightness = 0
        else if (lightness > 255) lightness = 255

        if (chroma < 1) chroma = 1

        hue = (h * chroma.toDouble()).toInt()
    }
}