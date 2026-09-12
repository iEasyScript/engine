package org.projectx.core.net.prot.update

/**
 * One headbar entry inside a HITMARKS_AND_HEADBARS block. Fills are 0..255.
 *
 * The bar tweens [fromFill] -> [toFill] over [transitionCycles]; a zero tween renders [fromFill]
 * flat and omits [toFill] from the wire entirely. [delay] only offsets activation from the client's
 * current cycle — it is NOT a lifetime: the client expires the bar off its own headbar cache def,
 * so the server never sends fade timing. [secondType] stacks an independent second bar (-1 = none).
 */
data class Headbar(
    val type: Int,
    val fromFill: Int = 0,
    val toFill: Int = 0,
    val transitionCycles: Int = 0,
    val delay: Int = 0,
    val secondType: Int = -1,
    val secondFromFill: Int = 0,
    val secondToFill: Int = 0,
    val remove: Boolean = false,
)
