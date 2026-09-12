package org.projectx.core.net.prot.update

/** One hitmark inside an update mask's HITMARKS list. */
data class Hit(
    val type: Int,
    val damage: Int = -1,
    val soak: Int = -1,
    val delay: Int = -1,
    val duration: Int = -1,
    val tintType: Int = -1,
)

