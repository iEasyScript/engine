package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.Recolourable
import world.gregs.voidps.cache.type.OpcodeOrdered

data class SpotAnimType(
    override var id: Int = -1,
    var modelId: Int = 0,
    var animationId: Int = -1,
    var scaleX: Float = 1.0f,
    var scaleY: Float = 1.0f,
    var rotation: Float = 0.0f,
    var ambience: Int = 0,
    var unknown9a: Byte = 0,
    var unknown9b: Int = -1,
    var unknown10: Boolean = false,
    override var originalColours: ShortArray? = null,
    override var modifiedColours: ShortArray? = null,
    override var originalTextureColours: ShortArray? = null,
    override var modifiedTextureColours: ShortArray? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    var unknown8: Int = 0,
    var unknown44: Int = 0,
    var unknown45: Int = 0,
) : CacheType, Recolourable, Extra, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpotAnimType

        if (id != other.id) return false
        if (modelId != other.modelId) return false
        if (animationId != other.animationId) return false
        if (scaleX != other.scaleX) return false
        if (scaleY != other.scaleY) return false
        if (rotation != other.rotation) return false
        if (ambience != other.ambience) return false
        if (unknown9a != other.unknown9a) return false
        if (unknown9b != other.unknown9b) return false
        if (unknown10 != other.unknown10) return false
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
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + modelId
        result = 31 * result + animationId
        result = 31 * result + scaleX.hashCode()
        result = 31 * result + scaleY.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + ambience
        result = 31 * result + unknown9a
        result = 31 * result + unknown9b
        result = 31 * result + unknown10.hashCode()
        result = 31 * result + (originalColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedColours?.contentHashCode() ?: 0)
        result = 31 * result + (originalTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + extras.hashCode()
        return result
    }
    companion object {
        val EMPTY = SpotAnimType()
    }
}