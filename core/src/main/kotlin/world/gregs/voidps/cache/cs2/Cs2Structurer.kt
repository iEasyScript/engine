package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Break
import world.gregs.voidps.cache.cs2.ir.AndAll
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Condition
import world.gregs.voidps.cache.cs2.ir.OrAny
import world.gregs.voidps.cache.cs2.ir.negate
import world.gregs.voidps.cache.cs2.ir.Continue
import world.gregs.voidps.cache.cs2.ir.Cs2Function
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Goto
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.Label
import world.gregs.voidps.cache.cs2.ir.Return
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.SwitchCase
import world.gregs.voidps.cache.cs2.ir.While

/**
 * Rebuilds `if` / `else` / `while` / `switch` from the control-flow graph.
 *
 * CS2 comes out of a straightforward expression compiler, so the block layout
 * follows a small number of shapes and address order is meaningful: forward
 * branches skip over a region, and the only backward branches are loop edges.
 * Anything that does not match a known shape falls back to a label and an
 * explicit `goto`, which still re-compiles exactly.
 */
class Cs2Structurer(
    private val script: Cs2Script,
    private val scriptId: Int,
    private val context: Cs2Context,
    /**
     * Skips structuring and renders the blocks literally. Used as a fallback for
     * the few scripts whose shape the structurer cannot reproduce exactly.
     */
    private val faithful: Boolean = false,
) {
    private val cfg = Cs2Cfg.build(script)
    private val lifter = Cs2Lifter(script, context)
    private val lifted = arrayOfNulls<LiftedBlock>(cfg.blocks.size)
    private val entryStacks = arrayOfNulls<StackState>(cfg.blocks.size)
    private val labelled = HashSet<Int>()

    fun structure(): Cs2Function {
        liftAll()
        if (faithful) return function(unstructured())

        // A jump can be discovered after its target block was already emitted,
        // so structure once to collect the labels and again to place them.
        structureRange(0, cfg.blocks.size, NO_BREAK)
        val structured = structureRange(0, cfg.blocks.size, NO_BREAK)

        // If any jump ended up without a landing place, the shape was one this
        // structurer does not recognise. Fall back to a literal block-by-block
        // rendering, which is less pleasant to read but always correct.
        val body = if (danglingLabels(structured).isEmpty()) structured else unstructured()
        return function(body)
    }

    /**
     * Return values come back in the order they were pushed, which can interleave
     * the stacks, so the tuple type is taken from an actual `return` rather than
     * from the grouped signature.
     */
    private fun returnTypesFrom(body: List<Stmt>): List<Cs2Type> {
        val returned = firstReturn(body) ?: return script.returnOrder.ifEmpty { returnTypes() }
        return returned.values.map { it.type }
    }

    private fun firstReturn(statements: List<Stmt>): Return? {
        for (statement in statements) {
            when (statement) {
                is Return -> if (statement.values.isNotEmpty()) return statement
                is If -> (firstReturn(statement.then) ?: firstReturn(statement.otherwise))?.let { return it }
                is While -> firstReturn(statement.body)?.let { return it }
                is Switch -> {
                    statement.cases.forEach { case -> firstReturn(case.body)?.let { return it } }
                    firstReturn(statement.default)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun function(body: List<Stmt>) = Cs2Function(
        scriptId = scriptId,
        name = script.name,
        intArgs = script.intArgsCount,
        stringArgs = script.stringArgsCount,
        longArgs = script.longArgsCount,
        intLocals = script.intLocalsCount,
        stringLocals = script.stringLocalsCount,
        longLocals = script.longLocalsCount,
        body = body,
        returns = returnTypesFrom(body),
        argumentOrder = script.argumentOrder,
    )

    /** Labels jumped to but never placed. */
    private fun danglingLabels(body: List<Stmt>): Set<String> {
        val wanted = HashSet<String>()
        val placed = HashSet<String>()
        fun walk(statements: List<Stmt>) {
            for (statement in statements) {
                when (statement) {
                    is Goto -> wanted.add(statement.label)
                    is Label -> placed.add(statement.name)
                    is If -> {
                        walk(statement.then)
                        walk(statement.otherwise)
                    }
                    is While -> walk(statement.body)
                    is Switch -> {
                        statement.cases.forEach { walk(it.body) }
                        walk(statement.default)
                    }
                    else -> Unit
                }
            }
        }
        walk(body)
        return wanted - placed
    }

    /**
     * Every block in address order, each labelled, with its terminator written
     * out as an explicit jump. This is the shape the code generator reproduces
     * exactly, so it is the safe answer when structuring does not fit.
     */
    private fun unstructured(): List<Stmt> {
        val out = ArrayList<Stmt>()
        for (id in cfg.blocks.indices) {
            // Emitting the rest without it would silently drop its work, and a
            // branch into it would have nowhere to land.
            val block = lifted[id] ?: error("block $id did not lift")
            out.add(Label("L$id"))
            out.addAll(block.statements)
            when {
                block.returns != null -> out.add(Return(block.returns))
                block.condition != null -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(If(block.condition, listOf(Goto("L$target")), emptyList()))
                }
                block.switchSubject != null -> out.add(switchAsJumps(id, block))
                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    if (target >= 0) out.add(Goto("L$target"))
                }
            }
        }
        return out
    }

    /** A switch rendered as one jump per key, with no structured arms. */
    private fun switchAsJumps(id: Int, block: LiftedBlock): Switch {
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1
        val cases = table.mapNotNull { case ->
            val target = cfg.blockStartingAt(last + case.offset + 1)?.id ?: return@mapNotNull null
            SwitchCase(listOf(case.key), listOf(Goto("L$target")))
        }
        return Switch(block.switchSubject!!, cases, emptyList(), block.switchTable, defaultFirst = true)
    }

    private fun returnTypes(): List<Cs2Type> {
        val signature = script.returnSignature ?: ReturnSignature.EMPTY
        return List(signature.ints) { Cs2Type.INT } +
            List(signature.strings) { Cs2Type.STRING } +
            List(signature.longs) { Cs2Type.LONG }
    }

    /** Lifts every block, propagating the operand stacks along control-flow edges. */
    private fun liftAll() {
        entryStacks[0] = StackState()
        val worklist = ArrayDeque(listOf(0))
        while (worklist.isNotEmpty()) {
            val id = worklist.removeFirst()
            if (lifted[id] != null) continue
            val entry = entryStacks[id] ?: StackState()
            val block = lifter.lift(cfg.blocks[id], entry)
            lifted[id] = block
            for (successor in cfg.blocks[id].successors) {
                if (entryStacks[successor] == null) entryStacks[successor] = block.exitStack
                if (lifted[successor] == null) worklist.addLast(successor)
            }
        }

        // Scripts carry a trailing default `return` that both arms of an
        // if/else can make unreachable. Dropping it would change the bytecode,
        // so unreached blocks are lifted from an empty stack as well.
        for (id in cfg.blocks.indices) {
            if (lifted[id] != null) continue
            lifted[id] = try {
                lifter.lift(cfg.blocks[id], StackState())
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Block a conditional branch jumps to, or -1. */
    private fun branchTarget(id: Int): Int {
        val block = cfg.blocks[id]
        val targets = Cs2Cfg.jumpTargets(script, block.end - 1)
        return targets.firstOrNull()?.let { cfg.blockStartingAt(it)?.id } ?: -1
    }

    private fun endsWithGoto(id: Int): Boolean =
        Cs2Cfg.isUnconditionalBranch(script[cfg.blocks[id].end - 1].op)

    private fun endsWithCondition(id: Int): Boolean = lifted[id]?.condition != null

    private fun endsWithSwitch(id: Int): Boolean = lifted[id]?.switchSubject != null

    private fun endsWithReturn(id: Int): Boolean = lifted[id]?.returns != null

    /**
     * Structures blocks `[from, to)` into a statement list.
     *
     * `breakTargets` holds blocks that a `break` would land on, so a jump to one
     * becomes a `break` rather than a goto.
     */
    private fun structureRange(
        from: Int,
        to: Int,
        /** The block a `break` lands on: the innermost loop's or switch's exit, or -1. */
        breakAt: Int,
        /**
         * Blocks that running off the end of this region reaches anyway, so a jump
         * to one of them emits nothing.
         *
         * It is a set rather than a single block because the region's own end can
         * itself be the end of everything enclosing it: a jump out of the innermost
         * `if` then leaves several constructs at once, and every join point it
         * passes through is an equally correct place to land.
         */
        exits: Set<Int> = emptySet(),
        /** A jump here goes back to a loop test. */
        continueTarget: Int = -1,
    ): List<Stmt> {
        val out = ArrayList<Stmt>()
        var id = from
        while (id < to) {
            val block = lifted[id]
            if (block == null) {
                id++
                continue
            }
            if (id in labelled) out.add(Label("L$id"))
            // A rotated loop re-runs its header on every pass, so those
            // statements belong inside the loop, not in front of it.
            if (!rotatesInto(id, to)) out.addAll(block.statements)

            when {
                endsWithReturn(id) -> {
                    out.add(Return(block.returns!!))
                    id++
                }

                endsWithSwitch(id) -> {
                    val consumed = structureSwitch(id, to, out, breakAt, exits, continueTarget)
                    id = consumed
                }

                endsWithCondition(id) -> {
                    val consumed = structureConditional(id, to, out, breakAt, exits, continueTarget)
                    id = consumed
                }

                endsWithGoto(id) -> {
                    val target = branchTarget(id)
                    val last = nextLiveBlock(id + 1, to) < 0
                    when {
                        target < 0 -> Unit
                        // The loop's own back edge is emitted by the loop itself.
                        target == continueTarget -> if (!last) out.add(Continue())
                        target in exits -> Unit // the enclosing construct jumps here anyway
                        target == breakAt -> out.add(Break())
                        target == to -> Unit // the region ends here anyway
                        else -> {
                            labelled.add(target)
                            out.add(Goto("L$target"))
                        }
                    }
                    id++
                }

                else -> id++
            }
        }
        return out
    }

    /**
     * Handles a block ending in a conditional branch.
     *
     * Three shapes are recognised, in order: a `while` loop (the region the
     * branch skips ends by jumping back here), an `if`/`else` (the then-region
     * ends by jumping past the else-region), and a bare `if`.
     */
    /**
     * A conditional branch reduced to "run [regionStart, skipTarget) when
     * [condition] holds".
     */
    private data class Guard(val condition: Condition, val regionStart: Int, val skipTarget: Int)

    /**
     * The condition guarding block [id]'s branch, with every test that shares its
     * skip target folded into one `&&` chain.
     *
     * A test whose own guard skips to the same place as this one's is the
     * language's `if (a) { if (b) ... }` written out, so reading it back as
     * `a && b` says the same thing and lays the branches out identically.
     * Chaining stops at the first block that does work of its own - those
     * statements belong inside the body - and at a loop header, whose test has
     * to stay where the back edge can reach it.
     */
    private fun guardAt(id: Int): Guard? {
        var guard = link(id) ?: return null
        if (loops(id) && lifted[id]?.statements?.isNotEmpty() == true) return guard
        var cursor = guard.regionStart
        while (chainable(id, cursor)) {
            val next = link(cursor) ?: break
            if (next.skipTarget != guard.skipTarget) break
            guard = Guard(both(guard.condition, next.condition), next.regionStart, guard.skipTarget)
            cursor = next.regionStart
        }
        return guard
    }

    /** One term of an `&&` chain: an `||` chain where there is one, a single test otherwise. */
    private fun link(id: Int): Guard? = alternatives(id) ?: singleGuard(id)

    private fun both(left: Condition, right: Condition): Condition {
        val terms = ArrayList<Condition>()
        if (left is AndAll) terms.addAll(left.terms) else terms.add(left)
        if (right is AndAll) terms.addAll(right.terms) else terms.add(right)
        return AndAll(terms)
    }

    /** True when a back edge lands on [id], which makes it a loop test. */
    private fun loops(id: Int): Boolean = cfg.blocks[id].predecessors.any { it >= id }

    /**
     * True when [id] is nothing but a test that the chain starting at [start] may
     * absorb.
     *
     * Absorbing a test spells it as one more `&&` term, which erases it as a
     * place control can arrive at. That is only safe while nothing outside the
     * chain branches into it: a block another term jumps to is a join point, and
     * folding it away would leave that jump with nowhere to land.
     */
    private fun chainable(start: Int, id: Int): Boolean {
        if (id !in cfg.blocks.indices) return false
        val block = lifted[id] ?: return false
        if (block.condition == null || block.statements.isNotEmpty()) return false
        return cfg.blocks[id].predecessors.all { it in start until id }
    }

    /**
     * `(a && b) || (c && d)`: alternatives laid out one after another, each
     * entering the same body, each failing into the next, and the last failing
     * past the body altogether.
     *
     * A single alternative is not one of these - that is an ordinary `&&` chain -
     * so two are required before the shape is claimed.
     */
    private fun alternatives(id: Int): Guard? {
        for (body in bodyCandidates(id)) {
            orChain(id, body)?.let { return it }
        }
        return null
    }

    /**
     * The blocks the tests starting at [id] branch into, largest first.
     *
     * The body of an `||` sits after every test that guards it, so the real body
     * is the furthest of them; the nearer ones are where one term hands over to
     * the next.
     */
    private fun bodyCandidates(id: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        var cursor = id
        while (cursor < cfg.blocks.size) {
            val block = lifted[cursor] ?: break
            if (block.condition != null) {
                val target = branchTarget(cursor)
                if (target > cursor) out.add(target)
            } else if (!isTrampoline(cursor)) {
                break
            }
            cursor++
        }
        return out.sortedDescending()
    }

    private fun orChain(id: Int, body: Int): Guard? {
        val terms = ArrayList<Condition>()
        var cursor = id
        while (cursor < body) {
            val (condition, exit) = alternative(cursor, body) ?: return null
            terms.add(condition)
            if (exit > body) return if (terms.size > 1) Guard(OrAny(terms), body, exit) else null
            if (exit <= cursor) return null
            cursor = exit
        }
        return null
    }

    /**
     * One alternative of an `||`: the `&&` chain running from [start] that enters
     * [body], paired with the block it falls to when any of its terms fails.
     *
     * The term that enters the body has no branch of its own to fail through - it
     * simply falls out of the alternative - so where it lands has to agree with
     * where the earlier terms branch.
     */
    private fun alternative(start: Int, body: Int): Pair<Condition, Int>? {
        val terms = ArrayList<Condition>()
        var cursor = start
        var exit = -1
        while (cursor < body) {
            val block = lifted[cursor] ?: return null
            val condition = block.condition ?: return null
            if (cursor != start && block.statements.isNotEmpty()) return null
            if (cursor != start && cfg.blocks[cursor].predecessors.any { it !in start until cursor }) return null
            if (branchTarget(cursor) == body) {
                terms.add(condition)
                val fall = past(cursor + 1)
                if (exit >= 0 && exit != fall) return null
                return (if (terms.size == 1) terms.first() else AndAll(terms)) to fall
            }
            val guard = singleGuard(cursor) ?: return null
            if (guard.regionStart <= cursor) return null
            if (exit < 0) exit = guard.skipTarget else if (guard.skipTarget != exit) return null
            terms.add(guard.condition)
            cursor = guard.regionStart
        }
        return null
    }

    /** Where a block leads, following a lone unconditional branch through. */
    private fun past(id: Int): Int {
        if (id !in cfg.blocks.indices || !isTrampoline(id)) return id
        return branchTarget(id).takeIf { it >= 0 } ?: id
    }

    /**
     * One link of a guard chain: the condition block [id] tests and where control
     * goes when it fails.
     *
     * CS2 nearly always emits a conditional branch that hops over a single
     * unconditional one:
     * ```
     * i     BRANCH_LESS_THAN +1   ; enter the body when the test passes
     * i+1   BRANCH exit           ; otherwise skip it
     * i+2   <body>
     * ```
     * Read literally that is an `if` with an empty arm, so the trampoline is
     * folded away first and the condition kept as written. Without it the branch
     * skips the region directly and the sense is inverted.
     */
    private fun singleGuard(id: Int): Guard? {
        val first = lifted[id]?.condition ?: return null
        val taken = branchTarget(id)
        if (taken < 0) return null

        // The trampoline shape: the test enters its body and the branch it hops
        // over is the one that skips it.
        if (taken == id + 2 && id + 1 < cfg.blocks.size && isTrampoline(id + 1)) {
            val skip = branchTarget(id + 1)
            if (skip >= 0) return Guard(first, id + 2, skip)
        }

        // `a || b`: every term branches straight into the shared body, and a
        // lone branch past them all skips it.
        val terms = ArrayList<Condition>()
        var cursor = id
        while (cursor < cfg.blocks.size) {
            val block = lifted[cursor] ?: break
            val term = block.condition ?: break
            if (cursor != id && block.statements.isNotEmpty()) break
            if (branchTarget(cursor) != taken) break
            terms.add(term)
            cursor++
        }
        if (terms.size > 1 && cursor < cfg.blocks.size && isTrampoline(cursor) && cursor + 1 == taken) {
            val skip = branchTarget(cursor)
            if (skip >= 0) return Guard(OrAny(terms), taken, skip)
        }

        // `a || (b && c)`: this term jumps into the body and whatever follows
        // guards the same body. Recursing picks up the nested chain, but only
        // when the next block is a bare test - folding one that also does work
        // would drop that work, since nothing else emits it.
        if (terms.size == 1 && lifted.getOrNull(id + 1)?.statements.isNullOrEmpty()) {
            val rest = guardAt(id + 1)
            if (rest != null && rest.regionStart == taken) {
                return Guard(OrAny(listOf(first, rest.condition)), taken, rest.skipTarget)
            }
        }

        // Otherwise the branch simply skips the region, so the region runs when
        // the condition does *not* hold.
        return Guard(first.negate(), id + 1, taken)
    }

    /**
     * True when a block does nothing but jump *and* nothing else jumps to it.
     * Folding one away that another branch targets would leave that branch with
     * nowhere to land.
     */
    private fun isTrampoline(id: Int): Boolean {
        val block = cfg.blocks[id]
        return block.end - block.start == 1 &&
            Cs2Cfg.isUnconditionalBranch(script[block.start].op) &&
            lifted[id]?.statements.isNullOrEmpty() &&
            block.predecessors.size <= 1
    }

    /**
     * True when [id] is a loop header that does work before its test, so the
     * back edge lands on that work rather than on the comparison.
     */
    private fun rotatesInto(id: Int, to: Int): Boolean {
        if (!endsWithCondition(id) || lifted[id]?.statements.isNullOrEmpty()) return false
        val skipTarget = guardAt(id)?.skipTarget ?: return false
        if (skipTarget <= id || skipTarget > to) return false
        val tail = skipTarget - 1
        return tail > id && endsWithGoto(tail) && branchTarget(tail) == id
    }

    private fun structureConditional(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val guard = guardAt(id)
        if (guard == null) return id + 1
        val (condition, regionStart, skipTarget) = guard

        // A jump out of this region cannot be structured here. Fall back to the
        // single comparison rather than the chain: consuming a chain's blocks
        // would leave the enclosing region to emit them a second time.
        if (skipTarget <= id || skipTarget > to || regionStart >= to) {
            val plain = lifted[id]!!.condition!!
            val target = branchTarget(id)
            labelled.add(target.coerceIn(0, cfg.blocks.size - 1))
            out.add(If(plain, listOf(Goto("L$target")), emptyList()))
            return id + 1
        }

        val lastOfRegion = skipTarget - 1
        if (lastOfRegion >= regionStart && endsWithGoto(lastOfRegion)) {
            val jump = branchTarget(lastOfRegion)

            // while: the body jumps back to the test.
            if (jump == id) {
                val body = structureRange(
                    regionStart, skipTarget, breakAt = skipTarget, continueTarget = id,
                )
                if (rotatesInto(id, to)) {
                    val header = lifted[id]!!.statements +
                        If(condition.negate(), listOf(Break()), emptyList())
                    out.add(While(null, header + body))
                } else {
                    out.add(While(condition, body))
                }
                return skipTarget
            }

            // if/else: the then-region jumps past the else-region.
            if (jump in (skipTarget + 1)..to) {
                val join = joined(jump, to, exits)
                val then = structureRange(regionStart, skipTarget, breakAt, join, continueTarget)
                val otherwise = structureRange(skipTarget, jump, breakAt, join, continueTarget)
                out.add(If(condition, then, otherwise))
                return jump
            }
        }

        // Inside a loop, skipping the rest of the body leaves the loop, which is
        // not what running off the end of it does: written as an `if` around the
        // rest, control would fall back into the loop instead of out of it. Only
        // a single test is turned around this way - negating a chain would lay
        // its branches out differently from the ones it was read off.
        if (skipTarget == breakAt && continueTarget >= 0 && skipTarget == to &&
            condition is Compare && !condition.negated
        ) {
            out.add(If(condition.negate(), listOf(Break()), emptyList()))
            return regionStart
        }

        val then = structureRange(regionStart, skipTarget, breakAt, joined(skipTarget, to, exits), continueTarget)

        // A guarded region whose whole content is a jump is a test the compiler
        // kept even though nothing hangs off it. Written as an empty `if` the
        // jump would be lost, so it stays the jump it is.
        val tail = skipTarget - 1
        if (then.isEmpty() && tail >= regionStart && endsWithGoto(tail)) {
            val target = branchTarget(tail)
            if (target >= 0) {
                labelled.add(target)
                // A test that branches past the region reads as a guarded jump; one
                // that branches *into* it was laid out around a jump, which only the
                // two-armed form puts back where it was.
                val guarded = condition !is Compare || condition.negated
                out.add(
                    if (guarded) If(condition, listOf(Goto("L$target")), emptyList())
                    else If(condition.negate(), emptyList(), listOf(Goto("L$target"))),
                )
                return skipTarget
            }
        }
        out.add(If(condition, then, emptyList()))
        return skipTarget
    }

    /**
     * Where a region ending at [join] can jump to and still just be finishing.
     *
     * [join] itself always qualifies. Where it is also the end of the enclosing
     * region, finishing there finishes that region too, so everything the caller
     * could leave through is reachable the same way.
     */
    private fun joined(join: Int, to: Int, exits: Set<Int>): Set<Int> =
        if (join == to) exits + join else setOf(join)

    /**
     * The enclosing exits a region ending at [join] inherits, without [join]
     * itself.
     *
     * A switch arm falls out to the join rather than being branched there by the
     * code generator, so keeping the jump explicit is what reproduces the one the
     * compiler wrote - even the redundant jump to the very next instruction.
     */
    private fun carried(join: Int, to: Int, exits: Set<Int>): Set<Int> =
        if (join == to) exits - join else emptySet()

    /**
     * Handles a `SWITCH`. Case bodies run in address order and each ends by
     * jumping to the block after the statement, which becomes the `break`.
     */
    /** The next block in `[from, to)` that was reached, or -1. */
    private fun nextLiveBlock(from: Int, to: Int): Int {
        for (id in from until to) if (lifted[id] != null) return id
        return -1
    }

    private fun structureSwitch(
        id: Int,
        to: Int,
        out: MutableList<Stmt>,
        breakAt: Int,
        exits: Set<Int>,
        continueTarget: Int,
    ): Int {
        val block = lifted[id]!!
        val table = script.switchTables.getOrNull(block.switchTable) ?: emptyList()
        val last = cfg.blocks[id].end - 1

        // Group keys that jump to the same block into one case.
        val byTarget = LinkedHashMap<Int, MutableList<Int>>()
        for (case in table) {
            val targetBlock = cfg.blockStartingAt(last + case.offset + 1)?.id ?: continue
            byTarget.getOrPut(targetBlock) { ArrayList() }.add(case.key)
        }

        // The block right after the switch jumps to the default arm, which sits
        // past every case body.
        val afterSwitch = id + 1
        val jumpsToDefault = afterSwitch < cfg.blocks.size && isTrampoline(afterSwitch)
        val defaultTarget = if (jumpsToDefault) branchTarget(afterSwitch) else afterSwitch

        val caseStarts = byTarget.keys.sorted()
        if (caseStarts.isEmpty()) {
            out.add(Switch(block.switchSubject!!, emptyList(), emptyList(), block.switchTable))
            return afterSwitch
        }

        // Only the jump to a laid-out-last default arm is not an arm of its own;
        // where control falls straight into the default, its trailing jump is as
        // good a witness to the join point as any case body's.
        val end = switchEnd(caseStarts, if (jumpsToDefault) afterSwitch else -1, defaultTarget, to)

        // The default arm runs from where the no-match path lands until the next
        // case body starts, or to the join point when none follows it. Bounding
        // it this way keeps it from swallowing case bodies whichever layout the
        // compiler used.
        val defaultStop = minOf(end, caseStarts.firstOrNull { it > defaultTarget } ?: end)
        val hasDefault = defaultTarget < defaultStop

        val boundaries = (caseStarts + listOfNotNull(defaultTarget.takeIf { hasDefault }) + end)
            .filter { it <= end }
            .distinct()
            .sorted()

        val cases = ArrayList<SwitchCase>()
        for (start in caseStarts) {
            // A key selecting the join point itself has an arm that does nothing
            // at all. Dropping it would lose the key from the table.
            if (start > end) continue
            val stop = boundaries.firstOrNull { it > start } ?: end
            val arm = structureRange(start, stop, end, carried(end, to, exits), continueTarget)
            cases.add(SwitchCase(byTarget.getValue(start), arm))
        }
        val default =
            if (hasDefault) structureRange(defaultTarget, defaultStop, end, carried(end, to, exits), continueTarget)
            else emptyList()

        // The default arm is laid out wherever the source put it, which can be
        // between two case bodies rather than before or after all of them.
        val defaultAt = if (hasDefault) caseStarts.count { it < defaultTarget } else Int.MAX_VALUE
        out.add(Switch(block.switchSubject!!, cases, default, block.switchTable, !jumpsToDefault, defaultAt))
        return end
    }

    /**
     * Where a `switch` ends.
     *
     * The no-match jump lands either on a real default arm or straight on the
     * join point. Only an arm's *own* trailing jump can tell them apart: one
     * that jumps past every arm has found the join point, which makes the
     * no-match target a default arm. Branches from nested `if`s inside an arm
     * must not be mistaken for it, and the default arm sits either side of the
     * case bodies, so arms are bounded by the next arm in address order.
     */
    private fun switchEnd(caseStarts: List<Int>, afterSwitch: Int, defaultTarget: Int, to: Int): Int {
        val arms = (caseStarts + defaultTarget).distinct().sorted()
        val lastArm = arms.last()
        var end = to
        var found = false
        for ((index, start) in arms.withIndex()) {
            val boundary = arms.getOrNull(index + 1) ?: to
            val tail = boundary - 1
            if (tail < start || tail == afterSwitch || tail >= cfg.blocks.size) continue
            if (!endsWithGoto(tail)) continue
            val target = branchTarget(tail)
            if (target > lastArm && target <= end) {
                end = target
                found = true
            }
        }
        return if (found) end else defaultTarget
    }

    private companion object {
        /** Outside any loop or switch, so nothing is a `break`. */
        const val NO_BREAK = -1
    }
}
