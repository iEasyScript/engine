package com.projectx.script.api
import com.projectx.game.input.Key
import com.projectx.game.target

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import world.gregs.voidps.gameval.Gameval
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.action.ActionInput
import com.projectx.game.interfaces.Bank
import com.projectx.game.interfaces.Bank.Companion.BANK_INTERFACE_ID
import com.projectx.game.interfaces.Bank.Companion.doBankAction
import com.projectx.game.interfaces.Bank.Companion.doBankInventoryAction
import com.projectx.game.interfaces.Bank.Companion.doBankItemsAction
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.InstanceSystem
import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.game.interfaces.parseAllActionBarItems
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.Effect
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.SpotAnim
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import com.projectx.pathfinder.*
import com.projectx.script.Script
import com.projectx.script.api.Relics.NONE
import com.projectx.util.gaussian
import com.projectx.webwalker.WebWalkResult
import com.projectx.webwalker.WebWalker
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val PLAYER_LIST = mutableListOf<Player>()
private val NPC_LIST = ConcurrentHashMap<Int, NPC>()
private val SPOTANIM_LIST = CopyOnWriteArrayList<SpotAnim>()
private var ACTIONBAR_ABILITIES = mapOf<AbilityType, IFSlot>()

var playerIndex = 0L

fun readPlayerIntoStructures(clear: Boolean): Boolean {
    val startTime = System.currentTimeMillis()
    if (playerIndex == 0L) {
        if (clear) {
            PLAYER_LIST.clear()
        } else {
            // We are done updating the list for this 10-tick interval
            return true
        }
    }

    val timeBudgetMs = 7L

    val playerManager = Bootstrap.client.playerManager
    val totalPlayers = playerManager.players.size


    // Process players incrementally with time budget
    while (playerIndex < totalPlayers && (System.currentTimeMillis() - startTime) < timeBudgetMs) {
        try {
            val playerSegment = playerManager[playerIndex.toInt()]
            if (playerSegment.address() != 0L) {
                PLAYER_LIST.add(Player(playerSegment))
            }
        } catch (e: Exception) {
            // Handle any index out of bounds or memory access errors
        }
        playerIndex++
    }

    // Cycle complete - reset for next round and update display name
    if (playerIndex >= totalPlayers) {
        playerIndex = 0L
        loggedInDisplayName = Bootstrap.client.loggedInPlayer.getPlayerName() ?: ""
        return true
    }
    return false
}

fun readNpcIntoStructures() {
    NPC_LIST.clear()

    NPC_LIST.putAll(
        Bootstrap.client.npcManager.indices
            .mapNotNull {
                if (it <= 0) return@mapNotNull null
                val addr = Bootstrap.client.npcManager[it]
                if (addr != null && addr.address() != 0L) NPC(addr) else null
            }
            .filter { it.exists() }
            .associateBy { it.serverIndex }
    )
}

fun readSpotanimIntoStructures() {
    SPOTANIM_LIST.clear()

    SPOTANIM_LIST.addAll(
        Bootstrap.client.spotAnimManager.mapNotNull {
            if (it.address() != 0L) SpotAnim(it) else null
        }
    )
}

fun readActionBarAbilities() {
    ACTIONBAR_ABILITIES = parseAllActionBarAbilities()
}

var loggedInDisplayName = ""

var timeLoggedInAt = System.currentTimeMillis()

private var lastXpDropTime = 0L
fun refreshLastXpDrop() {
    lastXpDropTime = System.currentTimeMillis()
}

val timeSinceLastXpDrop
    get() = System.currentTimeMillis() - lastXpDropTime
private var lastAnimTime = 0L
val timeSinceLastAnim
    get() = if (localPlayer.isAnimating) {
        lastAnimTime = System.currentTimeMillis()
        0
    } else {
        if (lastAnimTime == 0L) lastAnimTime = System.currentTimeMillis()
        System.currentTimeMillis() - lastAnimTime
    }

val inventory
    get() = Bootstrap.client.inventoryManager.getWithInterface(93, 1473, 5)
val equipment
    get() = Bootstrap.client.inventoryManager.getWithInterface(94, 1464, 15)
val bank
    get() = Bootstrap.client.inventoryManager.getWithInterface(95, 517, 202)
val beastOfBurden
    get() = Bootstrap.client.inventoryManager.getWithInterface(530, 662, 5)
val areaLoot
    get() = Bootstrap.client.inventoryManager.getWithInterface(773, 1622, 11)
val areaLootOpen
    get() = Bootstrap.client.inventoryManager.exists(773)
val coinPouch
    get() = Bootstrap.client.inventoryManager[623]
val cosmeticEquipment
    get() = Bootstrap.client.inventoryManager[670]
val woodbox
    get() = Bootstrap.client.inventoryManager[937]

val localPlayer
    get() = Bootstrap.client.loggedInPlayer.self
val players
    get() = PLAYER_LIST
val npcs
    get() = NPC_LIST
val spotAnims
    get() = SPOTANIM_LIST
val projectiles
    get() = Bootstrap.client.projectileList
val varps
    get() = Bootstrap.client.playerVarDomain
val varcs
    get() = Bootstrap.client.clientVarDomain
val interfaces
    get() = Bootstrap.client.interfaceList
val inInstancedArea
    get() = localPlayer.tile.x >= 6400

/**
 * Before you guys go using allObjects all over the place. Consider using the other filtering functions
 * allObjects returns an absolute assload of scenery objects and should rarely, if ever, be used.
 */
val allObjects
    get() = Bootstrap.client.sceneManager.getAllObjectsWithinRange(localPlayer.tile, 60)
val groundItems
    get() = Bootstrap.client.itemStackList.allGroundItems

/**
 * Var utilities
 */
val prayerPoints
    get() = ceil(varps.getVarBit(16736).toDouble() / 10.0).toInt()
val prayerMax
    get() = getRealLevel(Skill.PRAYER) * 10.0
val prayerPercent
    get() = (prayerPoints.toFloat() / prayerMax.toFloat()) * 100.0
val healthMax
    get() = varps.getVar(13538)
val healthCurrent
    get() = varps.getVar(13537)
val healthPercent
    get() = (healthCurrent.toFloat() / healthMax.toFloat()) * 100.0
val bossHealthCurrent
    get() = varps.getVarBit(53292)
val bossHealthType
    get() = varps.getVarBit(53293)
val bossHealthMax
    get() = varps.getVarBit(53294)
val bossHealthPercent
    get() = (bossHealthCurrent.toFloat() / bossHealthMax.toFloat()) * 100.0
val getDivineCharges
    get() = (varps.getVar(5984) / 3000)

/**
 * Ground item utilities
 */
suspend fun Script.findAndPickupItems(vararg items: String): Boolean {
    while (true) {
        if (inventory.freeSlots <= 0)
            break
//        val item = findClosestReachableGroundItem(items)
//        if (item?.take() == false)
//            break
        delayWhile(5000) { localPlayer.isMoving }
        return true
    }
    return false
}

/**
 * Spotanim utilities
 */
@JvmOverloads
fun getAllSpotAnimsWithinRange(range: Int = 20, predicate: (SpotAnim) -> Boolean): List<SpotAnim> = spotAnims
    .filter {
        it.tile.plane == localPlayer.tile.plane && it.tile.getDistance(localPlayer.tile) <= range && predicate.invoke(
            it
        )
    }
    .sortedBy { it.tile.getDistance(localPlayer.tile) }

@JvmOverloads
fun findClosestSpotAnim(
    maxRange: Int = 20,
    checkReachable: Boolean = false,
    predicate: (SpotAnim) -> Boolean
): SpotAnim? {
    return getAllSpotAnimsWithinRange(maxRange) { predicate.invoke(it) }
        .mapNotNull { spotAnim ->
            if (checkReachable) {
                val route = routeToTile(localPlayer.tile, spotAnim.tile)
                return@mapNotNull if (route.success && !route.alternative) Pair(spotAnim, route.distance) else null
            }
            return@mapNotNull Pair(spotAnim, localPlayer.tile.getDistance(spotAnim.tile))
        }
        .minByOrNull { it.second }
        ?.first
}

@JvmOverloads
fun findClosestSpotAnim(id: Int, range: Int = 20) = findClosestSpotAnim(range) { it.id == id }

/**
 * Object utilities
 */
fun getAllObjectsWithinRange(range: Int): List<SceneObject> {
    return Bootstrap.client.sceneManager.getAllObjectsWithinRange(localPlayer.tile, range)
}


fun getAllObjectsWithinRange(targetTile: Tile, range: Int): List<SceneObject> {
    return Bootstrap.client.sceneManager.getAllObjectsWithinRange(targetTile, range)
}

fun findClosestObjectToTile(
    fromTile: Tile,
    range: Int = 20,
    checkReachable: Boolean = false,
    predicate: (SceneObject) -> Boolean
): SceneObject? {
    return Bootstrap.client.sceneManager.getAllObjectsWithinRange(fromTile, range)
        .filter { it.tile.plane == fromTile.plane && predicate.invoke(it) }
        .mapNotNull { obj ->
            if (checkReachable) {
                val route = routeToObject(fromTile, obj)
                return@mapNotNull if (route.success && !route.alternative) Pair(obj, route.distance) else null
            }
            return@mapNotNull Pair(obj, fromTile.getDistance(obj.tile))
        }
        .minByOrNull { it.second }
        ?.first
}

