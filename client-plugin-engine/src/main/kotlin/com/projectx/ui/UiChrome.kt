package com.projectx.ui

import com.projectx.ui.backend.native.ImGuiTexture

/**
 * UI-chrome textures (the logo) warmed on the RENDER thread at ImGui (re)init, where the game's GL
 * context is current so creation is a reliable inline upload. Holding the strong ref keeps the entry
 * live in ImageHelper's by-path cache, so every later `fromPath` on the main-logic thread hits the cache
 * instead of the cross-thread deferred queue — which is unreliable in the first frames after a reinject.
 */
object UiChrome {
    const val LOGO_PATH = "/icons/logo_alpha.png"

    /** White so callers tint per state; ImGui has no star glyph to fall back on. */
    const val STAR_ON_PATH = "/icons/star_on.png"
    const val STAR_OFF_PATH = "/icons/star_off.png"

    @Volatile
    var logo: ImGuiTexture? = null
        private set

    @Volatile
    var starOn: ImGuiTexture? = null
        private set

    @Volatile
    var starOff: ImGuiTexture? = null
        private set

    /** Call only from the render thread (eglSwapBuffers) with the GL context current. */
    fun warm() {
        if (logo == null) logo = ImGuiTexture.fromPath(LOGO_PATH)
        if (starOn == null) starOn = ImGuiTexture.fromPath(STAR_ON_PATH)
        if (starOff == null) starOff = ImGuiTexture.fromPath(STAR_OFF_PATH)
    }
}
