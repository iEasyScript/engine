package world.gregs.voidps.cache.store

/**
 * Everything about how a container's payload was compressed that the payload itself does not say
 * and that recompressing the same data has to be told to come out byte for byte the same.
 *
 * Jagex's packers have not been one program with one setting: gzip containers in the NXT cache
 * reproduce at deflate levels anywhere from 1 to 6, a few bzip2 tables need commons-compress rather
 * than libbzip2, and the model index's LZMA streams come out of two match finders. [Container.settings]
 * finds the combination that reproduces a stored payload, and `index.json` records whichever fields
 * are not the default.
 */
class CompressionSettings(
    /** The deflate level, 1 to 9. */
    val gzipLevel: Int = DEFAULT_GZIP_LEVEL,
    /** The gzip header's OS byte. */
    val gzipOs: Int = 0,
    val bzip2: BZip2Variant = BZip2Variant.LIBBZIP2,
    val lzma: LzmaVariant = LzmaVariant.FAST_BT4,
    /** The five LZMA property bytes, which name the dictionary size and the literal/position bits. */
    val lzmaProperties: ByteArray = Lzma.DEFAULT_PROPERTIES
) {

    val defaultLzmaProperties: Boolean
        get() = lzmaProperties.contentEquals(Lzma.DEFAULT_PROPERTIES)

    fun copy(
        gzipLevel: Int = this.gzipLevel,
        gzipOs: Int = this.gzipOs,
        bzip2: BZip2Variant = this.bzip2,
        lzma: LzmaVariant = this.lzma,
        lzmaProperties: ByteArray = this.lzmaProperties
    ): CompressionSettings = CompressionSettings(gzipLevel, gzipOs, bzip2, lzma, lzmaProperties)

    companion object {
        /** What the overwhelming majority of gzip containers reproduce with. */
        const val DEFAULT_GZIP_LEVEL = 6

        val DEFAULT = CompressionSettings()

        /**
         * The order the gzip levels are tried in when a payload is being matched: the common one
         * first, then the rest of the range.
         */
        val GZIP_LEVELS = intArrayOf(6, 5, 4, 3, 2, 1, 7, 8, 9)
    }
}
