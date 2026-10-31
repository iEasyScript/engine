package com.projectx.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectx.ui.UIState
import com.projectx.ui.compose.GameThread

/** A store script that was asked to start and could not, and what the store has said about it since. */
data class StartRefusal(val script: String, val access: StoreAccess?, val reason: Reason) {
    enum class Reason {
        CHECKING, NOT_OWNED, SIGNED_OUT, UNREACHABLE, CONFIRMED, SIGN_IN_EXPIRED,
        NO_INSTANCE, INSTANCE_SIGNED_OUT, INSTANCE_UNREACHABLE, INSTANCE_STOPPED, INSTANCE_READY,
    }
}

/**
 * The refusal the overlay shows. Raised wherever a start came from - the panel, a hotkey, another script - so the
 * overlay is opened for it rather than leaving the player wondering why nothing happened.
 */
object StoreNotices {
    var refusal by mutableStateOf<StartRefusal?>(null)
        private set

    // Written on the main-logic thread, like the rest of the overlay's models, whichever thread the start came from.
    fun refused(script: String, access: StoreAccess?, reason: StartRefusal.Reason) = GameThread.post {
        refusal = StartRefusal(script, access, reason)
        UIState.showMainWindow.value = true
    }

    fun dismiss() = GameThread.post { refusal = null }
}
