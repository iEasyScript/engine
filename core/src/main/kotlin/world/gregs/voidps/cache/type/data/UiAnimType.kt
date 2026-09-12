package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** A curve knot and the one bezier control that, with the next knot's, shapes the segment after it. */
data class UiAnimKnot(val x: Float, val y: Float, val controlX: Float, val controlY: Float)

data class UiAnimCurveType(
    override var id: Int = -1,
    var knots: List<UiAnimKnot> = emptyList(),
) : CacheType

/**
 * One interface animation preset. A [curveKind] the client does not know ends the record, so
 * everything after it is absent rather than defaulted, and [keys] holds as many words per keyframe
 * as [property]'s arity - three for one property, two for two of them, one for the rest.
 */
data class UiAnimType(
    override var id: Int = -1,
    var curveKind: Int = 0,
    var curve: Int? = null,
    var reverse: Int? = null,
    var property: Int? = null,
    var valueSpace: Int? = null,
    var keys: List<Int>? = null,
) : CacheType
