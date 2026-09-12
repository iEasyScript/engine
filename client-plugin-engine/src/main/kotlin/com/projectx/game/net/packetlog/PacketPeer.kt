package com.projectx.game.net.packetlog

import java.io.File
import java.net.InetAddress

/**
 * The address the client is actually connected to, read from the kernel's own socket table.
 *
 * Deliberately not read out of client memory: that would need an offset for the connection's peer
 * field, and an unverified offset produces a plausible wrong answer rather than an error - which is
 * exactly the failure this cross-check exists to catch.
 *
 * [Unavailable] and [None] are different answers and callers must treat them differently: the first
 * means this platform has no socket table to read, the second means it was read and the client is
 * not connected to anything.
 */
sealed interface Peer {
    /** No socket table on this platform, so the cross-check cannot run at all. */
    data object Unavailable : Peer

    /** Read successfully; the client has no established game connection. */
    data object None : Peer

    data class Host(val address: String) : Peer
}

object PacketPeer {

    private const val TCP_ESTABLISHED = "01"

    /** JS5 and the worldlist also connect out, so a port filter would be guesswork; take them all. */
    fun current(): Peer {
        val net = File("/proc/self/net/tcp")
        val net6 = File("/proc/self/net/tcp6")
        if (!net.isFile && !net6.isFile) return Peer.Unavailable

        val established = buildList {
            addAll(parse(net, ipv6 = false))
            addAll(parse(net6, ipv6 = true))
        }.filter { !it.startsWith("0.0.0.0") && it != "::" }

        // Prefer a routable peer when several sockets are open, so a loopback JS5 or launcher
        // connection cannot mask the address the game session actually runs over.
        val routable = established.firstOrNull { !it.startsWith("127.") && it != "::1" }
        return when {
            routable != null -> Peer.Host(routable)
            established.isNotEmpty() -> Peer.Host(established.first())
            else -> Peer.None
        }
    }

    private fun parse(file: File, ipv6: Boolean): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().drop(1).mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 4 || columns[3] != TCP_ESTABLISHED) return@mapNotNull null
                val remote = columns[2].substringBefore(':')
                if (ipv6) decodeIpv6(remote) else decodeIpv4(remote)
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeIpv4(hex: String): String? {
        if (hex.length != 8) return null
        return runCatching {
            (3 downTo 0).joinToString(".") { hex.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        }.getOrNull()
    }

    private fun decodeIpv6(hex: String): String? {
        if (hex.length != 32) return null
        return runCatching {
            // Each 32-bit group is little-endian in /proc, the groups themselves are in order.
            val bytes = ByteArray(16)
            for (group in 0 until 4) {
                for (byte in 0 until 4) {
                    bytes[group * 4 + byte] =
                        hex.substring(group * 8 + (3 - byte) * 2, group * 8 + (3 - byte) * 2 + 2).toInt(16).toByte()
                }
            }
            InetAddress.getByAddress(bytes).hostAddress
        }.getOrNull()
    }
}
