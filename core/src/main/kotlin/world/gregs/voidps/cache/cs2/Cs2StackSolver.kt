package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.Cache
import kotlin.math.abs

/**
 * Solves what every opcode does to the three operand stacks, from the corpus.
 *
 * Two things are observable in a clientscript and nothing else is. The first is
 * arithmetic: the stacks start empty, every path to a `RETURN` has to leave the
 * same depths behind, and two paths that meet have to agree - which makes the
 * whole corpus one linear system over each opcode's net effect per stack, with a
 * script's return depths as the only other unknowns. The second is the floor: an
 * opcode can never pop more than is there, so the shallowest stack any of its
 * occurrences ever sees is a ceiling on its pop count.
 *
 * The ceiling is what separates `pop 2 push 1` from `pop 1 push 0`, which the
 * arithmetic cannot: both have the same net. Where the ceiling meets the
 * `push >= 0` floor the counts are forced; where it does not, the ceiling is
 * still the largest the corpus allows, and an expression compiler reaches it the
 * first time the opcode is used as a whole statement. The two cases are reported
 * apart, because only the first is a proof.
 *
 * Nothing is assumed about any opcode beyond [Cs2CoreOps] - without an anchor
 * the system is homogeneous and every opcode doing nothing at all satisfies it.
 *
 * This is how a build is bootstrapped, not the last word: an opcode no script
 * reaches is invisible here, and one whose pops are never all on the stack at
 * once reads as popping fewer. [Cs2StackEffectImport] reads the handlers
 * themselves and wins wherever the two speak about the same opcode.
 */
object Cs2StackSolver {

    private const val STACKS = 3
    private const val RETURN_VARS = 1 shl 22
    private const val FILL_LIMIT = 96
    private const val COMBINE_BUDGET = 2_000_000
    private const val ROUNDS = 8
    private const val ATTEMPTS = 4
    private const val WITNESSES = 16
    private const val MIN_BLAME = 3
    private const val BLAME_SHARE = 60
    private const val SPEC_REACH = 3
    private const val MIN_WITNESS = 2

    class Result(
        val effects: Map<Int, StackEffect>,
        val kinds: Map<Int, OpKind>,
        /** Opcodes whose counts are the only ones the corpus permits. */
        val forced: Set<Int>,
        /** Opcodes whose net is solved but whose pop counts rest on the ceiling alone. */
        val bounded: Set<Int>,
        /** Opcodes the corpus exercises but does not pin. */
        val unsolved: Set<Int>,
        /** Of those, the ones whose net effect the arithmetic never settled. */
        val netless: Set<Int>,
        /** Opcodes with no constant effect, mapped to the family they matched. */
        val dynamic: Map<Int, OpKind?>,
        /** Opcodes no script in the corpus contains. */
        val unseen: Set<Int>,
        val occurrences: Map<Int, Int>,
        val scripts: Int,
        val unreadable: List<Int>,
    )

    fun solve(cache: Cache, log: (String) -> Unit = {}): Result {
        val corpus = Corpus.read(cache)
        log("${corpus.bodies.size} scripts walked, ${corpus.unreadable.size} unreadable")

        val dynamic = HashMap<Int, OpKind?>()
        for (opcode in corpus.typeSpecDriven()) dynamic[opcode] = null
        log("${dynamic.size} opcodes are handed a type-spec string at every use")

        var hooks = emptyMap<Int, Int>()
        var nets = emptyMap<Int, IntArray>()
        var returns = emptyMap<Int, IntArray>()
        for (attempt in 1..ATTEMPTS) {
            val system = System()
            for (body in corpus.bodies) corpus.constrain(body, system, dynamic.keys, hooks)
            system.solve(log)
            log("${system.solvedCount} values from ${system.size} equations, ${system.blamed.size} scripts dissented")
            nets = system.nets()
            returns = system.returns()
            blame(corpus, system.blamed, dynamic, log)

            // The solve only hears from a script that reduces to one unknown. A
            // replay hears from all of them, so anything with a moving effect
            // that slipped through the vote shows up here as a script that will
            // not walk at all.
            for (round in 1..ROUNDS) {
                val failures = corpus.bodies
                    .filterTo(HashSet()) { corpus.replays(it, nets, returns, dynamic.keys) == null }
                    .mapTo(HashSet()) { it.id }
                log("replay round $round: ${failures.size} scripts do not walk")
                if (failures.isEmpty() || !blame(corpus, failures, dynamic, log)) break
            }
            if (attempt == ATTEMPTS) break
            val fitted = fitHooks(corpus, nets, returns, dynamic, hooks, log)
            if (fitted.size == hooks.size) break
            hooks = fitted
        }

        val ceilings = corpus.ceilings(nets, returns, dynamic.keys, hooks)
        log("${ceilings.size} opcodes reached at a known depth")

        val effects = HashMap<Int, StackEffect>()
        val kinds = HashMap<Int, OpKind>()
        val forced = HashSet<Int>()
        val bounded = HashSet<Int>()
        val unsolved = HashSet<Int>()
        val netless = HashSet<Int>()

        for (op in Cs2Opcodes.all) {
            val core = Cs2CoreOps.effectOf(op.naming)
            if (core != null) {
                effects[op.id] = core
                forced.add(op.id)
                continue
            }
            val kind = Cs2CoreOps.kindOf(op.naming)
            if (kind != null) {
                kinds[op.id] = kind
                continue
            }
            if (op.id in dynamic || op.operand == Cs2Operand.TAGGED) continue
            if (op.id !in corpus.occurrences) continue
            val net = nets[op.id]
            val ceiling = ceilings[op.id]
            if (net == null || ceiling == null) {
                unsolved.add(op.id)
                if (net == null) netless.add(op.id)
                continue
            }
            val effect = split(net, ceiling)
            if (effect == null) {
                dynamic[op.id] = null
                continue
            }
            effects[op.id] = effect
            if (forcedBy(net, ceiling)) forced.add(op.id) else bounded.add(op.id)
        }

        classify(corpus, effects, kinds, dynamic, log)

        return Result(
            effects = effects,
            kinds = kinds,
            forced = forced,
            bounded = bounded,
            unsolved = unsolved,
            netless = netless,
            dynamic = dynamic,
            unseen = Cs2Opcodes.all.map { it.id }.filterTo(HashSet()) { it !in corpus.occurrences },
            occurrences = corpus.occurrences,
            scripts = corpus.bodies.size,
            unreadable = corpus.unreadable,
        )
    }

