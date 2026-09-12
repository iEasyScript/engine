package com.projectx.script.impl.qb.minigames.clues

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.impl.qb.minigames.clues.PuzzlesUI.LOCKBOX_PUZZLE_IF
import com.projectx.script.impl.qb.minigames.clues.PuzzlesUI.SLIDING_PUZZLE_IF
import com.projectx.script.impl.qb.minigames.clues.PuzzlesUI.TOWERS_PUZZLE_IF
import com.projectx.traversal.Traversal
import com.projectx.util.gaussian
import com.projectx.util.random
import java.util.*
import java.util.function.ToIntFunction
import kotlin.math.abs

private val atWarsRetreat get() = Tile.of(3295, 10146, 0).withinDistance(localPlayer.tile, 50)
private val atGe get() = Tile.of(3162, 3463, 0).withinDistance(localPlayer.tile, 50)

@ScriptDescription(
    name = "Clues State Machine",
    version = "1.0.0",
    author = "QB",
    description = "Fully automated clues using state machine pattern",
)
class CluesStateMachine : StateMachineScript<CluesStateMachine>(), ConfigurableScript {
    override fun getStartState(): State<CluesStateMachine> {
        return CluesState()
    }

    enum class ClueType {
        DIG,
        EMOTE,
        SEARCH,
        NPC
    }

    enum class ClueEnum(val id: Int, val path: () -> Traversal<CluesStateMachine>, val type: ClueType) {
    }

    fun onConfigUpdated() {
        println("Config updated")
        this::class.java.declaredFields.filter { ConfigItem::class.java.isAssignableFrom(it.type) }.forEach { field ->
            field.isAccessible = true
            val configItem = field.get(this) as? ConfigItem<*>
            val name = configItem?.name
            val value = configItem?.value
            println("$name: $value")
        }
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
    }
}

class CluesState : State<CluesStateMachine>() {
    override suspend fun CluesStateMachine.checkNext(): State<CluesStateMachine>? {
        try {
            if (PuzzlesUI.celticOpen() != -1) {
                return CelticKnotState()
            }

            if (interfaces.isOpen(SLIDING_PUZZLE_IF)) {
                return SlidingPuzzleState()
            }
            if (interfaces.isOpen(TOWERS_PUZZLE_IF)) {
                return TowerPuzzleState()
            }
            if (interfaces.isOpen(LOCKBOX_PUZZLE_IF)) {
                return LockboxPuzzleState()
            }
        } catch (ex: Exception) {
        }
        var openedScroll = inventory.firstOrNull { it.name.startsWith("Clue scroll") }
        if (openedScroll != null) {
            var clueID = openedScroll.id
        }

        return null
    }

    var hasTped = false
    var hasToSwap = false

