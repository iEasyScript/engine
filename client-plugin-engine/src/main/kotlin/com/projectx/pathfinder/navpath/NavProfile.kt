package com.projectx.pathfinder.navpath

import com.projectx.script.api.abilityCooldownTicks
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.coinPouch
import com.projectx.script.api.diveAbility
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.store.Entitlements
import com.projectx.util.FeatureIds
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkPermissions
import com.projectx.webwalker.WebLinks
import org.projectx.core.game.combat.Ability
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import java.util.concurrent.ThreadLocalRandom

/**
 * The account as the hosted planner needs it: the requirement keys its link rows are gated on, valued from live game
 * state, and whether Surge and Dive can shape the route. Read on the game thread; a key that is left out counts as
 * unmet there, so only what this engine can really evaluate is sent.
 */
internal object NavProfile {
    private const val TICK_MS = 600L

    // Upstream's profile sends these as a 0/1 flag rather than the raw value.
    private val FLAG_VARBITS = setOf(18526, 28, 34, 38, 32, 39)

    // Upstream reads this key through getVarBit despite its varp_ name; the rows were written against that.
    private val VARP_READ_AS_VARBIT = setOf(2793)

    private val LODESTONE_VARBITS = setOf(
        18526, 28, 34, 38, 32, 39, 33, 18529, 36, 53270, 35, 31, 29, 37, 18525, 18524, 40, 18527, 22430, 30, 44270,
        18523, 52518, 36173, 24967, 18528, 60739,
    )

    private val BASE_VARBITS = setOf(
        55818, 10236, 9482, 10848, 10826, 48161, 48108, 9928, 30678, 13876, 13267, 33059, 9022, 13178, 52651, 4291,
        11413, 11269, 13349, 11498, 11428, 22270, 47236, 47184, 47185, 14041, 9663, 9987, 16231,
    )

    private val BASE_VARPS = setOf(2535, 2326, 2102, 2696, 2265, 2675, 2661, 2171, 2762)

    private val ids = FeatureIds("NavProfile") {
        mapOf(
            "charges" to Gameval.requireId(Gameval.VARBIT, "quick_tele_charges"),
            "byDefault" to Gameval.requireId(Gameval.VARBIT, "quick_tele_by_default"),
            "inCombat" to Gameval.requireId(Gameval.VARBIT, "combatv2_player_is_in_combat"),
            "doubleSurge" to Gameval.requireId(Gameval.VARBIT, "dino_agility_double_surge_unlocked"),
        )
    }

    private val sessionSalt = ThreadLocalRandom.current().nextInt() and 0x7fffffff

    fun request(start: Tile, goal: Tile, teleports: Boolean, useItems: Boolean, abilities: Boolean): NavRequest {
        val backpack = runCatching { inventory.toList() }.getOrDefault(emptyList())
        val worn = runCatching { equipment.toList() }.getOrDefault(emptyList())
        val known = ids.get()
        val inCombat = known?.get("inCombat")?.let { varbit(it) > 0 } == true
        val requirements = LinkedHashMap<String, Int>()

        requirements["coins"] = runCatching { coinPouch.firstOrNull()?.amount ?: 0 }.getOrDefault(0)
        requirements["combatLevel"] = COMBAT_LEVEL
        for (key in WebLinkPermissions.skillFlags) WebLinkPermissions.skillLevel(key)?.let { requirements[key] = it }

        val linkVars = WebLinks.all.asSequence().flatMap(WebLink::requirements).map { it.key }
            .filter { it.startsWith("varbit_") || it.startsWith("varp_") }.toSet()
        val varbits = (BASE_VARBITS + linkVars.mapNotNull { it.removePrefix("varbit_").takeIf { k -> k != it }?.toIntOrNull() }).toMutableSet()
        val varpKeys = BASE_VARPS + linkVars.mapNotNull { it.removePrefix("varp_").takeIf { k -> k != it }?.toIntOrNull() }
        if (!teleports || inCombat) varbits -= LODESTONE_VARBITS else varbits += LODESTONE_VARBITS
        for (id in varbits) requirements["varbit_$id"] = if (id in FLAG_VARBITS) flag(varbit(id) > 0) else varbit(id)
        for (id in varpKeys) requirements["varp_$id"] = if (id in VARP_READ_AS_VARBIT) varbit(id) else varp(id)

        if (teleports && useItems) {
            for (key in WebLinkPermissions.itemFlags) WebLinkPermissions.itemFlag(key, backpack, worn)?.let { requirements[key] = it }
            requirements["hasPortableFairyRing"] = flag(WebLinkPermissions.portableRingUsable(backpack, worn))
        }
        if (teleports && known != null) {
            requirements["hasQuickTele"] = flag(varbit(known.getValue("charges")) > 0 && varbit(known.getValue("byDefault")) > 0)
        }

        val surge = abilities && actionbarAbilities[Ability.SURGE.type] != null
        val dive = abilities && diveAbility != null
        val diveCooldownTicks = diveAbility?.let { runCatching { abilityCooldownTicks(it, true) }.getOrDefault(0.0) } ?: 0.0
        val doubleSurge = known?.get("doubleSurge")?.let { varbit(it) > 0 } == true
        return NavRequest(
            start = start,
            goal = goal,
            requirements = requirements.toList(),
            surge = surge,
            surgeCharges = if (doubleSurge) 2 else 1,
            dive = dive,
            diveAvailableInMs = (diveCooldownTicks * TICK_MS).toLong(),
            seed = seed(),
        )
    }

    /** Stable for the player all session, and different between players and sessions. */
    private fun seed(): Int {
        val name = runCatching { localPlayer.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return sessionSalt or 1
        var hash = -0x340d631b7bdddcdbL
        for (b in name.toByteArray()) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        val seed = ((hash xor (hash ushr 32)).toInt() xor sessionSalt) and 0x7fffffff
        return if (seed == 0) 1 else seed
    }

    private fun varbit(id: Int): Int = runCatching { varps.getVarBit(id) }.getOrDefault(0)

    private fun varp(id: Int): Int = runCatching { varps.getVar(id) }.getOrDefault(0)

    private fun flag(value: Boolean) = if (value) 1 else 0

    /** Upstream's profile sends a fixed combat level; the rows gated on it were measured against that. */
    private const val COMBAT_LEVEL = 60
}

/** The live hosted planner, its address and the player's store token. */
internal object Navpath {
    private const val URL_PROPERTY = "projectx.navpath.url"
    private const val TOKEN_TTL_MS = 60_000L

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenReadAt = 0L

    val planner = HostedPlanner(
        transport = NavHttp,
        baseUrl = { System.getProperty(URL_PROPERTY)?.takeIf { it.isNotBlank() } ?: (Entitlements.storeBase().trimEnd('/') + "/api/navpath") },
        token = ::token,
    )

    private fun token(): String? {
        val now = System.currentTimeMillis()
        if (now - tokenReadAt > TOKEN_TTL_MS) {
            cachedToken = Entitlements.token()
            tokenReadAt = now
        }
        return cachedToken
    }
}
