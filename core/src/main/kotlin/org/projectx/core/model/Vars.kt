package org.projectx.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.projectx.core.EnvVars
import org.projectx.core.Logger
import org.projectx.core.game.ServerPermVarcDefaults
import org.projectx.core.game.combat.VarReader
import org.projectx.core.net.Session
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.ConcurrentHashMap

@Serializable
class Vars(
    val saved: MutableMap<Int, Int> = HashMap(),
    val serverpermVarcs: MutableMap<Int, Int> = HashMap(),
) : VarReader {
    companion object {
        val BIT_MASKS = IntArray(32).apply {
            var value = 2
            for (i in 0 until 32) {
                this[i] = value - 1
                value *= 2
            }
        }

        // Values and capture ORDER stay here because the login block is byte-verified against a live
        // capture; the cache cannot reproduce the captured values/order. Membership (which ids are
        // serverperm) is derived from the cache via [serverpermVarcIds] and reconciled against these keys.
        val DEFAULT_SERVERPERM_VARCS: Map<Int, Int> by lazy {
            val out = LinkedHashMap<Int, Int>(ServerPermVarcDefaults.VALUES.size)
            for ((name, value) in ServerPermVarcDefaults.VALUES) {
                out[Gameval.requireId(Gameval.VAR_CLIENT, name)] = value
            }
            out
        }

        val serverpermVarcIds: Set<Int> by lazy {
            val fromCache = runCatching { Cache.varcs.filter { it.serverperm }.map { it.id }.toSet() }
                .getOrDefault(emptySet())
            if (fromCache.isEmpty()) {
                DEFAULT_SERVERPERM_VARCS.keys
            } else {
                val missing = DEFAULT_SERVERPERM_VARCS.keys - fromCache
                if (missing.isNotEmpty()) {
                    Logger.log("Vars", "cache serverperm set missing ${missing.size} captured varc ids")
                }
                fromCache
            }
        }

        fun isServerpermVarc(id: Int): Boolean = id in serverpermVarcIds
    }

    @Transient
    private val modified: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val varpValues: IntArray by lazy {
        IntArray(Cache.varPlayers.size)
    }
    @Transient
    private var session: Session? = null

    fun init(session: Session): Vars {
        this.session = session
        saved.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    fun initOffline(): Vars {
        saved.forEach { (varId, value) -> setVar(varId, value) }
        return this
    }

    @JvmOverloads
    fun setVar(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        if (forceSend) modified.add(id)
        if (id < 0 || id >= varpValues.size || varpValues[id] == value) return
        varpValues[id] = value
        if (save) saved[id] = value
        modified.add(id)
        if (EnvVars.debug) Logger.log("Vars", "varp ${Gameval.varpLabel(id)} = $value")
    }

    fun setVar(name: String, value: Int, forceSend: Boolean = false, save: Boolean = false) =
        setVar(Gameval.requireId(Gameval.VAR_PLAYER, name), value, forceSend, save)

    fun saveVar(id: Int, value: Int) = setVar(id, value, save = true)
    fun saveVar(name: String, value: Int) = setVar(name, value, save = true)

    @JvmOverloads
    fun setVarBit(id: Int, value: Int, forceSend: Boolean = false, save: Boolean = false) {
        val defs = Cache.varbits[id]
        val bitLength = defs.endBit - defs.startBit
        val mask = BIT_MASKS[bitLength]
        val cappedValue = value.coerceIn(0, mask)
        val shiftedMask = mask shl defs.startBit
        val varpValue = (varpValues[defs.index] and shiftedMask.inv()) or
                ((cappedValue shl defs.startBit) and shiftedMask)
        if (varpValue != varpValues[defs.index]) {
            if (EnvVars.debug) Logger.log("Vars", "varbit ${Gameval.varbitPlayerLabel(id)} = $cappedValue")
            setVar(defs.index, varpValue, forceSend, save)
        }
    }

    fun setVarBit(name: String, value: Int, forceSend: Boolean = false, save: Boolean = false) =
        setVarBit(Gameval.requireId(Gameval.VARBIT, name), value, forceSend, save)

    fun saveVarBit(id: Int, value: Int) = setVarBit(id, value, save = true)
    fun saveVarBit(name: String, value: Int) = setVarBit(name, value, save = true)

    override fun getVar(id: Int): Int = if (id in varpValues.indices) varpValues[id] else 0
    fun getVar(name: String): Int = getVar(Gameval.requireId(Gameval.VAR_PLAYER, name))

    override fun getVarBit(id: Int): Int {
        val defs = Cache.varbits[id]
        val bitLength = defs.endBit - defs.startBit
        return (varpValues[defs.index] shr defs.startBit) and BIT_MASKS[bitLength]
    }

    fun getVarBit(name: String): Int = getVarBit(Gameval.requireId(Gameval.VARBIT, name))

    fun bitFlagged(varpId: Int, bit: Int) = (varpValues[varpId] and (1 shl bit)) != 0

    fun syncVarsToClient() {
        val s = session ?: return
        modified.forEach { id ->
            if (id < 0 || id >= varpValues.size) return@forEach
            val value = varpValues[id]
            if (value.toLong() in -128..127) {
                s.queuePacket(VarpSmall(id, value))
            } else {
                s.queuePacket(VarpLarge(id, value))
            }
        }
        modified.clear()
    }

    fun syncAllToClient() {
        val s = session ?: return
        for (id in varpValues.indices) {
            val value = varpValues[id]
            if (value == 0) continue
            if (value.toLong() in -128..127) {
                s.queuePacket(VarpSmall(id, value))
            } else {
                s.queuePacket(VarpLarge(id, value))
            }
        }
    }

    suspend fun clearVars() {
        varpValues.fill(0)
        session?.send(ResetClientVarcache())
    }

    suspend fun setVarc(id: Int, value: Int) {
        val s = session ?: return
        if (value.toLong() in -128..127) {
            s.send(ClientSetVarcSmall(id, value))
        } else {
            s.send(ClientSetVarcLarge(id, value))
        }
    }

    suspend fun setVarc(name: String, value: Int) = setVarc(Gameval.requireId(Gameval.VAR_CLIENT, name), value)

    suspend fun setVarcStr(id: Int, value: String) {
        session?.send(ClientSetVarcStr(id, value))
    }

    fun saveVarc(id: Int, value: Int) {
        if (isServerpermVarc(id)) {
            serverpermVarcs[id] = value
        }
    }

    fun saveVarc(name: String, value: Int) = saveVarc(Gameval.requireId(Gameval.VAR_CLIENT, name), value)

    fun serverpermBlock(): ByteArray {
        val merged = LinkedHashMap(DEFAULT_SERVERPERM_VARCS)
        merged.putAll(serverpermVarcs)
        val out = BufferWriter(1 + merged.size * 6)
        out.writeByte(1)
        for ((id, value) in merged) {
            out.writeShort(id)
            out.writeInt(value)
        }
        return out.toArray()
    }
}