    /**
     * Works out how much of a hook's cost is fixed, from the depths it runs at.
     *
     * The spec string accounts for the bound arguments and the callback's script
     * id; what it does not say is whether the component being hooked is on the
     * stack too. That is one int either way, and the answer is the one that fits
     * under every depth the opcode is ever reached at and exactly meets the
     * shallowest - the site where the hook is a statement of its own.
     */
    private fun fitHooks(
        corpus: Corpus,
        nets: Map<Int, IntArray>,
        returns: Map<Int, IntArray>,
        dynamic: MutableMap<Int, OpKind?>,
        known: Map<Int, Int>,
        log: (String) -> Unit,
    ): Map<Int, Int> {
        val fitted = HashMap(known)
        for ((opcode, slack) in corpus.slack(nets, returns, dynamic.keys, known)) {
            if (slack.ints !in 0..1 || slack.strings != 0 || slack.longs != 0) continue
            if (slack.lengths < 2) continue
            fitted[opcode] = slack.ints
            dynamic[opcode] = OpKind.HOOK
        }
        log("${fitted.size} hooks fitted; ${fitted.values.count { it == 1 }} of them take a component")
        return fitted
    }

    /** Adds the opcodes that best explain a set of failing scripts. Returns false when none do. */
    private fun blame(
        corpus: Corpus,
        failures: Set<Int>,
        dynamic: MutableMap<Int, OpKind?>,
        log: (String) -> Unit,
    ): Boolean {
        val culprits = corpus.suspects(failures, coreIds() + dynamic.keys)
        culprits.forEach { dynamic[it] = null }
        log("  blamed on ${culprits.size} opcodes")
        return culprits.isNotEmpty()
    }

    private fun coreIds(): Set<Int> = Cs2Opcodes.all
        .filterTo(HashSet()) { Cs2CoreOps.effectOf(it.naming) != null || Cs2CoreOps.kindOf(it.naming) != null }
        .mapTo(HashSet()) { it.id }

    /** Pop counts sit on the ceiling; the push count is whatever the net then requires. */
    private fun split(net: IntArray, ceiling: IntArray): StackEffect? {
        for (stack in 0 until STACKS) if (ceiling[stack] < maxOf(0, -net[stack])) return null
        return StackEffect(
            popInt = ceiling[0], popStr = ceiling[1], popLong = ceiling[2],
            pushInt = ceiling[0] + net[0], pushStr = ceiling[1] + net[1], pushLong = ceiling[2] + net[2],
        )
    }

    private fun forcedBy(net: IntArray, ceiling: IntArray): Boolean =
        (0 until STACKS).all { ceiling[it] == maxOf(0, -net[it]) }

