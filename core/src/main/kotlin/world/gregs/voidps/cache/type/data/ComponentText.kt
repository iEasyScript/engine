package world.gregs.voidps.cache.type.data

data class ComponentText(
    var fontId: Int = -1,
    var fontStyle: Int = 0,
    var text: String = "",
    var lineHeight: Int = 0,
    var horizontalAlignment: Int = 0,
    var verticalAlignment: Int = 0,
    var shadowed: Boolean = false,
    var colour: Int = 0,
    var alpha: Int = 0,
    var unknownStyle: Int = 0,
) : ComponentContent
