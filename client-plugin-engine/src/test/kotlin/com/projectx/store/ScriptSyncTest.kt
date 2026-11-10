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
    private var servedFormat: String? = null
    private var status = 200
    private var lastAuth: String? = null
    private var lastQuery: String? = null

    private val blob = "PXE1".toByteArray() + ByteArray(12) { 1 } + "ciphertext and its sixteen byte tag".toByteArray()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("projectx-sync")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jar") { exchange ->
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            lastQuery = exchange.requestURI.rawQuery
            servedFormat?.let { exchange.responseHeaders.add("X-ProjectX-Jar-Format", it) }
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

    private fun gates(sha: String? = null, version: String = "3.6.2", free: Boolean = false) = StoreJar(
        internalName = "GatesOfElidinis",
        version = version,
        url = "http://127.0.0.1:${server.address.port}/jar?game=rs3",
        sha256 = sha,
        fileName = "gates-of-elidinis.jar",
        free = free,
    )

    private fun serveEncrypted() {
        served = blob
        servedFormat = "pxe1"
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun files() = dir.toFile().list()!!.filterNot { it.startsWith(".") }.toSet()

    private fun sync(vararg jars: StoreJar) =
        ScriptSync.sync(jars.mapTo(HashSet()) { it.internalName }, jars.toList(), "tok", HttpClient.newHttpClient(), dir)

    @Test
    fun `names a build exactly as the launcher does`() {
        assertEquals("GatesOfElidinis-3.6.2.jar", ScriptSync.jarName("GatesOfElidinis", "3.6.2"))
        assertEquals("GatesOfElidinis-3.6.2.pxe", ScriptSync.pxeName("GatesOfElidinis", "3.6.2"))
        assertEquals("GatesOfElidinis-3.6.2.pxe.json", PxeRecord.fileFor(ScriptSync.pxeName("GatesOfElidinis", "3.6.2")))
    }

    @Test
    fun `a hostile name or version cannot leave the scripts folder`() {
        val name = ScriptSync.pxeName("../../evil", "../1.0")
        assertFalse('/' in name || '\\' in name, name)
    }

    @Test
    fun `a script whose name merely begins the same is not an older build`() {
        val agility = StoreJar("Agility", "2.0.0", "", null, null)
        assertFalse(ScriptSync.isBuildOf("Agility-Course-1.0.0.jar", agility))
        assertFalse(ScriptSync.isBuildOf("Agility-Course-1.0.0.pxe", agility))
        assertTrue(ScriptSync.isBuildOf("Agility-1.9.0.jar", agility))
        assertTrue(ScriptSync.isBuildOf("Agility-1.9.0.pxe", agility))
        assertTrue(ScriptSync.isBuildOf("Agility-1.9.0.pxe.json", agility))
        assertFalse(ScriptSync.isBuildOf("Agility-1.9.0.txt", agility))
    }

    @Test
    fun `only owned scripts whose current build is absent are fetched`() {
        val owned = gates()
        val current = StoreJar("Rasial", "2.4.0", "", null, null)
        val notOwned = StoreJar("Vault", "1.0.0", "", null, null)

        val wanted = ScriptSync.missing(
            owned = setOf("GatesOfElidinis", "Rasial"),
            catalogue = listOf(owned, current, notOwned),
            present = setOf("GatesOfElidinis-3.6.1.pxe", "GatesOfElidinis-3.6.1.pxe.json", "Rasial-2.4.0.pxe", "Rasial-2.4.0.pxe.json"),
        )

        assertEquals(listOf(owned), wanted)
    }

    @Test
    fun `a paid script with only a plain jar of the current release is fetched again, encrypted`() {
        val paid = StoreJar("Rasial", "2.4.0", "", null, null)
        val free = StoreJar("Fishing", "1.0.0", "", null, null, free = true)

        val wanted = ScriptSync.missing(
            owned = setOf("Rasial", "Fishing"),
            catalogue = listOf(paid, free),
            present = setOf("Rasial-2.4.0.jar", "Fishing-1.0.0.jar"),
        )

        assertEquals(listOf(paid), wanted)
    }

    @Test
    fun `an encrypted build without its record is not counted as installed`() {
        val wanted = ScriptSync.missing(setOf("GatesOfElidinis"), listOf(gates()), setOf("GatesOfElidinis-3.6.2.pxe"))
        assertEquals(listOf(gates()), wanted)
    }

    @Test
    fun `every other build of a script goes once its encrypted build is in place`() {
        val present = setOf(
            "GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json",
            "GatesOfElidinis-3.6.2.jar", "GatesOfElidinis-3.6.1.pxe", "GatesOfElidinis-3.6.1.pxe.json",
            "gates-of-elidinis.jar", "Rasial-2.4.0.jar", "Rasial-2.3.0.jar",
        )

        val gone = ScriptSync.superseded(listOf(gates(), StoreJar("Rasial", "2.4.0", "", null, null)), present)

        assertEquals(
            setOf("GatesOfElidinis-3.6.2.jar", "GatesOfElidinis-3.6.1.pxe", "GatesOfElidinis-3.6.1.pxe.json", "gates-of-elidinis.jar"),
            gone,
        )
    }

    @Test
    fun `a build the launcher installed while the game ran gets the list rescanned`() {
        val onDisk = ScriptSync.ownedOnDisk(
            owned = setOf("GatesOfElidinis"),
            catalogue = listOf(gates()),
            present = setOf("GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"),
        )
        assertEquals(setOf("GatesOfElidinis"), onDisk)
        assertTrue(ScriptSync.needsRescan(onDisk, registered = setOf("Rasial")))
        assertFalse(ScriptSync.needsRescan(onDisk, registered = setOf("GatesOfElidinis", "Rasial")))
    }

    @Test
    fun `a download that failed does not rescan every five minutes for nothing`() {
        val onDisk = ScriptSync.ownedOnDisk(setOf("GatesOfElidinis"), listOf(gates()), present = emptySet())
        assertTrue(onDisk.isEmpty())
        assertFalse(ScriptSync.needsRescan(onDisk, registered = emptySet()))
    }

    @Test
    fun `a bought script arrives encrypted with its record and every older build goes`() {
        serveEncrypted()
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.jar"), "old plaintext")
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.pxe"), "old encrypted")
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.pxe.json"), "{}")
        Files.writeString(dir.resolve("gates-of-elidinis.jar"), "from an older launcher")

        val installed = sync(gates(sha("the plaintext jar".toByteArray())))

        assertEquals(1, installed.size)
        assertContentEquals(blob, Files.readAllBytes(dir.resolve("GatesOfElidinis-3.6.2.pxe")))
        assertEquals(
            PxeRecord("GatesOfElidinis", "3.6.2", sha("the plaintext jar".toByteArray())),
            PxeRecord.decode(Files.readString(dir.resolve("GatesOfElidinis-3.6.2.pxe.json"))),
        )
        assertEquals(setOf("GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"), files())
        assertEquals("Bearer tok", lastAuth)
        assertEquals("game=rs3&format=pxe1", lastQuery)
    }

    @Test
    fun `a plaintext jar of the current release is replaced by the encrypted build`() {
        serveEncrypted()
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.2.jar"), "plaintext from an older launcher")

        sync(gates())

        assertEquals(setOf("GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"), files())
    }

    @Test
    fun `a plaintext jar an older launcher wrote beside the encrypted build is removed without a download`() {
        status = 500
        Files.write(dir.resolve("GatesOfElidinis-3.6.2.pxe"), blob)
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.2.pxe.json"), PxeRecord("GatesOfElidinis", "3.6.2", null).encode())
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.2.jar"), "plaintext from an older launcher")

        assertTrue(sync(gates()).isEmpty())

        assertEquals(null, lastAuth)
        assertEquals(setOf("GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"), files())
    }

    @Test
    fun `a download labelled encrypted that is not is not installed`() {
        served = "PK\u0003\u0004 a plain jar".toByteArray()
        servedFormat = "pxe1"
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.pxe"), "old encrypted")

        assertTrue(sync(gates()).isEmpty())

        assertEquals(setOf("GatesOfElidinis-3.6.1.pxe"), files())
    }

    @Test
    fun `a free script still arrives as a plain jar`() {
        served = "PK pretend jar 3.6.2".toByteArray()
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.jar"), "old")

        val installed = sync(gates(sha(served), free = true))

        assertEquals(1, installed.size)
        assertContentEquals(served, Files.readAllBytes(dir.resolve("GatesOfElidinis-3.6.2.jar")))
        assertEquals(setOf("GatesOfElidinis-3.6.2.jar"), files())
    }

    @Test
    fun `a paid script a store sends plain is installed plain and not fetched again every refresh`() {
        served = "PK pretend jar".toByteArray()
        val jar = gates(sha(served), version = "9.9.9")

        assertEquals(1, sync(jar).size)
        lastAuth = null
        assertTrue(sync(jar).isEmpty())

        assertEquals(null, lastAuth)
        assertEquals(setOf("GatesOfElidinis-9.9.9.jar"), files())
    }

    @Test
    fun `a jar that does not match its checksum is not installed`() {
        served = "not the jar you were promised".toByteArray()

        val installed = sync(gates(sha("the real jar".toByteArray()), free = true))

        assertTrue(installed.isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a refused download leaves the folder as it was`() {
        status = 403
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.1.jar"), "old")

        val installed = sync(gates())

        assertTrue(installed.isEmpty())
        assertEquals(setOf("GatesOfElidinis-3.6.1.jar"), files())
    }

    @Test
    fun `nothing is fetched when the current build is already there`() {
        Files.write(dir.resolve("GatesOfElidinis-3.6.2.pxe"), blob)
        Files.writeString(dir.resolve("GatesOfElidinis-3.6.2.pxe.json"), PxeRecord("GatesOfElidinis", "3.6.2", null).encode())
        status = 500

        val installed = sync(gates())

        assertTrue(installed.isEmpty())
        assertEquals(null, lastAuth)
    }

    @Test
    fun aCommunityScriptOwnedWithoutTheCommunityJarNeedsIt() {
        assertTrue(ScriptSync.needsCommunityJar(setOf("AIOWoodcutting"), listOf(gates()), emptySet()))
    }

    @Test
    fun theCommunityJarIsNotFetchedWhenOneIsAlreadyInstalled() {
        assertFalse(ScriptSync.needsCommunityJar(setOf("AIOWoodcutting"), listOf(gates()), setOf("community-scripts-1.8.12.jar")))
    }

    @Test
    fun ownedScriptsWithTheirOwnJarsNeverFetchTheCommunityJar() {
        assertFalse(ScriptSync.needsCommunityJar(setOf("GatesOfElidinis"), listOf(gates()), emptySet()))
    }

    @Test
    fun onlyCommunityScriptJarsCountAsTheCommunityJar() {
        assertTrue(ScriptSync.isCommunityJar("community-scripts-1.9.0.jar"))
        assertFalse(ScriptSync.isCommunityJar("community-scripts.jar.part"))
        assertFalse(ScriptSync.isCommunityJar("GatesOfElidinis-3.7.2.jar"))
    }
}
