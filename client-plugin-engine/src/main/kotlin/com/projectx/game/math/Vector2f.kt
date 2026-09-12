package com.projectx.game.math

data class Vector2f(val x: Float, val y: Float) {
    fun transform(x: Float, y: Float) = Vector2f(this.x + x, this.y + y)
}
