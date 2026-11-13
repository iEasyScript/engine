package com.projectx.webwalker

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.Equipment
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import com.projectx.util.gaussian
import world.gregs.voidps.gameval.Gameval
import java.util.Collections
import java.util.WeakHashMap

/**
 * The Dramen staff, or the Lunar staff the game accepts in its place. The planner routes with it carried, in the
 * backpack or worn, but the game only lets you through with it wielded, so the walker wields it before a link that
 * needs it and puts back whatever it took off once the walk is over.
 *
 * Two kinds of link need it: one whose planner row lists the staff requirement, such as the Zanaris shed door, and
 * a fairy ring (not the portable one) before A Fairy Tale III is done; the planner's fairy ring steps carry no
 * requirement ids, so the ring asks for itself.
 */
internal object DramenStaff {
    /** The planner's requirement id for a staff carried. */
    const val REQUIREMENT_ID = 379

    // From this stage of A Fairy Tale III on, rings need no staff; from this stage of A Fairy Tale II the Fairy
    // Godfather allows them with one.
    private const val FAIRY_TALE_III_DONE = 180
    private const val FAIRY_TALE_II_RING_PERMISSION = 40

    private const val WIELD_OPTION = "Wield"
    private val EQUIP_OPTIONS = listOf("Wield", "Wear", "Equip")
    private const val WIELD_WAIT_MS = 2_400L
    private const val RESTORE_TRIES = 2

    private val ids = FeatureIds("DramenStaff") {
        mapOf(
            "dramen" to Gameval.requireId(Gameval.OBJ, "dramen_staff"),
            "lunar" to Gameval.requireId(Gameval.OBJ, "lunar_moonclan_liminal_staff"),
            "fairyTale3" to Gameval.requireId(Gameval.VARBIT, "ft3_quest"),
            "fairyTale2" to Gameval.requireId(Gameval.VARBIT, "fairy2_queencure_quest"),
        )
    }

    private val marked: MutableSet<WebLink> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** The weapon and off-hand items worn before the staff went on (-1 for an empty slot), or null when none. */
    @Volatile
    private var swappedOut: IntArray? = null

    /** Marks [link] as one whose planner row requires the staff. */
    fun requireFor(link: WebLink) {
        marked += link
    }

    fun requiredFor(link: WebLink): Boolean =
        link in marked || link.kind == WebLinkKind.FAIRY_RING && !link.portable && ringsNeedIt()

    /** True while fairy rings still need a staff wielded: A Fairy Tale III not done. */
    fun ringsNeedIt(): Boolean = varbit("fairyTale3") < FAIRY_TALE_III_DONE

    /**
     * The planner's key for its staff copies of the fairy ring rows: A Fairy Tale III done, or, with items allowed, the
     * Fairy Godfather's permission and a staff carried. It counts Fairy Tale III on purpose: the planner keeps one row
     * per ring tile for the portable ring's check, the copy, so the copy must pass wherever the original does.
     */
    fun ringStaffAccess(withItems: Boolean): Boolean =
        !ringsNeedIt() || withItems && varbit("fairyTale2") >= FAIRY_TALE_II_RING_PERMISSION && carried()

    fun carried(): Boolean = backpackStaff() != null || worn()

    fun worn(): Boolean = staffIds().let { staffs -> runCatching { equipment.any { it.id in staffs } }.getOrDefault(false) }

    val restorePending: Boolean get() = swappedOut != null

    /** Wields the staff from the backpack and waits for it to show as worn; true once it is worn. */
    suspend fun wield(script: Script): Boolean {
        if (worn()) return true
        val staff = backpackStaff() ?: return false
        if (swappedOut == null) {
            val weapon = wornId(Equipment.Slot.WEAPON)
            val offHand = wornId(Equipment.Slot.SHIELD)
            if (weapon >= 0 || offHand >= 0) swappedOut = intArrayOf(weapon, offHand)
        }
        println("[WebWalk] Wielding the ${staff.name} for the next step")
        if (!staff.click(WIELD_OPTION)) return false
        script.delayUntil(WIELD_WAIT_MS) { worn() }
        return worn()
    }

    /**
     * Puts back the weapon and off-hand the staff replaced, weapon first so a one-hander and its off-hand both go back
     * on. An item no longer in the backpack is skipped, and the swap is forgotten either way, so a failing click never
     * loops.
     */
    suspend fun restore(script: Script) {
        val wanted = swappedOut ?: return
        for (id in wanted) {
            if (id < 0) continue
            repeat(RESTORE_TRIES) {
                if (equipped(id)) return@repeat
                val item = inventory.firstOrNull { it.id == id } ?: return@repeat
                if (EQUIP_OPTIONS.none { item.click(it) }) return@repeat
                script.delayUntil(WIELD_WAIT_MS) { equipped(id) }
                script.delay(gaussian(180, 2_500))
            }
        }
        println("[WebWalk] Put back what the Dramen staff replaced")
        swappedOut = null
    }

    private fun equipped(id: Int): Boolean = runCatching { equipment.any { it.id == id } }.getOrDefault(false)

    private fun wornId(slot: Equipment.Slot): Int = runCatching { Equipment.Slot.getItem(slot)?.id }.getOrNull() ?: -1

    private fun backpackStaff(): Item? = staffIds().let { staffs -> runCatching { inventory.firstOrNull { it.id in staffs } }.getOrNull() }

    private fun staffIds(): Set<Int> = ids.get()?.let { setOf(it.getValue("dramen"), it.getValue("lunar")) }.orEmpty()

    private fun varbit(key: String): Int = ids.get()?.get(key)?.let { runCatching { varps.getVarBit(it) }.getOrDefault(0) } ?: 0
}