    override suspend fun CluesStateMachine.stateLoop() {
        if (inCombat) {
            val npcTarget =
                npcs.values.firstOrNull {
                    it.tile.withinDistance(localPlayer.tile, 8) &&
                            (it.name.contains("wizard") || it.name.contains("shaman") || it.name.contains("Cabbage") || it.name.contains("wild"))
                }
            if (npcTarget != null) {
                hasTped = true
                if (localPlayer.interactionSid != npcTarget.serverIndex) {
                    npcTarget.interact("Attack")
                    delay(gaussian(921, 560))
                }
                return
            }
            if(!hasTped && !atGe) {
                IFSlot(1464, 15, 12).click(3)
                delayUntil(gaussian(5529L, 2529L)) { atGe }
                return
            }
        }

        if (localPlayer.isAniMoving) return

        if (inventory.hasItem(Regex(".*puzzle casket.*", RegexOption.IGNORE_CASE))) {
            inventory.getItem(Regex(".*puzzle casket.*", RegexOption.IGNORE_CASE))?.click("Open")
            delayUntil(gaussian(5299L, 1200L)) { PuzzlesUI.celticOpen() != -1 || PuzzlesUI.lockboxOpen() != -1 || PuzzlesUI.slidingOpen() != -1 || PuzzlesUI.towersOpen() != -1 }
            return
        }

        if (inventory.hasItem(Regex(".*puzzle scroll.*", RegexOption.IGNORE_CASE))) {
            inventory.getItem(Regex(".*puzzle scroll.*", RegexOption.IGNORE_CASE))?.click("Open")
            delayUntil(gaussian(5299L, 1200L)) { PuzzlesUI.celticOpen() != -1 || PuzzlesUI.lockboxOpen() != -1 || PuzzlesUI.slidingOpen() != -1 || PuzzlesUI.towersOpen() != -1 }
            return
        }

        if (inventory.hasItem(Regex(".*scroll box.*", RegexOption.IGNORE_CASE))) {
            inventory.getItem(Regex(".*scroll box.*", RegexOption.IGNORE_CASE))?.click("Open")
            delay(gaussian(921, 560))
            return
        }

        if (inventory.hasItem(Regex(".*puzzle box.*", RegexOption.IGNORE_CASE))) {
            inventory.getItem(Regex(".*puzzle box.*", RegexOption.IGNORE_CASE))?.click("Open")
            delayUntil(gaussian(5299L, 1200L)) { PuzzlesUI.celticOpen() != -1 || PuzzlesUI.lockboxOpen() != -1 || PuzzlesUI.slidingOpen() != -1 || PuzzlesUI.towersOpen() != -1 }
            delay(855, 320)
            return
        }
        var x = inventory.firstOrNull { it.name.startsWith("Clue scroll") }

        if (x == null) {
            var x2 = inventory.firstOrNull { it.name.startsWith("Sealed clue scroll") }
            if (x2 != null) {
                inventory.getItem(Regex(".*sealed clue scroll.*", RegexOption.IGNORE_CASE))?.click("Open")
                delay(gaussian(852, 400))
                return
            }
        } else {
            println(hasToSwap)
            if (!x.invOps.contains("Dig") || hasToSwap) {
                println("Clue doesnt have dig option")
                IFSlot(1464, 15, 1).click(2)
                delay(850, 350)
                IFSlot(1189, 19, -1).dialogueContinue()
                delayUntil(gaussian(1225L, 800L)) { dialogueOptionVisible("Yes, swap it.") }
                continueDialogueContaining("Yes, swap it.")
                delay(825, 660)
                hasTped = false
                hasToSwap = false
                return
            } else {
                if (!hasTped) {
                    IFSlot(1464, 15, 4).click(2)
                    delayUntil(gaussian(2205L, 800L)) { interfaces.isOpen(1186) }
                    if (interfaces.isOpen(1186)) {
                        IFSlot(1186, 8, -1).dialogueContinue()
                        delay(600, 200)
                    }
                    if (interfaces.isOpen(1188)) {
                        continueDialogueContaining("Yes, I am ready to teleport.")
                        delay(600, 200)
                    }
                    hasTped = true
                    waitThenDelayUntil(
                        random(1200, 1800).toLong(),
                        random(2000, 2500).toLong()
                    ) { !localPlayer.isAniMoving }
                    return
                }
                x.click("Dig")
                delay(random(600, 800))
                hasTped = false
            }
        }
    }

    override fun CluesStateMachine.onStateEvent(event: Event) {
        when (event) {
            is Chat -> {
                println(event.message)
                if (event.message.contains("You currently do not have access to"))
                    hasToSwap = true
            }
        }
    }
}

class CelticKnotState : State<CluesStateMachine>() {
    override suspend fun CluesStateMachine.checkNext(): State<CluesStateMachine>? {
        if (PuzzlesUI.celticOpen() != -1) {
            return null
        }

        return CluesState()
    }

    override suspend fun CluesStateMachine.stateLoop() {
        println("CelticKnotState.stateLoop: iface=${PuzzlesUI.celticOpen()}")
        val interfaceId = PuzzlesUI.celticOpen()
        if (interfaceId == -1) return
        val interfaceConfig = INTERFACE_CONFIGS[interfaceId] ?: return

        println("CelticKnotState.stateLoop: config=$interfaceConfig")

        for (i in interfaceConfig.rings.indices) {
            val ringDescriptor = interfaceConfig.rings[i]
            val current = varps.getVarBit(4941 + i)
            if (current == 0) continue
            val rotateCounterClockwise = current >= ringDescriptor.modulo / 2
            val clicksRequired = if (rotateCounterClockwise) ringDescriptor.modulo - current else current
            val controlComponentId =
                if (rotateCounterClockwise) ringDescriptor.counterClockwiseComponent else ringDescriptor.clockwiseComponent
            repeat(clicksRequired) { IFSlot(interfaceId, controlComponentId, -1).click(1) }
            println("CelticKnotState.stateLoop: clicked $clicksRequired times")
            delay(random(600, 800))
        }

        for (i in interfaceConfig.rings.indices) {
            if (varps.getVarBit(4941 + i) != 0) return
        }
        println("CelticKnotState.stateLoop: all zero")
        IFSlot(interfaceId, interfaceConfig.unlockId, -1).click(1)
        delay(600, 200)
        return
    }

