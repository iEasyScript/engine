package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.FLOOR_OVERLAY
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.OverlayType
import world.gregs.voidps.cache.type.encoder.OverlayEncoder

class OverlayDecoder : ConfigDecoder<OverlayType>(FLOOR_OVERLAY) {

    override fun create(size: Int) = Array(size) { OverlayType(it) }

    private val encoder = OverlayEncoder()

    override fun canonicalOpcodes(definition: OverlayType): IntArray = encoder.opcodes(definition)

    override fun OverlayType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                colourRgb = buffer.readUnsignedMedium()
                colour = calculateHsl(colourRgb)
            }
            2 -> texture = buffer.readUnsignedByte()
            3 -> {
                texture = buffer.readUnsignedShort()
                if (texture == 65535) {
                    texture = -1
                }
            }
            5 -> Unit
            7 -> {
                blendColourRgb = buffer.readUnsignedMedium()
                blendColour = calculateHsl(blendColourRgb)
            }
            8 -> Unit
            9 -> scale = buffer.readUnsignedShort() shl 2
            10 -> blockShadow = false
            11 -> unknown11 = buffer.readUnsignedByte()
            12 -> underlayOverrides = true
            13 -> waterColour = buffer.readUnsignedMedium()
            14 -> waterScale = buffer.readUnsignedByte() shl 2
            16 -> waterIntensity = buffer.readUnsignedByte()
            20 -> unknown20 = buffer.readUnsignedShort()
            21 -> unknown21 = buffer.readUnsignedByte() != 0
            22 -> unknown22 = buffer.readUnsignedShort()
            else -> unknown(opcode, buffer)
        }
    }

    companion object {

        private fun calculateHsl(i: Int): Int {
            return if (i == 16711935) {
                -1
            } else rgbToHsl(i)
        }

        private fun rgbToHsl(rgb: Int): Int {
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
                    (maximum - minimum) / (minimum + maximum)
                } else {
                    (maximum - minimum) / (2.0 - maximum - minimum)
                }

                h = when (maximum) {
                    r -> (g - b) / (maximum - minimum)
                    g -> 2.0 + (b - r) / (maximum - minimum)
                    else -> 4.0 + (r - g) / (maximum - minimum)
                }
            }

            h /= 6.0

            val hue = (256.0 * h).toInt()
            var saturation = (256.0 * s).toInt()
            var lightness = (256.0 * l).toInt()

            saturation = saturation.coerceIn(0, 255)
            lightness = lightness.coerceIn(0, 255)

            saturation = when {
                lightness > 243 -> saturation shr 4
                lightness > 217 -> saturation shr 3
                lightness > 192 -> saturation shr 2
                lightness > 179 -> saturation shr 1
                else -> saturation
            }

            return ((hue and 0xff) shr 2 shl 10) + (saturation shr 5 shl 7) + (lightness shr 1)
        }
    }
}