package com.projectx.game.input.wire

import com.projectx.game.platform.Platform

/** A mouse packet family the client can put on the wire. */
enum class WirePacketVariant {
    /** One entry from the click ring. Always a button press - there is no "moved only" encoding. */
    MOUSE_CLICK,

    /** Variable-length history drained from the movement ring. The only way position alone is reported. */
    MOUSE_MOVEMENT_HISTORY,

    /** Windows only: the OS hardware-vs-injected verdict paired with a click. */
    NATIVE_MOUSE_CLICK,

    /** Windows only: the native history packet, carrying one source-flag byte per movement sample. */
    NATIVE_MOUSE_MOVEMENT_HISTORY,
}

/**
 * Which packet families a synthetic cursor trail has to be able to produce on this platform.
 *
 * There are four mouse prots, not one: each of the two senders emits a conditional movement-history packet and
 * an unconditionally called click packet. Windows carries the second sender, which additionally appends a
 * source-flags byte per movement sample. Emitting a subset is worse than emitting nothing - a trail that
 * announces clicks, or history whose paired source bytes are missing, is a stronger signal than silence. So
 * [complete] gates the wire path rather than merely warning.
 *
 * Resolved once here so a new platform, or a build that changes the set, is a single edit.
 */
object WirePacketVariants {
    val required: Set<WirePacketVariant> =
        if (Platform.current == Platform.WINDOWS) {
            setOf(
                WirePacketVariant.MOUSE_MOVEMENT_HISTORY,
                WirePacketVariant.NATIVE_MOUSE_CLICK,
                WirePacketVariant.NATIVE_MOUSE_MOVEMENT_HISTORY,
            )
        } else {
            setOf(WirePacketVariant.MOUSE_MOVEMENT_HISTORY)
        }

    val unavailable: Map<WirePacketVariant, String>
        get() = buildMap {
            if (WirePacketVariant.MOUSE_MOVEMENT_HISTORY in required) {
                EventMouseMoveSupport.unavailableReason?.let { put(WirePacketVariant.MOUSE_MOVEMENT_HISTORY, it) }
            }
            if (WirePacketVariant.NATIVE_MOUSE_CLICK in required) {
                NativeMouseClickSupport.unavailableReason?.let { put(WirePacketVariant.NATIVE_MOUSE_CLICK, it) }
            }
            if (WirePacketVariant.NATIVE_MOUSE_MOVEMENT_HISTORY in required) {
                NativeMouseClickSupport.unavailableReason?.let {
                    put(WirePacketVariant.NATIVE_MOUSE_MOVEMENT_HISTORY, it)
                }
            }
        }

    val complete: Boolean get() = unavailable.isEmpty()

    fun describe(): String =
        if (complete) "wire packet variants: ${required.joinToString { it.name }}"
        else "wire path disabled - " + unavailable.entries.joinToString("; ") { "${it.key.name}: ${it.value}" }
}
