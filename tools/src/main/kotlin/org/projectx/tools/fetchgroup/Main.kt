package org.projectx.tools.fetchgroup

import org.projectx.tools.cachedownloader.JS5Protocol
import org.projectx.tools.util.JavConfig
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.compress.DecompressionContext
import java.io.File

fun main(args: Array<String>) {
    val index = args.getOrNull(0)?.toIntOrNull() ?: error("usage: <index> <group> [host] [outFile]")
    val group = args.getOrNull(1)?.toIntOrNull() ?: error("usage: <index> <group> [host] [outFile]")
    val host = args.getOrNull(2) ?: "content.runescape.com"
    val port = 43594
    val out = args.getOrNull(3)

    val jav = JavConfig.fetch()
    val token = jav.params[29] ?: ""
    val (major, minor) = jav.serverVersion(949, 1)
    println("host=$host:$port version=$major.$minor token=${token.take(12)}…")

    val (sock, inp, outp) = JS5Protocol.connect(host, port, major, minor, token)
    try {
        val ctx = DecompressionContext()

        JS5Protocol.sendFileRequest(outp, 255, index, major)
        val ref = JS5Protocol.readResponse(inp)
        val table = parseRefTable(ctx, ref.container) ?: error("could not parse ref table for index $index")
        val entry = table.entries.find { it.id == group }
        if (entry == null) {
            println("index $index does NOT declare group $group (reftable rev=${table.revision}, ${table.entries.size} groups)")
            return
        }
        println("declared: index=$index group=$group crc=${entry.crc} version=${entry.version}")

        val (sock2, inp2, outp2) = JS5Protocol.connect(host, port, major, minor, token)
        JS5Protocol.sendFileRequest(outp2, index, group, major)
        val resp = JS5Protocol.readResponse(inp2)
        sock2.close()
        val container = resp.container
        val crc = CRC.calculate(container)
        val decompressed = ctx.decompress(container)
        println("fetched ${container.size} container bytes, crc=$crc (reftable crc=${entry.crc}, match=${crc == entry.crc})")
        println("decompressed=${decompressed?.size ?: -1} bytes")

        val target = out ?: "index${index}_group${group}.bin"
        File(target).writeBytes(container)
        println("wrote container -> $target")
    } finally {
        sock.close()
    }
}
