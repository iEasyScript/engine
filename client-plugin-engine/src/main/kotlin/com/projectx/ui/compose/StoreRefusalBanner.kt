package com.projectx.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.projectx.store.Instances
import com.projectx.store.StartRefusal
import com.projectx.store.StartRefusal.Reason
import com.projectx.store.StoreNotices
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import com.projectx.util.Browser

/** Why a store script just refused to start, and the way to the store page that fixes it. */
@Composable
fun StoreRefusalBanner(refusal: StartRefusal) {
    val type = LocalType.current
    val access = refusal.access
    val confirmed = refusal.reason == Reason.CONFIRMED || refusal.reason == Reason.INSTANCE_READY
    val checking = refusal.reason == Reason.INSTANCE_CHECKING
    val accent = when {
        confirmed -> Palette.running
        checking -> Palette.amber
        else -> Palette.stop
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(shape).background(Palette.raised).border(1.dp, accent.copy(alpha = 0.6f), shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                when {
                    confirmed -> "${refusal.script} is ready"
                    checking -> "Starting ${refusal.script}..."
                    else -> "${refusal.script} can't start"
                },
                style = type.heading.copy(color = accent),
            )
            BasicText(explanation(refusal), style = type.body)
            access?.prices?.takeIf { !access.free && !confirmed }?.let {
                BasicText(it.tiers() + if (it.salePercent > 0) ", ${it.salePercent}% off right now" else "", style = type.label.copy(color = Palette.muted))
            }
        }
        if (!confirmed && access != null) {
            ActionButton("Get it from the store", { Browser.open(access.storeUrl) }, tone = ButtonTone.Primary, height = 36.dp)
        }
        if (refusal.reason == Reason.NO_INSTANCE) {
            ActionButton("Add an instance", { Browser.open(Instances.instancesUrl()) }, tone = ButtonTone.Primary, height = 36.dp)
        }
        ActionButton("Dismiss", { StoreNotices.dismiss() }, height = 36.dp)
    }
}

private fun explanation(refusal: StartRefusal): String {
    val free = refusal.access?.free == true
    return when (refusal.reason) {
        Reason.CONFIRMED -> "You have it now. Press Start again."
        Reason.INSTANCE_READY -> "This client has an instance now. Press Start again."
        Reason.INSTANCE_CHECKING -> "Getting an instance for this client. It starts by itself in a moment."
        Reason.NO_INSTANCE -> "All your instances are in use (${Instances.used} of ${Instances.total}). Stop the scripts on another client, or add an instance on the website."
        Reason.INSTANCE_SIGNED_OUT -> "Scripts need a Project X account. Sign in with Discord in the launcher's Store tab, then press Start again."
        Reason.INSTANCE_UNREACHABLE -> "Couldn't reach xclient.dev to check your instances. Press Start again in a moment."
        Reason.INSTANCE_STOPPED -> "This client was stopped from the website. Restart the client to run scripts on it again."
        Reason.UNREACHABLE -> "The store couldn't be reached to check whether you have it. Try again in a moment."
        Reason.SIGN_IN_EXPIRED -> "Your store sign-in is no longer valid. Sign out and back in on the launcher's Store tab, then press Start again."
        Reason.SIGNED_OUT -> "It's a store script. Sign in with Discord in the launcher's Store tab, then " +
            (if (free) "get it free on the store." else "subscribe to it on the store.")
        Reason.CHECKING, Reason.NOT_OWNED ->
            if (free) "It's free, but you need to get it from the store before it runs. It lasts 30 days and renews for free. Press Start again once you have it."
            else "You need a subscription from the store to run it (${refusal.access?.prices?.summary(false) ?: "see the store"}). Press Start again once you have it."
    }
}
