package com.projectx.puzzle.invention

import kotlin.math.abs

const val TRACK_SLOTS = 5

enum class SlotHint { NEUTRAL, LIKELY, LOCKED, SWAP_FIRST, SWAP_SECOND }

/** What to do next with the current arrangement. */
class Advice(
    val solved: Boolean,
    val swapFirst: Int,
    val swapSecond: Int,
    val slotHints: Array<SlotHint>,
    val believedBest: IntArray,
)

/**
 * Solver for the Invention discovery track.
 *
 * The server scores an arrangement with one number — `invent_discovery_score`, 0 for "Perfect" rising
 * in twos to 12 — and that score is *exactly additive* over the twenty-five (slot, module) pairs: every
 * arrangement tried is one linear equation in twenty-five unknown affinities, verified against captured
 * sessions to machine precision. So the affinities are fitted rather than guessed at, and the lowest
 * scoring arrangement the fit has yet to see becomes the next probe. Ridge regularisation stands in for
 * the equations still missing while the fit is under-determined.
 *
 * Modules are ids 1..5; an arrangement is a permutation indexed by track slot 0..4.
 */
class InventionDiscoveryModel {

    private val penalties = LinkedHashMap<Long, Int>()
    private val arrangements = LinkedHashMap<Long, IntArray>()

    val observationCount get() = penalties.size

    var bestPenalty = Int.MAX_VALUE
        private set

    var bestArrangement: IntArray? = null
        private set

    val solved get() = bestPenalty == 0

    fun reset() {
        penalties.clear()
        arrangements.clear()
        bestPenalty = Int.MAX_VALUE
        bestArrangement = null
    }

    fun record(perm: IntArray, penalty: Int) {
        if (!isArrangement(perm) || penalty < 0) return
        val key = keyOf(perm)
        penalties[key] = penalty
        arrangements[key] = perm.copyOf()
        if (penalty < bestPenalty) {
            bestPenalty = penalty
            bestArrangement = perm.copyOf()
        }
    }

    fun penaltyOf(perm: IntArray): Int? = penalties[keyOf(perm)]

    fun advise(current: IntArray): Advice {
        val hints = Array(TRACK_SLOTS) { SlotHint.NEUTRAL }
        if (!isArrangement(current)) return Advice(false, -1, -1, hints, current.copyOf())
        if (penaltyOf(current) == 0) return Advice(true, -1, -1, hints, current.copyOf())

        val affinity = fit()
        val believedBest = pickBest(ALL_ARRANGEMENTS, current, affinity)
        val target = if (!penalties.containsKey(keyOf(believedBest))) believedBest else {
            val untried = ALL_ARRANGEMENTS.filter { !penalties.containsKey(keyOf(it)) }
            if (untried.isEmpty()) believedBest else pickBest(untried, current, affinity)
        }

        for (slot in 0 until TRACK_SLOTS) {
            if (current[slot] != believedBest[slot]) continue
            hints[slot] = if (confident(slot, current[slot], affinity)) SlotHint.LOCKED else SlotHint.LIKELY
        }

        val (first, second) = swapToward(current, target)
        if (first >= 0) {
            hints[first] = SlotHint.SWAP_FIRST
            if (second >= 0) hints[second] = SlotHint.SWAP_SECOND
        }
        return Advice(false, first, second, hints, believedBest)
    }

    /** The one transposition that moves [current] a step closer to [target]. */
    fun swapToward(current: IntArray, target: IntArray): Pair<Int, Int> {
        if (!isArrangement(current) || !isArrangement(target)) return -1 to -1
        val first = (0 until TRACK_SLOTS).firstOrNull { current[it] != target[it] } ?: return -1 to -1
        val second = (0 until TRACK_SLOTS).firstOrNull { current[it] == target[first] } ?: return -1 to -1
        return first to second
    }

