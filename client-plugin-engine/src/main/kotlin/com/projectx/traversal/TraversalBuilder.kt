package com.projectx.traversal

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Lodestone
import com.projectx.traversal.nodes.*
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.util.Area
import com.projectx.webwalker.WebWalker

class TraversalBuilder<T : StateMachineScript<T>> {
    private val nodes = arrayListOf<TraversalNode>()

    fun interactObj(action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(-1, action, objectSearchRadius = searchRadius))
    fun interactObj(action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(-1, action, objectSearchRadius = searchRadius, customReached = reached))
    fun interactObj(id: Int, action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(id, action, objectSearchRadius = searchRadius))
    fun interactObj(name: String, action: String, searchRadius: Int = 30) = nodes.add(ObjectNode(name, action, objectSearchRadius = searchRadius))
    fun interactObj(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(id, action, objectSearchRadius = searchRadius, customReached = reached))
    fun interactObj(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(ObjectNode(name, action, objectSearchRadius = searchRadius, customReached = reached))

    fun interactNpc(action: String, searchRadius: Int = 30) = nodes.add(NpcNode(-1, action, npcSearchRadius = searchRadius))
    fun interactNpc(action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(-1, action, npcSearchRadius = searchRadius, customReached = reached))
    fun interactNpc(id: Int, action: String, searchRadius: Int = 30) = nodes.add(NpcNode(id, action, npcSearchRadius = searchRadius))
    fun interactNpc(name: String, action: String, searchRadius: Int = 30) = nodes.add(NpcNode(name, action, npcSearchRadius = searchRadius))
    fun interactNpc(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(id, action, npcSearchRadius = searchRadius, customReached = reached))
    fun interactNpc(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean) = nodes.add(NpcNode(name, action, npcSearchRadius = searchRadius, customReached = reached))

    fun clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, reached: (() -> Boolean)? = null) = nodes.add(IFSlotNode(ifSlot, optionNum, customReached = reached))
    fun clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, destination: Area) = nodes.add(IFSlotNode(ifSlot, optionNum, destination))

    fun clickItem(itemName: String, option: String, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemName, optionName = option, customReached = reached))

    fun clickItem(itemId: Int, option: String, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemId, optionName = option, customReached = reached))

    fun clickItem(itemName: String, optionNum: Int, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemName, optionNum = optionNum, customReached = reached))

    fun clickItem(itemId: Int, optionNum: Int, reached: (() -> Boolean)? = null) =
        nodes.add(ItemNode(itemId, optionNum = optionNum, customReached = reached))

    fun clickItem(itemName: String, option: String, destination: Area) =
        nodes.add(ItemNode(itemName, optionName = option, destination = destination))

    fun clickItem(itemId: Int, option: String, destination: Area) =
        nodes.add(ItemNode(itemId, optionName = option, destination = destination))

    fun clickItem(itemName: String, optionNum: Int, destination: Area) =
        nodes.add(ItemNode(itemName, optionNum = optionNum, destination = destination))

    fun clickItem(itemId: Int, optionNum: Int, destination: Area) =
        nodes.add(ItemNode(itemId, optionNum = optionNum, destination = destination))

    fun path(start: Tile, end: Tile, reached: (() -> Boolean)? = null) = nodes.add(PathNode(start, end, reached))
    fun chebychevPath(start: Tile, end: List<Tile>, fallback: (() -> Boolean)? = null, reached: (() -> Boolean)? = null) = nodes.add(ChebychevNode(start, end, fallback, reached))

    fun walkExact(tile: Tile, minimap: Boolean = false, reached: () -> Boolean) = nodes.add(TileExactNode(tile, minimap, customReached = reached))

    fun webWalk(
        destination: Tile,
        arriveDistance: Int = WebWalker.DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
        reached: (() -> Boolean)? = null,
    ) = nodes.add(WebWalkNode(destination, arriveDistance, reached, useLodestones))

    fun door(doorInfo: DoorInfo, direction: DoorDirection, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, direction, reached))
    fun doorIn(doorInfo: DoorInfo, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, DoorDirection.IN, reached))
    fun doorOut(doorInfo: DoorInfo, reached: (() -> Boolean)? = null) = nodes.add(DoorNode(doorInfo, DoorDirection.OUT, reached))

    fun useLodestone(lodestone: Lodestone, reached: (() -> Boolean)? = null) = nodes.add(LodestoneNode(lodestone, reached))

    fun useItemOnObj(itemName: String, obj: SceneObject, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, obj, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, obj: SceneObject, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, obj, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemName: String, objectName: String, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, objectName, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, objectName: String, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, objectName, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemName: String, objectId: Int, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemName, objectId, objectSearchRadius = searchRadius, customReached = reached))
    fun useItemOnObj(itemId: Int, objectId: Int, searchRadius: Int = 20, reached: (() -> Boolean)? = null) = nodes.add(ItemOnObjectNode(itemId, objectId, objectSearchRadius = searchRadius, customReached = reached))

    fun build(next: State<T>, finishedCondition: T.() -> Boolean): Traversal<T> {
        require(nodes.isNotEmpty()) { "Traversal must contain at least one node" }

        val nodeList = TraversalNodeList(nodes.first())
        nodes.drop(1).forEach { nodeList.add(it) }

        return Traversal(next, finishedCondition, nodeList)
    }
}
