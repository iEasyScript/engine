package com.projectx.game.net.packetlog

import org.projectx.packetlog.store.PacketSchema.ServerProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerProfileResolverTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? =
        { key -> pairs.toMap()[key] }

    @Test
    fun `agreement resolves to the declared profile`() {
        assertEquals(
            ServerProfile.LOCAL,
            ServerProfileResolver.resolve(Peer.Host("127.0.0.1"), env("PROJECTX_SERVER_PROFILE" to "local")).profile
        )
        assertEquals(
            ServerProfile.LIVE,
            ServerProfileResolver.resolve(Peer.Host("18.185.1.20"), env("PROJECTX_SERVER_PROFILE" to "live")).profile
        )
    }

    /** A private server on a public address is legitimate, so a routable peer never implies live. */
    @Test
    fun `a public peer does not upgrade an undeclared session to live`() {
        assertEquals(ServerProfile.UNKNOWN, ServerProfileResolver.resolve(Peer.Host("18.185.1.20"), env()).profile)
        assertEquals(
            ServerProfile.LOCAL,
            ServerProfileResolver.resolve(Peer.Host("203.0.113.7"), env("PROJECTX_SERVER_PROFILE" to "local")).profile,
            "a declared-local server on a public address is still local"
        )
    }

    @Test
    fun `a contradiction quarantines rather than guessing`() {
        assertEquals(
            ServerProfile.UNKNOWN,
            ServerProfileResolver.resolve(Peer.Host("127.0.0.1"), env("PROJECTX_SERVER_PROFILE" to "live")).profile,
            "declared live but talking to loopback"
        )
    }

    @Test
    fun `an unreadable peer or missing declaration quarantines`() {
        assertEquals(
            ServerProfile.UNKNOWN,
            ServerProfileResolver.resolve(Peer.None, env("PROJECTX_SERVER_PROFILE" to "live")).profile
        )
        assertEquals(ServerProfile.UNKNOWN, ServerProfileResolver.resolve(Peer.Host("127.0.0.1"), env()).profile)
    }

    /** A platform with no socket table must still record, with the missing cross-check noted. */
    @Test
    fun `an unavailable cross-check trusts the declaration rather than discarding the session`() {
        val resolution = ServerProfileResolver.resolve(Peer.Unavailable, env("PROJECTX_SERVER_PROFILE" to "live"))
        assertEquals(ServerProfile.LIVE, resolution.profile)
        assertEquals(true, resolution.detail.contains("cross-check unavailable"))
    }

    @Test
    fun `the custom cache directory stands in for a missing profile export`() {
        assertEquals(
            ServerProfile.LOCAL,
            ServerProfileResolver.resolve(
                Peer.Host("127.0.0.1"),
                env("RS_CACHE_DIR" to "/home/u/.local/share/project-x-launcher/custom/Jagex/RuneScape")
            ).profile
        )
    }

    /**
     * An engine injected into a hand-started client has no profile export, but the launcher's own
     * directory scheme still says which mode the cache belongs to.
     */
    @Test
    fun `the live cache directory implies live, and the peer check still guards it`() {
        val live = env("RS_CACHE_DIR" to "/home/u/.local/share/project-x-launcher/Jagex/RuneScape")
        assertEquals(
            ServerProfile.LIVE,
            ServerProfileResolver.resolve(Peer.Host("18.185.1.20"), live).profile
        )
        assertEquals(
            ServerProfile.UNKNOWN,
            ServerProfileResolver.resolve(Peer.Host("127.0.0.1"), live).profile,
            "the live cache dir cannot override a loopback peer"
        )
    }
}
