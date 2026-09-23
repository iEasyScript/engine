package com.projectx.script

import androidx.compose.runtime.Composable

/**
 * A script that draws its own panel with Compose, shown as a card beside the other script windows.
 *
 * [Panel] runs on the render thread, which must never read game memory - the game thread changes it while the panel
 * draws. Read game state with [Script.live], or keep it in `mutableStateOf` properties that `loop()` updates, and have
 * [Panel] only display those. The components in `com.projectx.ui.compose.components` and the theme in
 * `com.projectx.ui.compose.theme` give a panel the overlay's look.
 *
 * A panel that throws is taken down and logged rather than left to break the other cards.
 */
interface ComposePanel {
    /** The card's title; null uses the script's name. */
    val panelTitle: String? get() = null

    @Composable
    fun Panel()
}
