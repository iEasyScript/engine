package com.projectx.ui.backend.flags

@JvmInline
value class PopupFlags(val value: Int) {
    companion object {
        val None = PopupFlags(0)
        val MouseButtonLeft = PopupFlags(0)        // Guaranteed to always be == 0 (same as ImGuiMouseButton_Left)
        val MouseButtonRight = PopupFlags(1)       // Guaranteed to always be == 1 (same as ImGuiMouseButton_Right)
        val MouseButtonMiddle = PopupFlags(2)      // Guaranteed to always be == 2 (same as ImGuiMouseButton_Middle)
        val MouseButtonMask = PopupFlags(0x1F)
        val MouseButtonDefault = PopupFlags(1)     // Default for BeginPopupContext*() functions
        val NoReopen = PopupFlags(1 shl 5)         // Don't reopen same popup if already open (won't reposition, won't reinitialize navigation)
        val NoOpenOverExistingPopup = PopupFlags(1 shl 7)  // Don't open if there's already a popup at the same level of the popup stack
        val NoOpenOverItems = PopupFlags(1 shl 8)  // For BeginPopupContextWindow(): don't return true when hovering items, only when hovering empty space
        val AnyPopupId = PopupFlags(1 shl 10)      // For IsPopupOpen(): ignore the ImGuiID parameter and test for any popup
        val AnyPopupLevel = PopupFlags(1 shl 11)   // For IsPopupOpen(): search/test at any level of the popup stack (default test in the current level)
        val AnyPopup = PopupFlags(AnyPopupId.value or AnyPopupLevel.value)  // AnyPopupId | AnyPopupLevel
    }
    
    operator fun plus(other: PopupFlags) = PopupFlags(value or other.value)
}