    /**
     * Puts a family to the opcodes that have no constant effect.
     *
     * A hook setter reads its shape out of a type-spec string on the stack and a
     * param lookup out of the cache, so neither has one net effect to find. Both
     * are still testable: assume the family, replay the scripts that use the
     * opcode, and keep the assumption only if every one of them balances.
     */
    private fun classify(
        corpus: Corpus,
        effects: Map<Int, StackEffect>,
        kinds: MutableMap<Int, OpKind>,
        dynamic: MutableMap<Int, OpKind?>,
        log: (String) -> Unit,
    ) {
        if (dynamic.isEmpty()) return
        val resolver = Resolver(corpus, effects, kinds)
        var identified = 0
        for (opcode in dynamic.keys.sorted()) {
            val witnesses = corpus.witnesses(opcode, resolver)
            if (witnesses.isEmpty()) continue
            val match = hypotheses(opcode).firstOrNull { hypothesis ->
                witnesses.all { resolver.balances(it, hypothesis) }
            } ?: continue
            dynamic[opcode] = match.kind
            kinds[opcode] = match.kind
            resolver.assume(match)
            identified++
        }
        log("$identified of ${dynamic.size} opcodes with no constant effect matched a family")
    }

    private fun hypotheses(opcode: Int): List<Hypothesis> {
        val hooks = listOf(1, 0).flatMap { component ->
            listOf(true, false).map { triggers -> Hypothesis(opcode, OpKind.HOOK, component, triggers) }
        }
        val params = (2 downTo 0).map { Hypothesis(opcode, OpKind.PARAM, it, false) }
        return hooks + params
    }

    private class Hypothesis(
        val opcode: Int,
        val kind: OpKind,
        val popInt: Int,
        val triggers: Boolean,
    ) {
        fun applyTo(op: Cs2Op): Cs2Op = op.copy(
            kind = kind,
            popInt = popInt, popStr = 0, popLong = 0,
            pushInt = 0, pushStr = 0, pushLong = 0,
            hasTriggerArray = triggers,
        )
    }

    // ----------------------------------------------------------------- corpus

    private class Body(val id: Int, val script: Cs2Script, val cfg: Cs2Cfg)

    /** One instruction the replay reached, with the depths it ran at. */
    private class Site(val opcode: Int, val at: Int, val depths: IntArray)

