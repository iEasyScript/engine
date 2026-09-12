package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * The animation a clip or a blend leaf plays. [discarded] is read by the client and thrown away,
 * so it is kept to put the record back; the three trailing fields are absent unless [hasOverride].
 */
data class AnimReference(
    val sequence: Int,
    val fieldA: Int,
    val fieldB: Int,
    val fieldC: Int,
    val flag: Int,
    val hasOverride: Int,
    val discarded: Int?,
    val overrideName: String?,
    val overrideValue: Int?,
)

/** One weighted branch of a four-part blend node: the two floats that precede its child. */
data class AnimBranch(val first: Float, val second: Float)

/**
 * One node of a blend tree. [kind] fixes which fields the file carries and how many children
 * follow: none, two, one per [weights] entry, or one per [branches] entry.
 */
data class AnimExpressionNode(
    val kind: Int,
    val reference: AnimReference?,
    val first: Float?,
    val second: Float?,
    val variable: String?,
    val source: String?,
    val target: String?,
    val weights: List<Float>?,
    val branches: List<AnimBranch>?,
    val trailing: List<Int>?,
)

/** [expression] is the blend tree flattened in read order; a node's children follow it directly. */
data class AnimClip(
    val name: String,
    val kind: Int,
    val reference: AnimReference?,
    val expression: List<AnimExpressionNode>?,
)

data class AnimTransition(
    val source: String,
    val target: String,
    val duration: Long,
    val fieldA: Int,
    val fieldB: Int,
    val variable: String,
    val fieldC: Int,
)

data class AnimLayer(
    val name: String,
    val clips: List<AnimClip>,
    val transitions: List<AnimTransition>,
)

data class AnimState(
    val layer: AnimLayer,
    val value: Int,
    val name: String,
    val first: Long,
    val second: Long,
)

/** [form] selects the whole record: a single [layer], a list of [states], or neither. */
data class AnimStateMachineType(
    override var id: Int = -1,
    var version: Int = 0,
    var form: Int = -1,
    var layer: AnimLayer? = null,
    var states: List<AnimState>? = null,
    /** Trailing zeroes: every group of this index is padded out to the same ceiling. */
    var padding: Int = 0,
) : CacheType
