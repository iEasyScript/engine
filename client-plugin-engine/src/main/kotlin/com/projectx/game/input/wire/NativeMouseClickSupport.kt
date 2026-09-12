package com.projectx.game.input.wire

import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OffsetTable
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Capability probe for the Windows-only source-report packets.
 *
 * The rings are now mapped in the Ghidra database, and the listener that fills them was shown to do nothing
 * but store the sample — no raycast, no DoAction — so driving this path is viable. Two things still block it:
 *
 *  - the ring geometry has no entries in this build's offset table, and those tables are generated from the
 *    database by the updater rather than hand-written;
 *  - the native sender has a **move** ring as well as a click ring, and its movement-history packet carries
 *    one source-flag byte per movement sample. Emitting the click companion alone would leave the history
 *    stream inconsistent with it, which is a worse signal than staying quiet.
 *
 * So the variant stays unavailable and the wire path stays off on Windows.
 */
internal object NativeMouseClickSupport {
    private const val RINGS_IN_OFFSET_TABLE = false

    private val sendFunction: MethodHandle? by lazy {
        runCatching {
            NativeAccess.BASE_ADDR.asSlice(OFunctions.CLIENTPROT_SENDNATIVEMOUSECLICK, 8)
                .toFunctionHandle(FunctionDescriptor.ofVoid(JAVA_LONG))
        }.getOrNull()
    }

    val unavailableReason: String?
        get() = when {
            sendFunction == null ->
                "ClientProt::SendNativeMouseClick has no entry in the ${OffsetTable.platform} " +
                    "build ${OffsetTable.build} offset table"
            !RINGS_IN_OFFSET_TABLE ->
                "the native click and move ring geometry is documented in Ghidra but has no entry in the " +
                    "${OffsetTable.platform} build ${OffsetTable.build} offset table, and the move ring's " +
                    "per-sample source bytes must be fed alongside the click companion"
            else -> null
        }
}
