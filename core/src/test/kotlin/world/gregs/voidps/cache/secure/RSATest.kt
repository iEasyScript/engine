package world.gregs.voidps.cache.secure

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RSA sign/verify self-test: signing with the private exponent and verifying with e=65537
 * must recover the exact
 * plaintext, and the master-index plaintext layout (0x01 + 64-byte Whirlpool) must be
 * immune to BigInteger signed/unsigned ambiguity.
 *
 * Uses a fixed 1024-bit test key (deterministically generated, NOT a production key).
 */
class RSATest {

    private val modulus = BigInteger(
        "87147057f2369af17059662ecd5b667c9fe4101b51c9b0101f53b2ee62b37410" +
            "606be4a2e172300b3cfe34de6d9a5abc6d98220abe1f2b51fa378607805d161e" +
            "3ecc976e004b623df283d5f656f28d97d5127b4f218a352cd06748ee0b3a8b1a" +
            "4cd391577e40611bba14d43a99b87c31aeefc48c1555c8c63c547b2b57a1b58d",
        16,
    )
    private val privateExponent = BigInteger(
        "2f3b0b4904ece8e250b9418c4dcb8e9b36c0e48cd8dd1f8d93968d72e2da0254" +
            "1392c61fd99a3688476c4f0a12e2aca543e0b4ce72c9ad38fdb75a4ad72c3526" +
            "1c4ba93aadea2054e7426c5d1e34d482c12e693a46b5af5e2b5cfcb744154ee4" +
            "8a07c7f1919941f5101ebb5a8fcc676b30b902cd48ea601bb393b93962aba6a1",
        16,
    )
    private val publicExponent = BigInteger.valueOf(65537)

    /** Strip the leading 0x00 sign byte BigInteger.toByteArray() may prepend. */
    private fun normalize(bytes: ByteArray): ByteArray =
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    @Test
    fun `sign with private key then verify with public key recovers the plaintext`() {
        // Master-index signature layout: [0x01][64-byte Whirlpool digest]
        val plaintext = ByteArray(65)
        plaintext[0] = 0x01
        Random(99).nextBytes(plaintext, 1, 65)

        val signature = RSA.crypt(plaintext, modulus, privateExponent)
        val recovered = normalize(RSA.crypt(signature, modulus, publicExponent))

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `verify-then-extract digest matches the signed digest`() {
        val digest = ByteArray(64)
        Random(7).nextBytes(digest)
        val plaintext = ByteArray(65)
        plaintext[0] = 0x01
        digest.copyInto(plaintext, 1)

        val signature = RSA.crypt(plaintext, modulus, privateExponent)
        val verified = normalize(RSA.crypt(signature, modulus, publicExponent))

        assertEquals(65, verified.size, "verified block size")
        assertEquals(0x01, verified[0].toInt(), "leading marker byte")
        assertContentEquals(digest, verified.copyOfRange(1, 65))
    }

    @Test
    fun `plaintext with leading 0x01 marker has no signed-unsigned ambiguity`() {
        // RSA.crypt feeds the bytes to the SIGNED BigInteger(ByteArray) constructor.
        // The 0x01 marker keeps the high bit clear so signed == unsigned — the signing
        // path is only safe because of this. Pin it.
        val plaintext = ByteArray(65)
        plaintext[0] = 0x01
        Random(123).nextBytes(plaintext, 1, 65)

        assertEquals(BigInteger(1, plaintext), BigInteger(plaintext))
        assertTrue(plaintext[0].toInt() and 0x80 == 0, "marker byte must keep the sign bit clear")
    }

    @Test
    fun `encrypt-decrypt round trip for login-style blocks`() {
        // Login block convention: leading 10 (magic) keeps the value positive and
        // below the modulus.
        val block = ByteArray(60)
        block[0] = 10
        Random(5).nextBytes(block, 1, 60)

        val encrypted = RSA.crypt(block, modulus, publicExponent)
        val decrypted = normalize(RSA.crypt(encrypted, modulus, privateExponent))

        assertContentEquals(block, decrypted)
    }
}