    private class Corpus(
        val bodies: List<Body>,
        private val index: Map<Int, Int>,
        val unreadable: List<Int>,
    ) {
        val occurrences: Map<Int, Int> = HashMap<Int, Int>().also { counts ->
            for (body in bodies) {
                for (opcode in body.script.instructions.mapTo(HashSet()) { it.op.id }) {
                    counts[opcode] = (counts[opcode] ?: 0) + 1
                }
            }
        }

        fun script(id: Int): Cs2Script? = index[id]?.let { bodies[it].script }

        fun body(id: Int): Body = bodies[index.getValue(id)]

        /**
         * Turns one script into equations.
         *
         * Every path to a `RETURN` leaves the script's return depths behind, and
         * two paths that meet leave the same depths, so both are equalities
         * between the running totals. A script the walk cannot finish - it calls
         * something the cache does not hold - contributes nothing at all rather
         * than a constraint that is only half true.
         */
        fun constrain(body: Body, system: System, opaque: Set<Int>, hooks: Map<Int, Int>) {
            val blocks = body.cfg.blocks
            val entry = arrayOfNulls<Array<Form>>(blocks.size)
            entry[0] = Array(STACKS) { Form() }
            val worklist = ArrayDeque(listOf(0))
            val pending = ArrayList<Form>()

            while (worklist.isNotEmpty()) {
                val id = worklist.removeFirst()
                val state = Array(STACKS) { entry[id]!![it].copy() }
                var complete = true
                for (at in blocks[id].indices) {
                    val instruction = body.script[at]
                    if (Cs2Cfg.isReturn(instruction.op)) {
                        for (stack in 0 until STACKS) {
                            pending.add(state[stack].copy().also { it.add(returnVar(index.getValue(body.id), stack), -1) })
                        }
                        complete = false
                        break
                    }
                    // An opcode with no fixed effect ends the path rather than
                    // the script: everything the walk had already established
                    // upstream of it still holds.
                    val hook = hooks[instruction.op.id]?.let { hookNet(body.script, at, it) }
                    if (hook != null) {
                        for (stack in 0 until STACKS) state[stack].constant += hook[stack]
                        continue
                    }
                    if (instruction.op.id in opaque) {
                        complete = false
                        break
                    }
                    if (!advance(instruction, state, system)) {
                        system.refuse()
                        return
                    }
                }
                if (!complete) continue
                for (successor in blocks[id].successors) {
                    val known = entry[successor]
                    if (known == null) {
                        entry[successor] = Array(STACKS) { state[it].copy() }
                        worklist.addLast(successor)
                    } else {
                        for (stack in 0 until STACKS) pending.add(known[stack].difference(state[stack]))
                    }
                }
            }
            for (equation in pending) system.add(equation, body.id)
        }

        private fun advance(instruction: Cs2Instruction, state: Array<Form>, system: System): Boolean {
            val op = instruction.op
            // An opcode with no fixed effect gets a variable of its own at every
            // site, so only the constraints that run through it are lost.
            if (op.operand == Cs2Operand.TAGGED) {
                taggedStack(instruction.pushTag)?.let { state[it].constant++ }
                return true
            }
            val core = Cs2CoreOps.effectOf(op.naming)
            if (core != null) {
                for (stack in 0 until STACKS) state[stack].constant += netOf(core)[stack]
                return true
            }
            when (Cs2CoreOps.kindOf(op.naming)) {
                OpKind.JOIN_STRING -> state[1].constant += 1L - instruction.intOperand
                OpKind.GOSUB -> {
                    val callee = index[instruction.intOperand] ?: return false
                    val header = bodies[callee].script
                    state[0].constant -= header.intArgsCount
                    state[1].constant -= header.stringArgsCount
                    state[2].constant -= header.longArgsCount
                    for (stack in 0 until STACKS) state[stack].add(returnVar(callee, stack), 1)
                }
                else -> for (stack in 0 until STACKS) state[stack].add(netVar(op.id, stack), 1)
            }
            return true
        }

        /** The shallowest each stack ever is at the moment an opcode runs. */
        /**
         * The shallowest each stack is when an opcode runs, ignoring one-off
         * sites.
         *
         * A depth is only as trustworthy as the nets it was accumulated from, and
         * one opcode still carrying a wrong net drags the sites downstream of it
         * below where they belong. Requiring a depth to turn up more than once
         * before it counts throws those away without needing to know which opcode
         * was at fault; an opcode that genuinely appears only once keeps its only
         * reading.
         */
        fun ceilings(
            nets: Map<Int, IntArray>,
            returns: Map<Int, IntArray>,
            dynamic: Set<Int>,
            hooks: Map<Int, Int> = emptyMap(),
        ): Map<Int, IntArray> {
            val histogram = HashMap<Int, Array<HashMap<Int, Int>>>()
            for (body in bodies) {
                val seen = replay(body, nets, returns, dynamic, hooks) ?: continue
                for (site in seen) {
                    val stacks = histogram.getOrPut(site.opcode) { Array(STACKS) { HashMap() } }
                    for (stack in 0 until STACKS) {
                        stacks[stack].merge(site.depths[stack], 1, Int::plus)
                    }
                }
            }
            return histogram.mapValues { (_, stacks) ->
                IntArray(STACKS) { stack -> corroborated(stacks[stack]) }
            }
        }

        private fun corroborated(depths: Map<Int, Int>): Int {
            val repeated = depths.filterValues { it >= MIN_WITNESS }.keys.minOrNull()
            return repeated ?: depths.keys.min()
        }

        fun replays(
            body: Body,
            nets: Map<Int, IntArray>,
            returns: Map<Int, IntArray>,
            dynamic: Set<Int>,
        ): List<Site>? = replay(body, nets, returns, dynamic, emptyMap())

        /**
         * Depths at every instruction the walk can reach from an entry depth of
         * zero through opcodes whose net is already known.
         *
         * An opcode with no known net stops its block rather than the script, so
         * a single unsolved opcode does not cost the depths of everything around
         * it. A negative depth means the nets are wrong here and the whole
         * script's samples are dropped, since a depth that is too small would
         * lower a ceiling it has no right to.
         */
        private fun replay(
            body: Body,
            nets: Map<Int, IntArray>,
            returns: Map<Int, IntArray>,
            dynamic: Set<Int>,
            hooks: Map<Int, Int>,
        ): List<Site>? {
            val blocks = body.cfg.blocks
            val entry = arrayOfNulls<IntArray>(blocks.size)
            entry[0] = IntArray(STACKS)
            val worklist = ArrayDeque(listOf(0))
            val seen = ArrayList<Site>()

            while (worklist.isNotEmpty()) {
                val id = worklist.removeFirst()
                val state = entry[id]!!.copyOf()
                var complete = true
                for (at in blocks[id].indices) {
                    val instruction = body.script[at]
                    if (Cs2Cfg.isReturn(instruction.op)) {
                        complete = false
                        break
                    }
                    seen.add(Site(instruction.op.id, at, state.copyOf()))
                    val net = hooks[instruction.op.id]?.let { hookNet(body.script, at, it) }
                        ?: liveNet(instruction, nets, returns, dynamic)
                    if (net == null) {
                        complete = false
                        break
                    }
                    for (stack in 0 until STACKS) state[stack] += net[stack]
                    if (state.any { it < 0 }) return null
                }
                if (!complete) continue
                for (successor in blocks[id].successors) {
                    val known = entry[successor]
                    if (known == null) {
                        entry[successor] = state.copyOf()
                        worklist.addLast(successor)
                    } else if (!known.contentEquals(state)) {
                        return null
                    }
                }
            }
            return seen
        }

        private fun liveNet(
            instruction: Cs2Instruction,
            nets: Map<Int, IntArray>,
            returns: Map<Int, IntArray>,
            dynamic: Set<Int>,
        ): IntArray? {
            val op = instruction.op
            if (op.id in dynamic) return null
            if (op.operand == Cs2Operand.TAGGED) {
                return IntArray(STACKS).also { net -> taggedStack(instruction.pushTag)?.let { net[it] = 1 } }
            }
            Cs2CoreOps.effectOf(op.naming)?.let { return netOf(it) }
            return when (Cs2CoreOps.kindOf(op.naming)) {
                OpKind.JOIN_STRING -> intArrayOf(0, 1 - instruction.intOperand, 0)
                OpKind.GOSUB -> {
                    val callee = index[instruction.intOperand] ?: return null
                    val header = bodies[callee].script
                    val left = returns[callee] ?: return null
                    intArrayOf(
                        left[0] - header.intArgsCount,
                        left[1] - header.stringArgsCount,
                        left[2] - header.longArgsCount,
                    )
                }
                else -> nets[op.id]
            }
        }

        /**
         * Opcodes that explain a set of scripts the model cannot account for.
         *
         * The test is the share of an opcode's own uses that are among the
         * failures, not how many failures it appears in. An opcode with no fixed
         * effect breaks nearly every script it is used in, while a common one -
         * a store, a jump - turns up in the failures simply because it turns up
         * everywhere, and its share stays low.
         */
        fun suspects(failures: Set<Int>, exclude: Set<Int>): Set<Int> {
            val counts = HashMap<Int, Int>()
            for (id in failures) {
                for (opcode in body(id).script.instructions.mapTo(HashSet()) { it.op.id }) {
                    if (opcode !in exclude) counts[opcode] = (counts[opcode] ?: 0) + 1
                }
            }
            return counts.filterTo(HashMap()) { (opcode, failed) ->
                val total = occurrences[opcode] ?: return@filterTo false
                failed >= MIN_BLAME && failed * 100 >= total * BLAME_SHARE
            }.keys
        }

        /**
         * Opcodes whose arity is written on the stack rather than fixed.
         *
         * A hook setter is handed a type-spec string - one character per bound
         * argument, drawn from the same alphabet the cache uses for a variable's
         * type - so the same opcode pops a different number of values at every
         * site. That is visible without solving anything: the string is a
         * constant pushed a moment earlier, it is spec-shaped, and it varies.
         */
        fun typeSpecDriven(): Set<Int> {
            val total = HashMap<Int, Int>()
            val specced = HashMap<Int, Int>()
            val distinct = HashMap<Int, MutableSet<String>>()
            for (body in bodies) {
                for (at in body.script.instructions.indices) {
                    val opcode = body.script[at].op.id
                    total[opcode] = (total[opcode] ?: 0) + 1
                    val spec = specAt(body.script, at) ?: continue
                    specced[opcode] = (specced[opcode] ?: 0) + 1
                    if (spec.isNotEmpty()) distinct.getOrPut(opcode) { HashSet() }.add(spec)
                }
            }
            return total.keys.filterTo(HashSet()) { opcode ->
                val hits = specced[opcode] ?: 0
                hits * 4 >= total.getValue(opcode) * 3 && (distinct[opcode]?.size ?: 0) >= 2
            }
        }

        /**
         * What a hook costs at one site.
         *
         * The spec string names one bound argument per character, and the
         * callback's own script id rides underneath them. A trailing `Y` means a
         * trigger list follows whose length is only on the stack, so those sites
         * are left out rather than guessed at.
         */
        fun hookNet(script: Cs2Script, at: Int, component: Int): IntArray? {
            val spec = specAt(script, at) ?: return null
            if (spec.endsWith('Y')) return null
            var ints = 1 + component
            var strings = 1
            var longs = 0
            for (char in spec) {
                when (Cs2VarTypes.baseOf(char)) {
                    Cs2VarBase.STRING -> strings++
                    Cs2VarBase.LONG -> longs++
                    else -> ints++
                }
            }
            return intArrayOf(-ints, -strings, -longs)
        }

        /**
         * How much deeper each stack is than a candidate hook's spec string
         * accounts for, at its shallowest sites.
         *
         * The spec varies from site to site, so a single ceiling says nothing;
         * what should be constant is the slack left once the spec is paid for,
         * and that slack is the fixed part of the opcode's cost. [Slack.lengths]
         * is the check that the spec is really a spec: if the shallowest sites
         * agree on the slack across specs of *different* lengths, the string is
         * driving the arity. A plain opcode that happens to be handed ordinary
         * strings will only ever reach its floor at one of them.
         */
        class Slack(val ints: Int, val strings: Int, val longs: Int, val lengths: Int)

        fun slack(
            nets: Map<Int, IntArray>,
            returns: Map<Int, IntArray>,
            dynamic: Set<Int>,
            hooks: Map<Int, Int>,
        ): Map<Int, Slack> {
            val samples = HashMap<Int, MutableList<IntArray>>()
            for (body in bodies) {
                val seen = replay(body, nets, returns, dynamic, hooks) ?: continue
                for (site in seen) {
                    if (site.opcode !in dynamic) continue
                    val cost = hookNet(body.script, site.at, 0) ?: continue
                    samples.getOrPut(site.opcode) { ArrayList() }.add(
                        intArrayOf(
                            site.depths[0] + cost[0],
                            site.depths[1] + cost[1],
                            site.depths[2] + cost[2],
                            -cost[0],
                        ),
                    )
                }
            }
            return samples.mapValues { (_, sites) ->
                val ints = sites.minOf { it[0] }
                Slack(
                    ints = ints,
                    strings = sites.minOf { it[1] },
                    longs = sites.minOf { it[2] },
                    lengths = sites.filter { it[0] == ints }.mapTo(HashSet()) { it[3] }.size,
                )
            }
        }

        fun specAt(script: Cs2Script, at: Int): String? =
            (maxOf(0, at - SPEC_REACH) until at)
                .mapNotNull { script[it].strOperand }
                .lastOrNull { isTypeSpec(it) }

        private fun isTypeSpec(value: String): Boolean =
            value.dropLast(if (value.endsWith('Y')) 1 else 0).all { Cs2VarTypes.of(it) != null }

        /** Scripts that use an opcode and whose every other opcode is already settled. */
        fun witnesses(opcode: Int, resolver: Resolver): List<Body> {
            val out = ArrayList<Body>()
            for (body in bodies) {
                if (body.script.instructions.none { it.op.id == opcode }) continue
                if (body.script.instructions.any { it.op.id != opcode && !resolver.knows(it.op) }) continue
                out.add(body)
                if (out.size >= WITNESSES) break
            }
            return out
        }

        companion object {
            fun read(cache: Cache): Corpus {
                val scripts = Cs2Cache(cache)
                val bodies = ArrayList<Body>()
                val unreadable = ArrayList<Int>()
                for (id in scripts.scriptIds()) {
                    val body = try {
                        scripts.load(id)?.let { Body(id, it, Cs2Cfg.build(it)) }
                    } catch (e: Exception) {
                        null
                    }
                    if (body == null) unreadable.add(id) else bodies.add(body)
                }
                return Corpus(bodies, bodies.withIndex().associate { (at, body) -> body.id to at }, unreadable)
            }
        }
    }

