package com.projectx.game.nxt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A wrapper reads native objects through a bounded window. A window sized by hand encodes one
 * build's layout: when a field moves past it, every read through it throws and the caller quietly
 * gets nothing, which is how the interface list went dark on 950-1. So an object window must come
 * from the table through [OffsetTable.extent]; the only literals allowed are container headers,
 * strings and shared-pointer blocks below [HEADER_LIMIT], and the deliberately generous manager
 * windows at or above [GENEROUS_LIMIT].
 */
class WindowExtentTest {

    @Test
    fun `no wrapper sizes an object window by hand`() {
        val offenders = sourceFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                WINDOW_CALL.findAll(line)
                    .map { it.groupValues[1].toLong(16) }
                    .filter { it > HEADER_LIMIT && it < GENEROUS_LIMIT }
                    .map { "${file.relativeTo(sourceRoot()).path}:${index + 1}: 0x${it.toString(16)}" }
                    .toList()
                    .ifEmpty { null }
            }.flatten()
        }
        assertEquals(emptyList(), offenders, "object windows must be an OffsetObject.extent, not a literal")
    }

    @Test
    fun `every extent covers every field of its object in every bundled table`() {
        val short = OffsetTable.bundledTableNames().flatMap { table ->
            OffsetTable.bundled(table).values.entries.mapNotNull { (key, value) ->
                val objectName = key.substringBefore('.')
                val extent = OffsetTable.extent(objectName, table)
                if (value + Long.SIZE_BYTES > extent) "$table $key=0x${value.toString(16)} extent=0x${extent.toString(16)}" else null
            }
        }
        assertEquals(emptyList(), short)
    }

    @Test
    fun `an object with no fields still gets a usable window`() {
        val table = OffsetTable.bundledTableNames().first()
        assertTrue(OffsetTable.extent("ORouteWaypointManager", table) >= HEADER_LIMIT * 4)
    }

    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/projectx")
            if (candidate.isDirectory) return candidate
            val module = File(dir, "client-plugin-engine/src/main/kotlin/com/projectx")
            if (module.isDirectory) return module
            dir = dir.parentFile
        }
        error("engine sources not found from ${File("").absolutePath}")
    }

    private fun sourceFiles(): List<File> = sourceRoot().walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        val WINDOW_CALL = Regex("""\b(?:deref|pointerAtOffset|toMemorySegment|valueOrNull|value)\((?:[^()]*?,\s*)?(?:size\s*=\s*)?0x([0-9a-fA-F]+)L?\)""")
        const val HEADER_LIMIT = 0x40L
        const val GENEROUS_LIMIT = 0x10000L
    }
}
