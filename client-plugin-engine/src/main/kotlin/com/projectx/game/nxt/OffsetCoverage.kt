package com.projectx.game.nxt

import com.projectx.game.platform.Platform
import io.github.classgraph.ClassGraph

/**
 * Cross-checks every `by offset()` declaration against the bundled tables.
 *
 * A cross-platform offset that no table carries is a defect that would otherwise only surface as a
 * thrown lookup deep inside whatever script touched it first, so it is reported up front: as a
 * failing test on `check`, and again at injection time for the running client's own table.
 */
object OffsetCoverage {
    private const val OFFSETS_PACKAGE = "com.projectx.game.nxt"
    private val OFFSET_OBJECT = Regex("O[A-Z][A-Za-z0-9]*")

    data class Gaps(val table: String, val missing: List<String>) {
        val isEmpty: Boolean get() = missing.isEmpty()
    }

    /** Every declared offset key, with the platforms whose tables must carry it. */
    fun declarations(): List<OffsetDeclaration> {
        loadOffsetObjects()
        return OffsetTable.declarations().sortedBy { it.key }
    }

    fun gaps(tableName: String): Gaps {
        val table = OffsetTable.bundled(tableName)
        val missing = declarations()
            .filter { it.appliesTo(table.platformKind, table.renderer) && it.key !in table.values }
            .map { it.key }
        return Gaps(tableName, missing)
    }

    fun gaps(): List<Gaps> = OffsetTable.bundledTableNames().map(::gaps)

    /**
     * Keys a table turns out to carry despite the declaration excluding its platform — either a
     * port-pending marker whose value has landed, or a property narrowed to one platform whose
     * field the other platform's client has after all. Both leave a resolvable offset unreachable.
     */
    fun staleScoping(tableName: String): List<String> {
        val table = OffsetTable.bundled(tableName)
        return declarations()
            .filter { !it.appliesTo(table.platformKind, table.renderer) && it.key in table.values }
            .map { it.key }
    }

    /**
     * Reports coverage for the table this client is running against. Returns the keys that should
     * have resolved and cannot — always empty when the coverage test passed for this build.
     */
    fun reportCurrentPlatform(): List<String> {
        val declared = declarations()
        val renderer = OffsetTable.renderer
        val scoped = declared.count { !it.appliesTo(Platform.current, renderer) }
        val values = OffsetTable.bundled(OffsetTable.tableName).values
        val missing = declared
            .filter { it.appliesTo(Platform.current, renderer) && it.key !in values }
            .map { it.key }

        val pending = declared.filter { it.portPending && !it.appliesTo(Platform.current) }.map { it.key }
        println("[OffsetCoverage] table ${OffsetTable.tableName}: ${declared.size - scoped} offsets required on ${Platform.current.id}/${renderer.id}, $scoped scoped to other clients")
        if (pending.isNotEmpty()) {
            println("[OffsetCoverage] ${pending.size} not yet reverse engineered for ${Platform.current.id}: ${pending.joinToString()}")
        }
        if (missing.isNotEmpty()) {
            println("[OffsetCoverage] ${missing.size} MISSING from ${OffsetTable.platform} build ${OffsetTable.build}:")
            missing.forEach { println("[OffsetCoverage]   $it") }
        }
        return missing
    }

    /**
     * Constructing each offset object runs its property delegates, which is what registers the
     * declarations. Only the value lookup is lazy, so nothing here reads client memory.
     */
    private fun loadOffsetObjects() {
        val loader = OffsetCoverage::class.java.classLoader
        ClassGraph()
            .overrideClassLoaders(loader)
            .acceptPackagesNonRecursive(OFFSETS_PACKAGE)
            .enableClassInfo()
            .scan()
            .use { scan ->
                scan.allClasses
                    .filter { OFFSET_OBJECT.matches(it.simpleName) }
                    .forEach { runCatching { Class.forName(it.name, true, loader).getField("INSTANCE").get(null) } }
            }
    }
}