    fun isInterfaceOpen(): Boolean = PuzzlesUI.celticOpen() != -1

    private data class RingDescriptor(val clockwiseComponent: Int, val counterClockwiseComponent: Int, val modulo: Int)
    private data class KnotInterfaceConfig(val unlockId: Int, val rings: List<RingDescriptor>)

    companion object {
        private val INTERFACE_CONFIGS: Map<Int, KnotInterfaceConfig> = mapOf(
            394 to KnotInterfaceConfig(
                unlockId = 17, rings = listOf(
                    RingDescriptor(22, 21, 28), RingDescriptor(23, 24, 14), RingDescriptor(26, 25, 14)
                )
            ), 519 to KnotInterfaceConfig(
                unlockId = 176, rings = listOf(
                    RingDescriptor(10, 11, 16), RingDescriptor(13, 12, 16), RingDescriptor(14, 15, 16)
                )
            ), 525 to KnotInterfaceConfig(
                unlockId = 214, rings = listOf(
                    RingDescriptor(11, 10, 16), RingDescriptor(12, 13, 20), RingDescriptor(15, 14, 24)
                )
            ), 526 to KnotInterfaceConfig(
                unlockId = 232, rings = listOf(
                    RingDescriptor(10, 11, 18),
                    RingDescriptor(13, 12, 16),
                    RingDescriptor(14, 15, 10),
                    RingDescriptor(17, 16, 10)
                )
            ), 529 to KnotInterfaceConfig(
                unlockId = 178, rings = listOf(
                    RingDescriptor(10, 11, 12),
                    RingDescriptor(12, 13, 12),
                    RingDescriptor(15, 14, 12),
                    RingDescriptor(17, 16, 12)
                )
            ), 1000 to KnotInterfaceConfig(
                unlockId = 190, rings = listOf(
                    RingDescriptor(10, 11, 16), RingDescriptor(13, 12, 16), RingDescriptor(14, 15, 16)
                )
            ), 1001 to KnotInterfaceConfig(
                unlockId = 176, rings = listOf(
                    RingDescriptor(11, 10, 16), RingDescriptor(12, 13, 16), RingDescriptor(15, 14, 16)
                )
            ), 1002 to KnotInterfaceConfig(
                unlockId = 17, rings = listOf(
                    RingDescriptor(21, 22, 16), RingDescriptor(23, 24, 16), RingDescriptor(26, 25, 16)
                )
            ), 1003 to KnotInterfaceConfig(
                unlockId = 204, rings = listOf(
                    RingDescriptor(10, 11, 16), RingDescriptor(12, 13, 16), RingDescriptor(15, 14, 16)
                )
            )
        )
    }
}

class SlidingPuzzleState : State<CluesStateMachine>() {
    override suspend fun CluesStateMachine.checkNext(): State<CluesStateMachine>? {
        if (interfaces.isOpen(SLIDING_PUZZLE_IF)) {
            return null
        }

        return CluesState()
    }

