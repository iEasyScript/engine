package org.projectx.packetlog
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.*
import org.projectx.packetlog.upload.*
import org.junit.jupiter.api.Test
/** Points the real uploader at the deployed endpoint to confirm the contract end to end. */
class LiveEndpointProbe {
    @Test fun probe() {
        val endpoint = System.getProperty("projectx.endpoint") ?: return println("PROBE skipped")
        val dir = Files.createTempDirectory("live").toFile()
        val protTable = listOf(ProtEntry(949, 0, 45, "PLAYER_INFO", -2))
        fun make(player: String): java.io.File {
            val owned = SessionFiles.claim(dir)!!
            val md = SessionMetadata(player,1,1_700_000_000_000L,0,949,ByteArray(32),"linux","x86_64","949-5","probe",
                PacketSchema.ServerProfile.LIVE,null,1,true,"probe_v1")
            val store = PacketStore.open(owned.file, md, protTable, owned.uuid)
            val w = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 5))
            repeat(20) { i ->
                store.beginFlush()
                w.append(ChunkEvent(store.assignSequence(), 1_700_000_000_000L+i,0,i/3,0,45,PacketSchema.Quality.OK, byteArrayOf(i.toByte())))
                store.commitFlush(); w.pump(1_700_000_000_000L+i)
            }
            w.flush(); store.closeSession(1_700_000_010_000L,"clean"); w.close(); store.close(); owned.close()
            println("PROBE session ${owned.uuid} player=$player")
            return owned.file
        }
        val a = make("probe-a"); val b = make("probe-b")
        val creds = UploadCredentials.load()
        if (!creds.enrolled) return println("PROBE not enrolled - cannot push to $endpoint")
        val ready = CountDownLatch(2)
        listOf(a,b).map { f -> thread {
            ready.countDown(); ready.await(10, TimeUnit.SECONDS)
            runCatching { PacketUploader(endpoint, creds).upload(f) }
                .onSuccess { println("PROBE uploaded ${it.chunksSent} chunks from ${it.sessions} session(s), skipped=${it.skipped}") }
                .onFailure { println("PROBE failed: ${it.message}") }
        } }.forEach { it.join(60_000) }
    }
}
