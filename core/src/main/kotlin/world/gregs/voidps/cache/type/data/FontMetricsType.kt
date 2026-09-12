package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One font handle: either a scalable face rendered by FreeType, or a bitmap face whose glyph
 * imagery is a graphic sheet and whose per-character metrics are these tables.
 *
 * [version] selects the shape and is the only discriminator the file carries. The scalable shape
 * holds [fontFileId] and [pointSize]; the bitmap shapes hold everything from [flags] down, with
 * [graphicGroupId] present only from the newer of the two - the older shape substitutes the record's
 * own group id, which is why the legacy index's group ids are graphic group ids.
 *
 * The five per-character tables are indexed by character code. [pad] is read, scaled and stored by
 * the client but no consumer for it has been traced, so it keeps a positional name.
 */
data class FontMetricsType(
    override var id: Int = -1,
    var version: Int = -1,
    var fontFileId: Int? = null,
    var pointSize: Int? = null,
    var flags: Int? = null,
    var graphicGroupId: Int? = null,
    var cellWidth: IntArray? = null,
    var cellHeight: IntArray? = null,
    var topBearing: IntArray? = null,
    var atlasWidth: Int? = null,
    var atlasHeight: Int? = null,
    var atlasX: IntArray? = null,
    var atlasY: IntArray? = null,
    var baseline: Int? = null,
    var pad: IntArray? = null,
    var downscale: Int? = null,
    /** The trailing block the set form of [flags] selects; its layout is neither known nor served. */
    var variableBlock: ByteArray? = null,
) : CacheType {

    companion object {
        /** The bitmap shape without [graphicGroupId]. */
        const val BITMAP_LEGACY = 0

        const val BITMAP = 1

        const val SCALABLE = 2

        /** Every table is indexed by character code, so one entry per byte value. */
        const val CHARACTERS = 256

        const val PADDING = 4

        /** The low bit of [flags]: clear selects [baseline], set selects [variableBlock]. */
        const val VARIABLE_TRAILER = 0x1
    }
}