    private fun netOf(effect: StackEffect) = intArrayOf(
        effect.pushInt - effect.popInt,
        effect.pushStr - effect.popStr,
        effect.pushLong - effect.popLong,
    )

    private fun taggedStack(tag: Int): Int? = when (tag) {
        Cs2PushTag.INT -> 0
        Cs2PushTag.STRING -> 1
        Cs2PushTag.LONG -> 2
        else -> null
    }

    private fun netVar(opcode: Int, stack: Int) = opcode * STACKS + stack

    private fun returnVar(script: Int, stack: Int) = RETURN_VARS + script * STACKS + stack

    // ------------------------------------------------------------ linear system

    private class Form {
        val terms = HashMap<Int, Long>()
        var constant = 0L

        fun add(variable: Int, coefficient: Long) {
            val total = (terms[variable] ?: 0L) + coefficient
            if (total == 0L) terms.remove(variable) else terms[variable] = total
        }

        fun copy(): Form = Form().also {
            it.terms.putAll(terms)
            it.constant = constant
        }

        fun difference(other: Form): Form = copy().also { result ->
            for ((variable, coefficient) in other.terms) result.add(variable, -coefficient)
            result.constant -= other.constant
        }
    }

    /**
     * The equations, solved by substitution.
     *
     * Unit propagation takes every equation that has come down to one unknown;
     * what is left is finished by elimination, always picking the variable that
     * appears in the fewest equations so the system stays sparse.
     *
     * An equation that cannot be satisfied is not evidence against the
     * arithmetic, it is evidence that one of its script's opcodes has no fixed
     * effect. It is recorded and the whole round is thrown away rather than
     * solved around, because a value derived from an inconsistent system is
     * worth nothing.
     */
    private class System {
        private val equations = ArrayList<Form?>()
        private val origins = ArrayList<Int>()
        private val byVariable = HashMap<Int, MutableSet<Int>>()
        private val queue = ArrayDeque<Int>()
        private val values = HashMap<Int, Long>()

