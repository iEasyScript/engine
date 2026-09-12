package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.inventory
import com.projectx.script.api.varps

/**
 * Solver for Oaknock's Machine puzzle in *The Eyes of Glouphrie* (step 22).
 *
 * The machine shows up to four "numbers". Each must be matched by submitting an
 * exact number of discs whose **values sum** to that number - 1 disc for the
 * tutorial and first number, 2 for the second, 3 for the third (confirmed by CS2
 * scripts 1324-1327, which set 1/1/2/3 disc objects respectively). A disc's value
 * is `colour × shape`; only the value matters to the machine, so a "12" can be a
 * Yellow square or a Green triangle interchangeably.
 *
 * In-game (quest step 21) you "use the exchanger to get a disc equal to your
 * number" and "talk to Brimstail" for more discs. So when the inventory can't
 * cover a number, the solver doesn't dead-end on "you lack the discs" - it names
 * the spare disc to feed the exchanger and what to turn it into.
 *
 * Per tick: read every (target, current) pair plus the disc inventory, then for
 * each unfinished number emit a [QuestAction.TextHint] giving the exact disc set
 * to place (preferring discs already owned) and any exchanges needed for the rest.
 */
object EyesOfGlouphriePuzzleSolver : QuestStepSolver {
    override val id: String = "eyes-of-glouphrie.oaknocks-puzzle"

    /**
     * Per-slot (target varbit, current varbit, required disc count). The count is
     * intrinsic to the slot - the machine has that many fixed disc positions, so a
     * solution must use *exactly* that many discs. Phase 1 uses only the tutorial
     * slot; phase 2 shows the three numbered slots (1/2/3 discs) simultaneously.
     */
    private data class SlotDef(val target: Int, val current: Int, val discCount: Int, val label: String)
    private val SLOT_DEFS = listOf(
        SlotDef(target = 12826, current = 12833, discCount = 1, label = "Phase 1 (tutorial)"),
        SlotDef(target = 12827, current = 12834, discCount = 1, label = "Slot 1"),
        SlotDef(target = 12828, current = 12835, discCount = 2, label = "Slot 2"),
        SlotDef(target = 12829, current = 12836, discCount = 3, label = "Slot 3"),
    )

    private enum class Colour(val v: Int, val short: Char) {
        Red(1, 'R'), Orange(2, 'O'), Yellow(3, 'Y'), Green(4, 'G'),
        Blue(5, 'B'), Indigo(6, 'I'), Violet(7, 'V'),
    }
    private enum class Shape(val v: Int, val short: Char) {
        Circle(1, 'C'), Triangle(3, 'T'), Square(4, 'S'), Pentagon(5, 'P'),
    }

    private data class Disc(val colour: Colour, val shape: Shape) {
        val value: Int get() = colour.v * shape.v
        val itemId: Int get() = 9597 + (colour.ordinal * 4) + shape.ordinal
        val short: String get() = "${colour.short}${shape.short}"
        val long: String get() = "${colour.name} ${shape.name.lowercase()}"
    }

    private val ALL_DISCS: List<Disc> =
        Colour.entries.flatMap { c -> Shape.entries.map { Disc(c, it) } }
    private val VALUES_ASC: List<Int> = ALL_DISCS.map { it.value }.distinct().sorted()
    private val DISCS_BY_VALUE: Map<Int, List<Disc>> = ALL_DISCS.groupBy { it.value }

