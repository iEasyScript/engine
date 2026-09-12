package org.projectx.core.net.prot.update

/** A single transform entry inside a SPOT_ANIMS or SPOTANIM_TRANSFORM_LIST block. */
data class SpotAnimTransform(
    val flags: Int,
    val slotId: Int,
    val startTransformId: Int = 0,
    val endTransformId: Int = 0,
    val translationX: Int = 0,
    val translationY: Int = 0,
    val translationZ: Int = 0,
    val rotationX: Int = 0,
    val rotationY: Int = 0,
    val rotationZ: Int = 0,
    val scaleX: Int = 0,
    val scaleY: Int = 0,
    val scaleZ: Int = 0,
)

