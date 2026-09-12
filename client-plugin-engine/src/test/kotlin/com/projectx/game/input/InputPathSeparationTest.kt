package com.projectx.game.input

import com.projectx.game.input.wire.WireInput
import com.projectx.game.input.wire.WirePacketVariant
import com.projectx.game.input.wire.WirePacketVariants
import com.projectx.game.platform.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire path forwards a trail to the server and must never produce real input, so it must not reach the
 * action path at all. Nothing in the type system enforces that — both are ordinary objects in the same
 * module — so it is enforced here instead.
 */
class InputPathSeparationTest {

    @Test
    fun `wire path never references the action path`() {
        val offenders = sourceFiles("wire").flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> isComment(line) }
                .filter { (_, line) -> line.contains("input.action") || line.contains("ActionInput") }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "the wire path must not reach the action path — a server-only send would become real input:\n" +
                offenders.joinToString("\n")
        )
    }

    @Test
    fun `action path never drives the wire sender`() {
        val offenders = sourceFiles("action").flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> isComment(line) }
                .filter { (_, line) -> line.contains("WireInput") }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "the action path must not send on the wire — the client's own orchestrator already does:\n" +
                offenders.joinToString("\n")
        )
    }

    @Test
    fun `windows requires the native variants and other platforms do not`() {
        val expected = if (Platform.current == Platform.WINDOWS) {
            setOf(
                WirePacketVariant.MOUSE_MOVEMENT_HISTORY,
                WirePacketVariant.NATIVE_MOUSE_CLICK,
                WirePacketVariant.NATIVE_MOUSE_MOVEMENT_HISTORY,
            )
        } else {
            setOf(WirePacketVariant.MOUSE_MOVEMENT_HISTORY)
        }
        assertEquals(expected, WirePacketVariants.required)
    }

    /**
     * A trail must never be built from the click sender: its ring entries are button presses, so a synthetic
     * "the cursor was here" send announces a left click. Only the movement-history packet reports position.
     */
    @Test
    fun `a cursor trail never requires the click packet alone`() {
        assertTrue(
            WirePacketVariant.MOUSE_CLICK !in WirePacketVariants.required,
            "the click packet cannot express cursor motion — requiring it would sanction click spam"
        )
        assertTrue(
            WirePacketVariant.MOUSE_MOVEMENT_HISTORY in WirePacketVariants.required,
            "every platform needs the movement-history packet to report position"
        )
    }

    /**
     * Outside an injected client there is no game memory to reach, so every capability probe must fail and the
     * path must fail closed. A probe that returned "available" here would be reporting on state it cannot see.
     */
    @Test
    fun `the wire path fails closed outside the client`() {
        assertTrue(
            !WirePacketVariants.complete,
            "with no client to inspect, the packet set cannot be complete"
        )
        assertTrue(!WireInput.isEnabled, "WireInput must refuse to send when its packet set is incomplete")
        assertTrue(
            WirePacketVariants.unavailable.isNotEmpty(),
            "an incomplete packet set must say which variant is missing and why"
        )
    }

    /** Every required variant needs a probe, or a missing producer would silently read as available. */
    @Test
    fun `every required variant is covered by a capability probe`() {
        assertEquals(
            WirePacketVariants.required,
            WirePacketVariants.unavailable.keys,
            "each required variant must report a reason while it is unproducible"
        )
    }

    private fun sourceFiles(subPackage: String): List<File> {
        val dir = File(moduleRoot(), "src/main/kotlin/com/projectx/game/input/$subPackage")
        val files = dir.listFiles { f: File -> f.extension == "kt" }?.toList().orEmpty()
        assertTrue(files.isNotEmpty(), "no sources found under ${dir.path}")
        return files
    }

    /** Gradle runs tests from the module directory, but fall back to walking up so an IDE run works too. */
    private fun moduleRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "src/main/kotlin/com/projectx/game/input").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        error("could not locate the client-plugin-engine module root from ${File("").absolutePath}")
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }
}
