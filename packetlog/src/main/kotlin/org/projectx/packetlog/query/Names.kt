package org.projectx.packetlog.query

import org.projectx.core.net.prot.decode.RefDomain
import world.gregs.voidps.gameval.Gameval

/**
 * Turns the ids a decoder emits into the names people actually think in.
 *
 * Resolution happens here, at read time, rather than at capture: names come from the cache and
 * change when it does, so baking one into a stored row would make an old capture disagree with a
 * current cache. Storing the id and resolving late means a cache update improves every capture ever
 * taken instead of invalidating them.
 */
object Names {

    private val TYPE_BY_DOMAIN = mapOf(
        RefDomain.VARP to "var_player",
        RefDomain.VARBIT to "varbit",
        RefDomain.VARC to "var_client",
        RefDomain.VARCBIT to "varbit_client",
        RefDomain.INTERFACE to "interface",
        RefDomain.NPC to "npc",
        RefDomain.LOC to "loc",
        RefDomain.OBJ to "obj",
        RefDomain.SEQ to "seq",
        RefDomain.SPOTANIM to "spotanim",
        RefDomain.GRAPHIC to "graphic",
        RefDomain.STRUCT to "struct",
        RefDomain.ENUM to "enum",
        RefDomain.INV to "inv",
        RefDomain.SCRIPT to "clientscript",
    )

    private val SKILLS = arrayOf(
        "attack", "defence", "strength", "constitution", "ranged", "prayer", "magic", "cooking",
        "woodcutting", "fletching", "fishing", "firemaking", "crafting", "smithing", "mining",
        "herblore", "agility", "thieving", "slayer", "farming", "runecrafting", "hunter",
        "construction", "summoning", "dungeoneering", "divination", "invention", "archaeology",
        "necromancy",
    )

    fun of(domain: RefDomain, id: Long): String? = when (domain) {
        RefDomain.SKILL -> SKILLS.getOrNull(id.toInt())
        // A component is an interface and a child packed together, so it names both.
        RefDomain.COMPONENT -> componentName(id)
        else -> TYPE_BY_DOMAIN[domain]?.let { Gameval.name(it, id.toInt()) }
    }

    fun of(domainName: String, id: Long): String? =
        runCatching { RefDomain.valueOf(domainName) }.getOrNull()?.let { of(it, id) }

    fun label(domain: RefDomain, id: Long): String = of(domain, id)?.let { "$it($id)" } ?: id.toString()

    private fun componentName(hash: Long): String? {
        if (hash < 0) return null
        val iface = (hash ushr 16).toInt()
        val child = (hash and 0xFFFF).toInt()
        val ifaceName = Gameval.name("interface", iface) ?: return null
        val childName = Gameval.name("component", (iface shl 16) or child)
        return childName ?: "$ifaceName:$child"
    }
}
