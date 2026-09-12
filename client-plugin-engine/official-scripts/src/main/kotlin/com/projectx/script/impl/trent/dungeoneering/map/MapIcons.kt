package com.projectx.script.impl.trent.dungeoneering.map

import org.projectx.core.game.skill.Skill
import com.projectx.script.api.varcs
import com.projectx.script.impl.trent.dungeoneering.DungeonTables
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.GraphicFrame

object MapIcons {
    const val START = 2831
    const val BOSS = 2833

    // rand_map_player_pips_0..4 — one per party slot, drawn on 942:8 as 11px children. The local
    // player's pip gives the current map cell directly (the world↔map calibration anchor).
    val PLAYER_PIPS = 2825..2829

    // rand_map_rooms_unknown_0..3 — a room revealed on the map but not yet entered (drawn as "?").
    val UNKNOWN_ROOMS = 35883..35886

    private const val STAT_ICON_ENUM = 371
    private const val STAT_NAME_ENUM = 108
    private const val KEY_OBJ_ENUM = 3008

    // toolbelt_rand_key_1 = var_client 1812; held key index i (1..64) ⟺ varc(1811 + i) == 1.
    private const val KEY_VARC_BASE = 1811
    private const val MAX_KEYS = 64

    private val statByGraphic: Map<Int, Int> by lazy {
        Cache.enum(STAT_ICON_ENUM)?.map
            ?.mapNotNull { (stat, graphic) -> (graphic as? Int)?.let { it to stat } }
            ?.toMap() ?: emptyMap()
    }

    private val objByKeyIndex: Map<Int, Int> by lazy {
        Cache.enum(KEY_OBJ_ENUM)?.map
            ?.mapNotNull { (index, obj) -> (obj as? Int)?.let { index to it } }
            ?.toMap() ?: emptyMap()
    }

    private val keyObjs: Set<Int> by lazy { objByKeyIndex.values.toHashSet() }

    fun skillForGraphic(graphicId: Int): Skill? {
        val stat = statByGraphic[graphicId] ?: return null
        val name = Cache.enum(STAT_NAME_ENUM)?.getString(stat) ?: return null
        return DungeonTables.skillByName(name)
    }

    fun isKeyObj(objId: Int): Boolean = objId > 0 && objId in keyObjs

    const val NORTH = 1
    const val EAST = 2
    const val SOUTH = 4
    const val WEST = 8
    const val ALL_OPENINGS = NORTH or EAST or SOUTH or WEST
    private const val ROOMS_OFF_BASE = 2787
    private const val ROOMS_ON_BASE = 2806

    fun roomOpenings(graphicId: Int): Int? = when (graphicId) {
        in ROOMS_OFF_BASE..ROOMS_OFF_BASE + 18, in ROOMS_ON_BASE..ROOMS_ON_BASE + 18 -> doorMask(graphicId)
        else -> null
    }

    // A doorway is a stub of the room body that reaches the exact cell border at its centre; the body
    // otherwise sits inset. Decoded straight from the live graphic so it can never drift from what the
    // client draws. Memoised — only ~23 distinct room/"?" graphic ids ever appear.
    private val doorMaskCache = HashMap<Int, Int>()

    fun doorMask(graphicId: Int): Int = doorMaskCache.getOrPut(graphicId) { decodeDoorMask(graphicId) }

    private fun decodeDoorMask(graphicId: Int): Int {
        val graphic = Cache.graphic(graphicId) ?: return 0
        val w = graphic.maxWidth
        val h = graphic.maxHeight
        if (w <= 0 || h <= 0) return 0
        val opaque = Array(h) { BooleanArray(w) }
        graphic.frames?.forEach { paint(it, opaque, w, h) }
        val cx = w / 2
        val cy = h / 2
        var mask = 0
        if (borderRun(opaque, cx, 0, horizontal = true)) mask = mask or NORTH
        if (borderRun(opaque, cx, h - 1, horizontal = true)) mask = mask or SOUTH
        if (borderRun(opaque, 0, cy, horizontal = false)) mask = mask or WEST
        if (borderRun(opaque, w - 1, cy, horizontal = false)) mask = mask or EAST
        return mask
    }

    private fun paint(sub: GraphicFrame, opaque: Array<BooleanArray>, w: Int, h: Int) {
        val rgba = sub.rgba()
        for (y in 0 until sub.height) for (x in 0 until sub.width) {
            val i = x + y * sub.width
            if ((rgba[i * 4 + 3].toInt() and 255) == 0) continue
            val px = sub.offsetX + x
            val py = sub.offsetY + y
            if (px in 0 until w && py in 0 until h) opaque[py][px] = true
        }
    }

    private fun borderRun(opaque: Array<BooleanArray>, x: Int, y: Int, horizontal: Boolean): Boolean {
        val h = opaque.size
        val w = if (h == 0) 0 else opaque[0].size
        var count = 0
        for (d in -3..3) {
            val xx = if (horizontal) x + d else x
            val yy = if (horizontal) y else y + d
            if (yy in 0 until h && xx in 0 until w && opaque[yy][xx]) count++
        }
        return count >= 3
    }

    fun unknownRoomOpenings(graphicId: Int): Int? =
        if (graphicId in UNKNOWN_ROOMS) doorMask(graphicId) else null

    fun heldKeyObjs(): Set<Int> {
        val out = HashSet<Int>()
        for (index in 1..MAX_KEYS) {
            if (varcs.getVar(KEY_VARC_BASE + index) == 1) objByKeyIndex[index]?.let { out += it }
        }
        return out
    }

    fun heldKeyNames(): Set<String> = heldKeyObjs().mapNotNullTo(HashSet()) { Cache.obj(it)?.name }
}
