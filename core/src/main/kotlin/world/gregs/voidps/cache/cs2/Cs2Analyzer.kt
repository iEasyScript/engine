package world.gregs.voidps.cache.cs2

import java.util.concurrent.ConcurrentHashMap
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ParamType
import world.gregs.voidps.cache.cs2.ir.Cs2Type

/**
 * Whole-cache analysis that turns raw instruction lists into something the
 * decompiler can trust:
 *
 *  - every script's return signature, resolved across calls by fixpoint;
 *  - which param ids yield strings rather than ints;
 *  - confirmation that the opcode table's pop/push counts actually balance.
 *
 * The load-bearing observation is that CS2's compiler leaves all three operand
 * stacks empty at every basic-block boundary. Anything else would be a signature
 * error, so simulating each block in isolation both validates the table and
 * gives the decompiler a clean starting state per block.
 */
private const val MAX_JOINT_VARIABLES = 6

private const val MAX_PROPAGATION_PASSES = 12

private const val RECURSIVE_SOLVE_ROUNDS = 6

private const val VARIABLE_SOLVE_ROUNDS = 3

private val RETURN_CANDIDATES: List<ReturnSignature> =
    (0..20).flatMap { ints -> (0..6).flatMap { strings -> (0..4).map { ReturnSignature(ints, strings, it) } } }
        .sortedBy { it.total }

private val SOLVABLE_BASES = listOf(Cs2VarBase.INT, Cs2VarBase.STRING, Cs2VarBase.LONG)

class Cs2Analyzer(cache: Cache) : Cs2Context {

    private val scripts = Cs2Cache(cache)

    /**
     * Decoded cache scripts, and the ids the cache has nothing usable for.
     *
     * Concurrent because hot reload compiles on a watcher thread while the
     * virtual machine resolves `GOSUB` targets on the render thread. Everything
     * is warm after [analyse], so in practice the only contended path is the
     * one-off load of an id nothing referenced during analysis; two threads
     * racing there simply decode the same bytes twice.
     */
    private val loaded = ConcurrentHashMap<Int, Cs2Script>()
    private val missing = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Scripts compiled from source that stand in for the cached ones; see
     * [install]. Declared here rather than beside the rest of the hot reload
     * support because [script] reads it, and property initialisers run in
     * declaration order.
     */
    private val overrides = ConcurrentHashMap<Int, Cs2Script>()

    private val paramTypes = HashMap<Int, Boolean>()

    val ids: IntArray = scripts.scriptIds().sortedArray()

    override fun script(id: Int): Cs2Script? {
        overrides[id]?.let { return it }
        loaded[id]?.let { return it }
        if (id in missing) return null
        val decoded = try {
            scripts.load(id)
        } catch (e: Exception) {
            null
        }
        if (decoded == null) {
            missing.add(id)
            return null
        }
        loaded[id] = decoded
        return decoded
    }

    /**
     * The cached script for [id], ignoring any hot-reloaded replacement, so a
     * reverted edit can be compared against what the cache actually holds.
     */
    fun cachedScript(id: Int): Cs2Script? = loaded[id] ?: try {
        scripts.load(id)
    } catch (e: Exception) {
        null
    }

    /**
     * Param types come straight from the cache, which spells them as a script
     * variable type id; the legacy type character is an older encoding this
     * revision never writes. Ids the cache does not type fall back to whatever
     * the corpus solver worked out.
     */
    override fun paramIsString(paramId: Int): Boolean =
        declaredIsString(paramId) ?: paramTypes[paramId] ?: false

    /**
     * Built from the whole corpus at once, because a script's parameter is typed by callers it has
     * no way of knowing about. Nothing needs it until something is emitted.
     */
    private val typeFlow: Cs2TypeFlow by lazy { Cs2TypeFlow.build(this, ids) }

    override fun operandTypes(): Cs2TypeFlow = typeFlow

    /** What the cache says about a param's stack, or null where it says nothing. */
    private fun declaredIsString(paramId: Int): Boolean? {
        val declared = Cs2Records.params().getOrNull(paramId) ?: ParamType.EMPTY
        // A zero type id is the decoder's "absent", not the int type that shares it.
        if (declared.typeId != 0) {
            return Cs2VarTypes.baseOfId(declared.typeId)?.let { it == Cs2VarBase.STRING }
        }
        if (declared.type != 0.toChar()) return declared.type == 's'
        return null
    }