        private val contested = HashSet<Int>()

        val blamed = HashSet<Int>()
        var refused = 0
            private set

        val size: Int get() = equations.size
        val solvedCount: Int get() = values.size

        fun refuse() {
            refused++
        }


        fun add(form: Form, script: Int) {
            if (form.terms.isEmpty() && form.constant == 0L) return
            val index = equations.size
            equations.add(form)
            origins.add(script)
            for (variable in form.terms.keys) byVariable.getOrPut(variable) { HashSet() }.add(index)
            queue.addLast(index)
        }

        fun solve(log: (String) -> Unit) {
            propagate()
            eliminate(log)
        }

        /**
         * Assigns what the corpus agrees on, and records who disagreed.
         *
         * Taking the first equation that comes down to one unknown would do if
         * every opcode had a fixed effect. One that does not makes some of its
         * equations wrong, and a value read off a wrong equation spreads through
         * everything that touches the variable, burying the evidence of where the
         * trouble started. So a round gathers every proposal for a variable and
         * commits the one most of them make; the scripts that proposed something
         * else are the ones with an opcode whose effect moves, and they are
         * exactly what needs recording. A variable with no majority is abandoned
         * instead of guessed at.
         */
        private fun propagate() {
            while (true) {
                val proposals = HashMap<Int, HashMap<Long, Int>>()
                val proposers = HashMap<Int, MutableList<Long>>()
                val equationsFor = HashMap<Int, MutableList<Int>>()

                while (queue.isNotEmpty()) {
                    val index = queue.removeFirst()
                    val form = reduce(index) ?: continue
                    val (variable, coefficient) = form.terms.entries.first()
                    if (variable in contested || form.constant % coefficient != 0L) {
                        blamed.add(origins[index])
                        discard(index)
                        continue
                    }
                    val value = -form.constant / coefficient
                    proposals.getOrPut(variable) { HashMap() }.merge(value, 1, Int::plus)
                    proposers.getOrPut(variable) { ArrayList() }.add(value)
                    equationsFor.getOrPut(variable) { ArrayList() }.add(index)
                }

                var committed = 0
                var abandoned = 0
                for ((variable, votes) in proposals) {
                    val total = votes.values.sum()
                    val winner = votes.maxByOrNull { it.value }!!
                    val agreed = winner.value * 2 > total
                    if (agreed) {
                        values[variable] = winner.key
                        committed++
                    } else {
                        contested.add(variable)
                        abandoned++
                    }
                    val cast = proposers.getValue(variable)
                    equationsFor.getValue(variable).forEachIndexed { at, index ->
                        if (!agreed || cast[at] != winner.key) blamed.add(origins[index])
                        discard(index)
                    }
                    if (agreed) byVariable.remove(variable)?.forEach { queue.addLast(it) }
                }
                if (committed == 0 && abandoned == 0) return
            }
        }

