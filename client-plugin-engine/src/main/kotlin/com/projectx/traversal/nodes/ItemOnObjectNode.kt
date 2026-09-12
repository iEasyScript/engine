package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.allObjects
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area
import com.projectx.util.gaussian

class ItemOnObjectNode private constructor(
    private val itemIdentifier: ItemIdentifier,
    private val objIdentifier: ObjectIdentifier
) : TraversalNode() {
    private var customReached: (() -> Boolean)? = null
    private var destination: Area? = null
    private var nextClick: Long = 0
    private var objectSearchRadius: Int = 20

    constructor(
        itemName: String,
        obj: SceneObject,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.ObjectRef(obj)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        obj: SceneObject,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.ObjectRef(obj)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemName: String,
        objectName: String,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.NameRef(objectName)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        objectName: String,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.NameRef(objectName)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemName: String,
        objectId: Int,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.NameRef(itemName),
        ObjectIdentifier.IdRef(objectId)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    constructor(
        itemId: Int,
        objectId: Int,
        destination: Area? = null,
        objectSearchRadius: Int = 20,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemIdentifier.IdRef(itemId),
        ObjectIdentifier.IdRef(objectId)
    ) {
        this.destination = destination
        this.customReached = customReached
        this.objectSearchRadius = objectSearchRadius
    }

    private fun getItem(script: Script) = when (itemIdentifier) {
        is ItemIdentifier.NameRef -> inventory.find { it.id != -1 && it.name == itemIdentifier.name }
        is ItemIdentifier.IdRef -> inventory.find { it.id == itemIdentifier.id }
    }

    private fun getTarget(script: Script): SceneObject? = when (objIdentifier) {
        is ObjectIdentifier.ObjectRef -> objIdentifier.obj
        is ObjectIdentifier.NameRef -> if (findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
                obj.name().contains(objIdentifier.name)
            } != null) {
            findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
                obj.name().contains(objIdentifier.name)
            }
        } else {
            allObjects.filter { obj ->
                obj.name().contains(objIdentifier.name)
            }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
        }

        is ObjectIdentifier.IdRef -> if (findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
                objIdentifier.id == -1 || obj.id == objIdentifier.id
            } != null) {
            findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
                objIdentifier.id == -1 || obj.id == objIdentifier.id
            }
        } else {
            allObjects.filter { obj ->
                objIdentifier.id == -1 || obj.id == objIdentifier.id
            }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
        }
    }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true

        val item = getItem(script)
        if (item == null) {
            script.delay(100, 100)
            return true
        }

        val target = getTarget(script)
        if (target == null) {
            script.delay(100, 100)
            return true
        }

        val success = item.useOn(target)
        if (success) {
            script.delayUntil(15000) { !localPlayer.isMoving }
            nextClick = System.currentTimeMillis() + gaussian(
                PlayerProfiles.get().walkPathClickTime,
                PlayerProfiles.get().walkPathClickTime / 2
            )
            return true
        }
        return false
    }

    override fun reached(script: Script): Boolean =
        (customReached?.invoke() ?: destination?.inside(localPlayer.tile)) == true

    override fun copy(): TraversalNode = ItemOnObjectNode(this.itemIdentifier, this.objIdentifier).also {
        it.customReached = this.customReached
        it.destination = this.destination
        it.objectSearchRadius = this.objectSearchRadius
    }

    override fun toString() = "[${this.itemIdentifier} on ${this.objIdentifier}]"
}

sealed class ItemIdentifier {
    data class NameRef(val name: String) : ItemIdentifier()
    data class IdRef(val id: Int) : ItemIdentifier()
}