package org.projectx.core.game.combat

/**
 * How an ability interacts with the adrenaline bar, from `combatv2_ability_adrenaline_type`.
 *
 * Derived by grouping every ability that carries the param and reading off the members, so the
 * boundaries are observed rather than assumed: [THRESHOLD] is Hurricane/Assault/Asphyxiate,
 * [DEFENSIVE] is the Devotion/Debilitate/Reflect/Revenge group, [ULTIMATE] is Berserk/Tsunami/
 * Barricade, and [UTILITY] is Escape/Surge/stances.
 */
enum class AdrenalineType(val id: Int) {
    AUTO_ATTACK(0),
    BASIC(1),
    THRESHOLD(2),
    DEFENSIVE(3),
    ULTIMATE(4),
    SPECIAL_ATTACK(5),
    ITEM_SPECIAL(6),
    UTILITY(7);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun forId(id: Int): AdrenalineType? = byId[id]
    }
}
