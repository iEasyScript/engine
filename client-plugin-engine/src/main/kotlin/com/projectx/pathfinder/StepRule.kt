package com.projectx.pathfinder

import world.gregs.voidps.type.Direction

/** Whether a size-1 mover may step from (x, y, plane) one tile in [Direction]. */
internal fun interface StepRule {
    fun canStep(x: Int, y: Int, plane: Int, direction: Direction): Boolean
}

internal object Steps {
    /** The eight ways a tile can be left, in the order the server's pathfinder tries them: W E S N SW SE NW NE. */
    val SERVER_ORDER = arrayOf(
        Direction.WEST, Direction.EAST, Direction.SOUTH, Direction.NORTH,
        Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_WEST, Direction.NORTH_EAST,
    )

    /** Walking on [WorldCollision] as it stands now, door changes in the scene included. */
    fun live(): StepRule {
        val validator = StepValidator(WorldCollision.allFlags)
        return StepRule { x, y, plane, direction ->
            validator.canTravel(plane, x, y, direction.delta.x, direction.delta.y, extraFlag = 0)
        }
    }

    fun of(dx: Int, dy: Int): Direction? = Direction.of(dx, dy).takeIf { it != Direction.NONE }
}
