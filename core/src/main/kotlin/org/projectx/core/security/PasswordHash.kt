package org.projectx.core.security

import de.mkammerer.argon2.Argon2Factory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Argon2 password hashing utilities.
 *
 * Hashing/verification is intentionally expensive (64 MB, 10 iterations — hundreds of
 * milliseconds of CPU). Prefer the suspend variants from coroutine code so the work runs
 * on [Dispatchers.Default] instead of blocking the caller's dispatcher (e.g. a network
 * event loop). The blocking variants remain for non-suspend call sites.
 */
object PasswordHash {
    private val argon2 = Argon2Factory.create()

    fun hash(password: String): String {
        return argon2.hash(10, 65536, 1, password.toCharArray())
    }

    fun verify(password: String, hash: String): Boolean {
        return argon2.verify(hash, password.toCharArray())
    }

    /** Suspending [hash] — runs the Argon2 work on [Dispatchers.Default]. */
    suspend fun hashSuspend(password: String): String = withContext(Dispatchers.Default) {
        hash(password)
    }

    /** Suspending [verify] — runs the Argon2 work on [Dispatchers.Default]. */
    suspend fun verifySuspend(password: String, hash: String): Boolean = withContext(Dispatchers.Default) {
        verify(password, hash)
    }
}
