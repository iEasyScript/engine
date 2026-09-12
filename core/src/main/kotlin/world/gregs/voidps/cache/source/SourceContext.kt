package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import java.nio.file.Path

/**
 * What every codec is allowed to know about the tree it is working on.
 *
 * A codec is handed one index directory at a time, which keeps it honest about what it owns, but
 * a few facts are the tree's rather than any index's: which client era the cache belongs to, and
 * the gameval catalogs that name every id in it. [Unpacker], [Packer], [Verifier] and [Builder]
 * install the tree's context before they touch a codec, and [SourceCodecs.context] hands it out.
 */
class SourceContext(
    val root: Path,
    val revision: Int,
    /** The catalogs under `<tree>/gamevals`, read lazily and only when a codec asks. */
    val gamevals: GamevalCatalogs
) {

    val era: CacheEra
        get() = CacheEra.of(revision)

    companion object {

        fun of(tree: SourceTree): SourceContext =
            SourceContext(tree.directory, tree.revision, GamevalCatalogs(tree.gamevals))

        /** A context with no tree behind it, for a codec used on its own in a test. */
        fun none(revision: Int): SourceContext =
            SourceContext(Path.of("."), revision, GamevalCatalogs(null))
    }
}