    override suspend fun CluesStateMachine.stateLoop() {
        println("Interface open")
        val solveStartTime = System.currentTimeMillis()

        var attemptCount = 0

        while (attemptCount <= MAX_ATTEMPTS && (System.currentTimeMillis() - solveStartTime) < MAX_SOLVING_DURATION_MS) {
            val interfaceSlots: List<InterfaceComponent>? = interfaces.get(1931)?.get(18)?.slotChildren
            val slotGraphics: MutableList<Pair<Int?, Int?>> = ArrayList()

            if (interfaceSlots == null) return

            for (component in interfaceSlots) {
                val graphicId: Int = component.graphicId
                val slotIndex: Int = component.slotId
                slotGraphics.add(Pair(graphicId, slotIndex))
            }

            if (slotGraphics.isEmpty()) {
                println("Failed to get valid component data, retrying...")
                delay(500)
                continue
            }

            slotGraphics.sortWith(Comparator.comparingInt<Pair<Int?, Int?>>(ToIntFunction { graphicSlot -> graphicSlot.first!! }))

            var blankSlotIndex = -1
            for (graphicSlot in slotGraphics) {
                println(graphicSlot.toString())

                if (graphicSlot.first == -1) {
                    blankSlotIndex = graphicSlot.second!!
                    break
                }
            }

            if (blankSlotIndex == -1) {
                println("Blank tile not found, retrying...")
                delay(500, 150)
                continue
            }

            println("Blank found at API: $blankSlotIndex")

            val numberedTileSlots: MutableList<Pair<Int?, Int?>?> = ArrayList()
            for (graphicSlot in slotGraphics) {
                if (graphicSlot.first != -1) numberedTileSlots.add(graphicSlot)
            }

            val boardEncoding = IntArray(25)
            for (i in numberedTileSlots.indices) {
                val slotIndex: Int = numberedTileSlots[i]!!.second!!
                boardEncoding[slotIndex] = i + 1
            }
            boardEncoding[blankSlotIndex] = 0
            println("Board: " + boardEncoding.contentToString())

            val pathfinder = SlidingPuzzlePathfinder()
            println("SlideSolver1")
            val solutionMoves: MutableList<String?>? = pathfinder.findSolutionPath(boardEncoding)
            println("SlideSolver2")
            if (solutionMoves == null) {
                attemptCount++
                println("Attempt " + attemptCount + "/" + MAX_ATTEMPTS + ": Solution is null, trying random move...")

                if (!performRandomValidMove(blankSlotIndex)) {
                    println("Failed to make a random move, will try again")
                }
                delay(1200, 550)
                continue
            }

            if (solutionMoves.isEmpty()) {
                IFSlot(1931, 3, -1).click()
                delay(1600, 300)
                IFSlot(848, 21, -1).dialogueContinue()
                return
            }
            println("Solution found: " + solutionMoves.size + " moves")

            var executionSuccessful = true

            var blankCursor = blankSlotIndex

            for (move in solutionMoves) {
                if (!interfaces.isOpen(SLIDING_PUZZLE_IF)) {
                    break
                }

                println("Move: " + move)
                var tileSlotToClick = -1

                when (move) {
                    "up" -> tileSlotToClick = blankCursor - 5
                    "down" -> tileSlotToClick = blankCursor + 5
                    "left" -> tileSlotToClick = blankCursor - 1
                    "right" -> tileSlotToClick = blankCursor + 1
                }

                println("Interacting with")

                if (tileSlotToClick < 0 || tileSlotToClick >= 25) {
                    println("Invalid move: " + move + " from position " + blankCursor)
                    executionSuccessful = false
                }
                println("Interacting with:" + tileSlotToClick)
                IFSlot(1931, 18, tileSlotToClick).click()

                blankCursor = tileSlotToClick
                delay(421, 110)
            }

            if (executionSuccessful) {
                IFSlot(1931, 20, -1).click()
                return
            } else {
                attemptCount++
                println("Solution execution failed, retrying (" + attemptCount + "/" + MAX_ATTEMPTS + ")")
                delay(500, 500)
            }
        }

        println(
            "Failed to solve puzzle after " + attemptCount + " attempts or timed out after " +
                    (System.currentTimeMillis() - solveStartTime) + "ms. Closing interface."
        )

        IFSlot(1931, 20, -1).click()
        delay(600, 200)
    }

    override fun CluesStateMachine.onStateEvent(event: Event) {
        when (event) {
            is Chat -> {
                if (event.message.contains("Puzzle complete!"))
                    IFSlot(1931, 20, -1).click()
            }
        }
    }

    fun performRandomValidMove(blankSlotIndex: Int): Boolean {
        val random = Random()
        val validMoves: MutableList<Int?> = ArrayList()

        if (blankSlotIndex >= 5) {
            validMoves.add(blankSlotIndex - 5)
        }
        if (blankSlotIndex < 20) {
            validMoves.add(blankSlotIndex + 5)
        }
        if (blankSlotIndex % 5 != 0) {
            validMoves.add(blankSlotIndex - 1)
        }
        if (blankSlotIndex % 5 != 4) {
            validMoves.add(blankSlotIndex + 1)
        }

        if (validMoves.isEmpty()) {
            return false
        }

        val tileToClick: Int = validMoves[random.nextInt(validMoves.size)]!!

        IFSlot(1931, 18, tileToClick).click()

        return true
    }

    val MAX_ATTEMPTS: Int = 5

    val MAX_SOLVING_DURATION_MS: Long = 60000
}

