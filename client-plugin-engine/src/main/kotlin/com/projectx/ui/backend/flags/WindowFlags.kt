package com.projectx.ui.backend.flags

@JvmInline
value class WindowFlags(val value: Int) {
    companion object {
        val None = WindowFlags(0)
        val NoTitleBar = WindowFlags(1 shl 0)
        val NoResize = WindowFlags(1 shl 1) 
        val NoMove = WindowFlags(1 shl 2)
        val NoScrollbar = WindowFlags(1 shl 3)
        val NoScrollWithMouse = WindowFlags(1 shl 4)
        val NoCollapse = WindowFlags(1 shl 5)
        val AlwaysAutoResize = WindowFlags(1 shl 6)
        val NoBackground = WindowFlags(1 shl 7)
        val NoSavedSettings = WindowFlags(1 shl 8)
        val NoMouseInputs = WindowFlags(1 shl 9)
        val MenuBar = WindowFlags(1 shl 10)
        val HorizontalScrollbar = WindowFlags(1 shl 11)
        val NoFocusOnAppearing = WindowFlags(1 shl 12)
        val NoBringToFrontOnFocus = WindowFlags(1 shl 13)
        val NoDecoration = WindowFlags(NoTitleBar.value or NoResize.value or NoScrollbar.value or NoCollapse.value)
    }
    
    operator fun plus(other: WindowFlags) = WindowFlags(value or other.value)
}