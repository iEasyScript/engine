package world.gregs.voidps.cache.source.codec.cs2

import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import world.gregs.voidps.gameval.Gameval

/**
 * Where a clientscript's source lives under the index directory, and how a file says which script
 * it is.
 *
 * A named script sits at its gameval name's path - `[proc,quicksort]` is `proc/quicksort.ts` - and
 * one the catalog has no name for under the placeholder category. Either way the `// clientscript
 * <id>` header the emitter writes is what binds the file to its archive, so the path is a reading
 * aid that survives a rename of the catalog entry only until the tree is unpacked again.
 */
class Cs2Layout private constructor(
    private val paths: Map<Int, String>,
    private val ids: Map<String, Int>
) {

    fun pathOf(scriptId: Int): String =
        paths[scriptId] ?: "$UNNAMED_CATEGORY/$UNNAMED_PREFIX$scriptId$EXTENSION"

    fun scriptIdOf(path: String): Int? =
        ids[path] ?: UNNAMED_PATH.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()

    companion object {
        const val EXTENSION = ".ts"

        private const val DECLARATION_EXTENSION = ".d$EXTENSION"
        private const val UNNAMED_CATEGORY = "cs2"
        private const val UNNAMED_PREFIX = "cs2_"
        private const val REFERENCE = "/// <reference path=\"./cs2.d.ts\" />"

        private val NAME = Regex("""^\[([a-z0-9_]+),([A-Za-z0-9_.\-]+)]$""")
        private val UNNAMED_PATH = Regex("""^$UNNAMED_CATEGORY/$UNNAMED_PREFIX(\d+)\.ts$""")
        private val HEADER = Regex("""^// clientscript (\d+)""", RegexOption.MULTILINE)

        fun of(catalogs: GamevalCatalogs): Cs2Layout {
            val paths = HashMap<Int, String>()
            val ids = HashMap<String, Int>()
            for ((key, name) in catalogs.entries(Gameval.CLIENTSCRIPT)) {
                val id = key.toIntOrNull() ?: continue
                val match = NAME.matchEntire(name) ?: continue
                val path = "${match.groupValues[1]}/${match.groupValues[2]}$EXTENSION"
                if (ids.putIfAbsent(path, id) == null) {
                    paths[id] = path
                }
            }
            return Cs2Layout(paths, ids)
        }

        fun isScript(path: String): Boolean = path.endsWith(EXTENSION) && !path.endsWith(DECLARATION_EXTENSION)

        fun headerScriptId(source: String): Int? = HEADER.find(source)?.groupValues?.get(1)?.toIntOrNull()

        /** The emitter references `cs2.d.ts` as a sibling; a script in a category folder is one level down. */
        fun relocateReference(source: String, path: String): String {
            val prefix = "../".repeat(path.count { it == '/' })
            return source.replaceFirst(REFERENCE, "/// <reference path=\"${prefix}cs2.d.ts\" />")
        }
    }
}
