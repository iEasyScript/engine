package org.projectx.packetlog.upload

import kotlinx.serialization.Serializable

/**
 * Proof that a session is still being captured, sent between chunk uploads.
 *
 * ⛔ Lives here, in the shared module, for the same reason [SessionManifest] does: it is a contract
 * between two programs, and a renamed field on one side has to be a compile error rather than a
 * silently defaulted value on the other.
 *
 * The server's lease is what actually ends a session when a client vanishes, so this exists to keep
 * a *live* session from being reaped mid-capture. The counters ride along because they are already
 * being read to decide that - and they are what lets the dashboard show a capture in progress
 * rather than only the chunks that have landed.
 *
 * [sealedPackets] and [pendingPackets] are reported apart on purpose: only the sealed ones can have
 * reached the server, and presenting a capture as further along than it is would make the site lie
 * about how much of a session survives a kill.
 */
@Serializable
class SessionHeartbeat(
    val sealedPackets: Long,
    val pendingPackets: Long,
    val chunks: Long,
    val lastEventEpochMs: Long?,
    /** The writer has finished; only the upload is still outstanding. */
    val ended: Boolean,
)