    /** Least-squares affinity per (slot, module), ridged so an under-determined fit still resolves. */
    private fun fit(): DoubleArray {
        val size = TRACK_SLOTS * TRACK_SLOTS
        val normal = Array(size) { DoubleArray(size) }
        val rhs = DoubleArray(size)
        for ((key, penalty) in penalties) {
            val perm = arrangements.getValue(key)
            val cells = IntArray(TRACK_SLOTS) { it * TRACK_SLOTS + perm[it] - 1 }
            for (cell in cells) {
                rhs[cell] += penalty.toDouble()
                for (other in cells) normal[cell][other] += 1.0
            }
        }
        for (i in 0 until size) normal[i][i] += RIDGE
        return solve(normal, rhs)
    }

    private fun predicted(perm: IntArray, affinity: DoubleArray): Double {
        var total = 0.0
        for (slot in 0 until TRACK_SLOTS) total += affinity[slot * TRACK_SLOTS + perm[slot] - 1]
        return total
    }

    /** Lowest predicted penalty; ties go to whichever needs the fewest swaps from [reference]. */
    private fun pickBest(candidates: List<IntArray>, reference: IntArray, affinity: DoubleArray): IntArray {
        var best = candidates.first()
        var bestScore = predicted(best, affinity)
        var bestMismatch = mismatch(best, reference)
        for (perm in candidates) {
            val score = predicted(perm, affinity)
            val miss = mismatch(perm, reference)
            if (score < bestScore - EPSILON || (score < bestScore + EPSILON && miss < bestMismatch)) {
                best = perm
                bestScore = score
                bestMismatch = miss
            }
        }
        return best
    }

    private fun confident(slot: Int, module: Int, affinity: DoubleArray): Boolean {
        if (penalties.size < TRACK_SLOTS) return false
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (cell in affinity) {
            low = minOf(low, cell)
            high = maxOf(high, cell)
        }
        val range = (high - low).coerceAtLeast(EPSILON)
        val rival = (1..TRACK_SLOTS).filter { it != module }.minOf { affinity[slot * TRACK_SLOTS + it - 1] }
        return (rival - affinity[slot * TRACK_SLOTS + module - 1]) / range > CONFIDENCE_MARGIN
    }

    companion object {
        private const val RIDGE = 1e-3
        private const val EPSILON = 1e-9
        private const val CONFIDENCE_MARGIN = 0.12

        val ALL_ARRANGEMENTS: List<IntArray> = permutations(intArrayOf(1, 2, 3, 4, 5))

        fun isArrangement(perm: IntArray): Boolean {
            if (perm.size != TRACK_SLOTS) return false
            val seen = BooleanArray(TRACK_SLOTS + 1)
            for (module in perm) {
                if (module !in 1..TRACK_SLOTS || seen[module]) return false
                seen[module] = true
            }
            return true
        }

        private fun keyOf(perm: IntArray): Long = perm.fold(0L) { acc, module -> acc * 6 + module }

        private fun mismatch(a: IntArray, b: IntArray): Int {
            var diff = 0
            for (i in a.indices) if (a[i] != b[i]) diff++
            return diff
        }

        /** Gauss-Jordan with partial pivoting; the ridge on the diagonal keeps it non-singular. */
        private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
            val n = rhs.size
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) if (abs(matrix[row][col]) > abs(matrix[pivot][col])) pivot = row
                matrix[col] = matrix[pivot].also { matrix[pivot] = matrix[col] }
                rhs[col] = rhs[pivot].also { rhs[pivot] = rhs[col] }
                val diagonal = matrix[col][col]
                if (abs(diagonal) < EPSILON) continue
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = matrix[row][col] / diagonal
                    if (factor == 0.0) continue
                    for (k in col until n) matrix[row][k] -= factor * matrix[col][k]
                    rhs[row] -= factor * rhs[col]
                }
            }
            return DoubleArray(n) { if (abs(matrix[it][it]) < EPSILON) 0.0 else rhs[it] / matrix[it][it] }
        }

        private fun permutations(values: IntArray): List<IntArray> {
            val out = ArrayList<IntArray>()
            val used = BooleanArray(values.size)
            val current = IntArray(values.size)
            fun recurse(depth: Int) {
                if (depth == values.size) { out.add(current.copyOf()); return }
                for (i in values.indices) {
                    if (used[i]) continue
                    used[i] = true
                    current[depth] = values[i]
                    recurse(depth + 1)
                    used[i] = false
                }
            }
            recurse(0)
            return out
        }
    }
}
