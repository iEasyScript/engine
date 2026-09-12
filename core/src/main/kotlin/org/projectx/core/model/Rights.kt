package org.projectx.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Rights(val crown: Int) {
    PLAYER(0),
    MOD(1),
    ADMIN(2),
    DEVELOPER(2),
    OWNER(2);

    val isStaff: Boolean get() = this != PLAYER
}
