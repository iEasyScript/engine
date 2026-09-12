package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra

/**
 * Per-character metrics for one font, indexed by cp1252 code: the glyph's box, and where that box
 * sits in the font's texture atlas.
 */
data class FontType(
    override var id: Int = -1,
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var glyphWidths: ByteArray = byteArrayOf(),
    var glyphHeights: ByteArray = byteArrayOf(),
    var glyphTopOffsets: ByteArray = byteArrayOf(),
    var atlasWidth: Int = 0,
    var atlasHeight: Int = 0,
    var glyphAtlasX: IntArray = IntArray(0),
    var glyphAtlasY: IntArray = IntArray(0),
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknown5: Int = 0,
    var unknown6: Int = 0,
    var unknown7: Int = 0,
    var unknown8: Int = 0,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : CacheType, Extra {

    private fun glyphWidth(glyph: Int): Int {
        return glyphWidths[glyph].toInt() and 0xff
    }

    fun textWidth(input: String, icons: Array<GraphicFrame>? = null): Int {
        var tagStart = -1
        var totalWidth = 0
        for (index in input.indices) {
            var current = input[index]
            if (current.code == 60) {
                tagStart = index
                continue
            }
            if (current.code == 62 && tagStart != -1) {
                val tag = input.substring(tagStart + 1, index)
                tagStart = -1
                val tagChar = htmlEntityToChar(tag)
                if (tagChar == null) {
                    totalWidth += graphicWidth(tag, icons) ?: continue
                    continue
                }
                current = tagChar
            }
            if (tagStart == -1) {
                totalWidth += glyphWidth(charToByte(current) and 0xff)
            }
        }
        return totalWidth
    }

    fun truncateText(input: String, maxWidth: Int, icons: Array<GraphicFrame>? = emptyArray()): String {
        var maximumWidth = maxWidth
        if (maximumWidth >= textWidth(input, icons)) {
            return input
        }
        maximumWidth -= textWidth("...", null)
        var tagStart = -1
        var totalWidth = 0
        var prefix = ""
        for (index in input.indices) {
            var current = input[index]
            if (current.code == 60) {
                tagStart = index
                continue
            }
            if (current.code == 62 && tagStart != -1) {
                val tag = input.substring(tagStart - -1, index)
                tagStart = -1
                val tagChar = htmlEntityToChar(tag)
                if (tagChar == null) {
                    totalWidth += graphicWidth(tag, icons) ?: continue
                    if (totalWidth > maximumWidth) {
                        return "$prefix..."
                    }
                    prefix = input.substring(0, index - -1)
                    continue
                }
                current = tagChar
            }
            if (tagStart == -1) {
                totalWidth += glyphWidth(charToByte(current) and 0xff)
                if (totalWidth > maximumWidth) {
                    return "$prefix..."
                }
                prefix = input.substring(0, index + 1)
            }
        }
        return input
    }

    fun splitLines(input: String, width: Int, icons: Array<GraphicFrame>? = null) = splitLines(input, intArrayOf(width), icons)

    fun splitLines(input: String, widths: IntArray? = null, icons: Array<GraphicFrame>? = null): List<String> {
        val output = mutableListOf<String>()
        var totalWidth = 0
        var lineStart = 0
        var lineLength = -1
        var wordWidth = 0
        var wordStart = 0
        var tagStart = -1
        for (index in input.indices) {
            var current: Int = charToByte(input[index]) and 0xff
            var extraWidth = 0
            if (current == 60) {
                tagStart = index
                continue
            }
            var currentWidth: Int
            if (tagStart == -1) {
                extraWidth += glyphWidth(current)
                currentWidth = index
            } else {
                if (current != 62) {
                    continue
                }
                currentWidth = tagStart
                val tag = input.substring(1 + tagStart, index)
                tagStart = -1
                if (tag == "br") {
                    output.add(input.substring(lineStart, index - -1))
                    lineStart = index + 1
                    lineLength = -1
                    totalWidth = 0
                    continue
                }
                val entity = htmlEntityToChar(tag)
                if (entity != null) {
                    extraWidth += glyphWidth(charToByte(entity) and 0xff)
                } else {
                    totalWidth += graphicWidth(tag, icons) ?: continue
                }
                current = -1
            }
            if (extraWidth <= 0) {
                continue
            }
            totalWidth += extraWidth
            if (widths == null) {
                continue
            }
            if (current == 32) {
                wordStart = 1
                wordWidth = totalWidth
                lineLength = index
            }
            if (totalWidth > widths[if (widths.size > output.size) output.size else widths.size - 1]) {
                if (lineLength >= 0) {
                    output.add(input.substring(lineStart, lineLength + 1 - wordStart))
                    lineStart = lineLength + 1
                    lineLength = -1
                    totalWidth -= wordWidth
                } else {
                    output.add(input.substring(lineStart, currentWidth))
                    lineStart = currentWidth
                    lineLength = -1
                    totalWidth = extraWidth
                }
            }
            if (current == 45) {
                wordWidth = totalWidth
                lineLength = index
                wordStart = 0
            }
        }
        if (lineStart < input.length) {
            output.add(input.substring(lineStart))
        }
        return output
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FontType

        if (id != other.id) return false
        if (unknown1 != other.unknown1) return false
        if (unknown2 != other.unknown2) return false
        if (!glyphWidths.contentEquals(other.glyphWidths)) return false
        if (!glyphHeights.contentEquals(other.glyphHeights)) return false
        if (!glyphTopOffsets.contentEquals(other.glyphTopOffsets)) return false
        if (atlasWidth != other.atlasWidth) return false
        if (atlasHeight != other.atlasHeight) return false
        if (!glyphAtlasX.contentEquals(other.glyphAtlasX)) return false
        if (!glyphAtlasY.contentEquals(other.glyphAtlasY)) return false
        if (unknown3 != other.unknown3) return false
        if (unknown4 != other.unknown4) return false
        if (unknown5 != other.unknown5) return false
        if (unknown6 != other.unknown6) return false
        if (unknown7 != other.unknown7) return false
        if (unknown8 != other.unknown8) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + unknown1
        result = 31 * result + unknown2
        result = 31 * result + glyphWidths.contentHashCode()
        result = 31 * result + glyphHeights.contentHashCode()
        result = 31 * result + glyphTopOffsets.contentHashCode()
        result = 31 * result + atlasWidth
        result = 31 * result + atlasHeight
        result = 31 * result + glyphAtlasX.contentHashCode()
        result = 31 * result + glyphAtlasY.contentHashCode()
        result = 31 * result + unknown3
        result = 31 * result + unknown4
        result = 31 * result + unknown5
        result = 31 * result + unknown6
        result = 31 * result + unknown7
        result = 31 * result + unknown8
        result = 31 * result + stringId.hashCode()
        result = 31 * result + (extras?.hashCode() ?: 0)
        return result
    }

    companion object {
        private fun graphicWidth(tag: String, icons: Array<GraphicFrame>?): Int? {
            if (!tag.startsWith("img=") || icons == null) {
                return null
            }
            return try {
                val id = parseInt(tag.substring(4))
                icons[id].canvasWidth
            } catch (exception: Exception) {
                null
            }
        }

        private fun htmlEntityToChar(tag: String): Char? {
            return when (tag) {
                "lt" -> '<'
                "gt" -> '>'
                "nbsp" -> '\u00a0'
                "shy" -> '\u00ad'
                "times" -> '\u00d7'
                "euro" -> '\u20ac'
                "copy" -> '\u00a9'
                "reg" -> '\u00ae'
                else -> null
            }
        }

        private fun parseInt(string: String, radix: Int = 10, positive: Boolean = true): Int {
            require(radix in 2..36) { "Invalid radix: $radix" }
            var negative = false
            var valid = false
            var result = 0
            for (index in string.indices) {
                var digit = string[index].code
                if (index == 0) {
                    if (digit == 45) {
                        negative = true
                        continue
                    }
                    if (digit == 43 && positive) {
                        continue
                    }
                }
                digit -= if (digit in 48..57) 48 else if (digit in 65..90) 55 else if (digit in 97..122) 87 else throw NumberFormatException()
                if (digit >= radix) {
                    throw NumberFormatException()
                }
                if (negative) {
                    digit = -digit
                }
                val temp = (radix * result) + digit
                if (result != temp / radix) {
                    throw NumberFormatException()
                }
                valid = true
                result = temp
            }
            if (!valid) {
                throw NumberFormatException()
            }
            return result
        }

        private fun charToByte(c: Char): Int {
            if (c.code in 1..127 || c.code in 160..255) {
                return c.code
            }
            return when (c.code) {
                8364 -> -128
                8218 -> -126
                402 -> -125
                8222 -> -124
                8230 -> -123
                8224 -> -122
                8225 -> -121
                710 -> -120
                8240 -> -119
                352 -> -118
                8249 -> -117
                338 -> -116
                381 -> -114
                8216 -> -111
                8217 -> -110
                8220 -> -109
                8221 -> -108
                8226 -> -107
                8211 -> -106
                8212 -> -105
                732 -> -104
                8482 -> -103
                353 -> -102
                8250 -> -101
                339 -> -100
                382 -> -98
                376 -> -97
                else -> 63
            }
        }

        val EMPTY = FontType()
    }

}
