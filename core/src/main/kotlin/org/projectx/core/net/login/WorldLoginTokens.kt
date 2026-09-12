package org.projectx.core.net.login

import java.security.SecureRandom

/**
 * Lobby-side issuer for the cross-process lobby → world login handoff.
 *
 * `:lobby` and `:world` are SEPARATE JVMs/ports, so the authorization the lobby grants at
 * login-success must survive a process boundary. We deliberately do NOT route it through a shared
 * database: the only login step that touches the account store is the lobby validating the user's
 * password. Instead the lobby pushes the [IssuedWorldLogin] (plus the account snapshot) to the
 * connected world(s) over the social-gateway WebSocket (see `WorldLoginGrant`), and the world
 * verifies the contained [LoginToken] statelessly with the shared
 * [org.projectx.core.EnvVars.worldLoginTokenSecret] — exactly the stateless verification [LoginToken]
 * was designed for ("world servers can independently verify without database lookups or shared
 * session state").
 *
 * Flow:
 *  1. Lobby login-success: [issue] mints the signed token + random session ids; the lobby embeds the
 *     ids in the login-data block the client carries and pushes the whole grant to the world(s).
 *  2. World reconnect (loginType 2): the world reads the username from the (reliable) XTEA section,
 *     looks up the pushed grant, and verifies the token. No second credential exchange occurs.
 *
 * The random [IssuedWorldLogin.sessionId1]/[IssuedWorldLogin.sessionId2] are carried in the
 * login-data session-token fields so connection-level binding can be added with zero wire change
 * once the reconnect RSA block layout is fully reverse-engineered.
 */
data class IssuedWorldLogin(
    val sessionId1: Long,
    val sessionId2: Long,
    /** Signed [LoginToken] (HMAC, shared secret) — integrity check independent of any store. */
    val token: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

object WorldLoginTokens {
    private val random = SecureRandom()

    /** Lobby side: mint a TTL'd, signed authorization for [username] to enter a world. */
    fun issue(username: String, ttlMs: Long, secret: String): IssuedWorldLogin {
        val now = System.currentTimeMillis()
        return IssuedWorldLogin(
            sessionId1 = random.nextLong(),
            sessionId2 = random.nextLong(),
            token = LoginToken.issue(username, now, ttlMs, secret),
            issuedAt = now,
            expiresAt = now + ttlMs,
        )
    }
}