    /**
     * Which stack each domain-tagged variable lives on, where only the corpus says.
     *
     * The client reads that off the variable's declared type, so [Cs2VarDeclarations] answers
     * first. A variable whose record declares nothing is still typed by the scripts: a store is
     * compiled straight after the push that produced its value, so the pushed type names the
     * variable's, and reads of the same variable elsewhere follow it.
     */
    private val variableStacks: MutableMap<Int, Cs2VarBase> by lazy {
        val out = HashMap<Int, Cs2VarBase>()
        for (id in ids) {
            val script = script(id) ?: continue
            for (index in 1 until script.size) {
                if (script[index].op.kind != OpKind.TYPED_POP) continue
                val base = pushedBase(script[index - 1]) ?: continue
                out[variableKey(script[index].intOperand)] = base
            }
        }
        out
    }

    override fun variableStack(operand: Int): Cs2VarBase =
        Cs2VarDeclarations.baseOf(Cs2Packing.varDomainOf(operand), Cs2Packing.varIdOf(operand))
            ?: variableStacks[variableKey(operand)]
            ?: Cs2VarBase.INT

    private fun variableKey(operand: Int): Int =
        (Cs2Packing.varDomainOf(operand) shl 16) or Cs2Packing.varIdOf(operand)

    /** The single stack an instruction pushes onto, or null when it is not one push. */
    private fun pushedBase(instruction: Cs2Instruction): Cs2VarBase? {
        val op = instruction.op
        if (op.operand == Cs2Operand.TAGGED) {
            return when (instruction.pushTag) {
                Cs2PushTag.INT -> Cs2VarBase.INT
                Cs2PushTag.STRING -> Cs2VarBase.STRING
                Cs2PushTag.LONG -> Cs2VarBase.LONG
                else -> null
            }
        }
        if (op.kind != OpKind.NORMAL) return null
        return when {
            op.pushInt == 1 && op.pushStr == 0 && op.pushLong == 0 -> Cs2VarBase.INT
            op.pushStr == 1 && op.pushInt == 0 && op.pushLong == 0 -> Cs2VarBase.STRING
            op.pushLong == 1 && op.pushInt == 0 && op.pushStr == 0 -> Cs2VarBase.LONG
            else -> null
        }
    }

    /** Problems found while validating a single script. */
    data class ScriptProblem(val scriptId: Int, val message: String)

    data class Report(
        val analysed: Int,
        val problems: List<ScriptProblem>,
        val stringParams: Set<Int>,
        val returnSignatures: Map<Int, ReturnSignature>,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun analyse(log: (String) -> Unit = {}): Report {
        val all: List<Pair<Int, Cs2Script>> =
            ids.toList().mapNotNull { id -> script(id)?.let { id to it } }
        log("${all.size} scripts loaded")

        resolveParamTypes(all, log)
        resolveReturnSignatures(all, log)
        for (round in 1..VARIABLE_SOLVE_ROUNDS) {
            if (solveVariableStacks(all, log) == 0) break
            settleReturnSignatures(all, log)
        }
        refineParamTypes(all, log)

        val problems = ArrayList<ScriptProblem>()
        for ((id, script) in all) {
            val failure = validate(script)
            if (failure != null) problems.add(ScriptProblem(id, failure))
        }
        log("${problems.size} scripts failed validation")

        return Report(
            analysed = all.size,
            problems = problems,
            stringParams = paramTypes.filterValues { it }.keys + declaredStringParams(),
            returnSignatures = all.associate { (id, script) ->
                id to (script.returnSignature ?: ReturnSignature.EMPTY)
            },
        )
    }

    private fun declaredStringParams(): Set<Int> =
        Cs2Records.params().indices.filterTo(HashSet()) { declaredIsString(it) == true }

    // ------------------------------------------------------------ param types

    /**
     * `*_PARAM` opcodes push an int or a string depending on the param's
     * declared type, which is not in the script. The type is still pinned down
     * by the stacks having to balance: try a script's unknown param ids both
     * ways and keep the assignment that validates.
     */
    private fun resolveParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        var resolved = 0
        for ((_, script) in all) {
            val unknown = paramIdsIn(script)
                .filter { declaredIsString(it) == null && it !in paramTypes }
                .distinct()
            if (unknown.isEmpty()) continue
            if (unknown.size > 12) continue // too many to enumerate; a later script will pin them
            val choice = (0 until (1 shl unknown.size)).firstOrNull { mask ->
                unknown.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                validate(script) == null
            }
            if (choice == null) {
                unknown.forEach { paramTypes.remove(it) }
            } else {
                resolved += unknown.size
            }
        }
        log(
            "$resolved param ids solved from the corpus, ${paramTypes.count { it.value }} of them strings; " +
                "${declaredStringParams().size} more typed as strings by the cache",
        )
    }

