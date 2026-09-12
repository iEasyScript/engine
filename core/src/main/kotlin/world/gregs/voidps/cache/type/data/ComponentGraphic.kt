package world.gregs.voidps.cache.type.data

data class ComponentGraphic(
    var graphicId: Int = -1,
    var angle: Int = 0,
    var tiling: Int = 0,
    var alpha: Int = 0,
    var outline: Int = 0,
    var outlineColour: Int = 0,
    var flipVertical: Boolean = false,
    var flipHorizontal: Boolean = false,
    var colour: Int = 0,
    var unknownFlag: Boolean = false,
    var unknownColour: Int = 0,
) : ComponentContent
