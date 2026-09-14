package com.projectx.script

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScriptJarShadowTest {
    private lateinit var home: File
    private lateinit var scripts: File
    private var savedHome: String? = null

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("shadow-home").toFile()
        scripts = File(home, "scripts").apply { mkdirs() }
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        savedHome?.let { System.setProperty("user.home", it) }
        home.deleteRecursively()
    }

    @Test
    fun `loads a copy, never the jar in the scripts folder`() {
        val jar = File(scripts, "official-scripts-1.0.0.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val copy = File(ScriptJarShadow.copiesOf(listOf(jar.absolutePath)).single())

        assertNotEquals(jar.canonicalPath, copy.canonicalPath)
        assertTrue(copy.readBytes().contentEquals(jar.readBytes()))
    }

    @Test
    fun `an updated jar gets a fresh copy and the superseded copy is removed`() {
        val jar = File(scripts, "official-scripts.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = File(ScriptJarShadow.copiesOf(listOf(jar.absolutePath)).single())

        jar.writeBytes(byteArrayOf(4, 5, 6, 7))
        jar.setLastModified(jar.lastModified() + 5_000)
        val second = File(ScriptJarShadow.copiesOf(listOf(jar.absolutePath)).single())

        assertNotEquals(first.name, second.name)
        assertTrue(second.readBytes().contentEquals(byteArrayOf(4, 5, 6, 7)))
        assertFalse(first.exists())
    }

    @Test
    fun `an unchanged jar reuses its copy`() {
        val jar = File(scripts, "community-scripts.jar").apply { writeBytes(byteArrayOf(9, 9)) }
        val first = ScriptJarShadow.copiesOf(listOf(jar.absolutePath)).single()
        val second = ScriptJarShadow.copiesOf(listOf(jar.absolutePath)).single()
        assertEquals(first, second)
    }
}