    /**
     * The first pass assigns each param id the moment it is seen, so an early
     * script can lock in a wrong guess. This revisits scripts that still fail,
     * allowing already-assigned ids to be flipped, and keeps any change that
     * fixes the script without breaking one that already passed.
     */
    private fun refineParamTypes(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        indexParamUsers(all)
        repeat(4) { round ->
            var fixed = 0
            for ((_, script) in all) {
                if (validate(script) == null) continue
                val ids = paramIdsIn(script).filter { declaredIsString(it) == null }.distinct()
                if (ids.isEmpty() || ids.size > 12) continue
                val original = ids.associateWith { paramTypes[it] }

                val worked = (0 until (1 shl ids.size)).any { mask ->
                    ids.forEachIndexed { bit, id -> paramTypes[id] = (mask shr bit) and 1 == 1 }
                    validate(script) == null && !breaksOthers(ids, script)
                }
                if (worked) {
                    fixed++
                } else {
                    original.forEach { (id, value) ->
                        if (value == null) paramTypes.remove(id) else paramTypes[id] = value
                    }
                }
            }
            log("param refinement round ${round + 1}: $fixed scripts fixed")
            if (fixed == 0) return
        }
    }

    /** Scripts that mention a given param id, so refinement only re-checks those. */
    private val scriptsByParamId = HashMap<Int, MutableList<Cs2Script>>()

    private fun indexParamUsers(all: List<Pair<Int, Cs2Script>>) {
        if (scriptsByParamId.isNotEmpty()) return
        for ((_, script) in all) {
            for (id in paramIdsIn(script).distinct()) {
                scriptsByParamId.getOrPut(id) { ArrayList() }.add(script)
            }
        }
    }

    /** True when the current param typing breaks another script that uses these ids. */
    private fun breaksOthers(ids: List<Int>, exclude: Cs2Script): Boolean =
        ids.asSequence()
            .flatMap { scriptsByParamId[it].orEmpty().asSequence() }
            .distinct()
            .any { it !== exclude && validate(it) != null }

    /** Param ids reachable by `*_PARAM` opcodes, read from the constant push before them. */
    private fun paramIdsIn(script: Cs2Script): List<Int> {
        val out = ArrayList<Int>()
        for (index in script.instructions.indices) {
            if (script[index].op.kind != OpKind.PARAM) continue
            for (back in index - 1 downTo maxOf(0, index - 4)) {
                val constant = script[back].intConstant ?: continue
                out.add(constant)
                break
            }
        }
        return out
    }

    // --------------------------------------------------------- variable stacks

    /**
     * Types the variables no config record declares and no store pins down.
     *
     * Their only remaining evidence is that the stacks have to balance, so each
     * unknown is tried on each stack and the assignment that leaves the most of
     * its readers validating wins. Small sets are settled jointly; a script that
     * reads more unknowns than can be enumerated is settled one at a time, which
     * is enough because a single wrong stack is what breaks it.
     */
    private fun solveVariableStacks(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit): Int {
        val users = HashMap<Int, MutableList<Cs2Script>>()
        for ((_, script) in all) {
            for (key in untypedVariablesIn(script)) users.getOrPut(key) { ArrayList() }.add(script)
        }
        var solved = 0
        for ((_, script) in all) {
            val keys = untypedVariablesIn(script)
            if (keys.isEmpty() || validate(script) == null) continue
            val readers = (keys.flatMap { users[it].orEmpty() } + script).distinct()
            val original = keys.map { variableStacks[it] }
            val baseline = validating(readers)
            val best =
                if (keys.size <= MAX_JOINT_VARIABLES) bestJointly(keys, readers, baseline)
                else bestOneAtATime(keys, readers, baseline)
            if (best != null) {
                assign(keys, best)
                solved += keys.size
            } else {
                keys.forEachIndexed { at, key ->
                    original[at].let { if (it == null) variableStacks.remove(key) else variableStacks[key] = it }
                }
            }
        }
        log("$solved variable stacks solved from the corpus")
        return solved
    }