        /** Substitutes what is known; returns the form only when one unknown is left. */
        private fun reduce(index: Int): Form? {
            val form = equations[index] ?: return null
            for (variable in form.terms.keys.toList()) {
                val value = values[variable] ?: continue
                form.constant += form.terms.remove(variable)!! * value
                byVariable[variable]?.remove(index)
            }
            if (form.terms.isNotEmpty() && form.terms.size != 1) return null
            if (form.terms.isEmpty()) {
                if (form.constant != 0L) blamed.add(origins[index])
                discard(index)
                return null
            }
            return form
        }

        private fun discard(index: Int) {
            val form = equations[index] ?: return
            for (variable in form.terms.keys) byVariable[variable]?.remove(index)
            equations[index] = null
        }

        private fun eliminate(log: (String) -> Unit) {
            var combines = 0
            var round = 0
            while (combines < COMBINE_BUDGET) {
                round++
                var pivots = 0
                for (index in equations.indices) {
                    val pivot = equations[index] ?: continue
                    if (pivot.terms.size !in 2..FILL_LIMIT) continue
                    val variable = pivot.terms.keys.minByOrNull { byVariable[it]?.size ?: 0 } ?: continue
                    val coefficient = pivot.terms.getValue(variable)
                    val targets = byVariable[variable]?.toList() ?: continue
                    discard(index)
                    for (other in targets) {
                        if (other == index) continue
                        val target = equations[other] ?: continue
                        combine(target, other, pivot, variable, coefficient)
                        combines++
                    }
                    pivots++
                    propagate()
                    if (combines >= COMBINE_BUDGET) break
                }
                log("  elimination pass $round: $pivots pivots, $combines combines, ${values.size} values")
                if (pivots == 0) break
            }
        }

        private fun combine(target: Form, index: Int, pivot: Form, variable: Int, coefficient: Long) {
            val scale = target.terms.getValue(variable)
            for ((other, value) in target.terms.toList()) target.terms[other] = value * coefficient
            target.constant *= coefficient
            for ((other, value) in pivot.terms) {
                val total = (target.terms[other] ?: 0L) - value * scale
                if (total == 0L) {
                    target.terms.remove(other)
                    byVariable[other]?.remove(index)
                } else if (target.terms.put(other, total) == null) {
                    byVariable.getOrPut(other) { HashSet() }.add(index)
                }
            }
            target.constant -= pivot.constant * scale
            normalise(target)
            queue.addLast(index)
        }

