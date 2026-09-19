package com.projectx.ui.backend.dsl.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ImGuiCol] and [ImGuiStyleVar] are passed to ImGui by ordinal, so they are positional mirrors of the
 * enums in the vendored `imgui.h`. Nothing enforces that at compile time, and a drift is silent in the
 * common case: every push simply lands on the neighbouring style slot, so the overlay renders with the
 * wrong colours and metrics rather than failing.
 *
 * It stops being silent only where the shifted slot also changes type - a `PushStyleVar` float landing on
 * an ImVec2 slot is rejected by ImGui, the matching `PopStyleVar(n)` then over-pops, and the style stack
 * underflows every frame until ImGui's error recovery tears the frame down. That is what an ImGui bump
 * did here: six style vars and three colours were added upstream, the Kotlin mirrors were not updated,
 * and the overlay flickered and rendered corrupt.
 *
 * Only the entries before `_COUNT` are compared; the obsolete `= alias` entries that follow it are not
 * part of the sequence.
 */
class ImGuiEnumParityTest {

    @Test
    fun `ImGuiCol mirrors the vendored imgui header`() =
        assertMirrors("ImGuiCol", ImGuiCol.entries.map { it.name })

    @Test
    fun `ImGuiStyleVar mirrors the vendored imgui header`() =
        assertMirrors("ImGuiStyleVar", ImGuiStyleVar.entries.map { it.name })

    private fun assertMirrors(enumName: String, kotlinNames: List<String>) {
        val header = File(repositoryRoot(), "client-plugin-engine/native-bootstrap/imgui/imgui.h")
        assertTrue(header.isFile, "${header.path} is missing")

        val expected = headerEntries(header.readText(), enumName) + "COUNT"
        assertEquals(
            expected,
            kotlinNames,
            "$enumName has drifted from imgui.h. These enums are passed by ordinal, so every entry after " +
                "the first difference addresses the wrong ImGui slot. Re-mirror the header.",
        )
    }

    /** The entries of `enum <name>_ { ... }` in declaration order, stopping at `_COUNT`. */
    private fun headerEntries(header: String, enumName: String): List<String> {
        val body = Regex("""^enum ${enumName}_$\s*\{(.*?)^};""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
            .find(header)
            ?.groupValues
            ?.get(1)
            ?: error("imgui.h has no `enum ${enumName}_` block")

        val entry = Regex("""^\s*${enumName}_(\w+),""")
        val names = mutableListOf<String>()
        for (line in body.lineSequence()) {
            val name = entry.find(line)?.groupValues?.get(1) ?: continue
            if (name == "COUNT") break
            names += name
        }
        check(names.isNotEmpty()) { "parsed no entries from `enum ${enumName}_`" }
        return names
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "ai-input/spec").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        error("could not locate the repository root from ${File("").absolutePath}")
    }
}
