package com.projectx.util

import world.gregs.voidps.gameval.Gameval

/**
 * The gameval ids one feature needs, resolved once and never silently.
 *
 * The whole dictionary set is gated at startup by GamevalCoverage, so reaching here means the set
 * loaded but this feature's names are not in it - a stale dump after a cache update, usually. That
 * disables the feature, and [get] says so in the log: which feature, which name, where the names
 * came from. It reports once rather than per tick, because a line every 20ms is not diagnosable.
 *
 * What this must never do is let the resolve throw out of a `<clinit>`. A static initialiser that
 * throws leaves the class permanently uninitialisable, so one missing id becomes
 * `ExceptionInInitializerError` on the first tick and `NoClassDefFoundError` on every tick after it,
 * taking down whatever hook was calling the feature - the failure surfaces as a stack trace in the
 * caller with the actual cause several frames away.
 */
class FeatureIds<T : Any>(private val feature: String, resolve: () -> T) {

    private val result = runCatching(resolve)

    @Volatile
    private var reported = false

    /** The ids, or null when the feature is disabled. Reports why the first time it is asked. */
    fun get(): T? {
        val ids = result.getOrNull()
        if (ids == null && !reported) {
            reported = true
            println("[$feature] DISABLED: ${result.exceptionOrNull()?.message}")
            println("[$feature]   gameval names came from ${Gameval.sourceDescription}")
            println("[$feature]   the feature stays off for this session; re-dump the gamevals and restart")
        }
        return ids
    }

    /** True once the feature has been disabled, for callers that report their own state. */
    val disabled: Boolean get() = result.isFailure
}
