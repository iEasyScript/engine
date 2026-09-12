package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.Parameterized
import world.gregs.voidps.cache.type.ParamRecord

data class ComponentType(
    override var id: Int = -1,
    var version: Int = -1,
    var type: Int = 0,
    var debugName: String? = null,
    var contentType: Int = 0,
    var basePositionX: Int = 0,
    var basePositionY: Int = 0,
    var baseWidth: Int = 0,
    var baseHeight: Int = 0,
    var horizontalSizeMode: Int = 0,
    var verticalSizeMode: Int = 0,
    var horizontalPositionMode: Int = 0,
    var verticalPositionMode: Int = 0,
    var aspectWidth: Int = 0,
    var aspectHeight: Int = 0,
    var parent: Int = -1,
    var flags: Int = 0,
    var layer: ComponentLayer? = null,
    var rectangle: ComponentRectangle? = null,
    var text: ComponentText? = null,
    var graphic: ComponentGraphic? = null,
    var model: ComponentModel? = null,
    var line: ComponentLine? = null,
    var type10: ComponentType10? = null,
    var panel: ComponentPanel? = null,
    var checkbox: ComponentCheckbox? = null,
    var input: ComponentInput? = null,
    var grid: ComponentGrid? = null,
    var dropdown: ComponentDropdown? = null,
    var settings: Int = 0,
    var unknownSettings: Int = 0,
    var unknownSettingsByte: Int = 0,
    var keys: Map<Int, ComponentKey>? = null,
    var name: String = "",
    var options: List<String>? = null,
    var mouseIcons: Map<Int, Int>? = null,
    var optionOverride: String = "",
    var dragDeadZone: Int = 0,
    var dragDeadTime: Int = 0,
    var dragRenderBehaviour: Int = 0,
    var targetVerb: String = "",
    var targetSlot: Int = -1,
    var unknownTargetShort: Int = 0,
    var unknownTargetShort2: Int = 0,
    var unknownVersionedShort: Int = 0,
    override var params: Map<Int, Any>? = null,
    var paramStringVersions: Map<Int, Int>? = null,
    var hooks: Map<Int, ComponentHook>? = null,
    var varTriggers: List<Int>? = null,
    var invTriggers: List<Int>? = null,
    var statTriggers: List<Int>? = null,
    var varcTriggers: List<Int>? = null,
    var varcStringTriggers: List<Int>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
) : CacheType, Extra, Parameterized {

    override var paramRecords: List<ParamRecord>? = null

    val hidden: Boolean get() = flags and HIDDEN != 0

    val interfaceId: Int get() = InterfaceType.id(id)

    val componentId: Int get() = InterfaceType.componentId(id)

    fun hook(slot: Int): ComponentHook? = hooks?.get(slot)

    val onClick: ComponentHook? get() = hook(ComponentHookSlot.CLICK)

    val onClickRepeat: ComponentHook? get() = hook(ComponentHookSlot.CLICK_REPEAT)

    val onRelease: ComponentHook? get() = hook(ComponentHookSlot.RELEASE)

    val onHold: ComponentHook? get() = hook(ComponentHookSlot.HOLD)

    val onMouseOver: ComponentHook? get() = hook(ComponentHookSlot.MOUSE_OVER)

    val onMouseRepeat: ComponentHook? get() = hook(ComponentHookSlot.MOUSE_REPEAT)

    val onMouseLeave: ComponentHook? get() = hook(ComponentHookSlot.MOUSE_LEAVE)

    val onDrag: ComponentHook? get() = hook(ComponentHookSlot.DRAG)

    val onDragComplete: ComponentHook? get() = hook(ComponentHookSlot.DRAG_COMPLETE)

    val onScrollWheel: ComponentHook? get() = hook(ComponentHookSlot.SCROLL_WHEEL)

    val onTargetEnter: ComponentHook? get() = hook(ComponentHookSlot.TARGET_ENTER)

    val onTargetLeave: ComponentHook? get() = hook(ComponentHookSlot.TARGET_LEAVE)

    val onVarTransmit: ComponentHook? get() = hook(ComponentHookSlot.VAR_TRANSMIT)

    val onInvTransmit: ComponentHook? get() = hook(ComponentHookSlot.INV_TRANSMIT)

    val onStatTransmit: ComponentHook? get() = hook(ComponentHookSlot.STAT_TRANSMIT)

    val onVarcTransmit: ComponentHook? get() = hook(ComponentHookSlot.VARC_TRANSMIT)

    val onVarcStringTransmit: ComponentHook? get() = hook(ComponentHookSlot.VARC_STRING_TRANSMIT)

    val onLoad: ComponentHook? get() = hook(ComponentHookSlot.LOAD)

    val onTimer: ComponentHook? get() = hook(ComponentHookSlot.TIMER)

    val onOp: ComponentHook? get() = hook(ComponentHookSlot.OP)

    val onOpTarget: ComponentHook? get() = hook(ComponentHookSlot.OPT)

    companion object {
        const val HIDDEN = 0x1

        const val LAYER = 0
        const val RECTANGLE = 3
        const val TEXT = 4
        const val GRAPHIC = 5
        const val MODEL = 6
        const val LINE = 9
        const val TYPE_10 = 10
        const val PANEL = 11
        const val CHECKBOX = 12
        const val INPUT = 13
        const val GRID = 15
        const val DROPDOWN = 16
    }
}
