package com.projectx.game.math

data class Vector3f(val x: Float, val y: Float, val z: Float) {
    fun transform(x: Float, y: Float, z: Float) = Vector3f(this.x + x, this.y + y, this.z + z)
}
