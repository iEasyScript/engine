package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.*

data class NpcType(
    override var id: Int = -1,
    var modelIds: IntArray? = null,
    var name: String = "null",
    var size: Int = 1,
    var options: Array<String?> = arrayOf(null, null, null, null, null, "Examine"),
    override var originalColours: ShortArray? = null,
    override var modifiedColours: ShortArray? = null,
    override var originalTextureColours: ShortArray? = null,
    override var modifiedTextureColours: ShortArray? = null,
    override var recolourPalette: ByteArray? = null,
    var dialogueModels: IntArray? = null,
    var drawMinimapDot: Boolean = true,
    var combat: Int = -1,
    var scaleXY: Int = 128,
    var scaleZ: Int = 128,
    var priorityRender: Boolean = false,
    var ambient: Int = 0,
    var contrast: Int = 0,
    var headIcon: Int = -1,
    var rotation: Int = 32,
    override var varbit: Int = -1,
    override var varp: Int = -1,
    override var transforms: IntArray? = null,
    var clickable: Boolean = true,
    var slowWalk: Boolean = true,
    var animateIdle: Boolean = true,
    var primaryShadowColour: Short = 0,
    var secondaryShadowColour: Short = 0,
    var primaryShadowModifier: Byte = -96,
    var secondaryShadowModifier: Byte = -16,
    var walkMask: Byte = 0,
    var translations: Array<IntArray?>? = null,
    var hitbarGraphic: Int = -1,
    var height: Int = -1,
    var respawnDirection: Byte = 4,
    var renderEmote: Int = -1,
    var idleSound: Int = -1,
    var crawlSound: Int = -1,
    var walkSound: Int = -1,
    var runSound: Int = -1,
    var soundDistance: Int = 0,
    var primaryCursorOp: Int = -1,
    var primaryCursor: Int = -1,
    var secondaryCursorOp: Int = -1,
    var secondaryCursor: Int = -1,
    var attackCursor: Int = -1,
    var armyIcon: Int = -1,
    var graphicId: Int = -1,
    var ambientSoundVolume: Int = 255,
    var visiblePriority: Boolean = false,
    var mapFunction: Int = -1,
    var invisiblePriority: Boolean = false,
    var hue: Byte = 0,
    var saturation: Byte = 0,
    var lightness: Byte = 0,
    var opacity: Byte = 0,
    var mainOptionIndex: Byte = -1,
    var unknown160: IntArray? = null,
    var unknown163: Int = -1,
    var unknown164a: Int = 256,
    var unknown164b: Int = 256,
    var unknown165: Int = 0,
    var unknown168: Int = 0,
    override var params: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : CacheType, Transforms, Recolourable, ColourPalette, Parameterized, Extra, UnusedRecords {
    override var paramRecords: List<ParamRecord>? = null
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NpcType

        if (id != other.id) return false
        if (modelIds != null) {
            if (other.modelIds == null) return false
            if (!modelIds.contentEquals(other.modelIds)) return false
        } else if (other.modelIds != null) return false
        if (name != other.name) return false
        if (size != other.size) return false
        if (!options.contentEquals(other.options)) return false
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
        if (dialogueModels != null) {
            if (other.dialogueModels == null) return false
            if (!dialogueModels.contentEquals(other.dialogueModels)) return false
        } else if (other.dialogueModels != null) return false
        if (drawMinimapDot != other.drawMinimapDot) return false
        if (combat != other.combat) return false
        if (scaleXY != other.scaleXY) return false
        if (scaleZ != other.scaleZ) return false
        if (priorityRender != other.priorityRender) return false
        if (ambient != other.ambient) return false
        if (contrast != other.contrast) return false
        if (headIcon != other.headIcon) return false
        if (rotation != other.rotation) return false
        if (varbit != other.varbit) return false
        if (varp != other.varp) return false
        if (transforms != null) {
            if (other.transforms == null) return false
            if (!transforms.contentEquals(other.transforms)) return false
        } else if (other.transforms != null) return false
        if (clickable != other.clickable) return false
        if (slowWalk != other.slowWalk) return false
        if (animateIdle != other.animateIdle) return false
        if (primaryShadowColour != other.primaryShadowColour) return false
        if (secondaryShadowColour != other.secondaryShadowColour) return false
        if (primaryShadowModifier != other.primaryShadowModifier) return false
        if (secondaryShadowModifier != other.secondaryShadowModifier) return false
        if (walkMask != other.walkMask) return false
        if (translations != null) {
            if (other.translations == null) return false
            if (!translations.contentDeepEquals(other.translations)) return false
        } else if (other.translations != null) return false
        if (hitbarGraphic != other.hitbarGraphic) return false
        if (height != other.height) return false
        if (respawnDirection != other.respawnDirection) return false
        if (renderEmote != other.renderEmote) return false
        if (idleSound != other.idleSound) return false
        if (crawlSound != other.crawlSound) return false
        if (walkSound != other.walkSound) return false
        if (runSound != other.runSound) return false
        if (soundDistance != other.soundDistance) return false
        if (primaryCursorOp != other.primaryCursorOp) return false
        if (primaryCursor != other.primaryCursor) return false
        if (secondaryCursorOp != other.secondaryCursorOp) return false
        if (secondaryCursor != other.secondaryCursor) return false
        if (attackCursor != other.attackCursor) return false
        if (armyIcon != other.armyIcon) return false
        if (graphicId != other.graphicId) return false
        if (ambientSoundVolume != other.ambientSoundVolume) return false
        if (visiblePriority != other.visiblePriority) return false
        if (mapFunction != other.mapFunction) return false
        if (invisiblePriority != other.invisiblePriority) return false
        if (hue != other.hue) return false
        if (saturation != other.saturation) return false
        if (lightness != other.lightness) return false
        if (opacity != other.opacity) return false
        if (mainOptionIndex != other.mainOptionIndex) return false
        if (unknown160 != null) {
            if (other.unknown160 == null) return false
            if (!unknown160.contentEquals(other.unknown160)) return false
        } else if (other.unknown160 != null) return false
        if (unknown163 != other.unknown163) return false
        if (unknown164a != other.unknown164a) return false
        if (unknown164b != other.unknown164b) return false
        if (unknown165 != other.unknown165) return false
        if (unknown168 != other.unknown168) return false
        if (params != other.params) return false
        if (stringId != other.stringId) return false
        return extras == other.extras
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (modelIds?.contentHashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + size
        result = 31 * result + options.contentHashCode()
        result = 31 * result + (originalColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedColours?.contentHashCode() ?: 0)
        result = 31 * result + (originalTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (modifiedTextureColours?.contentHashCode() ?: 0)
        result = 31 * result + (recolourPalette?.contentHashCode() ?: 0)
        result = 31 * result + (dialogueModels?.contentHashCode() ?: 0)
        result = 31 * result + drawMinimapDot.hashCode()
        result = 31 * result + combat
        result = 31 * result + scaleXY
        result = 31 * result + scaleZ
        result = 31 * result + priorityRender.hashCode()
        result = 31 * result + ambient
        result = 31 * result + contrast
        result = 31 * result + headIcon
        result = 31 * result + rotation
        result = 31 * result + varbit
        result = 31 * result + varp
        result = 31 * result + (transforms?.contentHashCode() ?: 0)
        result = 31 * result + clickable.hashCode()
        result = 31 * result + slowWalk.hashCode()
        result = 31 * result + animateIdle.hashCode()
        result = 31 * result + primaryShadowColour
        result = 31 * result + secondaryShadowColour
        result = 31 * result + primaryShadowModifier
        result = 31 * result + secondaryShadowModifier
        result = 31 * result + walkMask
        result = 31 * result + (translations?.contentDeepHashCode() ?: 0)
        result = 31 * result + hitbarGraphic
        result = 31 * result + height
        result = 31 * result + respawnDirection
        result = 31 * result + renderEmote
        result = 31 * result + idleSound
        result = 31 * result + crawlSound
        result = 31 * result + walkSound
        result = 31 * result + runSound
        result = 31 * result + soundDistance
        result = 31 * result + primaryCursorOp
        result = 31 * result + primaryCursor
        result = 31 * result + secondaryCursorOp
        result = 31 * result + secondaryCursor
        result = 31 * result + attackCursor
        result = 31 * result + armyIcon
        result = 31 * result + graphicId
        result = 31 * result + ambientSoundVolume
        result = 31 * result + visiblePriority.hashCode()
        result = 31 * result + mapFunction
        result = 31 * result + invisiblePriority.hashCode()
        result = 31 * result + hue
        result = 31 * result + saturation
        result = 31 * result + lightness
        result = 31 * result + opacity
        result = 31 * result + mainOptionIndex
        result = 31 * result + (unknown160?.contentHashCode() ?: 0)
        result = 31 * result + unknown163
        result = 31 * result + unknown164a
        result = 31 * result + unknown164b
        result = 31 * result + unknown165
        result = 31 * result + unknown168
        result = 31 * result + (params?.hashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + (extras?.hashCode() ?: 0)
        return result
    }

    val combatLevel: Int get() = combat

    fun hasAttackOption(): Boolean = options.contains("Attack")

    fun hasOption(option: String): Boolean = options.contains(option)

    fun getOption(index: Int): String? = options.getOrNull(index)

    fun getName(vars: Any?): String = name

    val headModels: IntArray? get() = dialogueModels

    val transformTo: IntArray? get() = transforms

    var varpBit: Int
        get() = varbit
        set(value) { varbit = value }

    fun getOpIdForName(opName: String): Int =
        options.indexOfFirst { it.equals(opName, ignoreCase = true) }.takeIf { it >= 0 } ?: -1

    fun getOp(optionId: Int): String = options.getOrNull(optionId) ?: "null"

    fun containsOp(i: Int, option: String): Boolean =
        options.getOrNull(i)?.equals(option, ignoreCase = true) == true

    fun containsOp(option: String): Boolean =
        options.any { it?.equals(option, ignoreCase = true) == true }

    companion object {
        val EMPTY = NpcType()
    }
}
