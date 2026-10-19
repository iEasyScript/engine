package com.projectx.game.net

import com.projectx.game.nxt.OffsetTable
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.ProtRevisions

/**
 * The protocol the attached client speaks, taken from its own build rather than from the newest revision this
 * engine knows. A major update re-scrambles every opcode, so reading a new client's packets with the previous
 * revision's tables mislabels all of them, and a capture recorded that way is wrong for good.
 */
object ClientProtocol {
    /** `951` for a `951-1` client; null when no client table is loaded. */
    val revision: Int? by lazy { runCatching { OffsetTable.build.substringBefore('-').toInt() }.getOrNull() }

    /** Null when this engine carries no codec for [revision]: packets then go unnamed, undecoded and unrecorded. */
    val codec: Codec? by lazy {
        revision?.let(ProtRevisions::codec).also {
            if (it == null) println(
                "[protocol] no codec for client revision ${revision ?: "unknown"} (known: ${ProtRevisions.known}); " +
                    "packet names, decoding, capture and server ticks are off until the engine learns it"
            )
        }
    }
}
