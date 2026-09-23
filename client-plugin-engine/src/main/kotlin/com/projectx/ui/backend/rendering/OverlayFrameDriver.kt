package com.projectx.ui.backend.rendering

import com.projectx.ui.compose.OverlayHost
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

            // Replay the latest buffer built on the main-logic thread (UiFrameProducer.build). The render thread
            // NEVER reads live game state - it only reads already-captured commands, so there is no thread to race
            // scene/interface mutation. Compose draws what they describe; ImGui only composites the result.
            val commands = UiFrameProducer.frames.promoteIfReadyOrKeepFront()
            UiFrameProducer.frames.beginConsume(commands)
            try {
                OverlayHost.drawFrame(commands)
            } finally {
                UiFrameProducer.frames.endConsume(commands)
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
