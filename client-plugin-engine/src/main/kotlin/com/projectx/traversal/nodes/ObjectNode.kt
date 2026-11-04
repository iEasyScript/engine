package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.nodes.NodeWaits.awaitInteraction
import com.projectx.util.Area
import world.gregs.voidps.type.Tile

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

	private var clickedFrom: Tile? = null

	private fun matches(obj: SceneObject): Boolean = when (objIdentifier) {
		is ObjectIdentifier.ObjectRef -> obj == objIdentifier.obj
		is ObjectIdentifier.NameRef -> obj.name().contains(objIdentifier.name)
		is ObjectIdentifier.IdRef -> objIdentifier.id == -1 || obj.id == objIdentifier.id
	} && (action == null || obj.hasOption(action!!))

	private fun getTarget(): SceneObject? = when (objIdentifier) {
		is ObjectIdentifier.ObjectRef -> objIdentifier.obj
		else -> LocalTargets.objectNear(objectSearchRadius, ::matches)
	}

	override suspend fun process(script: Script): Boolean {
		if (System.currentTimeMillis() < nextClick) return true
		val target = getTarget()
		if (target == null) {
			script.delay(100, 100)
			return true
		}
		val from = localPlayer.tile
		val success = if (action != null) target.interact(action!!) else target.interact(0)
		if (success) {
			clickedFrom = from
			script.awaitInteraction { reached(script) || traversalStopRequested() }
			nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
			return true
		}
		return false
	}

	override fun reached(script: Script): Boolean {
		customReached?.let { return it() }
		destination?.let { return it.inside(localPlayer.tile) }
		return movedByInteraction()
	}

	/**
	 * With nothing to arrive at, the step is done once the interaction has visibly carried the player: to another floor,
	 * or off the clicked tile onto the walk that comes next.
	 */
	private fun movedByInteraction(): Boolean {
		val from = clickedFrom ?: return false
		if (localPlayer.isMoving || localPlayer.isAnimating) return false
		val here = localPlayer.tile
		if (here.plane != from.plane) return true
		if (here == from) return false
		val nextTiles = (next as? WalkEquivalent)?.routeTiles() ?: return false
		return nextTiles.any { it.plane == here.plane && it.getLongestDelta(here) <= NEXT_WALK_NEAR }
	}

	override fun copy(): TraversalNode = ObjectNode(this.objIdentifier).also {
		it.action = this.action
		it.customReached = this.customReached
		it.destination = this.destination
		it.objectSearchRadius = this.objectSearchRadius
	}

	override fun toString() = "[${this.objIdentifier} - $action]"

	private companion object {
		private const val NEXT_WALK_NEAR = 2
	}
}

sealed class ObjectIdentifier {
	data class ObjectRef(val obj: SceneObject) : ObjectIdentifier()
	data class NameRef(val name: String) : ObjectIdentifier()
	data class IdRef(val id: Int) : ObjectIdentifier()
}