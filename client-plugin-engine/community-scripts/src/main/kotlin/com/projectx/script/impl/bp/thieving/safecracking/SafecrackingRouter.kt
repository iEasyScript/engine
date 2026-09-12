package com.projectx.script.impl.bp.thieving.safecracking

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.traversal.Traversal
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.Area
import kotlin.random.Random

private fun SceneObject.revalidate(): SceneObject? {
    return findClosestObject(10) { it.id == this.id && it.tile == this.tile }
}

@ScriptDescription(
    name = "Safecracking Router",
    author = "BP",
    version = "1.0.0",
    description = "Fully automated safecracking - currently only kandarin route"
)
class SafecrackingRouter : StateMachineScript<SafecrackingRouter>() {
    private var routeIndex: Int = 0
    var shouldHandIn: Boolean = false
    var lastHandledSpotAnimCreatedTime: Int = 0

    companion object {
        @JvmStatic
        val SPOTANIM_ID = 6882

        @JvmStatic
        val ACTION_CRACK_SAFE = "Crack open"

        @JvmStatic
        val OPTION_FENCE_ITEMS = "Fence items"

        @JvmStatic
        val OBJECT_NAME_SAFE = "Safe"

        @JvmStatic
        val TEXT_BAG_FULL = "Your loot bag is full."

        @JvmStatic
        val TEXT_BAG_75 = "Your loot bag is 75% full."
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        when (event) {
            is Chat -> {
                if (event.message.contains(TEXT_BAG_FULL) || event.message.contains(TEXT_BAG_75)) {
                    this.shouldHandIn = true
                }
            }
        }
    }

    private val ROUTE_KANDARIN = listOf(
        Safe.CAMELOT_CASTLE_WEST to ::toCamelotSafes,
        Safe.CAMELOT_CASTLE_EAST to ::toCamelotSafes,
        Safe.ARDOUGNE_SQUARE_NORTH to ::toArdougneSquareNorth,
        Safe.ARDOUGNE_SQUARE_SOUTH_WEST to ::toArdougneSquareSouthWest,
        Safe.ARDOUGNE_CASTLE_NORTH to ::toArdougneCastleNorth,
        Safe.ARDOUGNE_CASTLE_SOUTH to ::toArdougneCastleSouth,
        Safe.YANILLE_WEST_WALL to ::toYanilleWestWall,
        Safe.YANILLE_BAR_UPPER to ::toYanilleBar,
    )

    private val activeRoute = ROUTE_KANDARIN

    override fun getStartState(): State<SafecrackingRouter> {
        return Init()
    }

    fun nextSafe() {
        while (getActiveSafe().isCracked()) {
            this.routeIndex++
            if (this.routeIndex >= activeRoute.size) {
                this.routeIndex = 0
            }
        }
    }

    fun getActiveSafe(): Safe {
        return this.activeRoute[this.routeIndex].first
    }

    fun pathToCurrentSafe(): Traversal<SafecrackingRouter> {
        return this.activeRoute[this.routeIndex].second()
    }

}

class Init() : State<SafecrackingRouter>() {
    override suspend fun SafecrackingRouter.checkNext(): State<SafecrackingRouter> {
        return pathToCurrentSafe()
    }

    override suspend fun SafecrackingRouter.stateLoop() {
        delay(1000)
    }
}

class Safecracking() : State<SafecrackingRouter>() {

    override suspend fun SafecrackingRouter.checkNext(): State<SafecrackingRouter>? {
        if (getActiveSafe().isCracked()) {
            nextSafe()
            if (shouldHandIn && getActiveSafe().ordinal != Safe.ARDOUGNE_CASTLE_SOUTH.ordinal && getActiveSafe().ordinal != Safe.CAMELOT_CASTLE_EAST.ordinal) {
                return toLootHandIn
            }
            return pathToCurrentSafe()
        }
        return null
    }

