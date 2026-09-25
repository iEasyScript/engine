package com.projectx.script

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptConfigStoreTest {
    enum class Mode { SAFE, FAST }

    class Settings : ConfigurableScript {
        val section = ConfigSection("Main")
        val enabled = BooleanConfigItem("Enabled", "", false)
        val count = IntConfigItem("Count", "", 5, 0, 10)
        val pin = StringConfigItem("PIN", "", "")
        val mode = EnumConfigItem("Mode", "", Mode.entries.toTypedArray(), Mode.SAFE)
        val speed = OptionsConfigItem("Speed", "", arrayOf("slow", "quick"), "slow")
        val status = InfoDisplayConfigItem("Status", "", "idle")
    }

    class Untouched : ConfigurableScript {
        val enabled = BooleanConfigItem("Enabled", "", false)
        val count = IntConfigItem("Count", "", 5, 0, 10)
        val mode = EnumConfigItem("Mode", "", Mode.entries.toTypedArray(), Mode.SAFE)
    }

    private lateinit var dir: File
    private lateinit var previous: File

    @BeforeTest
    fun useTempDirectory() {
        previous = ScriptConfigStore.directory
        dir = Files.createTempDirectory("script-settings").toFile()
        ScriptConfigStore.directory = dir
    }

    @AfterTest
    fun restoreDirectory() {
        ScriptConfigStore.directory = previous
        dir.deleteRecursively()
    }

    @Test
    fun `a change is written to disk under the script's class name`() {
        val script = Settings()
        script.enabled.value = true
        script.count.value = 8
        script.pin.value = "1234"
        script.mode.value = Mode.FAST
        script.speed.value = "quick"
        ScriptConfigStore.save(script)

        val text = File(dir, "${Settings::class.java.name}.json").readText()
        assertTrue("\"count\": 8" in text, text)
        assertTrue("\"mode\": \"FAST\"" in text, text)
        assertTrue("status" !in text && "section" !in text, text)

        val next = Settings()
        ScriptConfigStore.applyTo(next)
        assertEquals(true, next.enabled.value)
        assertEquals(8, next.count.value)
        assertEquals("1234", next.pin.value)
        assertEquals(Mode.FAST, next.mode.value)
        assertEquals("quick", next.speed.value)
    }

    @Test
    fun `a new session reads settings back from disk and keeps defaults for anything stale`() {
        File(dir, "${Untouched::class.java.name}.json").writeText(
            """{ "enabled": true, "count": 99, "mode": "GONE", "removed": "x" }""",
        )
        val script = Untouched()
        ScriptConfigStore.applyTo(script)
        assertEquals(true, script.enabled.value)
        assertEquals(10, script.count.value)
        assertEquals(Mode.SAFE, script.mode.value)
    }
}
