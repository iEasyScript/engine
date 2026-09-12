package world.gregs.voidps.cache.type.data

interface ComponentContent

data class ComponentLayer(
    var scrollWidth: Int = 0,
    var scrollHeight: Int = 0,
    var noClickThrough: Int = 0,
    var scrollBar: List<Int> = emptyList(),
    var scrollBarColour: Int = 0,
) : ComponentContent

data class ComponentRectangle(
    var colour: Int = 0,
    var filled: Boolean = false,
    var alpha: Int = 0,
) : ComponentContent

data class ComponentModel(
    var modelId: Int = -1,
    var flags: Int = 0,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var offsetZ: Int = 0,
    var pitch: Int = 0,
    var roll: Int = 0,
    var yaw: Int = 0,
    var scale: Int = 0,
    var animation: Int = -1,
    var viewportWidth: Int = 0,
    var viewportHeight: Int = 0,
) : ComponentContent

data class ComponentLine(
    var width: Int = 0,
    var colour: Int = 0,
    var mirrored: Boolean = false,
) : ComponentContent

data class ComponentType10(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknown5: Int = 0,
    var unknownColour: Int = 0,
    var alpha: Int = 0,
    var colour: Int = 0,
    var graphic: ComponentGraphic = ComponentGraphic(),
    var text: ComponentText = ComponentText(),
) : ComponentContent

data class ComponentPanel(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Boolean = false,
    var unknown4: Int = 0,
) : ComponentContent

data class ComponentCheckbox(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknown5: Int = 0,
    var colour: Int = 0,
    var graphic: ComponentGraphic = ComponentGraphic(),
    var text: ComponentText = ComponentText(),
) : ComponentContent

data class ComponentInput(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknownColour: Int = 0,
    var unknown5: Int = 0,
    var alpha: Int = 0,
    var colour: Int = 0,
    var graphic: ComponentGraphic = ComponentGraphic(),
    var text: ComponentText = ComponentText(),
    var graphics: ComponentGraphicSet = ComponentGraphicSet(),
) : ComponentContent

data class ComponentGrid(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknown5: Int = 0,
    var unknown6: Boolean = false,
) : ComponentContent

data class ComponentDropdown(
    var unknown1: Int = 0,
    var unknown2: Int = 0,
    var unknown3: Int = 0,
    var unknown4: Int = 0,
    var unknown5: Int = 0,
    var unknown6: Int = 0,
    var unknown7: Int = 0,
    var entryNames: List<String> = emptyList(),
    var entryValueCount: Int = 0,
    var entryValues: List<Int>? = null,
    var entryOrder: List<Int> = emptyList(),
    var unknownColour1: Int = 0,
    var unknownColour2: Int = 0,
    var alpha: Int = 0,
    var colour: Int = 0,
    var graphics: List<ComponentGraphic> = emptyList(),
    var text: ComponentText = ComponentText(),
    var stateGraphics: ComponentGraphicSet = ComponentGraphicSet(),
) : ComponentContent