fun findClosestReachableObjectToTile(fromTile: Tile, maxRange: Int = 20, predicate: (SceneObject) -> Boolean) =
    findClosestObjectToTile(fromTile, maxRange, true, predicate)

fun findClosestObjectToTile(fromTile: Tile, name: String, range: Int = 20) =
    findClosestObjectToTile(fromTile, range) { it.name() == name }

fun findClosestObjectToTile(fromTile: Tile, id: Int, range: Int = 20) =
    findClosestObjectToTile(fromTile, range) { it.id == id }

fun findClosestObjectToTileWithOption(fromTile: Tile, option: String, range: Int = 20) =
    findClosestObjectToTile(fromTile, range) { it.hasOption(option) }

@JvmOverloads
fun findClosestReachableObject(maxRange: Int = 20, predicate: (SceneObject) -> Boolean) =
    findClosestObjectToTile(localPlayer.tile, maxRange, true, predicate)

@JvmOverloads
fun interactClosestReachableObject(option: String, range: Int = 20): Boolean {
    val target = findClosestReachableObject(range) { it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestReachableObject(objectId: Int, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableObject(range) { it.id == objectId && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestReachableObject(objectName: String, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableObject(range) { it.name() == objectName && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestReachableObject(objectNameRegex: Regex, option: String, range: Int = 20): Boolean {
    val target =
        findClosestReachableObject(range) { objectNameRegex.matches(it.name()) && it.hasOption(option) } ?: return false
    return target.interact(option)
}

fun interactClosestReachableObjectToTile(tile: Tile, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableObjectToTile(tile, range) { it.hasOption(option) } ?: return false
    return target.interact(option)
}

fun interactClosestReachableObjectToTile(tile: Tile, objectId: Int, option: String, range: Int = 20): Boolean {
    val target =
        findClosestReachableObjectToTile(tile, range) { it.id == objectId && it.hasOption(option) } ?: return false
    return target.interact(option)
}

fun interactClosestReachableObjectToTile(tile: Tile, objectName: String, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableObjectToTile(tile, range) { it.name() == objectName && it.hasOption(option) }
        ?: return false
    return target.interact(option)
}

fun interactClosestReachableObjectToTile(tile: Tile, objectNameRegex: Regex, option: String, range: Int = 20): Boolean {
    val target =
        findClosestReachableObjectToTile(tile, range) { objectNameRegex.matches(it.name()) && it.hasOption(option) }
            ?: return false
    return target.interact(option)
}

// Coordinate forms of the Tile-taking functions above: Tile is an inline value class, so those compile to mangled JVM
// names Java cannot call.

fun getAllObjectsWithinRange(x: Int, y: Int, plane: Int, range: Int): List<SceneObject> =
    getAllObjectsWithinRange(Tile.of(x, y, plane), range)

@JvmOverloads
fun findClosestObjectToTile(
    x: Int,
    y: Int,
    plane: Int,
    range: Int = 20,
    checkReachable: Boolean = false,
    predicate: (SceneObject) -> Boolean
): SceneObject? = findClosestObjectToTile(Tile.of(x, y, plane), range, checkReachable, predicate)

@JvmOverloads
fun findClosestReachableObjectToTile(x: Int, y: Int, plane: Int, maxRange: Int = 20, predicate: (SceneObject) -> Boolean) =
    findClosestReachableObjectToTile(Tile.of(x, y, plane), maxRange, predicate)

@JvmOverloads
fun findClosestObjectToTile(x: Int, y: Int, plane: Int, name: String, range: Int = 20) =
    findClosestObjectToTile(Tile.of(x, y, plane), name, range)

@JvmOverloads
fun findClosestObjectToTile(x: Int, y: Int, plane: Int, id: Int, range: Int = 20) =
    findClosestObjectToTile(Tile.of(x, y, plane), id, range)

@JvmOverloads
fun findClosestObjectToTileWithOption(x: Int, y: Int, plane: Int, option: String, range: Int = 20) =
    findClosestObjectToTileWithOption(Tile.of(x, y, plane), option, range)

@JvmOverloads
fun interactClosestReachableObjectToTile(x: Int, y: Int, plane: Int, option: String, range: Int = 20): Boolean =
    interactClosestReachableObjectToTile(Tile.of(x, y, plane), option, range)

@JvmOverloads
fun interactClosestReachableObjectToTile(x: Int, y: Int, plane: Int, objectId: Int, option: String, range: Int = 20): Boolean =
    interactClosestReachableObjectToTile(Tile.of(x, y, plane), objectId, option, range)

@JvmOverloads
fun interactClosestReachableObjectToTile(x: Int, y: Int, plane: Int, objectName: String, option: String, range: Int = 20): Boolean =
    interactClosestReachableObjectToTile(Tile.of(x, y, plane), objectName, option, range)

@JvmOverloads
fun findClosestReachableObject(name: String, range: Int = 20) = findClosestReachableObject(range) { it.name() == name }
@JvmOverloads
fun findClosestReachableObject(id: Int, range: Int = 20) = findClosestReachableObject(range) { it.id == id }
@JvmOverloads
fun findClosestReachableObjectWithOption(option: String, range: Int = 20) =
    findClosestReachableObject(range) { it.hasOption(option) }

@JvmOverloads
fun interactClosestObject(option: String, range: Int = 20): Boolean {
    val target = findClosestObjectToTile(localPlayer.tile, range) { it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestObject(objectId: Int, option: String, range: Int = 20): Boolean {
    val target = findClosestObjectToTile(localPlayer.tile, range) { it.id == objectId && it.hasOption(option) }
        ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestObject(objectName: String, option: String, range: Int = 20): Boolean {
    val target = findClosestObjectToTile(localPlayer.tile, range) { it.name() == objectName && it.hasOption(option) }
        ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestObjectFromIds(vararg objectIds: Int, option: String, range: Int = 20): Boolean {
    val target = getAllObjectsWithinRange(range).filter { it.id in objectIds && it.hasOption(option) }
        .minByOrNull { it.tile.getDistance(localPlayer.tile) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun findClosestObject(range: Int = 20, predicate: (SceneObject) -> Boolean) =
    findClosestObjectToTile(localPlayer.tile, range, false, predicate)

@JvmOverloads
fun findClosestObject(name: String, range: Int = 20) = findClosestObject(range) { it.name() == name }
@JvmOverloads
fun findClosestObject(name: String, range: Int = 20, predicate: (SceneObject) -> Boolean) =
    findClosestObject(range) { it.name() == name && predicate.invoke(it) }

@JvmOverloads
fun findClosestObject(id: Int, range: Int = 20) = findClosestObject(range) { it.id == id }
@JvmOverloads
fun findClosestObjectWithOption(option: String, range: Int = 20) = findClosestObject(range) { it.hasOption(option) }


@JvmOverloads
fun findClosestObjectInArea(
    area: Area,
    checkReachable: Boolean = false,
    predicate: (SceneObject) -> Boolean
): SceneObject? {
    return Bootstrap.client.sceneManager.getAllObjectsInArea(area)
        .filter { it.tile.plane == area.getRandomCoordinate().plane && predicate.invoke(it) }
        .mapNotNull { obj ->
            if (checkReachable) {
                val route = routeToObject(localPlayer.tile, obj)
                return@mapNotNull if (route.success && !route.alternative) Pair(obj, route.distance) else null
            }
            return@mapNotNull Pair(obj, localPlayer.tile.getDistance(obj.tile))
        }
        .minByOrNull { it.second }
        ?.first
}


/**
 * NPC utilities
 */
// Stale NPC entries in the manager occasionally have null backing memory; reading their
// tile/size NPEs deep in NativeAccess. Filter those out per-entry rather than letting the
// whole iteration die.
@JvmOverloads
fun allNpcsWithinRange(maxRange: Int = 20) = npcs.values.mapNotNull { npc ->
    runCatching { if (npc.tile.withinDistance(localPlayer.tile, maxRange)) npc else null }.getOrNull()
}.sortedBy { runCatching { it.tile.getDistance(localPlayer.tile) }.getOrDefault(Int.MAX_VALUE) }

@JvmOverloads
fun allNpcsWithinRange(maxRange: Int = 20, predicate: (NPC) -> Boolean) = npcs.values.mapNotNull { npc ->
    runCatching {
        if (npc.tile.withinDistance(localPlayer.tile, maxRange) && predicate.invoke(npc)) npc else null
    }.getOrNull()
}.sortedBy { runCatching { it.tile.getDistance(localPlayer.tile) }.getOrDefault(Int.MAX_VALUE) }

@JvmOverloads
fun findClosestNPC(maxRange: Int = 20, checkReachable: Boolean = false, predicate: (NPC) -> Boolean): NPC? {
    return allNpcsWithinRange(maxRange) { predicate.invoke(it) }
        .mapNotNull { npc ->
            if (checkReachable) {
                val route = routeToNPC(localPlayer.tile, npc)
                return@mapNotNull if (route.success && !route.alternative) Pair(npc, route.distance) else null
            }
            return@mapNotNull Pair(npc, localPlayer.tile.getDistance(npc.tile))
        }
        .minByOrNull { it.second }
        ?.first
}

@JvmOverloads
fun findClosestNPC(name: String, range: Int = 20) = findClosestNPC(range) { it.name() == name }
@JvmOverloads
fun findClosestNPC(id: Int, range: Int = 20) = findClosestNPC(range) { it.id == id }
@JvmOverloads
fun findClosestNPCWithOption(option: String, range: Int = 20) = findClosestNPC(range) { it.hasOption(option) }

@JvmOverloads
fun findClosestReachableNPC(maxRange: Int = 20, predicate: (NPC) -> Boolean) = findClosestNPC(maxRange, true, predicate)

@JvmOverloads
fun interactClosestNPC(option: String, range: Int = 20): Boolean {
    val target = findClosestNPC(range) { it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestNPC(npcId: Int, option: String, range: Int = 20): Boolean {
    val target = findClosestNPC(range) { it.id == npcId && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestNPC(npcName: String, option: String, range: Int = 20): Boolean {
    val target = findClosestNPC(range) { it.name == npcName && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun findClosestReachableNPC(name: String, range: Int = 20) = findClosestReachableNPC(range) { it.name() == name }
@JvmOverloads
fun findClosestReachableNPC(id: Int, range: Int = 20) = findClosestReachableNPC(range) { it.id == id }
@JvmOverloads
fun findClosestReachableNPCWithOption(option: String, range: Int = 20) =
    findClosestReachableNPC(range) { it.hasOption(option) }

@JvmOverloads
fun interactClosestReachableNPC(option: String, range: Int = 20): Boolean {
    val target = findClosestReachableNPC(range) { it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestReachableNPC(npcId: Int, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableNPC(range) { it.id == npcId && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun interactClosestReachableNPC(npcName: String, option: String, range: Int = 20): Boolean {
    val target = findClosestReachableNPC(range) { it.name() == npcName && it.hasOption(option) } ?: return false
    return target.interact(option)
}

@JvmOverloads
fun findNPC(range: Int = 20, predicate: (NPC) -> Boolean) = npcs.values.firstOrNull { predicate.invoke(it) }

/**
 * Walking utilities
 */
fun walkTo(tile: Tile, minimap: Boolean): Boolean {
    if (localPlayer.tile.withinDistance(tile, 40)) {
        DoActionOpcode.WALK.fire(if (minimap) 1 else 0, tile.x, tile.y)
        return true
    } else
        return false
}

// Tile-taking functions are name-mangled on the JVM; these coordinate forms are the Java entry points.
@JvmOverloads
fun walkToTile(x: Int, y: Int, minimap: Boolean = false) = walkTo(Tile.of(x, y, localPlayer.plane), minimap)

/** Walks to ([x], [y]) on [plane]; false when it is more than 40 tiles away. */
@JvmOverloads
fun walkToTile(x: Int, y: Int, plane: Int, minimap: Boolean = false) = walkTo(Tile.of(x, y, plane), minimap)

fun diveToTile(x: Int, y: Int) = dive(Tile.of(x, y, localPlayer.plane))

fun diveToTile(x: Int, y: Int, plane: Int) = dive(Tile.of(x, y, plane))

/**
 * Walks to [destination] from anywhere on the world map, teleporting to an unlocked lodestone first when that is
 * quicker and [useLodestones] allows it; see [WebWalker]. Java scripts use `Wait.webWalk`.
 */
suspend fun Script.webWalk(
    destination: Tile,
    arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
    useLodestones: Boolean = true,
): WebWalkResult = WebWalker.walk(this, destination, arriveDistance, useLodestones)

/**
 * Tile geometry and player state
 */
fun playerDistanceTo(x: Double, y: Double) = hypot(localPlayer.tileX - x, localPlayer.tileY - y)

/** The object whose footprint centre is nearest the player, or null for an empty list. */
fun <T : SceneObject> closestObject(objects: Iterable<T>): T? {
    val x = localPlayer.tileX.toDouble()
    val y = localPlayer.tileY.toDouble()
    return objects.minByOrNull { it.distanceTo(x, y) }
}

/** The NPC, player or other entity whose footprint centre is nearest the player, or null for an empty list. */
fun <T : Entity> closestEntity(entities: Iterable<T>): T? {
    val x = localPlayer.tileX.toDouble()
    val y = localPlayer.tileY.toDouble()
    return entities.minByOrNull { it.distanceTo(x, y) }
}

/** True when ([x], [y]) is at least [safeDistance] tiles, counting diagonals as one, from every {x, y} marker. */
fun isTileSafe(x: Int, y: Int, markers: List<IntArray>, safeDistance: Int) =
    markers.none { maxOf(abs(x - it[0]), abs(y - it[1])) < safeDistance }

/** The safe tile ([isTileSafe]) nearest the player within [range] tiles, as {x, y}, or null when there is none. */
fun nearestSafeTile(markers: List<IntArray>, safeDistance: Int, range: Int): IntArray? {
    val px = localPlayer.tileX
    val py = localPlayer.tileY
    var best: IntArray? = null
    var bestDistance = Int.MAX_VALUE
    for (dx in -range..range) {
        for (dy in -range..range) {
            val distance = dx * dx + dy * dy
            if (distance < bestDistance && distance <= range * range && isTileSafe(px + dx, py + dy, markers, safeDistance)) {
                bestDistance = distance
                best = intArrayOf(px + dx, py + dy)
            }
        }
    }
    return best
}

/**
 * True when ([x], [y]) lies inside any square zone. Each zone is {centreX, centreY, radius}, counting diagonals as
 * one: a 3x3 floor marker is radius 1, a 7x7 is radius 3. Add a tile to the radius for clearance.
 */
fun isTileInZones(x: Int, y: Int, zones: List<IntArray>): Boolean =
    zones.any { maxOf(abs(x - it[0]), abs(y - it[1])) <= it[2] }

/**
 * The tile the player can walk to soonest that lies outside every zone (see [isTileInZones]), as {x, y}, or null
 * when none is within [range] tiles. Walls and objects are respected, so the tile is really reachable; among tiles
 * equally few steps away the one nearest ([preferX], [preferY]) wins, so a dodge can land beside the next job.
 */
@JvmOverloads
fun safeTileOutside(
    zones: List<IntArray>,
    range: Int = 10,
    preferX: Int = localPlayer.tileX,
    preferY: Int = localPlayer.tileY,
): IntArray? {
    val startX = localPlayer.tileX
    val startY = localPlayer.tileY
    if (!isTileInZones(startX, startY, zones)) return intArrayOf(startX, startY)

    val plane = localPlayer.plane
    val validator = StepValidator(WorldCollision.allFlags)
    val side = range * 2 + 1
    val seen = BooleanArray(side * side)
    var frontier = listOf(intArrayOf(startX, startY))
    seen[range * side + range] = true

    repeat(range) {
        val next = ArrayList<IntArray>()
        var best: IntArray? = null
        var bestPreference = Int.MAX_VALUE
        for (tile in frontier) {
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = tile[0] + dx
                val y = tile[1] + dy
                val index = (y - startY + range) * side + (x - startX + range)
                if (abs(x - startX) > range || abs(y - startY) > range || seen[index]) continue
                if (!validator.canTravel(plane, tile[0], tile[1], dx, dy, extraFlag = 0)) continue
                seen[index] = true
                next += intArrayOf(x, y)
                if (!isTileInZones(x, y, zones)) {
                    val preference = maxOf(abs(x - preferX), abs(y - preferY))
                    if (preference < bestPreference) {
                        best = intArrayOf(x, y)
                        bestPreference = preference
                    }
                }
            }
        }
        if (best != null) return best
        if (next.isEmpty()) return null
        frontier = next
    }
    return null
}

/** The tile just outside [obj]'s footprint nearest the player, as {x, y}: where to stand, or land a dive, beside it. */
fun tileBeside(obj: SceneObject): IntArray {
    val px = localPlayer.tileX
    val py = localPlayer.tileY
    val minX = obj.tileX - 1
    val minY = obj.tileY - 1
    val maxX = obj.tileX + obj.footprintX
    val maxY = obj.tileY + obj.footprintY
    var best = intArrayOf(minX, minY)
    var bestDistance = Int.MAX_VALUE
    for (x in minX..maxX) {
        for (y in minY..maxY) {
            if (x != minX && x != maxX && y != minY && y != maxY) continue
            val distance = (x - px) * (x - px) + (y - py) * (y - py)
            if (distance < bestDistance) {
                bestDistance = distance
                best = intArrayOf(x, y)
            }
        }
    }
    return best
}

/** Not animating: the idle animation ids the client reports between actions. */
fun isPlayerIdle() = localPlayer.animationId in -1..1

fun isPlayerBusy() = localPlayer.isMoving || localPlayer.isAnimating

private val porters =
    intArrayOf(29275, 29276, 29277, 29278, 29279, 29280, 29281, 29282, 29283, 29284, 29285, 29286, 51490, 51491)

suspend fun Script.checkPorter() {
    val porter = inventory.firstOrNull { porters.contains(it.id) }
    if (!equipment.hasItem(*porters) && porter != null)
        if (porter.click("Wear"))
            delay(3500, 3000)
}

suspend fun Script.waitUntilNotMoving(timeout: Long = 30000) {
    waitThenDelayUntil(1200, timeout) { !localPlayer.isMoving }
}

suspend fun Script.waitUntilNotAniMoving(timeout: Long = 30000) {
    waitThenDelayUntil(1200, timeout) { !localPlayer.isAniMoving }
}

/**
 * Interface utilities
 */
@JvmOverloads
fun interactComponent(optionNum: Int, interfaceId: Int, componentId: Int, slotId: Int = -1): Boolean {
    val slot = IFSlot(interfaceId, componentId, slotId)
    return slot.click(optionNum)
}

fun openAreaLoot() = interactComponent(1, 1678, 7)
fun lootAllAreaLoot() = interactComponent(1, 1622, 21)
fun lootCustomAllAreaLoot() = interactComponent(1, 1622, 30)

val areaLootContainsHoldableItems: Boolean
    get() {
        if (areaLoot.isEmpty) return false
        if (!inventory.isFull) return true
        return areaLoot.any { it.id == 995 || (it.getDef().isStackable() && inventory.hasItem(it.id)) }
    }

val makeXOpen: Boolean
    get() = interfaces.isOpen(1370)

val hasActiveMakeXProgress: Boolean
    get() = interfaces.isOpen(1251) && activeMakeXLeft > 0

val activeMakeXLeft: Int
    get() = varcs.getVar(2229)

fun continueMakeX() {
    IFSlot(1370, 30, -1).dialogueContinue()
}

val dialogueOptions
    get() = buildMap {
        when {
            interfaces.isOpen(1188) -> {
                interfaces.getComponent(1188, 6)?.text?.let { set(it, IFSlot(1188, 8, -1)) }
                interfaces.getComponent(1188, 33)?.text?.let { set(it, IFSlot(1188, 13, -1)) }
                interfaces.getComponent(1188, 35)?.text?.let { set(it, IFSlot(1188, 18, -1)) }
                interfaces.getComponent(1188, 37)?.text?.let { set(it, IFSlot(1188, 23, -1)) }
                interfaces.getComponent(1188, 39)?.text?.let { set(it, IFSlot(1188, 28, -1)) }
            }

            interfaces.isOpen(720) -> {
                interfaces.getComponent(720, 14)?.text?.let { set(it, IFSlot(720, 1, -1)) }
                interfaces.getComponent(720, 21)?.text?.let { set(it, IFSlot(720, 20, -1)) }
                interfaces.getComponent(720, 24)?.text?.let { set(it, IFSlot(720, 23, -1)) }
                interfaces.getComponent(720, 27)?.text?.let { set(it, IFSlot(720, 26, -1)) }
                interfaces.getComponent(720, 30)?.text?.let { set(it, IFSlot(720, 29, -1)) }
                interfaces.getComponent(720, 33)?.text?.let { set(it, IFSlot(720, 32, -1)) }
                interfaces.getComponent(720, 36)?.text?.let { set(it, IFSlot(720, 35, -1)) }
                interfaces.getComponent(720, 39)?.text?.let { set(it, IFSlot(720, 38, -1)) }
                interfaces.getComponent(720, 42)?.text?.let { set(it, IFSlot(720, 41, -1)) }
                interfaces.getComponent(720, 45)?.text?.let { set(it, IFSlot(720, 44, -1)) }
            }
        }
    }

fun dialogueOptionVisible(option: String) = dialogueOptions.keys.firstOrNull { it.contains(option) } != null

fun continueDialogueContaining(contains: String): Boolean =
    dialogueOptions.keys.firstOrNull { it.contains(contains) }?.let { dialogueOptions[it] }?.dialogueContinue() == true

fun isDialogOpen(): Boolean {
    return interfaces.isOpen(1188) || interfaces.isOpen(1184) || interfaces.isOpen(1191) ||
            interfaces.isOpen(1193) || interfaces.isOpen(1500) || interfaces.isOpen(1189) ||
            interfaces.isOpen(1186) || interfaces.isOpen(720) || interfaces.isOpen(1370) ||
            interfaces.isOpen(1251) || interfaces.isOpen(847) || interfaces.isOpen(1187) ||
            varps.getVarBit(21222) == 1
}


suspend fun Script.clickKey(key: Int) {
    ActionInput.keyDown(key)
    delay(22, 15)
    ActionInput.keyUp(key)
}

fun keyDown(key: Char) = Key.forChar(key)?.let { keyDown(it) }
fun keyDown(key: Key) = ActionInput.keyDown(key.native)

fun keyUp(key: Char) = Key.forChar(key)?.let { keyUp(it) }
fun keyUp(key: Key) = ActionInput.keyUp(key.native)

suspend fun Script.clickKey(key: Char) = Key.forChar(key)?.let { clickKey(it) }
suspend fun Script.clickKey(key: Key) = clickKey(key.native)

/**
 * Banking utilities
 */
val bankOpen
    get() = interfaces.isOpen(BANK_INTERFACE_ID)
val bankWithdrawNotes
    get() = varps.getVar(160) == 1

private val knownBankOptions = arrayOf("Bank", "Use", "Open")

@JvmOverloads
fun openClosestBank(checkReachable: Boolean = false, range: Int = 20): Boolean {
    findClosestObjectToTile(localPlayer.tile, range, checkReachable) {
        it.name().contains("Bank") || it.hasOption("Bank")
    }?.let { bankObj ->
        for (op in knownBankOptions) {
            if (bankObj.hasOption(op))
                return bankObj.interact(op)
        }
        println("Bank object had unsupported action: ${bankObj.name()} ${bankObj.typeId} (${Gameval.locLabel(bankObj.typeId)})")
    }

    findClosestNPC(range, checkReachable) { it.name().contains("Bank") || it.hasOption("Bank") }?.let { bankNpc ->
        for (op in knownBankOptions) {
            if (bankNpc.hasOption(op))
                return bankNpc.interact(op)
        }
        println(
            "Bank npc had unsupported action: ${bankNpc.name} ${bankNpc.typeId} (${Gameval.npcLabel(bankNpc.typeId)}), might be too far away ${
                bankNpc.tile.getDistance(
                    localPlayer.tile
                )
            }"
        )
    }
    return false
}

@JvmOverloads
fun loadLastPresetClosestBank(checkReachable: Boolean = false, range: Int = 20): Boolean {
    findClosestObjectToTile(localPlayer.tile, range, checkReachable) {
        it.name().contains("Bank") || it.hasOption("Bank")
    }?.let { bankObj ->
        if (bankObj.hasOption("Load Last Preset from"))
            return bankObj.interact("Load Last Preset from")
        println("Bank object had unsupported action: ${bankObj.name()} ${bankObj.typeId} (${Gameval.locLabel(bankObj.typeId)})")
    }

    findClosestNPC(range, checkReachable) { it.name().contains("Bank") || it.hasOption("Bank") }?.let { bankNpc ->
        if (bankNpc.hasOption("Load Last Preset from"))
            return bankNpc.interact("Load Last Preset from")
        println(
            "Bank npc had unsupported action: ${bankNpc.name} ${bankNpc.typeId} (${Gameval.npcLabel(bankNpc.typeId)}), might be too far away ${
                bankNpc.tile.getDistance(
                    localPlayer.tile
                )
            }"
        )
    }
    return false
}

fun closeBank() = doBankAction(Bank.CLOSE_COMPONENT_ID)
fun equipFromBank(name: String) = doBankItemsAction(name, 9)
fun equipFromBank(id: Int) = doBankItemsAction(id, 9)
fun equipFromBankInventory(id: Int) = doBankInventoryAction(id, 8)
fun equipFromBankInventory(name: String) = doBankInventoryAction(name, 8)
fun selectBankAllTab() = doBankAction(Bank.TABS_ALL_COMPONENT_ID)
fun loadBankPreset(num: Int) = doBankAction(Bank.SHARE_QUICK_COMPONENT_ID, num)
fun depositBankInventory() = doBankAction(Bank.DEPOSIT_INVENTORY_COMPONENT_ID)
fun depositBankWorn() = doBankAction(Bank.DEPOSIT_WORN_COMPONENT_ID)
fun setBankWithdrawNotes(notes: Boolean) =
    if (bankWithdrawNotes != notes) doBankAction(Bank.CERT_COMPONENT_ID) else true

@JvmOverloads
fun depositBankItem(name: String, amount: Int = 0) = when (amount) {
    1 -> doBankInventoryAction(name, 2)
    5 -> doBankInventoryAction(name, 3)
    10 -> doBankInventoryAction(name, 4)
    50 -> doBankInventoryAction(name, 5)
    0 -> doBankInventoryAction(name, 7) //deposit all (also option num 1)
    else -> {
        println("Deposit-X not implemented (needs keyboard support)") //option 6 is Deposit-x
        false
    }
}

@JvmOverloads
fun depositBankItem(name: Regex, amount: Int = 0) = when (amount) {
    1 -> doBankInventoryAction(name, 2)
    5 -> doBankInventoryAction(name, 3)
    10 -> doBankInventoryAction(name, 4)
    50 -> doBankInventoryAction(name, 5)
    0 -> doBankInventoryAction(name, 7) //deposit all (also option num 1)
    else -> {
        println("Deposit-X not implemented (needs keyboard support)") //option 6 is Deposit-x
        false
    }
}

@JvmOverloads
fun withdrawBankItem(name: String, amount: Int = 0) = when (amount) {
    1 -> doBankItemsAction(name, 2)
    5 -> doBankItemsAction(name, 3)
    10 -> doBankItemsAction(name, 4)
    50 -> doBankItemsAction(name, 5)
    0 -> doBankItemsAction(name, 7) //withdraw all
    else -> {
        println("Withdraw-X not implemented (needs keyboard support)") //option 6 is withdraw-x
        false
    }
}

@JvmOverloads
fun withdrawBankItem(name: Regex, amount: Int = 0) = when (amount) {
    1 -> doBankItemsAction(name, 2)
    5 -> doBankItemsAction(name, 3)
    10 -> doBankItemsAction(name, 4)
    50 -> doBankItemsAction(name, 5)
    0 -> doBankItemsAction(name, 7) //withdraw all
    else -> {
        println("Withdraw-X not implemented (needs keyboard support)") //option 6 is withdraw-x
        false
    }
}

//TODO for these we can check if deposit box interface is open as well and call that instead if it is
fun depositAllInventory() = doBankAction(39)
fun depositAllEquipment() = doBankAction(42)
fun depositAllFamiliar() = doBankAction(45)

/**
 * Skill utilities
 */
fun getCurrentLevel(skill: Skill) = Bootstrap.client.mainLogicManager.statTable[skill].currentLevel
fun getRealLevel(skill: Skill) = Bootstrap.client.mainLogicManager.statTable[skill].realLevel
fun getXp(skill: Skill) = Bootstrap.client.mainLogicManager.statTable[skill].xp

/**
 * Fast and safe variant of current level lookup.
 * - Returns null if data is unavailable or an error occurs.
 * - No UI dependencies.
 */
fun getCurrentLevelOrNull(skill: Skill): Int? = try {
    Bootstrap.client.mainLogicManager.statTable[skill].currentLevel
} catch (_: Throwable) { null }

/**
 * Combat utilities
 */
enum class Prayer(val varbit: Int, val button: IFSlot) {
    PROTECT_MAGIC(16745, IFSlot(1458, 40, 13)),
    PROTECT_RANGED(16746, IFSlot(1458, 40, 14)),
    PROTECT_MELEE(16747, IFSlot(1458, 40, 15)),
    ECLIPSED_SOUL(55986, IFSlot(1458, 40, 26)),
    DIVINE_RAGE(55729, IFSlot(1458, 40, 27)),
    PROTECT_NECROMANCY(53274, IFSlot(1458, 40, 16)),
    DEFLECT_MAGIC(16768, IFSlot(1458, 40, 13)),
    DEFLECT_RANGE(16769, IFSlot(1458, 40, 14)),
    DEFLECT_MELEE(16770, IFSlot(1458, 40, 15)),
    DEFLECT_NECROMANCY(53281, IFSlot(1458, 40, 16)),
    SOUL_SPLIT(16779, IFSlot(1458, 40, 35)),
    LEECH_MELEE_STRENGTH(16775, IFSlot(1458, 40, 25)),
    CHRONICLE_ATTRACTION(49330, IFSlot(1458, 40, 30)),
    TURMOIL(16780, IFSlot(1458, 40, 37)),
    ANGUISH(16783, IFSlot(1458, 40, 38)),
    TORMENT(16784, IFSlot(1458, 40, 39)),
    SORROW(53278, IFSlot(1458, 40, 40)),
    MALEVOLENCE(34866, IFSlot(1458, 40, 41)),
    DESOLATION(34867, IFSlot(1458, 40, 42)),
    AFFLICTION(34868, IFSlot(1458, 40, 43)),
    RUINATION(53280, IFSlot(1458, 40, 44));


    val active
        get() = varps.getVarBit(varbit) == 1

    fun click() = button.click(1)
}

suspend fun Script.togglePrayer(prayer: Prayer, shouldBeActive: Boolean) {
    if (prayer.active != shouldBeActive) {
        prayer.click()
        delayUntil(gaussian(2000L, 1592L)) { prayer.active == shouldBeActive }
    }
}

val quickPrayersActive get() = varps.getVarBit(5941) == 1

suspend fun Script.toggleQuickPrayers(shouldBeActive: Boolean) {
    if (quickPrayersActive != shouldBeActive) {
        if (IFSlot(1430, 16).click())
            delayUntil(gaussian(2000L, 1592L)) { quickPrayersActive == shouldBeActive }
    }
}

val actionbarAbilities
    get() = ACTIONBAR_ABILITIES

val channelingAbility get() = varps.getVar(3533) > 0

fun castAbility(ability: Ability): Boolean {
    println("Casting: $ability")
    return actionbarAbilities[ability.type]?.click(1) == true
}

suspend fun Script.castAndWaitForCd(ability: Ability, timeout: Long = 6200): Boolean {
    return if (ability.offCdIgnoreGCD && castAbility(ability)) {
        delayUntil(timeout) { !ability.offCdIgnoreGCD }
        true
    } else {
        false
    }
}

suspend fun Script.castWithAdren(
    ability: Ability,
    required: Int,
    waitCondition: () -> Boolean = { !ability.offCdIgnoreGCD },
    timeout: Long = 6200
): Boolean {
    return if (adrenaline >= required && ability.offCdIgnoreGCD && castAbility(ability)) {
        delayUntil(timeoutMillis = timeout, predicate = waitCondition)
        true
    } else {
        false
    }
}

suspend fun Script.castIf(
    condition: Boolean,
    ability: Ability,
    waitCondition: () -> Boolean,
    timeout: Long = 6200
): Boolean {
    return if (condition && ability.offCdIgnoreGCD && castAbility(ability)) {
        delayUntil(timeout) { waitCondition() }
        true
    } else {
        false
    }
}

suspend fun Script.smartCast(
    ability: Ability,
    resourceRequired: Int = 0,
    condition: () -> Boolean = { true },
    waitCondition: () -> Boolean = { !ability.offCdIgnoreGCD },
    timeout: Long = 6200,
    checkCooldown: Boolean = true
): Boolean {
    return if ((!checkCooldown || ability.offCdIgnoreGCD) &&
        adrenaline >= resourceRequired &&
        condition() && castAbility(ability)
    ) {
        delayUntil(timeout) { waitCondition() }
        true
    } else {
        false
    }
}

suspend fun Script.castWithEffectStacks(
    ability: Ability,
    effect: Effect,
    minStacks: Int,
    waitCondition: (() -> Boolean)? = null,
    timeout: Long = 6200
): Boolean {
    return smartCast(
        ability = ability,
        condition = { effect.stacks >= minStacks },
        waitCondition = waitCondition ?: { !ability.offCdIgnoreGCD },
        timeout = timeout
    )
}

@JvmOverloads
fun abilityOffCd(ability: Ability, ignoreGCD: Boolean = false) =
    if (ignoreGCD) ability.offCdIgnoreGCD else ability.offCd

@JvmOverloads
fun abilityCooldownTicks(ability: Ability, ignoreGCD: Boolean = false) =
    if (ignoreGCD) ability.cooldownTicksIgnoreGCD else ability.cooldownTicks

fun surge() = Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)

fun escape() = Ability.ESCAPE.offCdIgnoreGCD && castAbility(Ability.ESCAPE)

private val DIVE_ABILITIES = listOf(Ability.DIVE, Ability.BLADED_DIVE)

val diveAbility get() = DIVE_ABILITIES.firstOrNull { actionbarAbilities[it.type] != null }

fun isDiveReady() = diveAbility?.offCdIgnoreGCD == true

fun dive(tile: Tile): Boolean {
    val ability = diveAbility ?: return false
    if (!ability.offCdIgnoreGCD) return false
    if (actionbarAbilities[ability.type]?.select() == true)
        return tile.target()
    return false
}

val canUseProtectionPrayers = !Effect.PROTECTION_PRAYER_BLOCKED.active

val adrenaline get() = varps.getVar(679).toDouble() / 10.0

val maxResidualSouls get() = if (equipment[5]?.getDef()?.params?.get(8928) == 48397) 5 else 3

val isOverloaded get() = Effect.OVERLOADED.active
val dreadnipActive get() = Effect.DREADNIP.active
val excaliburCD get() = varps.getVarBit(22838) != 0
val elvenShardCD get() = varps.getVarBit(40606) != 0
val onStandardPrayers get() = varps.getVarBit(16789) != 0
val onCursesPrayers get() = varps.getVarBit(16789) == 1
val inCombat get() = varps.getVarBit(1899) == 1

private const val MINING_STAMINA_VARBIT = 43187

/** Mining stamina in points (varbit mining_stamina, 0..1023); the maximum grows with Mining level. */
val miningStamina get() = varps.getVarBit(MINING_STAMINA_VARBIT)
val isStunned get()= localPlayer.spotAnims.any { it.id == 4531 }

val combatTarget get() = npcs.values.firstOrNull { npc -> npc.spotAnims.any { it.id in 9082..9086 || it.id in 9102..9106 } }
val hasCombatTarget get() = combatTarget != null

/** The action bar slot holding any of [itemIds], or null when none is on the bar. */
fun actionBarItemSlot(vararg itemIds: Int): IFSlot? =
    parseAllActionBarItems().firstOrNull { it.first in itemIds }?.second

/** Uses the first of [itemIds] found in the inventory with [option], falling back to the action bar. */
fun useInventoryOrActionBarItem(option: String, vararg itemIds: Int): Boolean {
    for (id in itemIds) {
        if (inventory.getItem(id)?.click(option) == true) return true
    }
    return actionBarItemSlot(*itemIds)?.click(1) == true
}

fun drinkOverload() = !isOverloaded && inventory.clickItem(Regex(".*overload.*", RegexOption.IGNORE_CASE), 1)
fun activateExcalibur() =
    !excaliburCD && inventory.clickItem(Regex(".*excalibur.*", RegexOption.IGNORE_CASE), "Activate")

fun activateElvenShard() = !elvenShardCD && inventory.clickItem("Ancient elven ritual shard", "Activate")
fun deployDreadnip() = !dreadnipActive && inventory.clickItem("Dreadnip", "Deploy")
fun throwVulnBomb() = Effect.VULNERABILITY.notActiveOnOpponent && inventory.clickItem("Vulnerability bomb", 1)
fun drinkAdrenPot() = !Effect.ADRENALINE_POTION_PREVENTION.active && inventory.clickItem(
    Regex(
        "(?i).*(adrenaline|replenishment).*(potion|flask).*",
        RegexOption.IGNORE_CASE
    ), 1
)

fun drinkPrayerPot() = inventory.clickItem(Regex(".*prayer potion.*", RegexOption.IGNORE_CASE), 1)
fun eatFood() = castAbility(Ability.EAT_FOOD)

/**
 * Instance wrapper
 */
val instanceExpired get() = InstanceSystem.isExpired()
val instanceTimeRemainingMs get() = InstanceSystem.getTimeRemainingMs()
val instanceTimeFormatted get() = InstanceSystem.getFormattedTimeRemaining()
val instanceStartInterfaceOpen get() = InstanceSystem.isOpen()
fun startInstance() = InstanceSystem.startInstance()

/**
 * Misc utilities
 */
data class DangerZone(val center: Tile, val radius: Int) {
    constructor(centerX: Int, centerY: Int, plane: Int, radius: Int) : this(Tile.of(centerX, centerY, plane), radius)

    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    val centerX: Int get() = center.x
    val centerY: Int get() = center.y
    val plane: Int get() = center.plane
}

data class TileArea(val tile: Tile, val sizeX: Int, val sizeY: Int) {
    constructor(x: Int, y: Int, plane: Int, sizeX: Int, sizeY: Int) : this(Tile.of(x, y, plane), sizeX, sizeY)

    val tileX: Int get() = tile.x
    val tileY: Int get() = tile.y
    val plane: Int get() = tile.plane
}

/** [calculateClosestSafeTile] from ([x], [y]) on [plane], for Java; the target area defaults to that one tile. */
@JvmOverloads
fun calculateClosestSafeTile(
    x: Int,
    y: Int,
    plane: Int,
    dangerZones: List<DangerZone>,
    targetArea: TileArea = TileArea(x, y, plane, 1, 1),
    obstacles: List<TileArea> = emptyList(),
    maxRadius: Int = 10,
    checkObstacleLos: Boolean = true
): Tile? = calculateClosestSafeTile(Tile.of(x, y, plane), dangerZones, targetArea, obstacles, maxRadius, checkObstacleLos)

/** [calculateClosestReachableSafeTile] from ([x], [y]) on [plane], for Java. */
@JvmOverloads
fun calculateClosestReachableSafeTile(
    x: Int,
    y: Int,
    plane: Int,
    dangerZones: List<DangerZone>,
    targetArea: TileArea = TileArea(x, y, plane, 1, 1),
    maxRadius: Int = 10
): Tile? = calculateClosestReachableSafeTile(Tile.of(x, y, plane), dangerZones, targetArea, maxRadius)

/** [bresenhamLos] between two tiles given as coordinates, for Java. */
fun bresenhamLos(startX: Int, startY: Int, endX: Int, endY: Int, obstacles: List<TileArea>): Boolean =
    bresenhamLos(Tile.of(startX, startY, 0), Tile.of(endX, endY, 0), obstacles)

fun calculateClosestSafeTile(
    currentPos: Tile,
    dangerZones: List<DangerZone>,
    targetArea: TileArea = TileArea(currentPos, 1, 1),
    obstacles: List<TileArea> = emptyList(),
    maxRadius: Int = 10,
    checkObstacleLos: Boolean = true
): Tile? {
    var searchRadius = 1
    val checkedTiles = mutableSetOf<Tile>()

    while (searchRadius <= maxRadius) {
        val potentialTiles = mutableListOf<Tile>()

        for (offset in -searchRadius..searchRadius) {
            val positions = listOf(
                Tile.of(currentPos.x + offset, currentPos.y + searchRadius, currentPos.plane),
                Tile.of(currentPos.x + offset, currentPos.y - searchRadius, currentPos.plane),
                Tile.of(currentPos.x + searchRadius, currentPos.y + offset, currentPos.plane),
                Tile.of(currentPos.x - searchRadius, currentPos.y + offset, currentPos.plane)
            ).distinct()

            potentialTiles.addAll(positions.filter { !checkedTiles.contains(it) })
        }

        potentialTiles.sortBy { tile ->
            val targetEndX = targetArea.tile.x + targetArea.sizeX - 1
            val targetEndY = targetArea.tile.y + targetArea.sizeY - 1

            val closestX = tile.x.coerceIn(targetArea.tile.x, targetEndX)
            val closestY = tile.y.coerceIn(targetArea.tile.y, targetEndY)

            val dx = tile.x - closestX
            val dy = tile.y - closestY
            dx * dx + dy * dy
        }

        for (tile in potentialTiles) {
            checkedTiles.add(tile)

            val isSafeFromAoe = dangerZones.none { aoe -> tile.withinDistance(aoe.center, aoe.radius) }

            if (!isSafeFromAoe) continue

            val isBlocked = obstacles.any { obstacle ->
                val obstacleEndX = obstacle.tile.x + obstacle.sizeX
                val obstacleEndY = obstacle.tile.y + obstacle.sizeY

                tile.x >= obstacle.tile.x && tile.x <= obstacleEndX &&
                        tile.y >= obstacle.tile.y && tile.y <= obstacleEndY
            }

            if (isBlocked) continue

            if (!checkObstacleLos || bresenhamLos(currentPos, tile, obstacles))
                return tile
        }

        searchRadius++
    }

    return null
}

fun bresenhamLos(start: Tile, end: Tile, obstacles: List<TileArea>): Boolean {
    val dx = end.x - start.x
    val dy = end.y - start.y

    val steps = maxOf(abs(dx), abs(dy))
    if (steps == 0) return true

    val xIncrement = dx.toFloat() / steps
    val yIncrement = dy.toFloat() / steps

    var currentX = start.x.toFloat()
    var currentY = start.y.toFloat()

    for (i in 0..steps) {
        val checkX = currentX.roundToInt()
        val checkY = currentY.roundToInt()

        for (obstacle in obstacles) {
            val obstacleEndX = obstacle.tile.x + obstacle.sizeX - 1
            val obstacleEndY = obstacle.tile.y + obstacle.sizeY - 1

            if (checkX >= obstacle.tile.x && checkX <= obstacleEndX &&
                checkY >= obstacle.tile.y && checkY <= obstacleEndY
            ) {
                return false
            }
        }

        currentX += xIncrement
        currentY += yIncrement
    }

    return true
}

fun calculateClosestReachableSafeTile(
    currentPos: Tile,
    dangerZones: List<DangerZone>,
    targetArea: TileArea = TileArea(currentPos, 1, 1),
    maxRadius: Int = 10
): Tile? = findClosestSafeTile(
    currentPos.x,
    currentPos.y,
    currentPos.plane,
    dangerZones,
    targetArea,
    maxRadius
)

/** WORLD HOPPING/MISC **/

val MEMBER_WORLDS_NON_PK_OR_REQUIREMENTS = arrayOf(
    1,
    2,
    4,
    5,
    6,
    9,
    10,
    12,
    14,
    15,
    16,
    21,
    22,
    23,
    24,
    25,
    26,
    27,
    28,
    31,
    32,
    35,
    36,
    37,
    39,
    40,
    44,
    45,
    46,
    47,
    49,
    50,
    51,
    53,
    54,
    56,
    58,
    59,
    60,
    62,
    63,
    64,
    65,
    67,
    68,
    69,
    70,
    71,
    72,
    73,
    74,
    75,
    76,
    77,
    78,
    79,
    82,
    83,
    84,
    85,
    87,
    88,
    89,
    91,
    92,
    96,
    97,
    98,
    99,
    100,
    102,
    103,
    104,
    105,
    106,
    116,
    117,
    118,
    119,
    121,
    123,
    124,
    134,
    138,
    139,
    140,
    252,
    257,
    258,
    259
)
val FREE_WORLDS_NON_PK_OR_REQUIREMENTS = arrayOf(
    3,
    7,
    8,
    11,
    17,
    19,
    20,
    29,
    34,
    38,
    41,
    43,
    55,
    61,
    94,
    108,
    122,
    141,
    210,
    215,
    225,
    236,
    239,
    245,
    250,
    251,
    255,
    256
)

/**
 * Gets the current world of your player, returning an integer world number.
 * IF, it is null, it'll return 0.
 *
 * THIS function gets the world from the friends list component.
 */
fun getCurrentWorld(): Int {
    val worldText = interfaces.getComponent(550, 48)?.text ?: return 0
    val regex = Regex("RuneScape\\s+(\\d+)")
    return regex.find(worldText)?.groupValues?.get(1)?.toInt() ?: 0
}

fun isLoggedIn() = Bootstrap.client.mainState == MainState.LOGGED_IN

/** True while the client is anywhere but in game: loading, reconnecting, login screen or lobby. */
fun isPlayerLoading() = !isLoggedIn()

/**
 * Handles the world hopping logic for Project X Scripts.
 */
suspend fun Script.randomizedWorldHop(membersOnly: Boolean = true) {
    val currentWorld = getCurrentWorld()

    println("[WORLD HOPPER] Option menu opened (CURRENT WORLD IS $currentWorld)")
    IFSlot(1477, 97, 1).click(1)
    waitThenDelayUntil(2000, 5555) { !interfaces.isOpen(1433) }

    if (interfaces.isOpen(1433)) {
        println("[WORLD HOPPER] Choosing Change worlds options")
        IFSlot(1433, 65, -1).click(1)
        waitThenDelayUntil(2000, 5555) { !interfaces.isOpen(1587) }
    }

    if (interfaces.isOpen(1587)) {
        var worldToUse = FREE_WORLDS_NON_PK_OR_REQUIREMENTS.filter { it != currentWorld }.random()

        if (membersOnly)
            worldToUse = MEMBER_WORLDS_NON_PK_OR_REQUIREMENTS.filter { it != currentWorld }.random()

        println("[WORLD HOPPER] Changing Worlds to $worldToUse")
        IFSlot(1587, 8, worldToUse).click(2)

        waitThenDelayUntil(2000, 30000) { isLoggedIn() }
        println("[WORLD HOPPER] Finished Loading World.")
    }
}


var worldToPopMap = mutableMapOf<Int, Int>()

// Tracks when a world was last used (epoch millis). Worlds in this map are excluded
// from selection while within the configured cooldown window.
private val worldUsageHistory = mutableMapOf<Int, Long>()

suspend fun Script.checkWorldPop(cooldownMinutes: Long = 10): Boolean {

    val nearbyNonWhitelistedPlayers = players.filter {
        it.name != localPlayer.name
    }
    if (nearbyNonWhitelistedPlayers.isNotEmpty()) {
        println("Non-whitelisted players detected: ${nearbyNonWhitelistedPlayers.joinToString(", ") { it.name }}")
        val currentWorld = getCurrentWorld()
        println("Current World: $currentWorld")
        if (currentWorld == 0) return false

        randomizedWorldHopQuick(membersOnly = true, currentWorld, cooldownMinutes)
        return true
    }
    return false
}

suspend fun Script.randomizedWorldHopQuick(
    membersOnly: Boolean = true,
    currentWorld: Int,
    cooldownMinutes: Long = 10
) {

    val availableWorlds = listOf(
        1, 4, 5, 6, 9, 12, 15,
        16, 21, 22, 23, 24, 25, 26, 27, 28, 31,
        32, 35, 36, 37, 39, 40, 42, 44, 45, 46,
        49, 50, 51, 53, 54, 58, 59, 60,
        62, 63, 64, 65, 66, 67, 68, 69, 71,
        72, 73, 74, 76, 77, 78, 82, 83,
        85, 88, 89, 91, 92, 97, 98, 99, 100,
        103, 104, 105, 106, 116, 117, 119,
        123, 124, 134, 138, 139, 140, 252
    )

    // Purge expired entries from usage history and prepare for fresh population scan
    val cooldownMs = cooldownMinutes * 60_000
    val now = System.currentTimeMillis()
    val expiry = now - cooldownMs
    worldUsageHistory.keys.toList().forEach { w ->
        val last = worldUsageHistory[w]
        if (last != null && last < expiry) worldUsageHistory.remove(w)
    }
    worldToPopMap.clear()
    IFSlot(1431, 0, 7).click(1)
    waitThenDelayUntil(100, 5555) { interfaces.isOpen(1433) }
    IFSlot(1433, 65, -1).click(1)
    waitThenDelayUntil(100, 5555) { interfaces.isOpen(1587) }


    if (interfaces.isOpen(1587)) {
        val component7 = interfaces.getComponent(1587, 9)

        var currentLoopWorld = 0

        component7?.slotChildren?.forEach {
            if (it.slotId % 9 == 2) {
                currentLoopWorld = it.text.toInt()
                println("World: ${it.text}")
            }
            if (it.slotId % 9 == 3 && availableWorlds.contains(currentLoopWorld)) {
                println("Pop: ${it.text}")
                worldToPopMap[currentLoopWorld] = it.text.toInt()
            }
        }

        // Exclude the current world and any worlds used within the cooldown window
        val eligibleWorlds = worldToPopMap
            .filter { entry ->
                entry.key != currentWorld &&
                        (worldUsageHistory[entry.key]?.let { last -> (now - last) >= cooldownMs } ?: true)
            }
        val worldToUse = eligibleWorlds.minByOrNull { it.value }?.key
        if (worldToUse == null) {
            println("[WORLD HOPPER] No suitable world found after filtering; aborting")
            return
        }
        println("[WORLD HOPPER] Changing Worlds to $worldToUse")
        // Record both the current and target worlds as used now to prevent immediate reuse
        worldUsageHistory[currentWorld] = now
        IFSlot(1587, 10, worldToUse).click(2)
        delayUntil(15000) { isPlayerLoading() }
        // Ensure local player is active
        delayUntil(15000) { !isPlayerLoading() }
        println("[WORLD HOPPER] Finished Loading World.")
    }
}
//GroupSystem Teleports

enum class GroupTeleports(val ifSlot: IFSlot, val groupName: String? = null) {
    CROESUS(IFSlot(1524, 11, 54), "Croesus")

}

suspend fun Script.teleportWithGroupSystem(boss: GroupTeleports) {
    val groupName_Text = interfaces[1519]?.get(3)?.slotChildren[0]?.text
    if (groupName_Text == null) {
        IFSlot(1432, 5, 11).click()
        delay(2000)
        return
    }

    if (groupName_Text == boss.groupName) {
        IFSlot(1528, 31, -1).click()
        delayUntil(20000) { interfaces.isOpen(1527) }
        IFSlot(1527, 12, -1).click()
        delayUntil(20000) { interfaces.isOpen(1188) }
        IFSlot(1188, 8, -1).dialogueContinue()
        delay(2000)
        IFSlot(1528, 67, -1).click()
        IFSlot(1477, 738, 1).click()
        return
    }

    if (groupName_Text.contains(localPlayer.name)) {
        IFSlot(1524, 115, -1).click()
        delayUntil(15000) { interfaces[1519]?.get(3)?.slotChildren[0]?.text == boss.groupName }
        return
    }

    if (groupName_Text == "You are not currently in a group.") {
        IFSlot(1519, 77, -1).click()
        IFSlot(1431, 0, 4).click()
        delayUntil(15000) { interfaces[1477]?.get(735)?.slotChildren[15]?.text != null }
        IFSlot(1477, 735, 15).click()
        delay(5000)
        boss.ifSlot.click()
        delay(1000)
        IFSlot(1524, 21, -1).click()
        delayUntil(15000) { interfaces[1519]?.get(3)?.slotChildren[0]?.text != "You are not currently in a group." }
        return
    }
}

/**
 * The lodestone network. [id] is the lodestone's teleport button on the lodestone map (interface 1092); [varbit] is
 * the player var that is set once it is unlocked, or -1 where no unlock var is known.
 */
enum class Lodestone(val id: Int, val object_id: Int, val object_id_unlocked: Int, val tile: Tile, val varbit: Int) {
    AL_KHARID(10, 69846, 69847, Tile.of(3297, 3184, 0), 28),
    ANACHRONIA(24, 113743, 113744, Tile.of(5431, 2338, 0), 44270),
    ARDOUGNE(11, 69848, 69849, Tile.of(2634, 3348, 0), 29),
    ASHDALE(33, -1, -1, Tile.of(2474, 2708, 2), 22430),
    BANDIT_CAMP(8, 69842, 69843, Tile.of(3214, 2954, 0), -1),
    BURTHOPE(12, -1, -1, Tile.of(2899, 3544, 0), -1),
    CANIFIS(26, 84755, 84756, Tile.of(3517, 3515, 0), 18523),
    CATHERBY(13, 69852, 69853, Tile.of(2811, 3449, 0), 31),
    DRAYNOR_VILLAGE(14, 69854, 69855, Tile.of(3105, 3298, 0), 32),
    EAGLES_PEAK(27, 84757, 84758, Tile.of(2366, 3479, 0), 18524),
    EDGEVILLE(15, 69856, 69857, Tile.of(3067, 3505, 0), 33),
    FALADOR(16, 69858, 69859, Tile.of(2967, 3403, 0), 34),
    FORT_FORINTHRY(22, 124994, 124995, Tile.of(3298, 3525, 0), 52518),
    FREMENNIK_PROVINCE(28, 84759, 84760, Tile.of(2712, 3677, 0), 18525),
    KARAMJA(29, 84761, 84762, Tile.of(2761, 3147, 0), 18526),
    LUNAR_ISLE(9, -1, -1, Tile.of(2085, 3914, 0), -1),
    LUMBRIDGE(17, 69860, 69861, Tile.of(3233, 3221, 0), 35),
    MENAPHOS(23, 109415, 109416, Tile.of(3216, 2716, 0), 36173),
    OOGLOG(30, -1, -1, Tile.of(2532, 2871, 0), 18527),
    PORT_SARIM(18, 69862, 69863, Tile.of(3011, 3217, 0), 36),
    PRIFDDINAS(34, 93371, 93372, Tile.of(2208, 3360, 1), 24967),
    SEERS_VILLAGE(19, 69864, 69865, Tile.of(2689, 3482, 0), 37),
    TAVERLEY(20, 69866, 69867, Tile.of(2878, 3442, 0), 38),
    TIRANNWN(31, 84765, 84766, Tile.of(2254, 3149, 0), 18528),
    UM(35, 127267, 127268, Tile.of(1084, 1768, 1), -1),
    VARROCK(21, 69868, 69869, Tile.of(3214, 3376, 0), 39),
    WILDERNESS(32, 84767, 84768, Tile.of(3143, 3635, 0), 18529),
    YANILLE(25, 69870, 69871, Tile.of(2529, 3094, 0), 40);

    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    val tileX: Int get() = tile.x
    val tileY: Int get() = tile.y
    val plane: Int get() = tile.plane

    /**
     * True once this lodestone can be teleported to. Burthorpe is open to everyone; a lodestone with no known unlock
     * var ([varbit] -1) reports false rather than risk a teleport the game refuses.
     */
    fun isUnlocked(): Boolean = when {
        this == BURTHOPE -> true
        varbit == -1 -> false
        else -> varps.getVarBit(varbit) > 0
    }
}

const val LODESTONE_MAP_INTERFACE = 1092

// The minimap's home teleport button, whose first option opens the lodestone network: one per minimap layout.
private val HOME_TELEPORT_BUTTONS = listOf(1465 to 34, 1319 to 33)

val isLodestoneUiOpen
    get() = interfaces.isOpen(LODESTONE_MAP_INTERFACE)

/** Clicks the minimap's home teleport button to open the lodestone network; false when no minimap shows it. */
fun openLodestoneMap(): Boolean = HOME_TELEPORT_BUTTONS.any { (interfaceId, componentId) -> interactComponent(1, interfaceId, componentId) }

/**
 * This function allows you to teleport using the lodestone network
 * @param lodestone - Uses Lodestone Enum Class to know which lodestone to teleport to.
 *
 * @example
 * useLodestone(Lodestone.CITY_OF_UM)
 */
suspend fun Script.useLodestone(lodestone: Lodestone) {
    if (isLodestoneUiOpen) {
        println("Clicking on lodestone: ${lodestone.name}")
        interactComponent(1, LODESTONE_MAP_INTERFACE, lodestone.id)
        waitThenDelayUntil(600, 5000) { !localPlayer.isAnimating }

        if (!localPlayer.isAnimating) {
            println("Waited for player animation to end")
            waitThenDelayUntil(600, 30000) { localPlayer.tile.withinDistance(lodestone.tile) }

            if (localPlayer.tile.withinDistance(lodestone.tile)) {
                print("Arrived at lodestone tile")
                waitUntilNotAniMoving(5000) //wait until player stepped off lodestone
            }
        }
    } else {
        println("Opening lodestone interface")
        openLodestoneMap()
        waitThenDelayUntil(600, 5000) { isLodestoneUiOpen }
    }
}


/**
 * Relic powers available to the player.
 * The numeric [value] corresponds to varbit values used by the client.
 * Includes a sentinel [NONE] for unset/unknown.
 */
enum class Relics(val value: Int) {
    NONE(0),
    FONT_OF_LIFE(1),
    SLAYER_INTROSPECTION(2),
    DIVINE_CONVERSION(3),
    ENDURANCE(4),
    FURY_OF_THE_SMALL(5),
    POUCH_PROTECTOR(6),
    CONSERVATION_OF_ENERGY(7),
    ABYSSAL_LINK(8),
    DEATHLESS(9),
    NEXUS_MOD(10),
    PHARM_ECOLOGY(11),
    ALWAYS_ADZE(12),
    PERSISTENT_RAGE(13),
    STICKY_FINGERS(14),
    BERSERKERS_FURY(15),
    DEATH_WARD(16),
    INSPIRE_AWE(17),
    INSPIRE_EFFORT(18),
    INSPIRE_GENIUS(19),
    INSPIRE_LOVE(20),
    RING_OF_LUCK(21),
    RING_OF_WEALTH(22),
    RING_OF_FORTUNE(23),
    UNEXPECTED_DIPLOMACY(24),
    HEIGHTENED_SENSES(25),
    LUCK_OF_THE_DWARVES(26),
    BAIT_AND_SWITCH(27),
    FLOW_STATE(28),
    DEATH_NOTE(29),
    SHADOWS_GRACE(30);

    companion object {
        /** Lookup a [Relics] by its numeric [id]; returns [NONE] when not found. */
        @JvmStatic
        fun fromId(id: Int): Relics = entries.firstOrNull { it.value == id } ?: NONE

        /** Lookup a [Relics] by its [name] (case-insensitive); returns [NONE] when not found. */
        @JvmStatic
        fun fromName(name: String): Relics = entries.firstOrNull { it.name.equals(name, true) } ?: NONE


    }
}

val slot1Varbit: Int = 57207
val slot2Varbit: Int = 57208
val slot3Varbit: Int = 57209

/** Returns the relic power equipped in slot 1 (or [Relics.NONE] if unset). */
fun Script.getSlot1RelicPower(): Relics = Relics.fromId(getRelicPowerFromVarbit(slot1Varbit))

/** Returns the relic power equipped in slot 2 (or [Relics.NONE] if unset). */
fun Script.getSlot2RelicPower(): Relics = Relics.fromId(getRelicPowerFromVarbit(slot2Varbit))

/** Returns the relic power equipped in slot 3 (or [Relics.NONE] if unset). */
fun Script.getSlot3RelicPower(): Relics = Relics.fromId(getRelicPowerFromVarbit(slot3Varbit))

/**
 * Returns true if [relic] is equipped in any of the three slots.
 */
fun Script.hasRelic(relic: Relics): Boolean {
    if (relic == Relics.NONE) return false
    val equipped = setOf(getSlot1RelicPower(), getSlot2RelicPower(), getSlot3RelicPower())
    return relic in equipped
}

/** Returns true if any of [relics] is equipped. */
fun Script.hasAnyRelic(vararg relics: Relics): Boolean {
    val equipped = setOf(getSlot1RelicPower(), getSlot2RelicPower(), getSlot3RelicPower())
    return relics.any { it != Relics.NONE && it in equipped }
}

/** Returns true if all of [relics] are equipped. */
fun Script.hasAllRelics(vararg relics: Relics): Boolean {
    val equipped =
        setOf(getSlot1RelicPower(), getSlot2RelicPower(), getSlot3RelicPower()).filter { it != Relics.NONE }
            .toSet()
    return relics.all { it in equipped }
}

/** Returns the set of equipped relics, excluding [Relics.NONE]. */
fun Script.getEquippedRelics(): Set<Relics> =
    setOf(getSlot1RelicPower(), getSlot2RelicPower(), getSlot3RelicPower()).filter { it != Relics.NONE }.toSet()

/** Raw varbit read for relic slot power id. */
private fun getRelicPowerFromVarbit(varbit: Int): Int {
    return varps.getVarBit(varbit)
}
