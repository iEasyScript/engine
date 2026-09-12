package org.projectx.core.net

import org.projectx.core.EnvVars
import java.util.concurrent.ConcurrentHashMap

class MultilogLimiter {
    private val connected = ConcurrentHashMap<String, Int>()
    private val whitelistedIps: Set<String> = parseWhitelist(EnvVars.multiLogWhitelist)

    private fun normalizeIpKey(ip: String): String {
        var s = ip.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        s = s.substringBefore('%')
        if (s == "0:0:0:0:0:0:0:1") s = "::1"
        return s
    }

    private fun parseWhitelist(whitelistStr: String?): Set<String> =
        whitelistStr
            ?.split(',')
            ?.map { normalizeIpKey(it.trim()) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun add(ip: String): Boolean {
        val key = normalizeIpKey(ip)
        if (key.isEmpty() || key in whitelistedIps) return true

        var allowed = false
        connected.compute(key) { _, count ->
            val current = count ?: 0
            if (current < EnvVars.multiLogLimit) {
                allowed = true
                current + 1
            } else {
                allowed = false
                current
            }
        }
        return allowed
    }

    fun remove(ip: String) {
        val key = normalizeIpKey(ip)
        if (key.isEmpty() || key in whitelistedIps) return

        connected.computeIfPresent(key) { _, count ->
            if (count <= 1) null else count - 1
        }
    }
}
