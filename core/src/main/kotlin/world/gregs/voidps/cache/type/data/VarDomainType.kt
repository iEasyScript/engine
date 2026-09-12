package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.UnusedRecords

interface VarDomainType : CacheType, UnusedRecords {
    var type: Int
    var lifetime: Int
    var transmit: Int
    val serverperm: Boolean get() = lifetime == SERVERPERMANENT

    /** 64-bit vars are held whole in one slot; reading only the low word silently drops bits 32-63. */
    val long: Boolean get() = type == LONG

    companion object {
        const val TEMPORARY = 0
        const val PERMANENT = 1
        const val SERVERPERMANENT = 2

        const val LONG = 110
    }
}
