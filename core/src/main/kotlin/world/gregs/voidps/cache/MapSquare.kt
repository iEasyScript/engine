package world.gregs.voidps.cache

/**
 * How index 5 addresses a map square, and what the files of one archive are.
 *
 * The NXT cache differs from the legacy one in the two ways that matter to a reader. A map square
 * is **one archive of up to nine files** rather than five separately named archives, and index 5's
 * reference table carries **no names at all** - not even hashes - so the region is read straight
 * off the archive id rather than looked up from a name hash. Both directions are pure arithmetic,
 * which is why nothing here needs the tree's `index.json`.
 */
object MapSquare {

    /** Tiles per level, per axis. */
    const val SIZE = 64

    /** Levels a surface map square has. */
    const val LEVELS = 4

    /** The archive holding region ([regionX], [regionY]). */
    fun archive(regionX: Int, regionY: Int): Int = regionX or (regionY shl COORDINATE_BITS)

    fun regionX(archive: Int): Int = archive and COORDINATE_MASK

    fun regionY(archive: Int): Int = archive shr COORDINATE_BITS

    /** `<regionX>_<regionY>`, the directory one archive is unpacked into. */
    fun directory(archive: Int): String = "${regionX(archive)}_${regionY(archive)}"

    /** The archive [directory] names, or null when it is not a region directory. */
    fun archiveOf(directory: String): Int? {
        val separator = directory.indexOf('_')
        if (separator <= 0) {
            return null
        }
        val regionX = directory.substring(0, separator).toIntOrNull() ?: return null
        val regionY = directory.substring(separator + 1).toIntOrNull() ?: return null
        if (regionX !in 0..COORDINATE_MASK || regionY < 0) {
            return null
        }
        return archive(regionX, regionY)
    }

    /**
     * The nine files a map square archive can hold, by file id.
     *
     * [file] is the name the cache source tree stores each one under and [levels] how many
     * `64 * 64` grids of tile records the legacy pair holds: a surface square has four, the
     * underwater scene the client builds with a single plane has one.
     *
     * The NXT client requests only [LOCS], [UNDERWATER_LOCS], [TERRAIN], [ENVIRONMENT], [LIGHTS]
     * and [PATCHES]. [LEGACY_TILES] and [LEGACY_UNDERWATER_TILES] are the pre-NXT tile grids that
     * [TERRAIN] replaced and can be stale where a region has been rebuilt since; [NPCS] nothing in
     * the client reads at all.
     */
    enum class File(val id: Int, val file: String, val levels: Int = 0) {
        LOCS(0, "locs"),
        UNDERWATER_LOCS(1, "underwater_locs"),
        NPCS(2, "npcs"),
        LEGACY_TILES(3, "legacy_terrain", LEVELS),
        LEGACY_UNDERWATER_TILES(4, "legacy_underwater", 1),
        TERRAIN(5, "terrain"),
        ENVIRONMENT(6, "environment"),
        LIGHTS(7, "lights"),
        PATCHES(8, "patches");

        companion object {
            private val byId = entries.associateBy { it.id }

            private val byFile = entries.associateBy { it.file }

            fun of(id: Int): File? = byId[id]

            fun ofFile(name: String): File? = byFile[name]
        }
    }

    /** Region coordinates pack into one archive id, the x coordinate in the low bits. */
    private const val COORDINATE_BITS = 7

    private const val COORDINATE_MASK = (1 shl COORDINATE_BITS) - 1
}
