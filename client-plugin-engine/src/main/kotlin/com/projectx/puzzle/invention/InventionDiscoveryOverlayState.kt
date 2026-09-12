package com.projectx.puzzle.invention

/**
 * Immutable snapshot published by [InventionDiscoveryFeature] on the main-logic thread and consumed by
 * the render thread. [slotHints] is indexed by track slot 0..4 (drawn on components 1708:14..18);
 * [lines] is the guidance panel text.
 */
data class InventionDiscoveryOverlayState(
    val slotHints: Array<SlotHint>,
    val lines: List<String>,
    val solved: Boolean,
) {
    companion object {
        const val INTERFACE_ID = 1708
        const val FIRST_ICON_COMPONENT = 14
    }
}
