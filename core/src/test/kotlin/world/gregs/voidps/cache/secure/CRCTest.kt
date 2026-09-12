package world.gregs.voidps.cache.secure

import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cache CRC must be exactly standard CRC-32 (the table polynomial `0x12477cdf.inv()`
 * is `0xEDB88320`, the reflected IEEE polynomial) — Jagex master-index/ref-table CRCs are
 * plain CRC-32 over the container bytes.
 */
class CRCTest {

    @Test
    fun `check value - CRC32 of ASCII 123456789 is CBF43926`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xCBF43926.toInt(), CRC.calculate(data))
    }

    @Test
    fun `empty input yields zero`() {
        assertEquals(0, CRC.calculate(ByteArray(0)))
    }

    @Test
    fun `matches java util zip CRC32 on random data`() {
        val random = Random(1234)
        for (size in intArrayOf(1, 2, 5, 64, 255, 1024, 4096)) {
            val data = random.nextBytes(size)
            val reference = CRC32().apply { update(data) }.value.toInt()
            assertEquals(reference, CRC.calculate(data), "size=$size")
        }
    }

    @Test
    fun `length parameter is an exclusive end index, not a count`() {
        // All call sites pass (offset = 0, length = data.size); the loop runs
        // `for (i in offset until length)`. Pin that contract.
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val prefix = data.copyOfRange(0, 5)
        assertEquals(CRC.calculate(prefix), CRC.calculate(data, 0, 5))

        val middle = data.copyOfRange(2, 5)
        val reference = CRC32().apply { update(middle) }.value.toInt()
        assertEquals(reference, CRC.calculate(data, 2, 5))
    }
}