private class SlidingPuzzlePathfinder {
    private class SearchState(
        board: IntArray,
        private val emptyPos: Int,
        private val costSoFar: Int,
        val parent: SearchState?,
        val move: String?
    ) : Comparable<SearchState> {
        val board: IntArray

        init {
            this.board = board.clone()
        }

        override fun compareTo(other: SearchState): Int {
            return Integer.compare(this.totalCost, other.totalCost)
        }

        val totalCost: Int
            get() = costSoFar + this.heuristic

        val heuristic: Int
            get() {
                var h = 0
                for (i in 0..24) {
                    if (board[i] != 0) {
                        val value = board[i]
                        val goalRow = (value - 1) / 5
                        val goalCol = (value - 1) % 5
                        val currentRow = i / 5
                        val currentCol = i % 5
                        h += abs(currentRow - goalRow) + abs(currentCol - goalCol)
                    }
                }
                return h
            }

        val neighborStates: MutableList<SearchState>
            get() {
                val neighbors: MutableList<SearchState> = ArrayList()
                val directions = intArrayOf(-5, 5, -1, 1)
                val moves = arrayOf<String?>("up", "down", "left", "right")

                for (i in 0..3) {
                    val newPos = emptyPos + directions[i]
                    if (newPos >= 0 && newPos < 25) {
                        if (i == 2 && emptyPos % 5 == 0) continue
                        if (i == 3 && emptyPos % 5 == 4) continue

                        val newBoard = board.clone()
                        newBoard[emptyPos] = newBoard[newPos]
                        newBoard[newPos] = 0
                        neighbors.add(SearchState(newBoard, newPos, costSoFar + 1, this, moves[i]))
                    }
                }
                return neighbors
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SearchState) return false
            return board.contentEquals(other.board)
        }

        override fun hashCode(): Int {
            return board.contentHashCode()
        }

        val isGoal: Boolean
            get() {
                for (i in 0..23) {
                    if (board[i] != i + 1) return false
                }
                return board[24] == 0
            }

    }

    fun findSolutionPath(initialBoard: IntArray): MutableList<String?>? {
        if (!isBoardSolvable(initialBoard)) {
            println("NotSolvable")
            return null
        }
        val queue = PriorityQueue<SearchState>()
        val visited: MutableSet<String?> = HashSet()
        val start = SearchState(initialBoard, findBlankIndex(initialBoard), 0, null, null)
        queue.add(start)
        visited.add(initialBoard.contentToString())

        val searchStartTime = System.currentTimeMillis()

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() - searchStartTime > 3000) {
                return mutableListOf()
            }

            val current = queue.poll()
            if (current.isGoal) {
                val moves: MutableList<String?> = ArrayList()
                var node: SearchState? = current
                while (node?.parent != null) {
                    moves.add(node.move)
                    node = node.parent
                }
                Collections.reverse(moves)
                return moves
            }
            for (neighbor in current.neighborStates) {
                val boardStr = neighbor.board.contentToString()
                if (!visited.contains(boardStr)) {
                    visited.add(boardStr)
                    queue.add(neighbor)
                }
            }

        }
        return null
    }

    fun findBlankIndex(board: IntArray): Int {
        for (i in 0..24) {
            if (board[i] == 0) return i
        }
        return -1
    }

    fun isBoardSolvable(board: IntArray): Boolean {
        var inversions = 0
        val flattened = board.clone()
        for (i in 0..23) {
            for (j in i + 1..24) {
                if (flattened[i] != 0 && flattened[j] != 0 && flattened[i] > flattened[j]) {
                    inversions++
                }
            }
        }
        val blankIndex = findBlankIndex(board)
        val blankRow = blankIndex / 5
        val distanceFromBottom = 4 - blankRow
        return (inversions + distanceFromBottom) % 2 == 0
    }
}

class TowerPuzzleState : State<CluesStateMachine>() {
    override suspend fun CluesStateMachine.checkNext(): State<CluesStateMachine>? {
        if (isSolved) return CluesState()

        if (interfaces.isOpen(TOWERS_PUZZLE_IF)) {
            return null
        }

        return CluesState()
    }

    private var visibilityClues: Array<IntArray>? = null
    private var solutionHeights: IntArray? = null
    var isSolved = false

    override suspend fun CluesStateMachine.stateLoop() {
        val puzzleInterface = PuzzlesUI.towersOpen()
        if (puzzleInterface == -1) return
        if (visibilityClues == null || solutionHeights == null) {
            visibilityClues = readVisibilityClues()
            solutionHeights = SkyscraperSolver(visibilityClues!!).solvePuzzle()
            return
        }

        val solutionValues = solutionHeights ?: return
        var allSet = true
        for (i in 0 until 25) {
            val currentHeight = varps.getVarBit(39675 + i)
            if (currentHeight != solutionValues[i]) {
                println()
                allSet = false
                IFSlot(puzzleInterface, 7, i).click(solutionValues[i] + 1)
                delay(100)
            }
        }
        if (!allSet) return

        IFSlot(puzzleInterface, 61, -1).click(1)
        return
    }