    override suspend fun SafecrackingRouter.stateLoop() {
        val safe = getActiveSafe()
        val safeSceneObject = findClosestObject(10) {
            it.id == safe.realId && it.tile == safe.tile && it.hasOption(SafecrackingRouter.ACTION_CRACK_SAFE)
        }

        if (safeSceneObject == null) {
            println("Safe not found")
            delay(600)
            return
        }

        val safeQuickAction = spotAnims.find { it.id == SafecrackingRouter.SPOTANIM_ID && it.tile == safe.tile }

        if (safeQuickAction != null) {
            safeSceneObject.interact(SafecrackingRouter.ACTION_CRACK_SAFE)
            lastHandledSpotAnimCreatedTime = safeQuickAction.createdClientcycle

            delayUntil(2000) { spotAnims.none { it.id == SafecrackingRouter.SPOTANIM_ID && it.tile == safe.tile && it.createdClientcycle == lastHandledSpotAnimCreatedTime } || safeSceneObject.revalidate() == null }
        } else if (!localPlayer.isAniMoving) {
            safeSceneObject.interact(SafecrackingRouter.ACTION_CRACK_SAFE)
            delayUntil(2000) { spotAnims.any { it.id == SafecrackingRouter.SPOTANIM_ID && it.tile == safe.tile } || safeSceneObject.revalidate() == null }
            delay(600)
        } else {
            delay(1000, 2000)
            return
        }

        delay(150, 500)
    }
}

class SafecrackingHandIn : State<SafecrackingRouter>() {
    private var didHandIn: Boolean = false

    override suspend fun SafecrackingRouter.checkNext(): State<SafecrackingRouter>? {
        if (didHandIn) {
            didHandIn = false
            return pathToCurrentSafe()
        }
        return null
    }

    override suspend fun SafecrackingRouter.stateLoop() {
        delay(2400, 600)
        if (!didHandIn) {
            val success = interactClosestNPC(SafecrackingRouter.OPTION_FENCE_ITEMS, 15)
            if (success) {
                waitForXPDrop(Skill.THIEVING)
                didHandIn = true
                shouldHandIn = false
            } else {
                delay(2000, 1000)
            }
        }
        delay(1000, 400)
    }
}

fun Tile.add(x: Int, y: Int, plane: Int = this.plane): Tile {
    return Tile.of(this.x + x, this.y + y, plane)
}

fun Tile.addX(x: Int, plane: Int = this.plane): Tile {
    return Tile.of(this.x + x, this.y, plane)
}

fun Tile.addY(y: Int, plane: Int = this.plane): Tile {
    return Tile.of(this.x, this.y + y, plane)
}

fun Tile.randomize(maxDelta: Int = 3): Tile {
    return Tile.of(
        this.x + Random.nextInt(-maxDelta, maxDelta),
        this.y + Random.nextInt(-maxDelta, maxDelta),
        this.plane
    )
}

private val toCamelotSafes
    get() = traversal(
        Safecracking(), { Safe.CAMELOT_CASTLE_EAST.tile.withinDistance(localPlayer.tile, 3) }) {
        clickIFSlot(IFSlot(1461, 1, 39), destination = Area(2753, 3475, 2762, 3481))
        interactObj("Gate", "Open") { Tile.of(2757, 3483, 0).withinDistance(localPlayer.tile, 1) }
        walkExact(
            Door.CAMELOT_CASTLE_MAIN_DOOR.tileOutside.addY(-2).randomize(2)
        ) { Door.CAMELOT_CASTLE_MAIN_DOOR.tileOutside.withinDistance(localPlayer.tile, 5) }
        doorIn(Door.CAMELOT_CASTLE_MAIN_DOOR.toDoorInfo())
        doorIn(Door.CAMELOT_CASTLE_WEST_DOOR.toDoorInfo())
        path(Door.CAMELOT_CASTLE_WEST_DOOR.tileInside, Safe.CAMELOT_CASTLE_WEST.tile.addX(1)) {
            Safe.CAMELOT_CASTLE_WEST.tile.withinDistance(
                localPlayer.tile,
                3
            )
        }
    }

