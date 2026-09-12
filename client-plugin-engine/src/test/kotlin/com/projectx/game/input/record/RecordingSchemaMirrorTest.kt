package com.projectx.game.input.record

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine writes recording databases and the Python legacy importer creates the same tables, so the DDL
 * exists in two places. This keeps Kotlin as the single source: the `.sql` in the shared spec directory is a
 * generated mirror, and a drift between them fails here instead of producing a database whose reader quietly
 * misinterprets a column.
 *
 * Set `-Dprojectx.regenerateSchemaMirror=true` to rewrite the mirror from the Kotlin DDL.
 */
class RecordingSchemaMirrorTest {
    private val header = listOf(
        "-- GENERATED from RecordingSchema.statements — do not hand-edit.",
        "-- Regenerate: ./gradlew :client-plugin-engine:test --tests '*RecordingSchemaMirrorTest*' \\",
        "--   -Dprojectx.regenerateSchemaMirror=true",
        "-- Kotlin owns this DDL because the engine is the only writer; the Python importer reads it.",
    )

    private fun expectedContent(): String {
        val statements = RecordingSchema.statements.joinToString("\n\n") { "$it;" }
        return (header + listOf("", statements)).joinToString("\n") + "\n"
    }

    @Test
    fun `the sql mirror matches the kotlin ddl`() {
        val mirror = File(repositoryRoot(), "ai-input/spec/recording_schema_v$SCHEMA_FILE_VERSION.sql")
        val expected = expectedContent()

        if (System.getProperty("projectx.regenerateSchemaMirror") == "true") {
            mirror.parentFile.mkdirs()
            mirror.writeText(expected)
            println("[RecordingSchemaMirrorTest] regenerated ${mirror.path}")
            return
        }

        assertTrue(
            mirror.isFile,
            "${mirror.path} is missing — regenerate it with -Dprojectx.regenerateSchemaMirror=true"
        )
        // Compared with newlines normalised: with core.autocrlf the mirror checks out CRLF on Windows and
        // LF elsewhere, and that is a property of the checkout rather than drift in the DDL.
        assertEquals(
            expected,
            mirror.readText().replace("\r\n", "\n"),
            "the sql mirror has drifted from RecordingSchema.statements — regenerate it with " +
                "-Dprojectx.regenerateSchemaMirror=true"
        )
    }

    @Test
    fun `the mirror filename tracks the schema version`() {
        assertEquals(
            RecordingSchema.VERSION,
            SCHEMA_FILE_VERSION,
            "bump SCHEMA_FILE_VERSION and rename the mirror when the schema version changes"
        )
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "ai-input/spec").isDirectory) return candidate
            candidate = candidate.parentFile
        }
        error("could not locate the repository root from ${File("").absolutePath}")
    }

    private companion object {
        const val SCHEMA_FILE_VERSION = 3
    }
}
