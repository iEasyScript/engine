package org.projectx.core.net.session

import kotlinx.coroutines.withTimeoutOrNull
import org.projectx.core.Logger.logError
import org.projectx.core.Logger.logInfo
import org.projectx.core.Logger.logTrace
import org.projectx.core.net.Session
import org.projectx.core.net.prot.ClientProt
import org.projectx.core.net.prot.NoTimeout

object SessionLoop {
    const val KEEPALIVE_INTERVAL_MS = 15_000L

    const val PACKET_RATE_WINDOW_MS = 10_000L

    const val MAX_PACKETS_PER_RATE_WINDOW = 2_000

    suspend fun run(session: GameSession, onPacket: suspend (ClientProt) -> Unit) {
        var packetCount = 0L
        var lastKeepaliveSent = System.currentTimeMillis()
        var rateWindowStart = lastKeepaliveSent
        var rateWindowCount = 0

        try {
            session.sendIfSupported(NoTimeout())
            session.flush()

            while (!session.disconnected) {
                val packet = withTimeoutOrNull(KEEPALIVE_INTERVAL_MS) {
                    session.readChannel.receive()
                }

                if (packet != null) {
                    packetCount++
                    rateWindowCount++
                    onPacket(packet)
                }

                session.flush()

                val now = System.currentTimeMillis()
                if (now - lastKeepaliveSent > KEEPALIVE_INTERVAL_MS) {
                    session.sendIfSupported(NoTimeout())
                    session.flush()
                    lastKeepaliveSent = now
                }

                if (now - rateWindowStart >= PACKET_RATE_WINDOW_MS) {
                    rateWindowStart = now
                    rateWindowCount = 0
                } else if (rateWindowCount > MAX_PACKETS_PER_RATE_WINDOW) {
                    logError("Packet flood from ${session.ip}: >$MAX_PACKETS_PER_RATE_WINDOW packets in ${PACKET_RATE_WINDOW_MS}ms, disconnecting")
                    session.disconnect()
                    break
                }
            }
        } catch (e: Exception) {
            if (Session.isExpectedDisconnect(e)) {
                logTrace("Session ended for ${session.ip}: ${e::class.simpleName}: ${e.message}")
            } else {
                logError("Session error for ${session.ip} (${session.username})", e)
            }
        }
        logInfo("Session closed for ${session.ip} (${session.username}) after $packetCount packets")
    }
}
