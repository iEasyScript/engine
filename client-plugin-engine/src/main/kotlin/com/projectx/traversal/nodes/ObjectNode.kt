package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.allObjects
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area
import com.projectx.util.gaussian

class ObjectNode private constructor(private val objIdentifier: ObjectIdentifier) : TraversalNode() {
	private var action: String? = null
	private var customReached: (() -> Boolean)? = null
	private var destination: Area? = null
	private var nextClick: Long = 0
	private var objectSearchRadius: Int = 20

	constructor(
		obj: SceneObject,
		action: String? = null,
		destination: Area? = null,
		objectSearchRadius: Int = 20,
		customReached: (() -> Boolean)? = null
	) : this(
		ObjectIdentifier.ObjectRef(obj)
	) {
		this.action = action
		this.destination = destination
		this.customReached = customReached
		this.objectSearchRadius = objectSearchRadius
	}

	constructor(
		objectName: String,
		action: String? = null,
		destination: Area? = null,
		objectSearchRadius: Int = 20,
		customReached: (() -> Boolean)? = null
	) : this(
		ObjectIdentifier.NameRef(objectName)
	) {
		this.action = action
		this.destination = destination
		this.customReached = customReached
		this.objectSearchRadius = objectSearchRadius
	}

	constructor(
		objectId: Int,
		action: String? = null,
		destination: Area? = null,
		objectSearchRadius: Int = 20,
		customReached: (() -> Boolean)? = null
	) : this(
		ObjectIdentifier.IdRef(objectId)
	) {
		this.action = action
		this.destination = destination
		this.customReached = customReached
		this.objectSearchRadius = objectSearchRadius
	}

	private fun getTarget(script: Script): SceneObject? = when (objIdentifier) {
		is ObjectIdentifier.ObjectRef -> objIdentifier.obj
		is ObjectIdentifier.NameRef -> if (findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
				obj.name().contains(objIdentifier.name) && (action == null || obj.hasOption(action!!))
			} != null) {
			findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
				obj.name().contains(objIdentifier.name) && (action == null || obj.hasOption(action!!))
			}
		} else {
			allObjects.filter { obj ->
				obj.name().contains(objIdentifier.name) && (action == null || obj.hasOption(action!!))
			}.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		}

		is ObjectIdentifier.IdRef -> if (findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
				(objIdentifier.id == -1 || obj.id == objIdentifier.id) && (action == null || obj.hasOption(action!!))
			} != null) {
			findClosestReachableObject(maxRange = objectSearchRadius) { obj ->
				(objIdentifier.id == -1 || obj.id == objIdentifier.id) && (action == null || obj.hasOption(action!!))
			}
		} else {
			allObjects.filter { obj ->
				(objIdentifier.id == -1 || obj.id == objIdentifier.id) && (action == null || obj.hasOption(action!!))
			}.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		}
	}

	override suspend fun process(script: Script): Boolean {
		if (System.currentTimeMillis() < nextClick) return true
		val target = getTarget(script)
		if (target == null) {
			script.delay(100, 100)
			return true
		}
		val success = if (action != null) target.interact(action!!) else target.interact(0)
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

	override fun copy(): TraversalNode = ObjectNode(this.objIdentifier).also {
		it.action = this.action
		it.customReached = this.customReached
		it.destination = this.destination
		it.objectSearchRadius = this.objectSearchRadius
	}

	override fun toString() = "[${this.objIdentifier} - $action]"
}

sealed class ObjectIdentifier {
	data class ObjectRef(val obj: SceneObject) : ObjectIdentifier()
	data class NameRef(val name: String) : ObjectIdentifier()
	data class IdRef(val id: Int) : ObjectIdentifier()
}