    private fun readVisibilityClues(): Array<IntArray> {
        return Array(4) { r -> IntArray(5) { c -> varps.getVarBit(39747 + r * 5 + c) } }
    }

    override fun CluesStateMachine.onStateEvent(event: Event) {
        when (event) {
            is Chat -> {
                if (event.message.contains("You solve the puzzle."))
                    isSolved = true
            }
        }
    }
}

private class SkyscraperSolver(private val clues: Array<IntArray>) {
    fun solvePuzzle(): IntArray {
        val grid = SolverGrid(5)
        for (x in 0 until 5) {
            val slots = IntArray(5) { y -> y + 5 * x }
            grid.constraints.add(LineConstraint(grid, slots, clues[1][x], clues[3][x], "row$x"))
        }
        for (x in 0 until 5) {
            val slots = IntArray(5) { y -> x + 5 * y }
            grid.constraints.add(LineConstraint(grid, slots, clues[0][x], clues[2][x], "col$x"))
        }
        grid.solve()
        val out = IntArray(grid.size * grid.size)
        val sol = grid.validSolutions.firstOrNull() ?: IntArray(out.size) { -1 }
        for (i in out.indices) out[i] = sol[i] + 1
        return out
    }

    private class SolverGrid(val size: Int) {
        var resolvedValues = IntArray(size * size) { -1 }
        var candidateOptions = Array(size * size) { IntArray(size) { it } }
        val constraints = mutableListOf<LineConstraint>()
        private val stateStack = mutableListOf<GridStateSnapshot>()
        val visitedStates = mutableListOf<IntArray>()
        val validSolutions = mutableListOf<IntArray>()

        fun isFullyResolved(): Boolean = resolvedValues.none { it == -1 }

        fun solve() {
            search()
            validSolutions.clear()
            for (state in visitedStates) if (state.indexOf(-1) == -1) validSolutions += state
        }

        fun search() {
            propagateConstraints()
            if (recordStateIfNew() && !isFullyResolved()) {
                for (a in 0 until size * size) {
                    if (candidateOptions[a].size == 1) continue
                    for (b in candidateOptions[a].indices) {
                        pushStateSnapshot()
                        try {
                            candidateOptions[a] = intArrayOf(candidateOptions[a][b])
                            assignValue(a, candidateOptions[a][0])
                            search()
                        } catch (_: Throwable) {
                        }
                        popStateSnapshot()
                    }
                }
            }
        }

        fun propagateConstraints() {
            var go = true
            while (go) {
                go = false
                for (constraint in constraints) {
                    val removedCount = constraint.pruneInvalidOptions()
                    if (!constraint.isValidated && removedCount > 0) go = true
                }
            }
        }

        fun recordStateIfNew(): Boolean {
            for (state in visitedStates) {
                var same = true
                for (i in resolvedValues.indices) if (state[i] != resolvedValues[i]) {
                    same = false; break
                }
                if (same) return false
            }
            visitedStates += resolvedValues.copyOf()
            return true
        }

        fun pushStateSnapshot() {
            stateStack += GridStateSnapshot(resolvedValues, candidateOptions)
        }

        fun popStateSnapshot() {
            val snapshot = stateStack.removeLast(); resolvedValues = snapshot.resolvedValues; candidateOptions =
                snapshot.candidateOptions
        }

        fun assignValue(slot: Int, value: Int) {
            resolvedValues[slot] = value
            for (constraint in constraints) {
                if (constraint.slots.indexOf(slot) == -1) continue
                var done = true
                for (down in 0 until size) {
                    val constraintSlot = constraint.slots[down]
                    val existingValue = resolvedValues[constraintSlot]
                    if (existingValue == -1) done = false
                    if (constraintSlot == slot) continue
                    val candidateIndex = candidateOptions[constraintSlot].indexOf(value)
                    if (existingValue == value) throw RuntimeException("invalidgrid")
                    if (candidateIndex != -1) {
                        candidateOptions[constraintSlot] =
                            candidateOptions[constraintSlot].filter { it != value }.toIntArray()
                        markConstraintsDirtyForSlot(constraintSlot)
                        if (candidateOptions[constraintSlot].size == 1) assignValue(
                            constraintSlot,
                            candidateOptions[constraintSlot][0]
                        )
                    }
                }
                if (!done) continue
                var cslot = 0
                var max = -1
                for (b in 0 until size) {
                    val v = resolvedValues[constraint.slots[b]]
                    if (v > max) {
                        max = v; cslot++
                    }
                }
                max = -1
                var down = 0
                for (b in size - 1 downTo 0) {
                    val v = resolvedValues[constraint.slots[b]]
                    if (v > max) {
                        max = v; down++
                    }
                }
                if (cslot == constraint.up && down == constraint.down) continue
                throw RuntimeException("invalidgrid")
            }
        }

