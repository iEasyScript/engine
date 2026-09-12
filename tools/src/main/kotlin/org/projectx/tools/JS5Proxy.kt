package org.projectx.tools

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val LISTEN_PORT = 43595
private const val TARGET_HOST = "localhost"
private const val TARGET_PORT = 43594

private val connId = AtomicInteger(0)

fun main() {
    println("[proxy] Listening on port $LISTEN_PORT, forwarding to $TARGET_HOST:$TARGET_PORT")
    val server = ServerSocket(LISTEN_PORT)

    while (true) {
        val clientSocket = server.accept()
        val id = connId.incrementAndGet()
        println("[proxy:$id] New connection from ${clientSocket.remoteSocketAddress}")

        Thread {
            try {
                val targetSocket = Socket(TARGET_HOST, TARGET_PORT)
                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()
                val targetIn = targetSocket.getInputStream()
                val targetOut = targetSocket.getOutputStream()

                val c2s = Thread {
                    relay(id, "C→S", clientIn, targetOut)
                    try { targetSocket.shutdownOutput() } catch (_: Exception) {}
                }
                val s2c = Thread {
                    relay(id, "S→C", targetIn, clientOut)
                    try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                }

                c2s.start()
                s2c.start()
                c2s.join()
                s2c.join()

                clientSocket.close()
                targetSocket.close()
                println("[proxy:$id] Connection closed")
            } catch (e: Exception) {
                println("[proxy:$id] Error: ${e.message}")
            }
        }.start()
    }
}

private fun relay(id: Int, direction: String, input: InputStream, output: OutputStream) {
    val buf = ByteArray(65536)
    var totalBytes = 0L
    var messageCount = 0
    try {
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
            output.flush()
            totalBytes += n
            messageCount++

            val ts = Instant.now().toString().substringAfter("T").substringBefore("Z")
            val hex = buf.take(n).take(64).joinToString(" ") { "%02x".format(it) }
            val truncated = if (n > 64) " ... (${n} bytes total)" else ""
            println("[proxy:$id] [$ts] $direction #$messageCount: $n bytes | $hex$truncated")

            if (direction == "C→S" && n >= 10) {
                var off = 0
                while (off + 10 <= n) {
                    val opcode = buf[off].toInt() and 0xFF
                    val index = buf[off + 1].toInt() and 0xFF
                    val group = ((buf[off + 2].toInt() and 0xFF) shl 24) or
                            ((buf[off + 3].toInt() and 0xFF) shl 16) or
                            ((buf[off + 4].toInt() and 0xFF) shl 8) or
                            (buf[off + 5].toInt() and 0xFF)
                    if ((opcode and 0x0E) == 0) {
                        val urgent = opcode and 1 != 0
                        println("[proxy:$id]   FILE_REQ: opcode=0x${"%02x".format(opcode)} index=$index group=$group ${if (urgent) "URGENT" else "prefetch"}")
                    } else {
                        println("[proxy:$id]   CONTROL: opcode=$opcode (0x${"%02x".format(opcode)})")
                    }
                    off += 10
                }
            }
        }
    } catch (e: Exception) {
        val ts = Instant.now().toString().substringAfter("T").substringBefore("Z")
        println("[proxy:$id] [$ts] $direction ended: ${e::class.simpleName}: ${e.message}")
    }
    println("[proxy:$id] $direction total: $totalBytes bytes in $messageCount chunks")
}
