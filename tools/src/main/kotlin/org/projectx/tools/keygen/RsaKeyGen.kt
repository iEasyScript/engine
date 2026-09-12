package org.projectx.tools.keygen

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Generates a 1024-bit RSA keypair for use with the NXT client.
 *
 * Outputs .env-compatible lines for the server (private exponent for decryption)
 * and a hex modulus comment for the launcher patcher config.
 *
 * Usage:
 *   ./gradlew :tools:rsaKeyGen                  # prints to stdout
 *   ./gradlew :tools:rsaKeyGen --args="keys.env" # writes to file
 */
fun main(args: Array<String>) {
    val bits = 1024
    val random = SecureRandom()
    val publicExponent = BigInteger("65537")

    var p: BigInteger
    var q: BigInteger
    var phi: BigInteger
    var modulus: BigInteger
    var privateExponent: BigInteger

    do {
        p = BigInteger.probablePrime(bits / 2, random)
        q = BigInteger.probablePrime(bits / 2, random)
        phi = (p - BigInteger.ONE) * (q - BigInteger.ONE)
        modulus = p * q
        privateExponent = publicExponent.modInverse(phi)
    } while (modulus.bitLength() != bits
        || privateExponent.bitLength() != bits
        || phi.gcd(publicExponent) != BigInteger.ONE
    )

    val output = buildString {
        appendLine("# RSA keypair generated for Project X server")
        appendLine("# The modulus is shared between JS5 (version table signing) and world (login decryption).")
        appendLine("# The exponent is the PRIVATE key — used by the server to decrypt.")
        appendLine("# The public key (65537) is what the client uses to encrypt.")
        appendLine()
        appendLine("RSA_JS5_MODULUS=${modulus}")
        appendLine("RSA_JS5_EXPONENT=${privateExponent}")
        appendLine("RSA_WORLD_MODULUS=${modulus}")
        appendLine("RSA_WORLD_EXPONENT=${privateExponent}")
        appendLine()
        appendLine("# Hex modulus for client patcher (PROJECTX_RSA_MODULUS env var):")
        appendLine("# ${modulus.toString(16)}")
    }

    if (args.isNotEmpty()) {
        val file = java.io.File(args[0])
        file.writeText(output)
        println("RSA keypair written to ${file.absolutePath}")
    } else {
        print(output)
    }
}
