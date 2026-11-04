package com.projectx.traversal.nodes

import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.equipment
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.traversal.TraversalNode
import com.projectx.util.Area
import com.projectx.traversal.nodes.NodeWaits.awaitItemEffect

class ItemNode private constructor(
    private val selector: ItemSelector,
    private val optionName: String? = null,
    private val optionNum: Int? = null,
    private var destination: Area? = null,
    private var customReached: (() -> Boolean)? = null
) : TraversalNode() {

    private var nextClick: Long = 0

    constructor(
        itemName: String,
        optionName: String,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.NameRef(itemName), optionName = optionName, destination = destination, customReached = customReached
    )

    constructor(
        itemId: Int,
        optionName: String,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.IdRef(itemId), optionName = optionName, destination = destination, customReached = customReached
    )

    constructor(
        itemName: String,
        optionNum: Int,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.NameRef(itemName), optionNum = optionNum, destination = destination, customReached = customReached
    )

    constructor(
        itemId: Int,
        optionNum: Int,
        destination: Area? = null,
        customReached: (() -> Boolean)? = null
    ) : this(
        ItemSelector.IdRef(itemId), optionNum = optionNum, destination = destination, customReached = customReached
    )

    private fun resolveItem(): Item? = when (selector) {
        is ItemSelector.NameRef -> named(selector.name) { it.equals(selector.name, ignoreCase = true) }
            ?: named(selector.name) { it.contains(selector.name, ignoreCase = true) }
        is ItemSelector.IdRef -> {
            inventory.firstOrNull { it.id == selector.id }
                ?: equipment.firstOrNull { it.id == selector.id }
        }
    }

    /** A charged item's name carries its charges, "Ring of duelling (8)", so a name may also match within it. */
    private fun named(name: String, match: (String) -> Boolean): Item? =
        inventory.firstOrNull { it.id != -1 && match(it.name) } ?: equipment.firstOrNull { it.id != -1 && match(it.name) }

    override suspend fun process(script: Script): Boolean {
        if (System.currentTimeMillis() < nextClick) return true
        val currentTile = localPlayer.tile
        val item = resolveItem()
        if (item == null) {
            println("Item not found")
            script.delay(100, 100)
            return true
        }

        val success = when {
            optionName != null -> item.click(optionName)
            optionNum != null -> item.click(optionNum)
            else -> false
        }

        if (success) {
            script.awaitItemEffect(currentTile) { reached(script) || traversalStopRequested() }
            nextClick = System.currentTimeMillis() + NodeWaits.clickGap()
            return true
        }
        return false
    }

    override fun reached(script: Script) = (customReached?.invoke() ?: destination?.inside(localPlayer.tile)) == true

    override fun copy(): TraversalNode = ItemNode(
        this.selector,
        this.optionName,
        this.optionNum,
        this.destination,
        this.customReached
    )

    override fun toString(): String = buildString {
        append("[")
        append(selector)
        if (optionName != null) append(" op=\"").append(optionName).append("\"")
        if (optionNum != null) append(" op#=").append(optionNum)
        append("]")
    }
}

sealed class ItemSelector {
    data class NameRef(val name: String) : ItemSelector()
    data class IdRef(val id: Int) : ItemSelector()
}
