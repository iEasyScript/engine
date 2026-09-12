package org.projectx.core.net.login

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.projectx.core.EnvVars
import org.projectx.core.net.Isaac
import org.projectx.core.net.ResponseOpcode
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.secure.RSA
import java.math.BigInteger
import java.security.SecureRandom

class LoginHandshake(
    private val rsaModulus: BigInteger,
    private val rsaExponent: BigInteger,
) {
    private val random = SecureRandom()

    suspend fun sendExchangeData(output: ByteWriteChannel) {
        output.writeByte(ResponseOpcode.JS5_SYNC)
        output.writeFully(ByteArray(8).also { random.nextBytes(it) })
        output.flush()
    }

    fun decryptRsaBlock(packet: Source): RsaResult {
        val rsaSize = packet.readUShort()
        if (rsaSize <= 0 || rsaSize > 512) return RsaResult.Fail(ResponseOpcode.COULD_NOT_COMPLETE_LOGIN)
        val decrypted = ByteReadPacket(RSA.crypt(packet.readByteArray(rsaSize), rsaModulus, rsaExponent))
        if (decrypted.readUByte() != 10) return RsaResult.Fail(ResponseOpcode.BAD_SESSION_ID)
        val isaacKeys = IntArray(4) { decrypted.readInt() }
        val sessionNonce = decrypted.readLong()
        return RsaResult.Ok(RsaBlock(isaacKeys, sessionNonce, decrypted))
    }

    fun buildCiphers(isaacKeys: IntArray): Pair<Isaac, Isaac> {
        val inCipher = Isaac(isaacKeys.copyOf())
        val outKeys = isaacKeys.copyOf()
        for (i in outKeys.indices) outKeys[i] += EnvVars.ISAAC_DELTA
        return inCipher to Isaac(outKeys)
    }
}

class RsaBlock(val isaacKeys: IntArray, val sessionNonce: Long, val tail: Source)

sealed interface RsaResult {
    data class Ok(val block: RsaBlock) : RsaResult
    data class Fail(val response: Int) : RsaResult
}
