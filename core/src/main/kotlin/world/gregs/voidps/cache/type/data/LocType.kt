package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.*

data class LocType(
    override var id: Int = -1,
    var modelIds: Array<IntArray>? = null,
    var modelTypes: ByteArray? = null,
    var name: String = "null",
    var sizeX: Int = 1,
    var sizeY: Int = 1,
    var blocksSky: Boolean = true,
    var solid: Int = 2,
    var interactive: Int = -1,
    var contouredGround: Byte = 0,
    var delayShading: Boolean = false,
    var offsetMultiplier: Int = 64,
    var ambient: Int = 0,
    var options: Array<String?>? = null,
    var contrast: Int = 0,
    override var originalColours: ShortArray? = null,
    override var modifiedColours: ShortArray? = null,
    override var originalTextureColours: ShortArray? = null,
    override var modifiedTextureColours: ShortArray? = null,
    override var recolourPalette: ByteArray? = null,
    var mirrored: Boolean = false,
    var castsShadow: Boolean = true,
    var modelSizeX: Int = 128,
    var modelSizeZ: Int = 128,
    var modelSizeY: Int = 128,
    var blockFlag: Int = 0,
    var offsetX: Int = 0,
    var offsetZ: Int = 0,
    var offsetY: Int = 0,
    var blocksLand: Boolean = false,
    var ignoreOnRoute: Boolean = false,
    var supportItems: Int = -1,
    override var varbit: Int = -1,
    override var varp: Int = -1,
    override var transforms: IntArray? = null,
    var unknown78a: Int = -1,
    var unknown78b: Int = 0,
    var unknown79a: Int = 0,
    var unknown79b: Int = 0,
    var unknown79c: IntArray? = null,
    var contouredGroundValue: Int = -1,
    var hideMinimap: Boolean = false,
    var unknown88: Boolean = true,
    var animateImmediately: Boolean = true,
    var isMembers: Boolean = false,
    var unknown97: Boolean = false,
    var unknown98: Boolean = false,
    var unknown101: Int = 0,
    var mapscene: Int = -1,
    var culling: Int = -1,
    var unknown104: Int = 255,
    var invertMapScene: Boolean = false,
    var animations: IntArray? = null,
    var percents: IntArray? = null,
    var mapDefinitionId: Int = -1,
    var unknown160: IntArray? = null,
    var unknown163a: Byte = 0,
    var unknown163b: Byte = 0,
    var unknown163c: Byte = 0,
    var unknown163d: Byte = 0,
    var unknown164: Int = 0,
    var unknown165: Int = 0,
    var unknown166: Int = 0,
    var unknown167: Int = 0,
    var unknown173a: Int = 256,
    var unknown173b: Int = 256,
    var unknown178: Int = 0,
    var dynamicTint: Boolean = false,
    var lights: List<LocLight>? = null,
    var singleAnimation: Int = -1,
    var rawPercents: IntArray? = null,
    var unknown79d: Int = 0,
    override var params: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : CacheType, Transforms, Recolourable, ColourPalette, Parameterized, Extra, UnusedRecords {
    override var paramRecords: List<ParamRecord>? = null
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    var block: Int = PROJECTILE or ROUTE

    var clipType: Int
        get() = solid
        set(value) { solid = value }

    var blocks: Boolean
        get() = blocksSky
        set(value) { blocksSky = value }

    var ignoreAltClip: Boolean
        get() = ignoreOnRoute
        set(value) { ignoreOnRoute = value }

    fun optionsIndex(option: String): Int = if (options != null) {
        options!!.indexOf(option)
    } else {
        -1
    }

    fun containsOption(option: String): Boolean = if (options != null) {
        options!!.contains(option)
    } else {
        false
    }

    fun containsOption(index: Int, option: String): Boolean = if (options != null) {
        options!![index] == option
    } else {
        false
    }

    fun containsOptionIgnoreCase(option: String): Boolean = if (options != null) {
        options!!.any { it != null && it.equals(option, ignoreCase = true) }
    } else {
        false
    }

    fun getOption(index: Int): String? = options?.getOrNull(index)

    fun getFirstOption(): String? = options?.getOrNull(0)

    fun getName(vars: Any?): String = name

    fun getOpIdForName(opName: String): Int =
        options?.indexOfFirst { it?.equals(opName, ignoreCase = true) == true } ?: -1

    fun getOp(optionId: Int): String = options?.getOrNull(optionId) ?: "null"

    fun containsOp(option: String): Boolean =
        options?.any { it?.equals(option, ignoreCase = true) == true } == true

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocType

        if (id != other.id) return false
        if (modelIds != null) {
            if (other.modelIds == null) return false
            if (!modelIds.contentDeepEquals(other.modelIds)) return false
        } else if (other.modelIds != null) return false
        if (modelTypes != null) {
            if (other.modelTypes == null) return false
            if (!modelTypes.contentEquals(other.modelTypes)) return false
        } else if (other.modelTypes != null) return false
        if (name != other.name) return false
        if (sizeX != other.sizeX) return false
        if (sizeY != other.sizeY) return false
        if (blocksSky != other.blocksSky) return false
        if (solid != other.solid) return false
        if (interactive != other.interactive) return false
        if (contouredGround != other.contouredGround) return false
        if (delayShading != other.delayShading) return false
        if (offsetMultiplier != other.offsetMultiplier) return false
        if (ambient != other.ambient) return false
        if (options != null) {
            if (other.options == null) return false
            if (!options.contentEquals(other.options)) return false
        } else if (other.options != null) return false
        if (contrast != other.contrast) return false
        if (originalColours != null) {
            if (other.originalColours == null) return false
            if (!originalColours.contentEquals(other.originalColours)) return false
        } else if (other.originalColours != null) return false
        if (modifiedColours != null) {
            if (other.modifiedColours == null) return false
            if (!modifiedColours.contentEquals(other.modifiedColours)) return false
        } else if (other.modifiedColours != null) return false
        if (originalTextureColours != null) {
            if (other.originalTextureColours == null) return false
            if (!originalTextureColours.contentEquals(other.originalTextureColours)) return false
        } else if (other.originalTextureColours != null) return false
        if (modifiedTextureColours != null) {
            if (other.modifiedTextureColours == null) return false
            if (!modifiedTextureColours.contentEquals(other.modifiedTextureColours)) return false
        } else if (other.modifiedTextureColours != null) return false
        if (recolourPalette != null) {
            if (other.recolourPalette == null) return false
            if (!recolourPalette.contentEquals(other.recolourPalette)) return false
        } else if (other.recolourPalette != null) return false
        if (mirrored != other.mirrored) return false
        if (castsShadow != other.castsShadow) return false
        if (modelSizeX != other.modelSizeX) return false
        if (modelSizeZ != other.modelSizeZ) return false
        if (modelSizeY != other.modelSizeY) return false
        if (blockFlag != other.blockFlag) return false
        if (offsetX != other.offsetX) return false
        if (offsetZ != other.offsetZ) return false
        if (offsetY != other.offsetY) return false
        if (blocksLand != other.blocksLand) return false
        if (ignoreOnRoute != other.ignoreOnRoute) return false
        if (supportItems != other.supportItems) return false
        if (varbit != other.varbit) return false
        if (varp != other.varp) return false
        if (transforms != null) {
            if (other.transforms == null) return false
            if (!transforms.contentEquals(other.transforms)) return false
        } else if (other.transforms != null) return false
        if (unknown78a != other.unknown78a) return false
        if (unknown78b != other.unknown78b) return false
        if (unknown79a != other.unknown79a) return false
        if (unknown79b != other.unknown79b) return false
        if (unknown79c != null) {
            if (other.unknown79c == null) return false
            if (!unknown79c.contentEquals(other.unknown79c)) return false
        } else if (other.unknown79c != null) return false
        if (contouredGroundValue != other.contouredGroundValue) return false
        if (hideMinimap != other.hideMinimap) return false
        if (unknown88 != other.unknown88) return false
        if (animateImmediately != other.animateImmediately) return false
        if (isMembers != other.isMembers) return false
        if (unknown97 != other.unknown97) return false
        if (unknown98 != other.unknown98) return false
        if (unknown101 != other.unknown101) return false
        if (mapscene != other.mapscene) return false
        if (culling != other.culling) return false
        if (unknown104 != other.unknown104) return false
        if (invertMapScene != other.invertMapScene) return false
        if (animations != null) {
            if (other.animations == null) return false
            if (!animations.contentEquals(other.animations)) return false
        } else if (other.animations != null) return false
        if (percents != null) {
            if (other.percents == null) return false
            if (!percents.contentEquals(other.percents)) return false
        } else if (other.percents != null) return false
        if (mapDefinitionId != other.mapDefinitionId) return false
        if (unknown160 != null) {
            if (other.unknown160 == null) return false
            if (!unknown160.contentEquals(other.unknown160)) return false
        } else if (other.unknown160 != null) return false
        if (unknown163a != other.unknown163a) return false
        if (unknown163b != other.unknown163b) return false
        if (unknown163c != other.unknown163c) return false
        if (unknown163d != other.unknown163d) return false
        if (unknown164 != other.unknown164) return false
        if (unknown165 != other.unknown165) return false
        if (unknown166 != other.unknown166) return false
        if (unknown167 != other.unknown167) return false
        if (unknown173a != other.unknown173a) return false
        if (unknown173b != other.unknown173b) return false
        if (unknown178 != other.unknown178) return false
        if (dynamicTint != other.dynamicTint) return false
        if (lights != other.lights) return false
        if (params != other.params) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (modelIds?.contentDeepHashCode() ?: 0)
        result = 31 * result + (modelTypes?.contentHashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + sizeX
        result = 31 * result + sizeY
        result = 31 * result + blocksSky.hashCode()
        result = 31 * result + solid
        result = 31 * result + interactive
        result = 31 * result + contouredGround
        result = 31 * result + delayShading.hashCode()
        result = 31 * result + offsetMultiplier
        result = 31 * result + ambient
        result = 31 * result + (options?.contentHashCode() ?: 0)
        result = 31 * result + contrast
        result = 31 * result + (originalColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedColours?.contentHashCode() ?: 0)
        result = 31 * result + (originalTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (recolourPalette?.contentHashCode() ?: 0)
        result = 31 * result + mirrored.hashCode()
        result = 31 * result + castsShadow.hashCode()
        result = 31 * result + modelSizeX
        result = 31 * result + modelSizeZ
        result = 31 * result + modelSizeY
        result = 31 * result + blockFlag
        result = 31 * result + offsetX
        result = 31 * result + offsetZ
        result = 31 * result + offsetY
        result = 31 * result + blocksLand.hashCode()
        result = 31 * result + ignoreOnRoute.hashCode()
        result = 31 * result + supportItems
        result = 31 * result + varbit
        result = 31 * result + varp
        result = 31 * result + (transforms?.contentHashCode() ?: 0)
        result = 31 * result + unknown78a
        result = 31 * result + unknown78b
        result = 31 * result + unknown79a
        result = 31 * result + unknown79b
        result = 31 * result + (unknown79c?.contentHashCode() ?: 0)
        result = 31 * result + contouredGroundValue
        result = 31 * result + hideMinimap.hashCode()
        result = 31 * result + unknown88.hashCode()
        result = 31 * result + animateImmediately.hashCode()
        result = 31 * result + isMembers.hashCode()
        result = 31 * result + unknown97.hashCode()
        result = 31 * result + unknown98.hashCode()
        result = 31 * result + unknown101
        result = 31 * result + mapscene
        result = 31 * result + culling
        result = 31 * result + unknown104
        result = 31 * result + invertMapScene.hashCode()
        result = 31 * result + (animations?.contentHashCode() ?: 0)
        result = 31 * result + (percents?.contentHashCode() ?: 0)
        result = 31 * result + mapDefinitionId
        result = 31 * result + (unknown160?.contentHashCode() ?: 0)
        result = 31 * result + unknown163a
        result = 31 * result + unknown163b
        result = 31 * result + unknown163c
        result = 31 * result + unknown163d
        result = 31 * result + unknown164
        result = 31 * result + unknown165
        result = 31 * result + unknown166
        result = 31 * result + unknown167
        result = 31 * result + unknown173a
        result = 31 * result + unknown173b
        result = 31 * result + unknown178
        result = 31 * result + dynamicTint.hashCode()
        result = 31 * result + (lights?.hashCode() ?: 0)
        result = 31 * result + (params?.hashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + (extras?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val ROUTE = 0x10
        const val PROJECTILE = 0x8
        val EMPTY = LocType()
    }
}
