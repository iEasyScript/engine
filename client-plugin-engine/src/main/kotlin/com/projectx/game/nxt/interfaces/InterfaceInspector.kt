package com.projectx.game.nxt.interfaces

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState

/**
 * Where a component currently stands between "exists" and "on screen".
 *
 * The distinction matters when inspecting: a component can be laid out with a real size and still not be
 * painted (clipped, behind a closed parent, or simply not reached this pass), and only a painted one has a
 * screen rect to highlight or click.
 */
enum class ComponentVisibility {
    /** Painted this frame - has a fresh screen rect, so it can be highlighted and picked. */
    DRAWN,

    /** Sized by the layout pass but not painted - real, addressable, nothing to point at. */
    LAID_OUT,

    /** No size: collapsed, closed, or never laid out. */
    HIDDEN,
}

data class InspectedComponent(
    val interfaceId: Int,
    val componentId: Int,
    val component: InterfaceComponent,
    val visibility: ComponentVisibility,
    val rect: ScreenRect?,
)

/**
 * Read-only view of the live interface tree for the debug inspector.
 *
 * Enumeration goes through [InterfaceList.getRaw] rather than the visibility-filtered accessor, because the
 * whole point is to show components that are *not* currently on screen alongside the ones that are.
 */
object InterfaceInspector {
    /** Guards the per-frame walk: a component's rect is only meaningful while the client is in the world. */
    private fun loggedIn(): Boolean =
        runCatching { Bootstrap.client.mainState == MainState.LOGGED_IN }.getOrDefault(false)

    private fun list(): InterfaceList? = runCatching { Bootstrap.client.interfaceList }.getOrNull()

    private const val INTERFACE_LIST_TTL_NANOS = 250_000_000L
    private var cachedInterfaces: List<Int> = emptyList()
    private var cachedAtNanos = 0L

    /**
     * Every interface id the client currently holds a parent for, whether or not it is being drawn.
     *
     * Cached briefly: the scan walks the whole list, and both the hierarchy tree and the cursor hit-test ask
     * for it every frame. Interfaces open and close on user actions, so a quarter second of staleness is not
     * observable but the per-frame cost is.
     */
    fun loadedInterfaces(): List<Int> {
        val now = System.nanoTime()
        if (now - cachedAtNanos < INTERFACE_LIST_TTL_NANOS) return cachedInterfaces
        val list = list() ?: return emptyList()
        val bound = runCatching { list.size.toInt() }.getOrDefault(0)
        cachedInterfaces = (0 until bound).filter { runCatching { list.getRaw(it) != null }.getOrDefault(false) }
        cachedAtNanos = now
        return cachedInterfaces
    }

    fun componentCount(interfaceId: Int): Int =
        runCatching { list()?.getRaw(interfaceId)?.size ?: 0 }.getOrDefault(0)

    fun components(interfaceId: Int): List<InspectedComponent> {
        val parent = runCatching { list()?.getRaw(interfaceId) }.getOrNull() ?: return emptyList()
        val count = runCatching { parent.size }.getOrDefault(0)
        return (0 until count).mapNotNull { componentId ->
            val component = runCatching { parent[componentId] }.getOrNull() ?: return@mapNotNull null
            inspect(interfaceId, componentId, component)
        }
    }

    fun component(interfaceId: Int, componentId: Int): InspectedComponent? {
        val component = runCatching { list()?.getComponentRaw(interfaceId, componentId) }.getOrNull() ?: return null
        return inspect(interfaceId, componentId, component)
    }

    /** Classifies a component the caller already holds, so a list of them needs no second lookup each. */
    fun describe(interfaceId: Int, component: InterfaceComponent): InspectedComponent {
        val componentId = runCatching { component.componentId }.getOrDefault(-1)
        return inspect(interfaceId, componentId, component)
    }

    /**
     * The [index]th child of [ownerComponentId]'s slot vector, re-resolved rather than held across frames.
     *
     * Slot children are runtime instances of one template component and all report that template's id, so an
     * index into the vector is the only thing that identifies a particular one.
     */
    fun slotChild(interfaceId: Int, ownerComponentId: Int, index: Int): InspectedComponent? {
        val owner = component(interfaceId, ownerComponentId) ?: return null
        val children = runCatching { owner.component.slotChildren }.getOrDefault(emptyList())
        val child = children.getOrNull(index) ?: return null
        val childId = runCatching { child.componentId }.getOrDefault(ownerComponentId)
        return inspect(interfaceId, childId, child)
    }

    private fun inspect(interfaceId: Int, componentId: Int, component: InterfaceComponent): InspectedComponent {
        val rect = runCatching { component.screenRect }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
        val visibility = when {
            rect != null -> ComponentVisibility.DRAWN
            runCatching { component.visible }.getOrDefault(false) -> ComponentVisibility.LAID_OUT
            else -> ComponentVisibility.HIDDEN
        }
        return InspectedComponent(interfaceId, componentId, component, visibility, rect)
    }

    /**
     * The component under ([x], [y]), or null.
     *
     * Smallest containing rect wins: components nest, so the innermost one is both the smallest and the one
     * the user is actually pointing at - picking the first match would return whichever container happened to
     * be enumerated first.
     */
    fun hitTest(x: Float, y: Float): InspectedComponent? {
        if (!loggedIn()) return null
        var best: InspectedComponent? = null
        var bestArea = Long.MAX_VALUE
        for (interfaceId in loadedInterfaces()) {
            for (candidate in components(interfaceId)) {
                val rect = candidate.rect ?: continue
                if (x < rect.x || y < rect.y || x > rect.x + rect.width || y > rect.y + rect.height) continue
                val area = rect.width.toLong() * rect.height.toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = candidate
                }
            }
        }
        return best
    }
}
