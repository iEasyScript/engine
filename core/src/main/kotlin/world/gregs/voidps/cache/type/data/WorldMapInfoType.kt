package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

data class WorldMapInfoType(
    override var id: Int = -1,
    var graphicId: Int = -1,
    var highlightGraphicId: Int = -1,
    var scaleX: Int = 0,
    var scaleY: Int = 0,
    var rotation: Int = 0,
    var textOffset: Int = 0,
    var alwaysShow: Boolean = false,
    var conditionType: Int = 0,
    var conditionValue: Int = 0,
) : CacheType
