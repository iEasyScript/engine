package com.projectx.webwalker

import com.projectx.script.api.bank
import com.projectx.script.api.bankOpen
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.equipment
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.localPlayer
import com.projectx.util.FeatureIds
import world.gregs.voidps.gameval.Gameval

/**
 * How many Herblore Habitat teleports the worn Master farmer hat has left, for the planner's `hasMasterFarmerHabitat`.
 *
 * Each teleport spends a juju teleport spiritbag from the bank, and the client keeps no var for that count. It shows in
 * two places only: the hat's Teleport menu, whose line reads "Herblore Habitat (31)", and the bank grid while the bank
 * is open. Both are read as they appear, and a landed Habitat teleport takes one off in between. Until either has
 * been seen the count is unknown and the hat is left out of routes; nothing is clicked to find out.
 */
internal object MasterFarmerHat {
    private const val UNKNOWN = -1
    private const val UNLIMITED = Int.MAX_VALUE
    private const val CHOICE_INTERFACE = 720
    private val HABITAT_LINE = Regex("Herblore Habitat \\((\\d+|unlimited)\\)", RegexOption.IGNORE_CASE)

    // The landing scatters, so it is a box; the cast took seven ticks to land when measured.
    private const val LAND_X = 2953
    private const val LAND_Y = 2933
    private const val LAND_PLANE = 0
    private const val LAND_RADIUS = 5
    private const val LANDING_WINDOW_MS = 9_000L
    private const val TICK_MS = 600L

    private val ids = FeatureIds("MasterFarmerHat") {
        mapOf(
            "hat" to Gameval.requireId(Gameval.OBJ, "pof_rewards_trh_master_head"),
            "bag" to Gameval.requireId(Gameval.OBJ, "ecosystem_teleport_bag"),
            "cast" to Gameval.requireId(Gameval.SEQ, "pog_player_2014_spirit_tree_teleport"),
        )
    }

    @Volatile
    private var count = UNKNOWN
    private var account: String? = null
    private var castAt = 0L
    private var bagsAtCast = -1
    private var lastLook = 0L

    /** True when the hat is worn and at least one Habitat teleport is known to be left. */
    fun habitatUsable(): Boolean = worn() && count > 0

    /** The hat worn or in the backpack: its teleport spends banked bags, so backpack bags never recharge it. */
    fun held(): Boolean {
        val hat = ids.get()?.get("hat") ?: return false
        return runCatching { equipment.any { it.id == hat } || inventory.any { it.id == hat } }.getOrDefault(false)
    }

    /** One look at the hat's menu, the bank and a teleport in flight. Call on the game thread. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastLook < TICK_MS) return
        lastLook = now
        runCatching {
            if (!isLoggedIn()) return
            syncAccount()
            readMenu()
            readBank()
            trackTeleport(now)
        }
    }

    private fun worn(): Boolean {
        val hat = ids.get()?.get("hat") ?: return false
        return runCatching { equipment.any { it.id == hat } }.getOrDefault(false)
    }

    private fun syncAccount() {
        val name = localPlayer.name
        if (name != account) {
            account = name
            count = UNKNOWN
            castAt = 0L
        }
    }

    private fun readMenu() {
        if (!interfaces.isOpen(CHOICE_INTERFACE)) return
        for (line in dialogueOptions.keys) {
            val value = HABITAT_LINE.find(line)?.groupValues?.get(1) ?: continue
            count = if (value.equals("unlimited", ignoreCase = true)) UNLIMITED else value.toIntOrNull() ?: continue
            return
        }
    }

    private fun readBank() {
        if (!bankOpen || count == UNLIMITED) return
        val bag = ids.get()?.get("bag") ?: return
        val items = bank.toList()
        // An empty grid is a bank still filling, not one with no spiritbags.
        if (items.isEmpty()) return
        count = items.filter { it.id == bag }.sumOf { it.amount }
    }

    private fun trackTeleport(now: Long) {
        val known = ids.get() ?: return
        val at = localPlayer.tile
        if (castAt == 0L) {
            if (localPlayer.animationId == known.getValue("cast") && !inLandingBox(at.x, at.y, at.plane)) {
                castAt = now
                bagsAtCast = backpackBags(known.getValue("bag"))
            }
            return
        }
        if (now - castAt > LANDING_WINDOW_MS) {
            castAt = 0L
            return
        }
        if (!inLandingBox(at.x, at.y, at.plane)) return
        castAt = 0L
        // A bag used from the backpack lands here too, and leaves the banked count alone.
        val fromBackpack = bagsAtCast > 0 && backpackBags(known.getValue("bag")) < bagsAtCast
        if (!fromBackpack && count > 0 && count != UNLIMITED) count--
    }

    private fun backpackBags(bag: Int): Int = inventory.filter { it.id == bag }.sumOf { it.amount }

    fun inLandingBox(x: Int, y: Int, plane: Int): Boolean =
        plane == LAND_PLANE && x in LAND_X - LAND_RADIUS..LAND_X + LAND_RADIUS && y in LAND_Y - LAND_RADIUS..LAND_Y + LAND_RADIUS
}
