package com.projectx.store

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptSyncTest {
    private lateinit var dir: Path
    private lateinit var server: HttpServer
    private var served: ByteArray = ByteArray(0)
    private var status = 200
    private var lastAuth: String? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("projectx-sync")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jar") { exchange ->
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(status, if (status == 200) served.size.toLong() else -1)
            if (status == 200) exchange.responseBody.use { it.write(served) }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun gates(sha: String? = null, version: String = "3.6.2") = StoreJar(
        internalName = "GatesOfElidinis",
        version = version,
        url = "http://127.0.0.1:${server.address.port}/jar",
        sha256 = sha,
        fileName = "gates-of-elidinis.jar",
    )

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun files() = dir.toFile().list()!!.filterNot { it.startsWith(".") }.toSet()

    @Test
    fun `names a build exactly as the launcher does`() {
        assertEquals("GatesOfElidinis-3.6.2.jar", ScriptSync.jarName("GatesOfElidinis", "3.6.2"))
    }

    @Test
    fun `a hostile name or version cannot leave the scripts folder`() {
        val name = ScriptSync.jarName("../../evil", "../1.0")
        assertFalse('/' in name || '\\' in name, name)
    }

    @Test
    fun `a script whose name merely begins the same is not an older build`() {
        val agility = StoreJar("Agility", "2.0.0", "", null, null)
        assertFalse(ScriptSync.isBuildOf("Agility-Course-1.0.0.jar", agility))
        assertTrue(ScriptSync.isBuildOf("Agility-1.9.0.jar", agility))
    }

    @Test
    fun `only owned scripts whose current build is absent are fetched`() {
        val owned = gates()
        val current = StoreJar("Rasial", "2.4.0", "", null, null)
        val notOwned = StoreJar("Vault", "1.0.0", "", null, null)

        val wanted = ScriptSync.missing(
            owned = setOf("GatesOfElidinis", "Rasial"),
            catalogue = listOf(owned, current, notOwned),
            present = setOf("GatesOfElidinis-3.6.1.jar", "Rasial-2.4.0.jar"),
        )

        assertEquals(listOf(owned), wanted)
    }

    @Test
    fun `a bought script arrives in the folder and the older build goes`() {
        served = "PK pretend jar 3.6.2".toByteArray()
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.jar"), "old")
        Files.writeString(dir.resolve("gates-of-elidinis.jar"), "from an older launcher")

        val installed = ScriptSync.sync(setOf("GatesOfElidinis"), listOf(gates(sha(served))), "tok", HttpClient.newHttpClient(), dir)

        assertEquals(1, installed.size)
        assertContentEquals(served, Files.readAllBytes(dir.resolve("GatesOfElidinis-3.6.2.jar")))
        assertEquals(setOf("GatesOfElidinis-3.6.2.jar"), files())
        assertEquals("Bearer tok", lastAuth)
    }

    @Test
    fun `a jar that does not match its checksum is not installed`() {
        served = "not the jar you were promised".toByteArray()

        val installed = ScriptSync.sync(setOf("GatesOfElidinis"), listOf(gates(sha("the real jar".toByteArray()))), "tok", HttpClient.newHttpClient(), dir)

        assertTrue(installed.isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a refused download leaves the folder as it was`() {
        status = 403
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.jar"), "old")

        val installed = ScriptSync.sync(setOf("GatesOfElidinis"), listOf(gates()), "tok", HttpClient.newHttpClient(), dir)

        assertTrue(installed.isEmpty())
        assertEquals(setOf("GatesOfElidinis-3.6.1.jar"), files())
    }

    @Test
    fun `nothing is fetched when the current build is already there`() {
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.2.jar"), "current")
        status = 500

        val installed = ScriptSync.sync(setOf("GatesOfElidinis"), listOf(gates()), "tok", HttpClient.newHttpClient(), dir)

        assertTrue(installed.isEmpty())
        assertEquals(null, lastAuth)
    }
}