        fun markConstraintsDirtyForSlot(index: Int) {
            for (constraint in constraints) if (constraint.slots.indexOf(index) != -1) constraint.isValidated = false
        }
    }

    private class LineConstraint(
        private val grid: SolverGrid,
        val slots: IntArray,
        val up: Int,
        val down: Int,
        val name: String
    ) {
        private val workingValues = IntArray(grid.size) { -1 }
        private val availableNumbers = BooleanArray(grid.size) { true }
        var isValidated = false
        private var removedCount = 0

        private fun evaluateVisibility(
            index: Int,
            workingValues: IntArray,
            availableNumbers: BooleanArray,
            fromTop: Boolean,
            target: Int
        ): Boolean {
            if (index == grid.size) {
                var v = 0
                var slot = -1
                if (fromTop) {
                    for (b in 0 until grid.size) if (workingValues[b] > slot) {
                        slot = workingValues[b]; v++
                    }
                } else {
                    for (b in grid.size - 1 downTo 0) if (workingValues[b] > slot) {
                        slot = workingValues[b]; v++
                    }
                }
                return if (target == 0) true else v == target
            }
            if (workingValues[index] != -1) return evaluateVisibility(
                index + 1,
                workingValues,
                availableNumbers,
                fromTop,
                target
            )
            val slot = slots[index]
            val value = grid.resolvedValues[slot]
            if (value != -1) {
                if (!availableNumbers[value]) return false
                workingValues[index] = value
                availableNumbers[value] = false
                val r = evaluateVisibility(index + 1, workingValues, availableNumbers, fromTop, target)
                workingValues[index] = -1
                availableNumbers[value] = true
                return r
            }
            for (candidate in 0 until grid.size) {
                if (!availableNumbers[candidate] || grid.candidateOptions[slot].indexOf(candidate) == -1) continue
                availableNumbers[candidate] = false
                workingValues[index] = candidate
                val r = evaluateVisibility(index + 1, workingValues, availableNumbers, fromTop, target)
                workingValues[index] = -1
                availableNumbers[candidate] = true
                if (r) return true
            }
            return false
        }

        fun pruneInvalidOptions(): Int {
            for (a in 0 until grid.size) {
                val index = slots[a]
                if (grid.resolvedValues[index] != -1) continue
                removedCount = 0
                for (optionIndex in grid.candidateOptions[index].size - 1 downTo 0) {
                    val candidate = grid.candidateOptions[index][optionIndex]
                    workingValues[a] = candidate
                    availableNumbers[candidate] = false
                    val okUp = evaluateVisibility(0, workingValues, availableNumbers, true, up)
                    val okDown = evaluateVisibility(0, workingValues, availableNumbers, false, down)
                    if (!okUp || !okDown) {
                        grid.candidateOptions[index] =
                            grid.candidateOptions[index].filter { it != candidate }.toIntArray()
                        removedCount++
                    }
                    availableNumbers[candidate] = true
                    workingValues[a] = -1
                }
                if (grid.candidateOptions[index].size == 1) grid.assignValue(index, grid.candidateOptions[index][0])
                if (removedCount > 0) grid.markConstraintsDirtyForSlot(index)
            }
            isValidated = true
            return removedCount
        }
    }

    private class GridStateSnapshot(fromResolvedValues: IntArray, fromCandidateOptions: Array<IntArray>) {
        val resolvedValues: IntArray = fromResolvedValues.copyOf()
        val candidateOptions: Array<IntArray> =
            Array(fromCandidateOptions.size) { idx -> fromCandidateOptions[idx].copyOf() }
    }
}

class LockboxPuzzleState : State<CluesStateMachine>() {
    override suspend fun CluesStateMachine.checkNext(): State<CluesStateMachine>? {
        if (interfaces.isOpen(LOCKBOX_PUZZLE_IF)) {
            return null
        }

        return CluesState()
    }

    private var tileGrid: Array<IntArray>? = null
    private var moveSequence: MutableList<Int>? = null
    private var nextMoveIndex = 0

