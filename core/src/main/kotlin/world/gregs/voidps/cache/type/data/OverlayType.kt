package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class OverlayType(
    override var id: Int = -1,
    var colour: Int = 0,
    var texture: Int = -1,
    var blendColour: Int = -1,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var unknown11: Int = 8,
    var underlayOverrides: Boolean = false,
    var waterColour: Int = 0,
    var waterScale: Int = 64,
    var waterIntensity: Int = 255,
    var unknown20: Int = 63,
    var unknown21: Boolean = false,
    var unknown22: Int = 64,
    var colourRgb: Int = -1,
    var blendColourRgb: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}