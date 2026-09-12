package com.projectx.game.nxt

enum class EntityType(val value: Int) {
    LOCATION(0),
    NPC_ENTITY(1),
    PLAYER_ENTITY(2),
    OBJ_STACK(3),
    SPOT_ANIMATION(4),
    PROJECTILE_ANIMATION(5),
    TERRAIN(6),
    WATER(7),
    COMBINED_LOCATION(8),
    LOCATION_CONTAINER(9),
    MAP_SQUARE(10),
    LIGHT_SOURCE(11),
    COMBINED_LOCATION_SECTION(12),
    HINT_ARROW(13),
    HINT_TRAIL(14),
    WATER_CONTAINER(15),
    LOCATION_INTERFACE(16),
    HINT_TRAIL_POINT(17),
    HINT_ARROW_POINTER(18);

    companion object {
        fun fromType(type: Int): EntityType {
            return entries.find { it.value == type } ?: error("Unknown entity type: $type")
        }
    }
}
