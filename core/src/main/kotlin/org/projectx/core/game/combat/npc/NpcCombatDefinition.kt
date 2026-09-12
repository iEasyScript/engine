package org.projectx.core.game.combat.npc

import kotlinx.serialization.Serializable

/** Modern-RS3 attack class an NPC fights with. */
@Serializable
enum class CombatStyle { MELEE, RANGED, MAGIC, NECROMANCY }

@Serializable
enum class AggressionType { PASSIVE, AGGRESSIVE }

/** Modern-RS3 weakness (the style/element that lands bonus accuracy/damage). */
@Serializable
enum class Weakness { NONE, STAB, SLASH, CRUSH, ARROWS, BOLTS, THROWN, AIR, WATER, EARTH, FIRE, NECROMANCY }

/** Per-combat-style values (affinities, damage, accuracy). Any style may be omitted. */
@Serializable
data class StyleValues(
    val melee: Int? = null,
    val ranged: Int? = null,
    val magic: Int? = null,
    val necromancy: Int? = null,
)

/** Modern-RS3 defensive/offensive stat block. All optional — cache params fill unspecified fields. */
@Serializable
data class NpcCombatStats(
    val affinity: StyleValues? = null,
    val armour: Int? = null,
    val damage: StyleValues? = null,
    val accuracy: StyleValues? = null,
    val weakness: Weakness? = null,
)

@Serializable
data class NpcAggression(
    val type: AggressionType? = null,
    val aggroRange: Int? = null,
    val deAggroRange: Int? = null,
    val maxDistanceFromSpawn: Int? = null,
)

/**
 * Authored combat animations as seq gameval NAMES (e.g. `"human_unarmedblock"`), preferred over raw
 * ids — a numeric string is still accepted as a fallback id. Resolved to seq ids by
 * [org.projectx.core.game.combat.npc.ResolvedNpcAnimations].
 */
@Serializable
data class NpcAnimations(val attack: String? = null, val defend: String? = null, val death: String? = null)

@Serializable
data class NpcSounds(val attack: Int? = null, val defend: Int? = null, val death: Int? = null)

/**
 * Authored NPC combat definition. Every field is optional and overrides the cache-derived base when
 * present (see [NpcCombatDefinitions.resolve]). Keyed by NPC [names] and/or cache [ids]; one file may
 * apply to several. [lifepoints] is the primary authored value — max HP is not in the cache def.
 */
@Serializable
data class NpcCombatDefinition(
    val names: List<String>? = null,
    val ids: List<Int>? = null,
    val lifepoints: Int? = null,
    val combatLevel: Int? = null,
    val attackStyle: CombatStyle? = null,
    val maxHit: Int? = null,
    val attackSpeed: Int? = null,
    val attackRange: Int? = null,
    val stats: NpcCombatStats? = null,
    val aggression: NpcAggression? = null,
    val animations: NpcAnimations? = null,
    val sounds: NpcSounds? = null,
    val respawnTicks: Int? = null,
    val headbar: String? = null,
    val hideHitpoints: Boolean? = null,
)
