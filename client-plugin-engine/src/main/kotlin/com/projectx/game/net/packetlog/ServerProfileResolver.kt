package com.projectx.game.net.packetlog

import java.security.MessageDigest
import org.projectx.packetlog.store.PacketSchema.ServerProfile

/**
 * Decides which database a session belongs to.
 *
 * Live and local captures must never mix, so this fails closed. The launcher's exported profile
 * states the intent; the peer address is only ever used to *contradict* it, never to classify on its
 * own - a private server can live on a public address, so reading a routable peer as "must be live"
 * would silently contaminate the corpus the private server is validated against. A missing export,
 * an unreadable peer, or a disagreement all resolve to [ServerProfile.UNKNOWN], which is filed to
 * quarantine and never uploaded.
 */
object ServerProfileResolver {

    private const val PROFILE_ENV = "PROJECTX_SERVER_PROFILE"

    /** Legacy fallback: the launcher already points a custom-server client at a separate cache dir. */
    private const val CACHE_DIR_ENV = "RS_CACHE_DIR"

    private val LOOPBACK_PREFIXES = listOf("127.", "::1", "0:0:0:0:0:0:0:1", "localhost")

    private val PRIVATE_PREFIXES = listOf("10.", "192.168.", "169.254.") +
        (16..31).map { "172.$it." }

    private enum class Reachability { LOCAL_NETWORK, PUBLIC }

    class Resolution(val profile: ServerProfile, val peerHash: ByteArray?, val detail: String)

    fun resolve(peer: Peer, env: (String) -> String? = System::getenv): Resolution {
        val declared = declaredProfile(env)
        val host = (peer as? Peer.Host)?.address
        val peerHash = host?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).copyOf(16) }

        if (declared == null) {
            return Resolution(ServerProfile.UNKNOWN, peerHash, "no launcher profile declared")
        }
        // A platform with no socket table cannot contradict anything, so the declaration stands and
        // the session records that the cross-check never ran. Quarantining every session on such a
        // platform would discard good data to guard against a risk we have no evidence of.
        if (peer is Peer.Unavailable) {
            return Resolution(declared, null, "$declared declared; peer cross-check unavailable on this platform")
        }
        if (peer is Peer.None) {
            return Resolution(ServerProfile.UNKNOWN, null, "launcher declared $declared but the client is not connected")
        }
        val reachability = reachability(host)
            ?: return Resolution(ServerProfile.UNKNOWN, peerHash, "launcher declared $declared but the peer is unreadable")

        // Only one combination is genuinely impossible. A declared-local server on a public
        // address is ordinary (Project X on a VPS); live traffic arriving over loopback is not, and
        // means something is redirecting the client - so that capture is not live data.
        val contradicts = when (declared) {
            ServerProfile.LIVE -> reachability == Reachability.LOCAL_NETWORK
            ServerProfile.LOCAL -> false
            ServerProfile.UNKNOWN -> true
        }
        return if (contradicts) {
            Resolution(ServerProfile.UNKNOWN, peerHash, "launcher declared $declared, peer is $reachability")
        } else {
            Resolution(declared, peerHash, "launcher and peer agree on $declared")
        }
    }

    private fun declaredProfile(env: (String) -> String?): ServerProfile? {
        env(PROFILE_ENV)?.trim()?.lowercase()?.let { declared ->
            return when (declared) {
                "live" -> ServerProfile.LIVE
                "local", "custom" -> ServerProfile.LOCAL
                else -> null
            }
        }
        // The launcher gives each mode its own data dir, with the custom server under a `custom`
        // subdirectory, so the cache path still says which mode a client was launched in even when
        // the profile export is missing - an engine injected into a client someone started by hand.
        // A weak signal, but the peer cross-check is what makes acting on it safe.
        val cacheDir = env(CACHE_DIR_ENV) ?: return null
        return if (cacheDir.contains("/custom/") || cacheDir.contains("\\custom\\")) {
            ServerProfile.LOCAL
        } else {
            ServerProfile.LIVE
        }
    }

    private fun reachability(peer: String?): Reachability? {
        val host = peer?.trim()?.lowercase()?.substringBefore('%')?.takeIf { it.isNotEmpty() } ?: return null
        if (LOOPBACK_PREFIXES.any { host.startsWith(it) }) return Reachability.LOCAL_NETWORK
        if (PRIVATE_PREFIXES.any { host.startsWith(it) }) return Reachability.LOCAL_NETWORK
        return Reachability.PUBLIC
    }
}
