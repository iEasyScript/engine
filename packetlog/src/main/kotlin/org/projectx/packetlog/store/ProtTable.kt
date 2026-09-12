package org.projectx.packetlog.store

import java.security.MessageDigest
import org.projectx.core.net.prot.Codec

/**
 * Snapshots the shared `:core` prot tables into rows a packet database can join against.
 *
 * Names are labels, not identity - they are still being rebuilt, and an unidentified prot renders as
 * `UNKNOWN_<n>`. Bodies are keyed by `(revision, dir, opcode)`, and [hash] records which labelling
 * was in force so re-reading an old session through a renamed table cannot relabel it silently.
 */
object ProtTable {

    fun snapshot(revision: Int, codec: Codec): List<ProtEntry> = buildList {
        for ((opcode, info) in codec.serverProtInfo) {
            add(
                ProtEntry(
                    revision = revision,
                    dir = PacketSchema.Direction.SERVER_TO_CLIENT.code,
                    opcode = opcode,
                    name = info.name,
                    wireSize = info.size.toInt(),
                )
            )
        }
        for ((opcode, info) in codec.clientProtInfo) {
            add(
                ProtEntry(
                    revision = revision,
                    dir = PacketSchema.Direction.CLIENT_TO_SERVER.code,
                    opcode = opcode,
                    name = info.name,
                    wireSize = info.size.toInt(),
                )
            )
        }
    }

    fun hash(entries: List<ProtEntry>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries.sortedWith(compareBy({ it.revision }, { it.dir }, { it.opcode }))) {
            digest.update("${entry.revision}:${entry.dir}:${entry.opcode}:${entry.name}:${entry.wireSize}\n".toByteArray())
        }
        return digest.digest()
    }
}
