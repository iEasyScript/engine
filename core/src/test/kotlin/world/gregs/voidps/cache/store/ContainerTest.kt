package world.gregs.voidps.cache.store

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContainerTest {

    private val sample = ByteArray(20_000) { (it % 37 + it / 200).toByte() } + Random(7).nextBytes(3_000)

    @Test
    fun `every compression round trips with and without a trailer`() {
        for (compression in Compression.entries) {
            for (version in listOf(null, 0x1234)) {
                val container = Container.compress(sample, compression, version)
                val encoded = container.encode()
                val decoded = Container.decode(encoded, trailer = version != null)
                assertEquals(compression, decoded.compression)
                assertEquals(version, decoded.version)
                assertContentEquals(sample, decoded.data(), "$compression $version")
                assertContentEquals(encoded, decoded.encode())
            }
        }
    }

    @Test
    fun `settings are recovered for every gzip level and both lzma match finders`() {
        for (level in 1..9) {
            val container = Container.compress(sample, Compression.GZIP, null, CompressionSettings(gzipLevel = level))
            val settings = assertNotNull(container.settings(sample))
            assertContentEquals(container.payload, Container.compress(sample, Compression.GZIP, null, settings).payload)
        }
        for (variant in LzmaVariant.entries) {
            val container = Container.compress(sample, Compression.LZMA, null, CompressionSettings(lzma = variant))
            val settings = assertNotNull(container.settings(sample))
            assertContentEquals(container.payload, Container.compress(sample, Compression.LZMA, null, settings).payload)
        }
        val bzip = Container.compress(sample, Compression.BZIP2, null)
        assertEquals(BZip2Variant.LIBBZIP2, assertNotNull(bzip.settings(sample)).bzip2)
    }

    @Test
    fun `a payload nothing reproduces reports no settings`() {
        val container = Container.compress(sample, Compression.GZIP, null)
        val tampered = Container.stored(Compression.GZIP, container.payload.copyOf().also { it[15] = (it[15] + 1).toByte() }, sample.size, null)
        assertNull(tampered.settings(sample))
    }

    @Test
    fun `xtea encryption covers the payload and the size but not the header`() {
        val keys = intArrayOf(1, 2, 3, 4)
        val container = Container.compress(sample, Compression.GZIP, 5)
        val encrypted = container.encode(keys)
        val plain = container.encode()
        assertContentEquals(plain.copyOf(5), encrypted.copyOf(5))
        assertContentEquals(plain, Container.decode(encrypted, keys).encode())
        assertEquals(container.crc(), Container.decode(encrypted, keys).crc())
    }
}
