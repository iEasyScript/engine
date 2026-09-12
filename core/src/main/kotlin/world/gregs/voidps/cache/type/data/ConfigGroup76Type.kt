package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

/** One of the six identical sub-records the water surface preset carries in its repeating block. */
data class ConfigGroup76Block(
    var enabled: Int? = null,
    var scalarA: Float? = null,
    var scalarB: Float? = null,
    var scalarC: Float? = null,
    var pairA: List<Float>? = null,
    var pairB: List<Float>? = null,
    var scalarD: Float? = null,
    var scalarE: Float? = null,
)

/**
 * The config index's water surface shading preset - how water is shaded, as opposed to [WaterType],
 * which says which tiles count as water.
 *
 * `ConfigGroup76Type` is a deliberately positional name: the binding to the config group is proven
 * but the Jagex type name is not known. The material ids are established from the gameval material
 * dictionary; everything the client does nothing traceable with keeps a positional name. Scale
 * factors the client applies at load time are not applied here - every field is the value the file
 * carries, so a record round trips through its own bytes.
 */
data class ConfigGroup76Type(
    override var id: Int = -1,
    var diffuseMaterialA: Int? = null,
    var normalScaleA: Int? = null,
    var diffuseMaterialB: Int? = null,
    var normalScaleB: Int? = null,
    var unknown5: Int? = null,
    var colour: Int? = null,
    var unknown7: IntArray? = null,
    var unknown8: Int? = null,
    var edgeMaterial: Int? = null,
    var edgeScale: Int? = null,
    var unknown11: Int? = null,
    var packedColour: Int? = null,
    var unknown13: Int? = null,
    var unknown14: Int? = null,
    var unknown15: Int? = null,
    var unknown16: Int? = null,
    var unknown17: Int? = null,
    var unknown18: Int? = null,
    var unknown19: Int? = null,
    var unknown20: Int? = null,
    var unknown21: Int? = null,
    var maskMaterial: Int? = null,
    var unknown23: Int? = null,
    var unknown24: Int? = null,
    var unknown25: Int? = null,
    var unknown26: IntArray? = null,
    var unknown27: Int? = null,
    var unknown28: Int? = null,
    var normalMaterialA: Int? = null,
    var normalMaterialB: Int? = null,
    var unusedMaterial: Int? = null,
    var unusedMaterialScale: Int? = null,
    var blocks: List<ConfigGroup76Block> = List(BLOCKS) { ConfigGroup76Block() },
    var unknown81: Float? = null,
    var unknown82: Float? = null,
    var unknown83: Float? = null,
    var discarded: Float? = null,
    var unknown85: Int? = null,
    var unknown86: Int? = null,
    var unknown87: Int? = null,
    var unknown88: List<Float>? = null,
    var colourA: Int? = null,
    var unknown90: Float? = null,
    var unknown91: Float? = null,
    var unknown92: Int? = null,
    var unknown93: Float? = null,
    var unknown94: Float? = null,
    var unknown95: Float? = null,
    var unknown96: Int? = null,
    var unknown97: List<Float>? = null,
    var colourB: Int? = null,
    var unknown99: Float? = null,
    var unknown100: Float? = null,
    var unknown101: Float? = null,
    var unknown102: Float? = null,
    var unknown103: Float? = null,
    var unknown104: Float? = null,
    var unknown105: Float? = null,
    var unknown106: Float? = null,
    var unknown107: Float? = null,
    var unknown108: Float? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    companion object {
        const val BLOCKS = 6
    }
}
