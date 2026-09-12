package world.gregs.voidps.gameval

import org.projectx.core.Logger.logError
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.secure.CRC

/**
 * Guards the private server against gameval component drift: names are only correct for the interface
 * index they were aligned to ([ComponentAlignmentStamp]), and a cache download that inserts one component
 * silently moves every name after it. Comparing the stamp with the cache actually being served turns that
 * silent shift into a loud startup error and a failing test.
 */
object GamevalAlignment {
    const val REALIGN_COMMAND =
        "./gradlew :tools:gamevalExport -Pargs=\"--cache ./data/betacache --out re-resources/gamevals --align ./data/cache\""

    fun servedInterfacesCrc(cache: Cache): Int? = cache.sector(255, Index.INTERFACES)?.let { CRC.calculate(it) }

    /** Why the component names cannot be trusted against [cache], or null when they can. */
    fun drift(cache: Cache): String? {
        if (!Gameval.available) return null
        val stamp = Gameval.componentAlignment()
            ?: return "component.json carries no alignment stamp, so its component ids are unverified beta slots. Re-run: $REALIGN_COMMAND"
        val served = servedInterfacesCrc(cache)
            ?: return "the served cache has no interface index to check the component names against"
        if (served == stamp.interfacesCrc) return null
        return "component names were aligned to interface index crc ${stamp.interfacesCrc} but the served cache's is $served, " +
            "so every name after an inserted component addresses the wrong slot. Re-run: $REALIGN_COMMAND"
    }

    fun warnIfDrifted(cache: Cache) {
        drift(cache)?.let { logError("Gameval drift: $it") }
    }
}