    /** Canonical disc to name when telling the player to produce a given value. */
    private fun representativeDisc(value: Int): Disc =
        DISCS_BY_VALUE.getValue(value).minByOrNull { it.itemId }!!

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        val active = readActiveSlots()
        if (active.isEmpty()) return staticReference()
        return QuestStepSolver.Result(overlayActions = buildHints(active, readInventory()))
    }

    private data class Slot(val label: String, val target: Int, val current: Int, val discCount: Int) {
        val complete: Boolean get() = target > 0 && current == target
    }

    /**
     * Phase 2 (the numbered slots) takes precedence - once any of them is live we
     * ignore the tutorial slot, whose varbit (12826) doesn't auto-clear after the
     * player finishes round 1 and would otherwise look active forever.
     */
    private fun readActiveSlots(): List<Slot> {
        val all = SLOT_DEFS.map { def ->
            Slot(
                label = def.label,
                target = safeVarbit(def.target) ?: 0,
                current = safeVarbit(def.current) ?: 0,
                discCount = def.discCount,
            )
        }
        val phase2 = all.drop(1).filter { it.target > 0 }
        return phase2.ifEmpty { all.take(1).filter { it.target > 0 } }
    }

    private fun safeVarbit(id: Int): Int? = try { varps.getVarBit(id) } catch (_: Throwable) { null }

    private fun readInventory(): MutableMap<Disc, Int> {
        val out = mutableMapOf<Disc, Int>()
        for (d in ALL_DISCS) {
            val n = try { inventory.count(d.itemId) } catch (_: Throwable) { 0 }
            if (n > 0) out[d] = n
        }
        return out
    }

    private fun buildHints(slots: List<Slot>, working: MutableMap<Disc, Int>): List<QuestAction> {
        val hints = mutableListOf<QuestAction>()
        hints += QuestAction.TextHint(formatInventory(working))

        // Solve the most constrained slots first so a rare high-value disc isn't
        // spent on a number that something cheaper could have covered. The slots
        // needing an exchange are deferred so they draw their "give" discs from the
        // genuine surplus left after the directly-solvable slots reserve theirs.
        val byLabel = LinkedHashMap<String, QuestAction>()
        val deferred = mutableListOf<Slot>()
        for (slot in slots.sortedByDescending { it.discCount }) {
            if (slot.complete) {
                byLabel[slot.label] = QuestAction.TextHint("${slot.label} [x] done (sum=${slot.current} = ${slot.target})")
                continue
            }
            val combo = bestCombo(slot.target, slot.discCount, valueCounts(working))
            when {
                combo == null -> byLabel[slot.label] = QuestAction.TextHint(
                    "${slotHeader(slot)} - no ${slot.discCount}-disc combination sums to ${slot.target}. Re-check the displayed number."
                )
                combo.needed.isEmpty() -> {
                    val discs = combo.values.map { takeOwnedDisc(it, working)!! }
                    byLabel[slot.label] = placeHint(slot, discs)
                }
                else -> deferred += slot
            }
        }
        for (slot in deferred) byLabel[slot.label] = exchangeHint(slot, working)

        for (slot in slots) byLabel[slot.label]?.let(hints::add)
        return hints
    }

    private fun placeHint(slot: Slot, discs: List<Disc>): QuestAction = QuestAction.TextHint(
        "${slotHeader(slot)} -> Place ${plural(slot.discCount)}: " +
            discs.joinToString(" + ") { discLabel(it) } + "  (sum=${slot.target})"
    )

    private fun exchangeHint(slot: Slot, working: MutableMap<Disc, Int>): QuestAction {
        val combo = bestCombo(slot.target, slot.discCount, valueCounts(working))
            ?: return QuestAction.TextHint(
                "${slotHeader(slot)} - no ${slot.discCount}-disc combination sums to ${slot.target}. Re-check the displayed number."
            )
        val kept = combo.kept.map { takeOwnedDisc(it, working)!! }
        val give = takeSpares(working, combo.needed.size)

        val exchanges = mutableListOf<String>()
        val obtains = mutableListOf<String>()
        combo.needed.forEachIndexed { i, value ->
            val target = representativeDisc(value)
            if (i < give.size) exchanges += "${discLabel(give[i])} -> ${discLabel(target)}"
            else obtains += discLabel(target)
        }

        val sb = StringBuilder("${slotHeader(slot)} -> need ${plural(slot.discCount)} summing to ${slot.target}.")
        if (kept.isNotEmpty()) sb.append("  Place owned: ${kept.joinToString(", ") { discLabel(it) }}.")
        if (exchanges.isNotEmpty()) sb.append("  Exchange (Oaknock's exchanger): ${exchanges.joinToString(", ")}.")
        if (obtains.isNotEmpty()) sb.append("  Get more discs from Brimstail, then exchange to: ${obtains.joinToString(", ")}.")
        return QuestAction.TextHint(sb.toString())
    }

    private fun slotHeader(slot: Slot): String {
        val base = "${slot.label} (${plural(slot.discCount)}): target ${slot.target}"
        return if (slot.current == 0) base
        else "$base  [reads ${slot.current} - ${if (slot.current > slot.target) "too high" else "too low"}, replace discs]"
    }

    /** A chosen multiset of disc values split into the ones already owned ([kept]) and the rest ([needed]). */
    private data class Combo(val values: List<Int>, val kept: List<Int>, val needed: List<Int>)

    /**
     * Best multiset of exactly [count] disc values summing to [target]: maximise
     * the count drawn from [owned] (fewest exchanges), then prefer cheaper discs to
     * obtain. Null when no [count]-disc combination reaches [target].
     */
    private fun bestCombo(target: Int, count: Int, owned: Map<Int, Int>): Combo? {
        val combos = enumerateValueCombos(target, count)
        if (combos.isEmpty()) return null
        return combos.map { scoreCombo(it, owned) }.minWith(
            compareByDescending<Combo> { it.kept.size }
                .thenBy { it.needed.sum() }
                .thenBy { it.values.joinToString(",") }
        )
    }

    private fun scoreCombo(values: List<Int>, owned: Map<Int, Int>): Combo {
        val avail = HashMap(owned)
        val kept = mutableListOf<Int>()
        val needed = mutableListOf<Int>()
        for (v in values) {
            val have = avail[v] ?: 0
            if (have > 0) { avail[v] = have - 1; kept += v } else needed += v
        }
        return Combo(values, kept, needed)
    }

    /** Every non-decreasing multiset of exactly [count] disc values summing to [target]. */
    private fun enumerateValueCombos(target: Int, count: Int): List<List<Int>> {
        if (count <= 0 || target <= 0) return emptyList()
        val out = mutableListOf<List<Int>>()
        val pick = IntArray(count)
        fun search(slot: Int, remaining: Int, startAt: Int) {
            if (slot == count) {
                if (remaining == 0) out += pick.toList()
                return
            }
            for (i in startAt until VALUES_ASC.size) {
                val v = VALUES_ASC[i]
                if (v > remaining) break
                pick[slot] = v
                search(slot + 1, remaining - v, i)
            }
        }
        search(0, target, 0)
        return out
    }

    private fun valueCounts(inv: Map<Disc, Int>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for ((d, n) in inv) out[d.value] = (out[d.value] ?: 0) + n
        return out
    }

    /** Remove and return one owned disc of [value]; null if none remain. */
    private fun takeOwnedDisc(value: Int, working: MutableMap<Disc, Int>): Disc? {
        val disc = working.entries
            .filter { it.key.value == value && it.value > 0 }
            .minByOrNull { it.key.itemId }?.key ?: return null
        decrement(working, disc)
        return disc
    }

    /** Remove and return up to [n] lowest-value spare discs to feed the exchanger. */
    private fun takeSpares(working: MutableMap<Disc, Int>, n: Int): List<Disc> {
        if (n <= 0) return emptyList()
        val flat = working.entries
            .flatMap { (d, c) -> List(c) { d } }
            .sortedWith(compareBy({ it.value }, { it.itemId }))
            .take(n)
        for (d in flat) decrement(working, d)
        return flat
    }

    private fun decrement(working: MutableMap<Disc, Int>, disc: Disc) {
        val left = (working[disc] ?: 0) - 1
        if (left <= 0) working.remove(disc) else working[disc] = left
    }

    private fun discLabel(d: Disc): String = "${d.long} (${d.value})"

    private fun plural(count: Int): String = "$count disc${if (count == 1) "" else "s"}"

    private fun formatInventory(inv: Map<Disc, Int>): String {
        if (inv.isEmpty()) return "Inventory: (no discs)"
        val total = inv.values.sum()
        val parts = inv.entries
            .sortedBy { it.key.colour.ordinal * 4 + it.key.shape.ordinal }
            .joinToString(", ") { (d, n) -> "${d.short}=${d.value}×$n" }
        return "Inventory ($total discs): $parts"
    }

    private fun staticReference(): QuestStepSolver.Result {
        return QuestStepSolver.Result(
            overlayActions = listOf(
                QuestAction.TextHint("Oaknock's Machine - match each number with exactly that many discs (1, then 1, 2, 3) whose values sum to it."),
                QuestAction.TextHint("Disc value = colour × shape. R=1 O=2 Y=3 G=4 B=5 I=6 V=7 ;  C=1 T=3 S=4 P=5."),
                QuestAction.TextHint("Open the machine for live hints; use Oaknock's exchanger to morph spare discs, talk to Brimstail for more."),
            ),
        )
    }
}
