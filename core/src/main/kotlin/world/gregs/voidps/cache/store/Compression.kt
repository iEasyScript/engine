package world.gregs.voidps.cache.store

/**
 * The compression types a JS5 container can use.
 *
 * Legacy caches use the first three; the NXT cache adds [LZMA], which its model index is almost
 * entirely stored in.
 */
enum class Compression(val id: Int) {
    NONE(0),
    BZIP2(1),
    GZIP(2),
    LZMA(3);

    /** The lower case name `index.json` spells it as. */
    val label: String
        get() = name.lowercase()

    companion object {
        private val byId = arrayOfNulls<Compression>(4).also { types ->
            for (type in entries) {
                types[type.id] = type
            }
        }

        /** The type [id] came from a container header. */
        fun of(id: Int): Compression = byId.getOrNull(id)
            ?: throw IllegalArgumentException("Unsupported container compression type $id.")

        /** The type [label] names in `index.json`. */
        fun of(label: String): Compression = entries.firstOrNull { it.label == label.lowercase() }
            ?: throw IllegalArgumentException("Unknown container compression '$label'.")
    }
}