private val toArdougneSquareNorth
    get() = traversal(
        Safecracking(), { Safe.ARDOUGNE_SQUARE_NORTH.tile.withinDistance(localPlayer.tile, 3) }) {
        clickIFSlot(IFSlot(1461, 1, 45)) {
            localPlayer.tile.plane == 0 && Area(2647, 3321, 2673, 3293).inside(
                localPlayer.tile
            )
        }
        path(Tile.of(2660, 3302, 0), Door.ARDOUGNE_SQUARE_NORTH_LOWER.tileOutside)
        doorIn(Door.ARDOUGNE_SQUARE_NORTH_LOWER.toDoorInfo())
        interactObj("Staircase", "Climb-up", 10) { localPlayer.tile.plane == 1 }
        doorIn(Door.ARDOUGNE_SQUARE_NORTH_UPPER.toDoorInfo())
        path(
            Door.ARDOUGNE_SQUARE_NORTH_UPPER.tileInside,
            Safe.ARDOUGNE_SQUARE_NORTH.tile.addY(1)
        ) { Safe.ARDOUGNE_SQUARE_NORTH.tile.withinDistance(localPlayer.tile, 3) }
    }

private val toArdougneSquareSouthWest
    get() = traversal(Safecracking(), { Safe.ARDOUGNE_SQUARE_SOUTH_WEST.tile.withinDistance(localPlayer.tile, 2) }) {
        clickIFSlot(IFSlot(1461, 1, 45)) {
            localPlayer.tile.plane == 0 && Area(2647, 3321, 2673, 3293).inside(
                localPlayer.tile
            )
        }
        walkExact(
            Door.ARDOUGNE_SQUARE_SOUTH_WEST_LOWER.tileOutside.addX(1).randomize(1)
        ) { Door.ARDOUGNE_SQUARE_SOUTH_WEST_LOWER.tileOutside.withinDistance(localPlayer.tile, 9) }
        doorIn(Door.ARDOUGNE_SQUARE_SOUTH_WEST_LOWER.toDoorInfo())
        interactObj("Staircase", "Climb-up") { localPlayer.tile.plane == 1 }
        doorIn(Door.ARDOUGNE_SQUARE_SOUTH_WEST_UPPER.toDoorInfo())
        path(
            Door.ARDOUGNE_SQUARE_SOUTH_WEST_UPPER.tileInside,
            Safe.ARDOUGNE_SQUARE_SOUTH_WEST.tile.addX(1)
        ) { Safe.ARDOUGNE_SQUARE_SOUTH_WEST.tile.withinDistance(localPlayer.tile, 2) }
    }

private val toArdougneCastleNorth
    get() = traversal(Safecracking(), { Safe.ARDOUGNE_CASTLE_NORTH.tile.withinDistance(localPlayer.tile, 1) }) {
        clickIFSlot(IFSlot(1461, 1, 45)) {
            localPlayer.tile.plane == 0 && Area(2647, 3321, 2673, 3293).inside(
                localPlayer.tile
            )
        }
        chebychevPath(
            Tile.of(2660, 3302, 0),
            listOf(
                Tile.of(2632, 3299, 0),
                Tile.of(2606, 3296, 0),
                Tile.of(2581, 3297, 0)
            )
        ) {
            Door.ARDOUGNE_CASTLE_LOWER_MAIN.tileOutside.withinDistance(
                localPlayer.tile,
                9
            )
        }
        doorIn(Door.ARDOUGNE_CASTLE_LOWER_MAIN.toDoorInfo())
        walkExact(Door.ARDOUGNE_CASTLE_LOWER_NORTH.tileOutside) {
            Door.ARDOUGNE_CASTLE_LOWER_NORTH.tileOutside.withinDistance(
                localPlayer.tile,
                5
            )
        }
        doorIn(Door.ARDOUGNE_CASTLE_LOWER_NORTH.toDoorInfo())
        interactObj("Staircase", "Climb-up", 10) { localPlayer.tile.plane == 1 }
        doorIn(Door.ARDOUGNE_CASTLE_UPPER_NORTH.toDoorInfo())
        path(
            Door.ARDOUGNE_CASTLE_UPPER_NORTH.tileInside,
            Safe.ARDOUGNE_CASTLE_NORTH.tile.addX(1)
        ) { Safe.ARDOUGNE_CASTLE_NORTH.tile.withinDistance(localPlayer.tile, 1) }
    }

