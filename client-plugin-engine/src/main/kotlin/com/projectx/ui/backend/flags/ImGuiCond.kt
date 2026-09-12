package com.projectx.ui.backend.flags

@JvmInline
value class ImGuiCond(val value: Int) {
    companion object {
        val None = ImGuiCond(0)
        val Always = ImGuiCond(1 shl 0)
        val Once = ImGuiCond(1 shl 1)
        val FirstUseEver = ImGuiCond(1 shl 2)
        val Appearing = ImGuiCond(1 shl 3)
    }
}