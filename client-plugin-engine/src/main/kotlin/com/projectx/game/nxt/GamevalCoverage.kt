package com.projectx.game.nxt

import world.gregs.voidps.gameval.Gameval

/**
 * Startup gate on the gameval dictionaries.
 *
 * Every id the engine resolves by name comes from them, so a missing set is a broken install rather
 * than a degraded one, and it refuses to boot. Failing here - naming both places that were tried -
 * is far easier to diagnose than the alternative, which is the first feature to want an id throwing
 * `ExceptionInInitializerError` mid-tick and `NoClassDefFoundError` on every tick after it.
 */
object GamevalCoverage {

    fun report() {
        if (Gameval.available) {
            println("[Gameval] dictionaries loaded from ${Gameval.sourceDescription}")
            return
        }
        val message = "gameval dictionaries not found: ${Gameval.sourceDescription}"
        println("[Gameval] ${"=".repeat(72)}")
        println("[Gameval] FATAL: $message")
        println("[Gameval]")
        println("[Gameval] Every id the engine looks up by name lives in these files, so nothing")
        println("[Gameval] that touches a varbit, interface or item can work without them.")
        println("[Gameval]")
        println("[Gameval] Fix it with any one of:")
        println("[Gameval]   - run from a working tree (re-resources/gamevals must be checked out:")
        println("[Gameval]     git submodule update --init re-resources)")
        println("[Gameval]   - point GAMEVAL_PATH at a copy of that folder")
        println("[Gameval]   - rebuild the engine jar, which packages its own copy")
        println("[Gameval] ${"=".repeat(72)}")
        error(message)
    }
}
