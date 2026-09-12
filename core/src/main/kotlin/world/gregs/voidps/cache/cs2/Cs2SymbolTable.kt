package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace
import java.io.File

/**
 * Names that have been changed from the generated defaults.
 *
 * Renaming is the point of emitting variables and scripts as plain identifiers,
 * so the binding between a name and the id it stands for has to live somewhere
 * an editor can rewrite. That somewhere is the generated declarations: each
 * entry carries its id in a comment, and this reads them back.
 */
class Cs2SymbolTable(
    private val variables: Map<String, VarRef>,
    private val scripts: Map<String, Int>,
) {
    fun variable(name: String): VarRef? = variables[name]

    fun script(name: String): Int? = scripts[name]

    companion object {
        val EMPTY = Cs2SymbolTable(emptyMap(), emptyMap())

        private val DECLARATION = Regex(
            """/\*\* (${prefixes()}) (\d+)( old)?( pad\d+)?[^*]*\*/\s*\R\s*declare (?:let|const) (\w+)""",
        )

        /** Longest first, so `varclansetting` is not read as `varclan`. */
        private fun prefixes(): String =
            VarSpace.entries.sortedByDescending { it.prefix.length }.joinToString("|") { it.prefix }

        private val SCRIPT_HEADER = Regex("""^// clientscript (\d+)( \[[^\]]*])?$""", RegexOption.MULTILINE)

        private val FUNCTION = Regex("""^function (\w+)\s*\(""", RegexOption.MULTILINE)

        private const val SCRIPT_DEPTH = 2

        /**
         * Reads `vars.d.ts` and every script's header from a decompiled folder.
         * Missing files simply mean nothing was renamed.
         */
        fun read(directory: File): Cs2SymbolTable {
            val variables = HashMap<String, VarRef>()
            val declarations = directory.resolve("vars.d.ts")
            if (declarations.isFile) {
                for (match in DECLARATION.findAll(declarations.readText())) {
                    val (prefix, id, variant, padding, name) = match.destructured
                    val reference = Cs2Symbols.variableOf(
                        prefix + id +
                            (if (variant.isEmpty()) "" else "Old") +
                            (if (padding.isEmpty()) "" else "__pad" + padding.trim().removePrefix("pad")),
                    ) ?: continue
                    variables[name] = reference
                }
            }

            val scripts = HashMap<String, Int>()
            for (file in directory.walkTopDown().maxDepth(SCRIPT_DEPTH)) {
                if (!file.isFile || !file.name.endsWith(".ts") || file.name.endsWith(".d.ts")) continue
                val text = file.readText()
                val id = SCRIPT_HEADER.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val name = FUNCTION.find(text)?.groupValues?.get(1) ?: continue
                scripts[name] = id
            }
            return Cs2SymbolTable(variables, scripts)
        }
    }
}