        private fun normalise(form: Form) {
            var divisor = form.terms.values.fold(0L) { carried, value -> gcd(carried, value) }
            divisor = gcd(divisor, form.constant)
            if (divisor <= 1L) return
            for ((variable, value) in form.terms.toList()) form.terms[variable] = value / divisor
            form.constant /= divisor
        }

        private fun gcd(first: Long, second: Long): Long {
            var left = abs(first)
            var right = abs(second)
            while (right != 0L) {
                val next = left % right
                left = right
                right = next
            }
            return left
        }

        fun nets(): Map<Int, IntArray> = collect(0 until RETURN_VARS) { it }

        fun returns(): Map<Int, IntArray> = collect(RETURN_VARS until Int.MAX_VALUE) { it - RETURN_VARS }

        private fun collect(range: IntRange, offset: (Int) -> Int): Map<Int, IntArray> {
            val out = HashMap<Int, IntArray>()
            for ((variable, value) in values) {
                if (variable !in range) continue
                val slot = offset(variable)
                val entry = out.getOrPut(slot / STACKS) { IntArray(STACKS) { Int.MIN_VALUE } }
                entry[slot % STACKS] = value.toInt()
            }
            out.values.removeIf { entry -> entry.any { it == Int.MIN_VALUE } }
            return out
        }
    }

    // -------------------------------------------------------------- hypotheses

    /** Replays whole scripts against the solved table plus one assumption under test. */
    private class Resolver(
        private val corpus: Corpus,
        private val effects: Map<Int, StackEffect>,
        private val kinds: Map<Int, OpKind>,
    ) : Cs2Context {
        private val assumed = HashMap<Int, Cs2Op>()
        private var paramsAreStrings = false

        override fun script(id: Int): Cs2Script? = corpus.script(id)

        override fun paramIsString(paramId: Int): Boolean = paramsAreStrings

        fun knows(op: Cs2Op): Boolean =
            op.id in effects || op.id in kinds || op.id in assumed || op.operand == Cs2Operand.TAGGED

        fun assume(hypothesis: Hypothesis) {
            assumed[hypothesis.opcode] = hypothesis.applyTo(Cs2Opcodes[hypothesis.opcode])
        }

        fun balances(body: Body, hypothesis: Hypothesis): Boolean {
            val under = hypothesis.applyTo(Cs2Opcodes[hypothesis.opcode])
            return listOf(false, true).any { strings ->
                paramsAreStrings = strings
                walks(body, under)
            }
        }

        private fun walks(body: Body, under: Cs2Op): Boolean {
            val entry = arrayOfNulls<SymbolicStacks>(body.cfg.blocks.size)
            entry[0] = SymbolicStacks()
            val worklist = ArrayDeque(listOf(0))
            var returns: ReturnSignature? = null
            try {
                while (worklist.isNotEmpty()) {
                    val id = worklist.removeFirst()
                    val stacks = entry[id]!!.copy()
                    var returned = false
                    for (at in body.cfg.blocks[id].indices) {
                        val instruction = body.script[at]
                        if (Cs2Cfg.isReturn(instruction.op)) {
                            val here = ReturnSignature(stacks.intDepth, stacks.stringDepth, stacks.longs)
                            if (returns != null && returns != here) return false
                            returns = here
                            returned = true
                            break
                        }
                        step(instruction, under, stacks)
                    }
                    if (returned) continue
                    for (successor in body.cfg.blocks[id].successors) {
                        val known = entry[successor]
                        if (known == null) {
                            entry[successor] = stacks.copy()
                            worklist.addLast(successor)
                        } else if (!known.sameDepthAs(stacks)) {
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                return false
            }
            return true
        }

        private fun step(instruction: Cs2Instruction, under: Cs2Op, stacks: SymbolicStacks) {
            val op = if (instruction.op.id == under.id) under else assumed[instruction.op.id] ?: settled(instruction.op)
            val swapped = if (op === instruction.op) instruction else instruction.copy(op = op)
            stacks.apply(
                effectiveSignature(swapped, this, stacks),
                pushedIntConstant = swapped.intConstant,
                pushedString = swapped.strOperand,
            )
        }

        private fun settled(op: Cs2Op): Cs2Op {
            val effect = effects[op.id] ?: return op
            return op.copy(
                kind = kinds[op.id] ?: op.kind,
                popInt = effect.popInt, popStr = effect.popStr, popLong = effect.popLong,
                pushInt = effect.pushInt, pushStr = effect.pushStr, pushLong = effect.pushLong,
            )
        }
    }
}