    private fun validating(scripts: List<Cs2Script>): Int = scripts.count { validate(it) == null }

    /**
     * How far the walks get, so a search can climb out of a plateau where no
     * single change makes a whole script balance but each one pushes its failure
     * further along.
     */
    private fun progress(scripts: List<Cs2Script>): Int = scripts.sumOf { script ->
        val walk = simulate(script)
        if (walk.failure == null) script.size + 1 else maxOf(walk.failedAt, 0)
    }

    private fun assign(keys: List<Int>, bases: List<Cs2VarBase>) {
        keys.forEachIndexed { at, key -> variableStacks[key] = bases[at] }
    }

    private fun bestJointly(keys: List<Int>, readers: List<Cs2Script>, baseline: Int): List<Cs2VarBase>? {
        var bestScore = baseline
        var best: List<Cs2VarBase>? = null
        for (assignment in assignments(keys.size)) {
            assign(keys, assignment)
            val score = validating(readers)
            if (score > bestScore) {
                bestScore = score
                best = assignment
            }
        }
        return best
    }

    private fun bestOneAtATime(keys: List<Int>, readers: List<Cs2Script>, baseline: Int): List<Cs2VarBase>? {
        val current = MutableList(keys.size) { Cs2VarBase.INT }
        assign(keys, current)
        var score = progress(readers)
        var improved = true
        while (improved) {
            improved = false
            for (at in keys.indices) {
                val held = current[at]
                for (base in SOLVABLE_BASES) {
                    if (base == current[at]) continue
                    current[at] = base
                    assign(keys, current)
                    val candidate = progress(readers)
                    if (candidate > score) {
                        score = candidate
                        improved = true
                    } else {
                        current[at] = held
                    }
                }
                assign(keys, current)
            }
        }
        return current.takeIf { validating(readers) > baseline }
    }

    private fun assignments(size: Int): Sequence<List<Cs2VarBase>> {
        val radix = SOLVABLE_BASES.size
        val total = generateSequence(1) { it * radix }.elementAt(size)
        return (0 until total).asSequence().map { combination ->
            var rest = combination
            List(size) {
                val base = SOLVABLE_BASES[rest % radix]
                rest /= radix
                base
            }
        }
    }

    private fun untypedVariablesIn(script: Cs2Script): List<Int> {
        val out = LinkedHashSet<Int>()
        for (instruction in script.instructions) {
            val kind = instruction.op.kind
            if (kind != OpKind.TYPED_PUSH && kind != OpKind.TYPED_POP) continue
            val operand = instruction.intOperand
            val domain = Cs2Packing.varDomainOf(operand)
            val id = Cs2Packing.varIdOf(operand)
            if (Cs2VarDeclarations.baseOf(domain, id) != null) continue
            val key = variableKey(operand)
            if (key in variableStacks) continue
            out.add(key)
        }
        return out.toList()
    }

    // ------------------------------------------------------ return signatures

