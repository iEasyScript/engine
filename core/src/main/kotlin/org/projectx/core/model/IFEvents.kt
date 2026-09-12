package org.projectx.core.model

import world.gregs.voidps.gameval.Gameval

class IFEvents(
    val interfaceId: Int,
    val componentId: Int,
    val fromSlot: Int = 0,
    val toSlot: Int = 0,
    var settings: Int = 0,
) {
    constructor(interfaceId: Int, componentId: Int, fromSlot: Int, toSlot: Int, init: IFEvents.() -> Unit)
        : this(interfaceId, componentId, fromSlot, toSlot, 0) { init() }

    constructor(component: String, fromSlot: Int, toSlot: Int, init: IFEvents.() -> Unit)
        : this(Gameval.requireComponentHash(component), fromSlot, toSlot, init)

    private constructor(componentHash: Int, fromSlot: Int, toSlot: Int, init: IFEvents.() -> Unit)
        : this(componentHash ushr 16, componentHash and 0xFFFF, fromSlot, toSlot, 0) { init() }

    fun enableContinueButton() = apply { settings = settings or 0x1 }

    fun enableRightClickOption(id: Int) = apply {
        require(id in 0..9)
        settings = settings or (1 shl (id + 1))
    }

    fun enableRightClickOptions(vararg ids: Int) = apply { ids.forEach { enableRightClickOption(it) } }

    fun enableUseOption(flag: UseFlag) = apply { settings = settings or (flag.flag shl 11) }

    fun enableUseOptions(vararg flags: UseFlag) = apply { flags.forEach { enableUseOption(it) } }

    fun getUseOptionFlags(): Int = (settings shr 11) and 0x7f

    fun setDepth(depth: Int) = apply {
        settings = (settings and (0x7 shl 18).inv()) or ((depth and 0x7) shl 18)
    }

    fun getDepth(): Int = (settings shr 18) and 0x7

    fun enableDrag() = apply { settings = settings or (1 shl 21) }

    fun enableUseTargetability() = apply { settings = settings or (1 shl 22) }

    fun enableIgnoreDepth() = apply { settings = settings or (1 shl 23) }

    fun enableAllowTargetSend() = apply { settings = settings or (1 shl 24) }

    override fun toString(): String {
        val sb = StringBuilder("IFEvents($interfaceId, $componentId")
        if (fromSlot != 0 || toSlot != 0) sb.append(", $fromSlot, $toSlot")
        sb.append(")")
        if (settings == 0) return sb.toString()

        if (settings and 0x1 != 0) sb.append(".enableContinueButton()")

        val ops = (0..9).filter { settings and (1 shl (it + 1)) != 0 }
        if (ops.size > 1) sb.append(".enableRightClickOptions(${ops.joinToString(", ")})")
        else if (ops.size == 1) sb.append(".enableRightClickOption(${ops[0]})")

        val useFlags = UseFlag.entries.filter { (settings shr 11) and it.flag != 0 }
        if (useFlags.size > 1) sb.append(".enableUseOptions(${useFlags.joinToString(", ") { "UseFlag.${it.name}" }})")
        else if (useFlags.size == 1) sb.append(".enableUseOption(UseFlag.${useFlags[0].name})")

        val depth = (settings shr 18) and 0x7
        if (depth != 0) sb.append(".setDepth($depth)")

        if (settings and (1 shl 21) != 0) sb.append(".enableDrag()")
        if (settings and (1 shl 22) != 0) sb.append(".enableUseTargetability()")
        if (settings and (1 shl 23) != 0) sb.append(".enableIgnoreDepth()")
        if (settings and (1 shl 24) != 0) sb.append(".enableAllowTargetSend()")

        return sb.toString()
    }
}

enum class UseFlag(val flag: Int) {
    GROUND_ITEM(0x1),
    NPC(0x2),
    WORLD_OBJECT(0x4),
    PLAYER(0x8),
    SELF(0x10),
    ICOMPONENT(0x20),
    WORLD_TILE(0x40);
}
