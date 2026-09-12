package com.projectx.ui.backend.rendering

import com.projectx.ui.UiChrome
import com.projectx.ui.backend.dsl.utils.ModernTheme.withModernTheme
import com.projectx.ui.backend.native.NativeBridge

/**
 * Draws one overlay frame on the render thread. Platform swap-buffers hooks supply their own
 * backend initialiser; everything after that is identical across platforms.
 */
object OverlayFrameDriver {
    private var initialized = false

    fun drawFrame(initBackend: () -> Unit) {
        var frameStarted = false
        try {
            if (!initialized) {
                initBackend()
                initialized = true
            }

            NativeBridge.newFrame()
            frameStarted = true

            // Render thread, GL context current — warm chrome textures here so they never rely on the
            // main-logic deferred-upload path (which fails to drain in the first frames after a reinject).
            UiChrome.warm()

            // Replay the latest buffer built on the main-logic thread (UiFrameProducer.build). The
            // render thread NEVER reads live game state — it only replays already-captured commands, so
            // there is no thread to race scene/interface mutation. (No direct-render fallback: that path
            // ran render() on this thread and was the source of the recurring SIGSEGVs.)
            val commands = UiFrameProducer.frames.promoteIfReadyOrKeepFront()
            if (commands.isNotEmpty()) {
                UiFrameProducer.frames.beginConsume(commands)
                try {
                    withModernTheme {
                        CommandRenderer.executePrecomputed(commands)
                    }
                } finally {
                    UiFrameProducer.frames.endConsume(commands)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            if (frameStarted) {
                try {
                    NativeBridge.render()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}