    private fun resolveReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        all.forEach { (_, script) -> script.returnSignature = ReturnSignature.EMPTY }
        settleReturnSignatures(all, log)
    }

    /** Re-settles the signatures around whatever already stands, without bootstrapping again. */
    private fun settleReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        repeat(RECURSIVE_SOLVE_ROUNDS) {
            propagateReturnSignatures(all, log)
            val settled = solveRecursiveReturnSignatures(all, log) + solveCyclicReturnSignatures(all, log)
            if (settled == 0) return
        }
    }

    private fun propagateReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit) {
        var pass = 0
        while (true) {
            pass++
            var changed = 0
            for ((_, script) in all) {
                val computed = computeReturnSignature(script) ?: continue
                if (computed != script.returnSignature) {
                    script.returnSignature = computed
                    changed++
                }
            }
            log("return-signature pass $pass: $changed changed")
            if (changed == 0 || pass >= MAX_PROPAGATION_PASSES) break
        }
    }

    /**
     * A directly or mutually recursive script cannot be bootstrapped by the
     * fixpoint: working out what it returns needs the answer already. There are
     * only a handful, and the stacks still have to balance, so the signature can
     * be found by trying the plausible shapes and keeping the one that works.
     */
    private fun solveRecursiveReturnSignatures(
        all: List<Pair<Int, Cs2Script>>,
        log: (String) -> Unit,
    ): Int {
        var solved = 0
        for ((id, script) in all) {
            if (!callsItself(id, script) || validate(script) == null) continue
            val original = script.returnSignature
            val match = RETURN_CANDIDATES.firstOrNull { candidate ->
                script.returnSignature = candidate
                validate(script) == null
            }
            if (match == null) script.returnSignature = original else solved++
        }
        log("recursive return-signature solve: $solved resolved")
        return solved
    }

    /**
     * Simulates to each `RETURN` and reports what is left on the stacks. Returns
     * null when the script does not simulate cleanly with the current
     * assumptions, leaving the previous estimate in place for the next pass.
     */
    /**
     * Settles the signatures of scripts that call one another in a cycle.
     *
     * Each member needs an answer only another member can give, so the fixpoint
     * never starts. Trying candidates for one member at a time and keeping what
     * leaves more of the cycle validating breaks the deadlock; a round that ends
     * no better off is rolled back rather than guessed at.
     */
    private fun solveCyclicReturnSignatures(all: List<Pair<Int, Cs2Script>>, log: (String) -> Unit): Int {
        val failing = all.filter { validate(it.second) != null }.toMap()
        if (failing.isEmpty()) return 0
        val calls = failing.keys.associateWith { id ->
            failing.getValue(id).instructions
                .filter { it.op.kind == OpKind.GOSUB && it.intOperand != id && it.intOperand in failing }
                .mapTo(HashSet()) { it.intOperand }
        }
        var solved = 0
        for (cycle in stronglyConnected(failing.keys) { calls.getValue(it) }) {
            if (cycle.size < 2) continue
            val members = cycle.map { failing.getValue(it) }
            val original = members.map { it.returnSignature }
            val baseline = validating(members)
            var score = baseline
            var improved = true
            while (improved) {
                improved = false
                for (member in members) {
                    var best = member.returnSignature
                    var bestScore = score
                    for (candidate in RETURN_CANDIDATES) {
                        member.returnSignature = candidate
                        val next = validating(members)
                        if (next > bestScore) {
                            bestScore = next
                            best = candidate
                        }
                    }
                    member.returnSignature = best
                    if (bestScore > score) {
                        score = bestScore
                        improved = true
                    }
                }
            }
            if (score > baseline) {
                solved += score - baseline
            } else {
                members.forEachIndexed { at, member -> member.returnSignature = original[at] }
            }
        }
        log("cyclic return-signature solve: $solved resolved")
        return solved
    }

    /**
     * Only a direct self-call reads the script's own signature during its own
     * simulation, so nothing else can be settled by trying candidate signatures.
     */
    private fun callsItself(id: Int, script: Cs2Script): Boolean =
        script.instructions.any { it.op.kind == OpKind.GOSUB && it.intOperand == id }

    private fun computeReturnSignature(script: Cs2Script): ReturnSignature? {
        val walk = simulate(script)
        return if (walk.failure != null) null else walk.returns
    }

    // -------------------------------------------------------------- validation

    /**
     * Returns null when the script simulates cleanly, or a description of the
     * first inconsistency found.
     */
    fun validate(script: Cs2Script): String? =
        simulate(script).failure

    /** Outcome of walking a script's control-flow graph with the operand stacks. */
    class Walk(
        val cfg: Cs2Cfg?,
        /** Stack state on entry to each block, once reachability settles. */
        val entryStates: Array<SymbolicStacks?>,
        val returns: ReturnSignature,
        val failure: String?,
        /** Instruction the walk gave up at, or -1 when it did not. */
        val failedAt: Int = -1,
    )

    /**
     * Propagates stack state along control-flow edges.
     *
     * Stacks are usually empty at block boundaries, but not always - the
     * compiler leaves a value live across a branch for chained comparisons - so
     * state is carried along edges rather than reset per block. Reaching a block
     * with mismatched depths means a signature is wrong.
     */
    fun simulate(script: Cs2Script, watch: ((Int, Cs2Instruction, SymbolicStacks) -> Unit)? = null): Walk {
        val cfg = try {
            Cs2Cfg.build(script)
        } catch (e: Exception) {
            return Walk(null, emptyArray(), ReturnSignature.EMPTY, "cfg: ${e.message}")
        }

        val entryStates = arrayOfNulls<SymbolicStacks>(cfg.blocks.size)
        entryStates[0] = SymbolicStacks()
        val worklist = ArrayDeque(listOf(0))
        var returns: ReturnSignature? = null

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = cfg.blocks[blockId]
            val stacks = entryStates[blockId]!!.copy()

            var returned = false
            for (index in block.indices) {
                val instruction = script[index]
                watch?.invoke(index, instruction, stacks)
                if (Cs2Cfg.isReturn(instruction.op)) {
                    val here = ReturnSignature(stacks.intDepth, stacks.stringDepth, stacks.longs)
                    if (here.total > 0) script.returnOrder = stacks.order()
                    if (returns != null && returns != here) {
                        return Walk(cfg, entryStates, returns, "inconsistent return signature: $returns vs $here", index)
                    }
                    returns = here
                    returned = true
                    break
                }
                // A call reveals the order its arguments were pushed in, which
                // the callee's own header cannot express.
                if (instruction.op.kind == OpKind.GOSUB) {
                    recordArgumentOrder(instruction.intOperand, stacks)
                }
                try {
                    step(instruction, stacks)
                } catch (e: Exception) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "instruction $index (${instruction.op.opName}): ${e.message}", index,
                    )
                }
            }
            if (returned) continue

            for (successor in block.successors) {
                val existing = entryStates[successor]
                if (existing == null) {
                    entryStates[successor] = stacks.copy()
                    worklist.addLast(successor)
                } else if (!existing.sameDepthAs(stacks)) {
                    return Walk(
                        cfg, entryStates, returns ?: ReturnSignature.EMPTY,
                        "block $successor reached with mismatched stacks ($existing vs $stacks)", block.end,
                    )
                } else {
                    existing.mergeConstantsFrom(stacks)
                }
            }
        }

        return Walk(cfg, entryStates, returns ?: ReturnSignature.EMPTY, null)
    }

    private fun recordArgumentOrder(scriptId: Int, stacks: SymbolicStacks) {
        val callee = script(scriptId) ?: return
        val total = callee.intArgsCount + callee.stringArgsCount + callee.longArgsCount
        if (total == 0 || callee.argumentOrder.isNotEmpty()) return
        val order = stacks.order()
        if (order.size < total) return
        val candidate = order.takeLast(total)
        // The caller's stack can hold values that are not arguments at all, so a
        // slice of it is only the argument order when it is a permutation of the
        // declared types; anything else would name a parameter the callee has not
        // got.
        val declared = listOf(callee.intArgsCount, callee.stringArgsCount, callee.longArgsCount)
        val found = listOf(Cs2Type.INT, Cs2Type.STRING, Cs2Type.LONG).map { type -> candidate.count { it == type } }
        if (declared == found) callee.argumentOrder = candidate
    }

    // ------------------------------------------------------------ hot reloading
    //
    // Replacements live in this analyzer rather than in a wrapping Cs2Context on
    // purpose: the virtual machine, the code generator and the structurer all
    // resolve calls through whichever context they were handed, and any of them
    // holding the bare analyzer would keep running the stale bytecode. Overriding
    // at the one place they all end up means a replacement cannot be missed.

    /** Script ids currently served from source rather than from the cache. */
    val overriddenIds: List<Int> get() = overrides.keys.sorted()

    fun isOverridden(id: Int): Boolean = overrides.containsKey(id)

    /**
     * What [install] managed to work out about a replacement script.
     *
     * [failure] is the simulation error, if any; a script that does not balance
     * is still installed, because refusing it would leave the developer running
     * code they cannot see the effect of, but its return signature is then only
     * the previous version's guess.
     */
    class Installed(
        val scriptId: Int,
        val returnSignature: ReturnSignature,
        val previousReturnSignature: ReturnSignature?,
        /** True when the argument counts in the header moved. */
        val headerChanged: Boolean,
        val failure: String?,
    ) {
        val signatureChanged: Boolean
            get() = previousReturnSignature != null && previousReturnSignature != returnSignature
    }

    /**
     * Replaces script [scriptId] with [script] and derives the analysis the code
     * generator and decompiler need from it.
     *
     * The code generator fills in the header and the instructions but nothing
     * interprocedural: `returnSignature`, `returnOrder` and `argumentOrder` are
     * products of whole-cache analysis. Left null, a statement-position call to
     * this script would discard the wrong number of results, so they are
     * re-derived here:
     *
     *  - `returnSignature` and `returnOrder` come from simulating the new
     *    instructions, exactly as [resolveReturnSignatures] does, bootstrapped
     *    from `EMPTY` so a self-recursive script still terminates.
     *  - `argumentOrder` is a property of the *callers* - it records the order
     *    they push in - so it carries over untouched as long as the header's
     *    argument counts did not move, and is dropped when they did.
     *
     * Callers already compiled into the cache are not adjusted, and cannot be:
     * see [callersOf] and [revalidate].
     */
    fun install(scriptId: Int, script: Cs2Script): Installed {
        val previous = script(scriptId)
        val headerChanged = previous != null && (
            previous.intArgsCount != script.intArgsCount ||
                previous.stringArgsCount != script.stringArgsCount ||
                previous.longArgsCount != script.longArgsCount
            )
        if (!headerChanged && script.argumentOrder.isEmpty()) {
            script.argumentOrder = previous?.argumentOrder.orEmpty()
        }

        // Derive everything before publishing, so a script the render thread can
        // already see is never half-analysed.
        script.returnSignature = ReturnSignature.EMPTY
        val walk = simulate(script)
        if (walk.failure == null) {
            script.returnSignature = walk.returns
            // A script that returns values through a self-call needs the second
            // walk to see its own signature; it also re-derives returnOrder
            // against the settled one.
            simulate(script)
        } else {
            script.returnSignature = previous?.returnSignature ?: ReturnSignature.EMPTY
        }

        overrides[scriptId] = script
        return Installed(
            scriptId = scriptId,
            returnSignature = script.returnSignature ?: ReturnSignature.EMPTY,
            previousReturnSignature = previous?.returnSignature,
            headerChanged = headerChanged,
            failure = walk.failure,
        )
    }

    /** Drops the replacement for [scriptId], falling back to the cached bytecode. */
    fun uninstall(scriptId: Int): Boolean = overrides.remove(scriptId) != null

    /** Drops every replacement. Returns how many there were. */
    fun uninstallAll(): Int {
        val count = overrides.size
        overrides.clear()
        return count
    }

    /**
     * Scripts that `GOSUB` into [scriptId].
     *
     * A call site is compiled against the callee's return signature - it discards
     * exactly as many results as the callee was known to leave - so when an edit
     * changes that signature, every caller's *existing* bytecode is wrong. They
     * cannot be patched incrementally; they have to be recompiled from source, or
     * the edit reverted. Scanned on demand because it is only needed when a
     * signature actually moves.
     */
    fun callersOf(scriptId: Int): List<Int> {
        val callers = ArrayList<Int>()
        for (id in ids) {
            val script = script(id) ?: continue
            val calls = script.instructions.any {
                it.op.kind == OpKind.GOSUB && it.intOperand == scriptId
            }
            if (calls) callers.add(id)
        }
        return callers
    }

    /** Re-simulates the given scripts against the installed overrides. */
    fun revalidate(scriptIds: Collection<Int>): List<ScriptProblem> =
        scriptIds.mapNotNull { id ->
            val script = script(id) ?: return@mapNotNull null
            validate(script)?.let { ScriptProblem(id, it) }
        }

    private fun step(instruction: Cs2Instruction, stacks: SymbolicStacks) {
        val effect = effectiveSignature(instruction, this, stacks)
        stacks.apply(
            effect,
            pushedIntConstant = instruction.intConstant,
            pushedString = instruction.strOperand,
            pushOrder = returnedOrder(instruction, effect),
        )
    }

    /**
     * A call leaves its results interleaved the way the callee pushed them, which
     * the grouped counts cannot express. Anything reading the top of the stacks -
     * a typed store above all - takes the wrong one without it.
     */
    private fun returnedOrder(instruction: Cs2Instruction, effect: StackEffect): List<Cs2Type>? {
        if (instruction.op.kind != OpKind.GOSUB) return null
        val order = script(instruction.intOperand)?.returnOrder ?: return null
        val counted = listOf(Cs2Type.INT, Cs2Type.STRING, Cs2Type.LONG).map { type -> order.count { it == type } }
        return order.takeIf { counted == listOf(effect.pushInt, effect.pushStr, effect.pushLong) }
    }
}
