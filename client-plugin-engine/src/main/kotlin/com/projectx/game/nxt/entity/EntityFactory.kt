package com.projectx.game.nxt.entity
import java.util.concurrent.ConcurrentHashMap
import com.projectx.game.nxt.OProjectile
import com.projectx.game.nxt.OHintTrail
import com.projectx.game.nxt.OHintArrow
import com.projectx.game.nxt.OSpotAnim
import com.projectx.game.nxt.OMapSquare
import com.projectx.game.nxt.OCombinedLocationSection
import com.projectx.game.nxt.OCombinedLocation
import com.projectx.game.nxt.OLocation
import com.projectx.game.nxt.OLoggedInPlayer
import com.projectx.game.nxt.ONPC
import com.projectx.game.nxt.OPathingEntity
import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OffsetObject

import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.MapSquare
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.entity.location.CombinedLocation
import com.projectx.game.nxt.entity.location.CombinedLocationSection
import com.projectx.game.nxt.entity.location.Location
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import java.lang.foreign.MemorySegment

/** Wraps a raw entity pointer in the class matching its [OEntity.ENTITY_TYPE] tag. */
object EntityFactory {
    private val unknownTypes = ConcurrentHashMap.newKeySet<Int>()
    /** Wide enough for whichever entity subtype the factory turns the segment into. */
    val extent: Long by lazy { FAMILY.maxOf { it.extent } }

    private val FAMILY: List<OffsetObject> = listOf(
        OEntity, OPathingEntity, ONPC, OLoggedInPlayer, OLocation, OCombinedLocation, OCombinedLocationSection,
        OMapSquare, OSpotAnim, OHintArrow, OHintTrail, OProjectile,
    )


    fun wrap(ptr: MemorySegment): Entity? {
        val raw = ptr.readByte(OEntity.ENTITY_TYPE).toInt()
        val type = runCatching { EntityType.fromType(raw) }.getOrNull()
        if (type == null && unknownTypes.add(raw)) println("[EntityFactory] entity type $raw is not in EntityType; every entity carrying it is dropped from scene walks")
        return when (type) {
            EntityType.LOCATION -> Location(ptr)
            EntityType.NPC_ENTITY -> NPC(ptr)
            EntityType.PLAYER_ENTITY -> Player(ptr)
            EntityType.OBJ_STACK -> ItemStack(ptr)
            EntityType.SPOT_ANIMATION -> SpotAnim(ptr)
            EntityType.PROJECTILE_ANIMATION -> Projectile(ptr)
            EntityType.COMBINED_LOCATION -> CombinedLocation(ptr)
            EntityType.MAP_SQUARE -> MapSquare(ptr)
            EntityType.COMBINED_LOCATION_SECTION -> CombinedLocationSection(ptr)
            EntityType.HINT_ARROW, EntityType.HINT_ARROW_POINTER -> HintArrow(ptr)
            else -> null
        }
    }
}
