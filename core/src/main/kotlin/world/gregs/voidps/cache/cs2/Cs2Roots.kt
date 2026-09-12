package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.Cache

/**
 * Splits the failing scripts into the ones that fail on their own account and
 * the ones that only fail because something they call does.
 *
 * A call site consumes its callee's return signature, so only a *directly*
 * called failing script poisons its caller: a callee that validates has a
 * settled signature and stops the chain. Fixing one root therefore clears
 * everything that reaches it through failing callees.
 */
class Cs2Roots(private val analyzer: Cs2Analyzer, private val problems: List<Cs2Analyzer.ScriptProblem>) {

    private val failing: Map<Int, String> = problems.associate { it.scriptId to it.message }

    private val calls: Map<Int, Set<Int>> = failing.keys.associateWith { id ->
        analyzer.script(id)?.instructions.orEmpty()
            .filter { it.op.kind == OpKind.GOSUB && it.intOperand in failing && it.intOperand != id }
            .mapTo(HashSet()) { it.intOperand }
    }

    private val callers: Map<Int, Set<Int>> = buildMap<Int, MutableSet<Int>> {
        for ((id, targets) in calls) for (target in targets) getOrPut(target) { HashSet() }.add(id)
    }

    val roots: List<Cs2Analyzer.ScriptProblem> get() = problems.filter { calls.getValue(it.scriptId).isEmpty() }

    val cascade: Int get() = problems.size - roots.size

    /** Failing scripts that reach [scriptId] through failing callees. */
    fun dependents(scriptId: Int): Int {
        val seen = HashSet<Int>()
        val queue = ArrayDeque(listOf(scriptId))
        while (queue.isNotEmpty()) {
            for (caller in callers[queue.removeFirst()].orEmpty()) {
                if (seen.add(caller)) queue.addLast(caller)
            }
        }
        return seen.size
    }

    /** Failing scripts in a call cycle, which no acyclic pass can bootstrap. */
    fun cycles(): List<Set<Int>> =
        stronglyConnected(failing.keys) { calls.getValue(it) }.filter { it.size > 1 }

    fun cause(problem: Cs2Analyzer.ScriptProblem): String {
        val message = problem.message
        return when {
            message.startsWith("inconsistent return signature") -> "inconsistent return signature"
            message.startsWith("block") -> "block merge mismatch"
            message.startsWith("cfg") -> "cfg"
            else -> Regex("\\(([A-Z][A-Z0-9_]*)\\)").find(message)?.let {
                "${it.groupValues[1]} ${message.substringAfterLast(": ")}"
            } ?: message
        }
    }
}

fun cs2Trace(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 trace <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId) ?: return println("No clientscript $scriptId in the cache.")
    println("$scriptId returns ${script.returnSignature}, args ${script.intArgsCount}/${script.stringArgsCount}/${script.longArgsCount}")
    val walk = analyzer.simulate(script) { index, instruction, stacks ->
        val callee = if (instruction.op.kind == OpKind.GOSUB) analyzer.script(instruction.intOperand) else null
        val note = callee?.let {
            " ; args ${it.intArgsCount}/${it.stringArgsCount}/${it.longArgsCount} returns ${it.returnSignature}"
        }.orEmpty()
        println("  %4d  %-8s %s%s".format(index, stacks.toString(), instruction, note))
    }
    println("failure: ${walk.failure}")
}

fun cs2Roots(cache: Cache) {
    val analyzer = Cs2Analyzer(cache)
    val report = analyzer.analyse()
    val split = Cs2Roots(analyzer, report.problems)
    val roots = split.roots
    println("failures: ${report.problems.size}   roots: ${roots.size}   cascade: ${split.cascade}")
    println()
    println("Roots by cause:")
    roots.groupingBy { split.cause(it) }.eachCount().entries
        .sortedByDescending { it.value }
        .forEach { (cause, count) -> println("  %-52s %d".format(cause, count)) }
    println()
    println("Roots by blast radius:")
    roots.sortedByDescending { split.dependents(it.scriptId) }.forEach {
        println("  %-6d %4d dependents  %s".format(it.scriptId, split.dependents(it.scriptId), it.message))
    }
    val cycles = split.cycles()
    if (cycles.isNotEmpty()) {
        println()
        println("Failing call cycles:")
        cycles.sortedByDescending { it.size }.forEach { println("  ${it.size}: " + it.sorted().joinToString(" ")) }
    }
    println()
    println("All failures by cause:")
    report.problems.groupBy { split.cause(it) }.entries
        .sortedByDescending { it.value.size }
        .forEach { (cause, group) ->
            println("  %-52s %d".format(cause, group.size))
            println("      " + group.joinToString(" ") { it.scriptId.toString() })
        }
}
