package org.projectx.core.game.combat

/**
 * One comparable form for the names the cache carries. The game mixes case, non-breaking spaces and underscores
 * into them, so "Scrimshaw Active", "SCRIMSHAW_ACTIVE" and "Scrimshaw<nbsp>active" all have to match: a script
 * should be able to use the name the buff bar draws without knowing which form the struct holds.
 */
object CombatNames {
    fun normalise(name: String): String =
        name.replace("<nbsp>", " ").replace('\u00A0', ' ').replace('_', ' ').trim().lowercase()

    fun matches(candidate: String, name: String): Boolean = normalise(candidate) == normalise(name)
}