private val toArdougneCastleSouth
    get() = traversal(Safecracking(), { Safe.ARDOUGNE_CASTLE_SOUTH.tile.withinDistance(localPlayer.tile, 1) }) {
        doorOut(Door.ARDOUGNE_CASTLE_UPPER_NORTH.toDoorInfo())
        path(
            Door.ARDOUGNE_CASTLE_UPPER_NORTH.tileOutside,
            Door.ARDOUGNE_CASTLE_UPPER_SOUTH.tileOutside
        ) { Door.ARDOUGNE_CASTLE_UPPER_SOUTH.tileOutside.withinDistance(localPlayer.tile, 9) }
        doorIn(Door.ARDOUGNE_CASTLE_UPPER_SOUTH.toDoorInfo())
        path(
            Door.ARDOUGNE_CASTLE_UPPER_SOUTH.tileInside,
            Safe.ARDOUGNE_CASTLE_SOUTH.tile.addX(1)
        ) { Safe.ARDOUGNE_CASTLE_SOUTH.tile.withinDistance(localPlayer.tile, 1) }
    }

private val toYanilleWestWall
    get() = traversal(Safecracking(), { Safe.YANILLE_WEST_WALL.tile.withinDistance(localPlayer.tile, 5) }) {
        useLodestone(Lodestone.YANILLE) { Area(2522, 3098, 2538, 3084).inside(localPlayer.tile) }
        path(Lodestone.YANILLE.tile, Door.YANILLE_WALL_DOOR.tileOutside)
        doorIn(Door.YANILLE_WALL_DOOR.toDoorInfo())
        path(Door.YANILLE_WALL_DOOR.tileInside, Safe.YANILLE_WEST_WALL.tile.addX(1)) {
            Safe.YANILLE_WEST_WALL.tile.withinDistance(
                localPlayer.tile,
                5
            )
        }
    }

private val toYanilleBar
    get() = traversal(Safecracking(), { Safe.YANILLE_BAR_UPPER.tile.withinDistance(localPlayer.tile, 5) }) {
        useLodestone(Lodestone.YANILLE) { Area(2522, 3098, 2538, 3084).inside(localPlayer.tile) }
        doorOut(Door.YANILLE_WALL_DOOR.toDoorInfo()) { Area(2526, 3096, 2538, 3090).inside(localPlayer.tile) }
        path(
            Door.YANILLE_WALL_DOOR.tileOutside,
            Door.YANILLE_BAR_DOOR.tileOutside
        ) { Door.YANILLE_BAR_DOOR.tileOutside.withinDistance(localPlayer.tile, 7) }
        doorIn(Door.YANILLE_BAR_DOOR.toDoorInfo())
        interactObj("Ladder", "Climb-up", 10) { localPlayer.tile.plane == 1 }
        path(Tile.of(2555, 3081, 1), Safe.YANILLE_BAR_UPPER.tile.addY(1)) {
            Safe.YANILLE_BAR_UPPER.tile.withinDistance(
                localPlayer.tile,
                5
            )
        }
    }

private val toLootHandIn
    get() = traversal(SafecrackingHandIn(), { localPlayer.tile.withinDistance(Tile.of(4761, 5775, 0), 7) }) {
        clickIFSlot(IFSlot(1461, 1, 31), destination = Area(3204, 3237, 3231, 3262))
        walkExact(Tile.of(3222, 3268, 0).addY(2).addX(-4).randomize(2), true) {
            Tile.of(3222, 3268, 0).withinDistance(localPlayer.tile, 15)
        }
        interactObj("Trapdoor", "Enter", 15) { Tile.of(4762, 5763, 0).withinDistance(localPlayer.tile, 5) }
        walkExact(Tile.of(4761, 5775, 0)) { Tile.of(4761, 5775, 0).withinDistance(localPlayer.tile, 7) }
    }
