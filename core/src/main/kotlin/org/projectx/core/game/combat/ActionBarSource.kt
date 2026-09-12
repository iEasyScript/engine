package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/**
 * Classifies the *source* of an action-bar drag (the interface a slot was dragged from) into the
 * ability binding it represents. Item sources (inventory/worn) are resolved by the caller, which
 * knows the container contents; this only handles ability providers — the ability books and the
 * prayer/summoning panels — whose slot index is the style enum key that maps to a struct.
 */
object ActionBarSource {
    private fun iface(name: String) = Gameval.id(Gameval.INTERFACE, name)

    private val STYLE_BY_INTERFACE: Map<Int, Int> by lazy {
        buildMap {
            fun put(type: Int, vararg names: String) {
                for (name in names) iface(name)?.let { put(it, type) }
            }
            put(1, "toplevel_v2_window_ability_book_melee")
            put(3, "toplevel_v2_window_ability_book_defence")
            put(4, "toplevel_v2_window_ability_book_constitution")
            put(5, "toplevel_v2_window_ability_book_ranged")
            put(
                6,
                "toplevel_v2_window_ability_book_magic",
                "toplevel_v2_window_ability_book_magic_ability",
                "toplevel_v2_window_ability_book_magic_combat",
                "toplevel_v2_window_ability_book_magic_teleport",
                "toplevel_v2_window_ability_book_magic_skilling",
            )
            put(7, "toplevel_v2_prayer")
            put(
                17,
                "toplevel_v2_window_ability_book_necromancy",
                "toplevel_v2_window_ability_book_necromancy_abilities",
                "toplevel_v2_window_ability_book_necromancy_spells",
            )
        }
    }

    fun isAbilityProvider(interfaceId: Int): Boolean = interfaceId in STYLE_BY_INTERFACE

    /** The ability binding for a drag from [interfaceId] at [slot], or null when the slot names no known struct. */
    fun abilityBinding(interfaceId: Int, slot: Int): AbilitySlot? {
        val type = STYLE_BY_INTERFACE[interfaceId] ?: return null
        val enumId = ActionBarModel.enumForType(type) ?: return null
        val struct = Cache.enum(enumId)?.values?.get(slot) as? Int ?: return null
        return if (Cache.struct(struct) != null) AbilitySlot(struct) else null
    }
}
