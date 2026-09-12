package com.projectx.game

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/**
 * SpotAnims and projectiles have no gameval of their own (the `graphic` gameval names graphics). The
 * cache spotanim definition (index 21, `Cache.spotAnim`) links an animation (a `seq`) whose gameval
 * name identifies the effect — so resolve the spotanim/projectile name through its linked seq.
 */
fun spotAnimName(spotAnimId: Int): String? =
    Cache.spotAnim(spotAnimId)?.animationId?.takeIf { it >= 0 }?.let { Gameval.seq(it) }

fun spotAnimLabel(spotAnimId: Int): String {
    val name = spotAnimName(spotAnimId)
    return if (name != null) "$name ($spotAnimId)" else spotAnimId.toString()
}
