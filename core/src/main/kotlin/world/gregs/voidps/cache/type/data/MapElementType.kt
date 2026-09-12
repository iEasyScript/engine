package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Parameterized
import world.gregs.voidps.cache.type.Transforms
import world.gregs.voidps.cache.type.UnusedRecords
import world.gregs.voidps.cache.type.ParamRecord

data class MapElementType(
    override var id: Int = -1,
    var graphic: Int = -1,
    var graphicAlt: Int = -1,
    var name: String = "",
    var colour: Int = 0,
    var colourAlt: Int = 0,
    var field6: Int = 0,
    var field7a: Boolean = true,
    var field7b: Boolean = false,
    var field8: Boolean = false,
    var varbit9: Int = -1,
    var varp9: Int = -1,
    var field9c: Int = 0,
    var field9d: Int = 0,
    var menuOptions: Array<String?>? = null,
    var polygonX: IntArray? = null,
    var polygonY: IntArray? = null,
    var polygonFillColour: Int = 0,
    var polygonPalette: IntArray? = null,
    var polygonVertexPalette: ByteArray? = null,
    var field16: Int = -1,
    var label: String = "",
    var field18: Int = -1,
    var field19: Int = 0,
    var varbit20: Int = -1,
    var varp20: Int = -1,
    var field20c: Int = 0,
    var field20d: Int = 0,
    var field21: Int = 0,
    var field22: Int = 0,
    var field23a: Int = 0,
    var field23b: Int = 0,
    var field23c: Int = 0,
    var field24a: Int = 0,
    var field24b: Int = 0,
    var field25: Int = -1,
    override var varbit: Int = -1,
    override var varp: Int = -1,
    override var transforms: IntArray? = null,
    var field28: Int = 0,
    var field29: Int = 0,
    var field30: Int = 0,
    override var params: Map<Int, Any>? = null,
) : CacheType, Transforms, Parameterized, UnusedRecords {
    override var paramRecords: List<ParamRecord>? = null
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = MapElementType()
    }
}