    override suspend fun CluesStateMachine.stateLoop() {
        println("LockBoxPuzzle")

        val lockboxInterface = PuzzlesUI.lockboxOpen()
        if (lockboxInterface == -1) return

        if (tileGrid == null || moveSequence == null) {
            tileGrid = readTileGrid()
            moveSequence = findSolutionMoves(tileGrid!!)
            nextMoveIndex = 0
            delay(200)
            return
        }

        val moves = moveSequence ?: return
        if (nextMoveIndex >= moves.size) {
            IFSlot(lockboxInterface, 34, -1).click(1)
            delay(200)
            return
        }

        val componentId = moves[nextMoveIndex]
        IFSlot(lockboxInterface, componentId, -1).click(1)
        nextMoveIndex++
        delay(200)
        return
    }

    private fun readTileGrid(): Array<IntArray> {
        // Two varcs (6371, 6372) pack 25 tiles as 2-bit values
        val grid = Array(5) { IntArray(5) }
        val varc0 = varcs.getVar(6371)
        val varc1 = varcs.getVar(6372)
        for (y in 0 until 5) {
            for (x in 0 until 5) {
                val index = y * 5 + x
                val value = if (index >= 16) varc1 else varc0
                val startBit = if (index >= 16) (index - 16) * 2 else index * 2
                val mask = 0b11
                grid[y][x] = (value ushr startBit) and mask
            }
        }
        return grid
    }

    private fun findSolutionMoves(grid: Array<IntArray>, target: Int = 0): MutableList<Int> {
        val clone = Array(5) { IntArray(5) }
        val moves = Array(5) { IntArray(5) }
        for (y in 0 until 5) for (x in 0 until 5) {
            clone[y][x] = grid[y][x]; moves[y][x] = 0
        }
        propagateTopRow(clone, moves, target)
        val aim = IntArray(5) { x -> clone[4][x] }
        val bot = arrayOf(intArrayOf(0, 1, 0, 0, 0), intArrayOf(1, 1, 0, 0, 0))
        val inv = arrayOf(intArrayOf(1, 0, 2, 0, 1), intArrayOf(0, 1, 0, 1, 0))
        for (m in 0 until 2) {
            while (aim[m] > 0) {
                for (y in 0 until 5) {
                    aim[y] = (aim[y] + inv[m][y]) % 3
                    repeat(bot[m][y]) { applyToggle(clone, moves, y, 0) }
                }
            }
        }
        if (aim[2] + aim[3] + aim[4] > 0) return mutableListOf()
        propagateTopRow(clone, moves, target)
        val out = mutableListOf<Int>()
        for (y in 0 until 5) for (x in 0 until 5) repeat(moves[y][x]) { out += (y * 5 + x + 1) }
        return out
    }

    private fun propagateTopRow(clone: Array<IntArray>, moves: Array<IntArray>, target: Int) {
        for (y in 1 until 5) {
            for (x in 0 until 5) {
                while (clone[y - 1][x] != target) applyToggle(clone, moves, x, y)
            }
        }
    }

    private fun applyToggle(clone: Array<IntArray>, moves: Array<IntArray>, x: Int, y: Int) {
        toggleCell(clone, x, y)
        toggleCell(clone, x + 1, y)
        toggleCell(clone, x - 1, y)
        toggleCell(clone, x, y + 1)
        toggleCell(clone, x, y - 1)
        moves[y][x] = (moves[y][x] + 1) % 3
    }

    private fun toggleCell(clone: Array<IntArray>, x: Int, y: Int) {
        if (x in 0..4 && y in 0..4) clone[y][x] = (clone[y][x] + 1) % 3
    }

    override fun CluesStateMachine.onStateEvent(event: Event) {
        when (event) {
            is Chat -> {
            }
        }
    }
}

object PuzzlesUI {
    const val SLIDING_PUZZLE_IF = 1931
    const val LOCKBOX_PUZZLE_IF = 1933
    const val TOWERS_PUZZLE_IF = 1934

    val CELTIC_KNOT_IFS = intArrayOf(394, 519, 525, 526, 529, 1000, 1001, 1002, 1003)

    fun slidingOpen(): Int = if (interfaces.isOpen(SLIDING_PUZZLE_IF)) SLIDING_PUZZLE_IF else -1
    fun lockboxOpen(): Int = if (interfaces.isOpen(LOCKBOX_PUZZLE_IF)) LOCKBOX_PUZZLE_IF else -1
    fun towersOpen(): Int = if (interfaces.isOpen(TOWERS_PUZZLE_IF)) TOWERS_PUZZLE_IF else -1
    fun celticOpen(): Int = CELTIC_KNOT_IFS.firstOrNull { interfaces.isOpen(it) } ?: -1
}

