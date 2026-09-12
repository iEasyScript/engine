package org.projectx.tools.cs2

import java.io.File
import kotlin.math.abs
import org.projectx.tools.cs2.vm.Cs2Event
import org.projectx.tools.cs2.vm.Cs2Hook
import org.projectx.tools.cs2.vm.Cs2HookDispatcher
import org.projectx.tools.cs2.vm.Cs2Host
import org.projectx.tools.cs2.vm.Cs2Values
import org.projectx.tools.cs2.vm.Cs2Vm
import org.projectx.tools.cs2.vm.NoOpCs2Host
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.cs2.ArgType
import world.gregs.voidps.cache.cs2.Cs2Analyzer
import world.gregs.voidps.cache.cs2.Cs2BehaviourImport
import world.gregs.voidps.cache.cs2.Cs2Cache
import world.gregs.voidps.cache.cs2.Cs2Cfg
import world.gregs.voidps.cache.cs2.Cs2Codec
import world.gregs.voidps.cache.cs2.Cs2ComponentTally
import world.gregs.voidps.cache.cs2.Cs2Declarations
import world.gregs.voidps.cache.cs2.Cs2Decompile
import world.gregs.voidps.cache.cs2.Cs2DispatchTableImport
import world.gregs.voidps.cache.cs2.Cs2Emitter
import world.gregs.voidps.cache.cs2.Cs2Gamevals
import world.gregs.voidps.cache.cs2.Cs2NameImport
import world.gregs.voidps.cache.cs2.Cs2NameOrigin
import world.gregs.voidps.cache.cs2.Cs2Naming
import world.gregs.voidps.cache.cs2.Cs2Op
import world.gregs.voidps.cache.cs2.Cs2OpcodeEntry
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import world.gregs.voidps.cache.cs2.Cs2Opcodes
import world.gregs.voidps.cache.cs2.Cs2Operand
import world.gregs.voidps.cache.cs2.Cs2Packing
import world.gregs.voidps.cache.cs2.Cs2Reference
import world.gregs.voidps.cache.cs2.Cs2ReferenceImport
import world.gregs.voidps.cache.cs2.Cs2Retype
import world.gregs.voidps.cache.cs2.Cs2Script
import world.gregs.voidps.cache.cs2.Cs2ShapeReading
import world.gregs.voidps.cache.cs2.Cs2StackEffectImport
import world.gregs.voidps.cache.cs2.Cs2StackSolver
import world.gregs.voidps.cache.cs2.Cs2Structurer
import world.gregs.voidps.cache.cs2.Cs2SymbolTable
import world.gregs.voidps.cache.cs2.Cs2TypeTally
import world.gregs.voidps.cache.cs2.Cs2VarTypes
import world.gregs.voidps.cache.cs2.Cs2Variables
import world.gregs.voidps.cache.cs2.OpKind
import world.gregs.voidps.cache.cs2.StackEffect
import world.gregs.voidps.cache.cs2.compile.Cs2CodeGen
import world.gregs.voidps.cache.cs2.compile.Cs2Parser
import world.gregs.voidps.cache.cs2.cs2Roots
import world.gregs.voidps.cache.cs2.cs2Trace
import world.gregs.voidps.cache.cs2.intConstant
import world.gregs.voidps.cache.cs2.ir.Goto
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.Label
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace
import world.gregs.voidps.cache.cs2.ir.While
import world.gregs.voidps.cache.cs2.verifyCodecRoundTrip
import world.gregs.voidps.cache.type.decoder.InterfaceDecoder

/**
 * Keeps the component readings that rest on nothing but the value's shape, emitting them
 * marked, rather than leaving them as the integers they are.
 */
private const val SHAPE_HEURISTIC = "--shape-heuristic"

/**
 * `cs2` sub-commands for the tools CLI.
 *
 * ```
 * cs2 calibrate           solve every opcode's operand encoding from the corpus
 * cs2 import <csv>        install a table exported from the client dispatch table
 * cs2 verify              decode + re-encode every script, assert byte identity
 * cs2 dump <id>           print the raw instruction listing for one script
 * cs2 info <id>           print one script's header
 * ```
 */
fun runCs2(cache: Cache, args: List<String>) {
    installScriptVarTypes()
    if ("--legacy-opcodes" in args) {
        println("Using the built-in pre-RS3 opcode table.")
    } else {
        installSolvedOpcodes(cache)
    }
    val shapeReading =
        if (SHAPE_HEURISTIC in args) Cs2ShapeReading.MARKED else Cs2ShapeReading.NUMERIC
    val positional = args.filterNot { it.startsWith("--") }
    when (positional.firstOrNull() ?: "help") {
        "verify" -> cs2Verify(cache)
        "calibrate" -> cs2Calibrate(cache)
        "export-table" -> Cs2CrossBuild.exportTable(cache, positional.getOrNull(1))
        "coldstart" -> Cs2CrossBuild.coldStart(cache)
        "unscramble" -> Cs2CrossBuild.unscramble(cache, args)
        "rehearse" -> Cs2CrossBuild.rehearse(cache, args)
        "import" -> cs2Import(cache, positional.getOrNull(1))
        "names" -> cs2Names(cache, positional.getOrNull(1))
        "behaviour", "behavior" -> cs2Behaviour(cache, positional.getOrNull(1))
        "reference" -> cs2Reference(cache, positional.getOrNull(1))
        "variants" -> cs2Variants(cache, positional.getOrNull(1))
        "stacks" -> cs2StackEffects(cache)
        "effects" -> cs2Effects(cache, positional.getOrNull(1))
        "vartypes" -> cs2VarTypes()
        "gamevals" -> cs2Gamevals(cache)
        "types" -> cs2Types(cache, positional.getOrNull(1)?.toIntOrNull())
        "dump" -> cs2Dump(cache, positional.getOrNull(1)?.toIntOrNull())
        "info" -> cs2Info(cache, positional.getOrNull(1)?.toIntOrNull())
        "analyse", "analyze" -> cs2Analyse(cache)
        "roots" -> cs2Roots(cache)
        "trace" -> cs2Trace(cache, positional.getOrNull(1)?.toIntOrNull())
        "decompile" -> cs2Decompile(cache, positional.getOrNull(1)?.toIntOrNull(), shapeReading)
        "decompile-all" -> cs2DecompileAll(cache, positional.getOrNull(1), shapeReading)
        "roundtrip" -> cs2RoundTrip(cache, positional.getOrNull(1)?.toIntOrNull())
        "structure" -> cs2Structure(cache, positional.getOrNull(1)?.toIntOrNull())
        "diff" -> cs2Diff(cache, positional.getOrNull(1)?.toIntOrNull(), positional.getOrNull(2) == "faithful", shapeReading)
        "run" -> cs2Run(cache, positional.getOrNull(1)?.toIntOrNull(), positional.drop(2))
        "declarations" -> cs2Declarations(positional.getOrNull(1))
        "compile" -> cs2Compile(cache, positional.getOrNull(1), positional.getOrNull(2)?.toIntOrNull())
        "export" -> cs2Export(cache, positional.getOrNull(1), positional.getOrNull(2)?.toIntOrNull(), shapeReading)
        "watch" -> cs2Watch(cache, positional.getOrNull(1), positional.getOrNull(2)?.toIntOrNull(), shapeReading)
        "sources", "verify-sources" -> cs2VerifySources(cache, positional.getOrNull(1), positional.getOrNull(2)?.toIntOrNull())
        "hotswap" -> cs2Hotswap(cache, positional.getOrNull(1), positional.getOrNull(2)?.toIntOrNull(), positional.drop(3), shapeReading)
        else -> println(
            """
            cs2 sub-commands:
              calibrate           solve the opcode table for this cache from the corpus
              import <csv>        install a table exported from the client dispatch table
              names [csv]         attach the recovered opcode names to the installed table
              behaviour [csv]     attach the names and argument types read from the handlers
              reference [csv]     attach Jagex's own names for handlers matched from another build
              variants [csv]      settle the opcodes the handler read left with several candidates
              effects [csv]       install the stack effects read from the client's handlers
              stacks              solve every opcode's stack effect from the corpus instead
              vartypes            print the script variable types and what they index
              gamevals            count how often each operand renders as a gameval name
              types [value]       report what the corpus typed, or where one number has been seen
              export-table [build] write the committed per-build opcode table to re-resources/cs2
              coldstart           re-solve this build's widths with nothing installed and diff
              unscramble --to <new cache dir> [--to-build <id>] [--dispatch <csv>] [--from-table <json>]
                                  derive the next build's opcode ids from this build's table
              rehearse [--churn f] [--seed n]
                                  run the derivation against a renumbering generated here
              verify              decode and re-encode every clientscript, checking byte identity
              structure [id]      report how much of the corpus reads as structured control flow
              roots               split the validation failures into roots and cascade
              trace <id>          simulate one script, printing the stacks per instruction
              dump <id>           print the instruction listing for one script
              diff <id> [faithful] show one script beside its re-compilation, structured or literal
              info <id>           print one script's header
              export <dir> [id]   decompile one script into dir, or just the declarations
              compile <dir> <id>  compile one edited script and compare it to the cache
              sources <dir> [n]   compile every source file in dir, reporting the byte-exact rate
              watch [dir] [secs]  hot-reload scripts as they are saved

            flags:
              --legacy-opcodes    use the built-in pre-RS3 opcode table
              --shape-heuristic   on the commands that emit source, keep a component reading
                                  that rests on the value's shape alone, emitting it marked
                                  /* unverified */. Left out, such a reading stays the integer
                                  it is, so nothing unverified reaches the output.
            """.trimIndent(),
        )
    }
}

private fun cs2Decompile(cache: Cache, scriptId: Int?, shapeReading: Cs2ShapeReading) {
    if (scriptId == null) {
        println("usage: cs2 decompile <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    println(Cs2Emitter(Cs2Decompile.function(script, scriptId, analyzer), shapeReading).emit())
}

/** Writes the ambient TypeScript declarations next to the decompiled scripts. */
private fun cs2Declarations(directory: String?) {
    val target = File(directory ?: ".").apply { mkdirs() }
    target.resolve("cs2.d.ts").writeText(Cs2Declarations.generate())
    target.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
    println("wrote ${target.resolve("cs2.d.ts").absolutePath}")
    println("wrote ${target.resolve("tsconfig.json").absolutePath}")
}

private fun cs2DecompileAll(cache: Cache, directory: String?, shapeReading: Cs2ShapeReading) {
    println("Decompiling every clientscript...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    val target = directory?.let { File(it).apply { mkdirs() } }
    target?.resolve("cs2.d.ts")?.writeText(Cs2Declarations.generate())
    target?.resolve("tsconfig.json")?.writeText(Cs2Declarations.tsconfig())
    val variables = LinkedHashSet<VarRef>()
    val failures = ArrayList<Pair<Int, String>>()
    var written = 0
    var gotos = 0
    val started = System.currentTimeMillis()

    for (id in analyzer.ids) {
        val script = analyzer.script(id) ?: continue
        try {
            val source = Cs2Decompile.source(script, id, analyzer, Cs2Cache(cache).raw(id), shapeReading)
            Cs2Variables.collect(Cs2Structurer(script, id, analyzer).structure().body, variables)
            if (source.contains("goto(")) gotos++
            target?.resolve("clientscript-$id.ts")?.writeText(source)
            written++
        } catch (e: Exception) {
            failures.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }

    target?.resolve("vars.d.ts")?.writeText(Cs2Declarations.variables(variables))

    println()
    println("decompiled:      $written")
    println("variables:       ${variables.size}")
    println("failed:          ${failures.size}")
    println("with raw gotos:  $gotos")
    println("elapsed:         ${System.currentTimeMillis() - started}ms")
    if (target != null) println("written to:      ${target.absolutePath}")

    if (failures.isNotEmpty()) {
        println()
        println("First failures:")
        failures.take(10).forEach { (id, message) -> println("  script $id: $message") }
        val grouped = failures.groupingBy { it.second.take(60) }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
        println()
        println("Grouped:")
        grouped.forEach { (message, count) -> println("  %-62s %d".format(message, count)) }
    }
}

/**
 * How often each naming rule actually fires across the corpus.
 *
 * Every rule here either resolves against a table Jagex publishes or it does
 * not fire at all, so the "named" column is the whole of what is being claimed.
 * The coordinate row is the exception and is measured rather than applied: it
 * has no table to check against, and the overlap column is why - thousands of
 * values pass a plausible-position test while provably being components.
 */
private fun cs2Gamevals(cache: Cache) {
    val scripts = Cs2Cache(cache)
    val domains = sortedMapOf<VarSpace, IntArray>(compareBy { it.ordinal })
    var varbits = 0
    var varbitsNamed = 0
    var constants = 0
    var componentShaped = 0
    var componentNamed = 0
    var coordShaped = 0
    var coordPlausible = 0
    var coordAlsoComponent = 0
    var padded = 0

    for (id in scripts.scriptIds()) {
        val script = try {
            scripts.load(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        for (instruction in script.instructions) {
            val operand = instruction.intOperand
            when (instruction.op.operand) {
                Cs2Operand.VAR -> {
                    val space = VarSpace.ofDomain(Cs2Packing.varDomainOf(operand)) ?: continue
                    val counts = domains.getOrPut(space) { IntArray(2) }
                    counts[0]++
                    if (Cs2Gamevals.varName(space, Cs2Packing.varIdOf(operand)) != null) counts[1]++
                    if (Cs2Packing.operandPaddingOf(operand) != 0) padded++
                }
                Cs2Operand.TRIBYTE, Cs2Operand.WIDE_VARBIT -> {
                    varbits++
                    if (Cs2Gamevals.varName(VarSpace.VARBIT, Cs2Packing.varbitIdOf(operand)) != null) varbitsNamed++
                    if (Cs2Packing.operandPaddingOf(operand) != 0) padded++
                }
                else -> Unit
            }
            val constant = instruction.intConstant ?: continue
            constants++
            if (Cs2Packing.isPackedComponent(constant)) {
                componentShaped++
                if (Cs2Gamevals.componentName(constant) != null) componentNamed++
            }
            if (!Cs2Packing.looksLikeCoord(constant)) continue
            coordShaped++
            if (!cache.exists(Index.MAPS, Cs2Packing.mapSquareOf(constant))) continue
            coordPlausible++
            if (Cs2Gamevals.componentName(constant) != null) coordAlsoComponent++
        }
    }

    println()
    println("variable operands            total    named")
    for ((space, counts) in domains) {
        println("  %-26s %7d  %7d".format("${space.prefix} (${Cs2Gamevals.varTable(space) ?: "no table"})", counts[0], counts[1]))
    }
    println("  %-26s %7d  %7d".format("varbit (${Cs2Gamevals.varTable(VarSpace.VARBIT)})", varbits, varbitsNamed))
    println("  operands carrying a trailing byte the dispatcher ignores: $padded")
    println()
    println("integer constants            $constants")
    println("  component-shaped           $componentShaped")
    println("  named in component.json    $componentNamed   <- rendered as component(...)")
    println("  coordinate-shaped          $coordShaped")
    println("  in a mapsquare the cache has $coordPlausible")
    println("  ...of which are named components $coordAlsoComponent   <- why coordinates are left numeric")
}

private fun cs2Analyse(cache: Cache) {
    println("Analysing clientscripts...")
    val started = System.currentTimeMillis()
    val report = Cs2Analyzer(cache).analyse { println("  $it") }
    println()
    println("analysed:      ${report.analysed}")
    println("string params: ${report.stringParams.size}")
    println("problems:      ${report.problems.size}")
    println("elapsed:       ${System.currentTimeMillis() - started}ms")

    val returning = report.returnSignatures.values.count { it.total > 0 }
    println("scripts returning values: $returning")

    if (report.problems.isNotEmpty()) {
        println()
        println("First problems:")
        report.problems.take(15).forEach { println("  script ${it.scriptId}: ${it.message}") }
        val opcode = Regex("\\(([A-Z][A-Z0-9_]*)\\)")
        val byOpcode = report.problems
            .groupingBy { opcode.find(it.message)?.groupValues?.get(1) ?: it.message.substringBefore(' ') }
            .eachCount()
            .entries.sortedByDescending { it.value }
        println()
        println("Grouped by opcode:")
        byOpcode.forEach { (name, count) -> println("  %-32s %d".format(name, count)) }
        printSuspectOpcodes(cache, report)
    }
    println()
    println(if (report.ok) "PASS - every script balances." else "FAIL")
}

/**
 * Underflows surface at whichever opcode happens to consume the stack, not at
 * the one whose signature is wrong. Ranking opcodes by how much more often they
 * appear in failing scripts than in passing ones points at the real culprit.
 */
private fun printSuspectOpcodes(cache: Cache, report: Cs2Analyzer.Report) {
    val scripts = Cs2Cache(cache)
    val failing = report.problems.map { it.scriptId }.toSet()
    val inFailing = HashMap<String, Int>()
    val inPassing = HashMap<String, Int>()

    for (id in scripts.scriptIds()) {
        val script = try {
            scripts.load(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        val used = script.instructions.mapTo(HashSet()) { it.op.opName }
        val into = if (id in failing) inFailing else inPassing
        used.forEach { into[it] = (into[it] ?: 0) + 1 }
    }

    val failCount = failing.size.coerceAtLeast(1)
    val passCount = (report.analysed - failing.size).coerceAtLeast(1)
    val ranked = inFailing.entries
        .filter { it.value >= 3 }
        .map { (name, count) ->
            val failRate = count.toDouble() / failCount
            val passRate = (inPassing[name] ?: 0).toDouble() / passCount
            Triple(name, failRate / (passRate + 1e-6), count)
        }
        .sortedByDescending { it.second }
        .take(15)

    println()
    println("Opcodes enriched in failing scripts (likely wrong signatures):")
    ranked.forEach { (name, enrichment, count) ->
        val op = Cs2Opcodes.byName(name)
        println(
            "  %-32s x%-8.1f in %d failing   pop(%d,%d,%d) push(%d,%d,%d)".format(
                name, enrichment, count,
                op?.popInt ?: 0, op?.popStr ?: 0, op?.popLong ?: 0,
                op?.pushInt ?: 0, op?.pushStr ?: 0, op?.pushLong ?: 0,
            ),
        )
    }
}

private fun cs2Verify(cache: Cache) {
    println("Verifying clientscript codec round-trip...")
    val started = System.currentTimeMillis()
    val report = verifyCodecRoundTrip(cache) { println("  $it") }
    val elapsed = System.currentTimeMillis() - started

    println()
    println("archives:   ${report.total}")
    println("identical:  ${report.identical}")
    println("mismatched: ${report.mismatched.size}")
    println("failed:     ${report.failed.size}")
    println("elapsed:    ${elapsed}ms")

    if (report.failed.isNotEmpty()) {
        println()
        println("First failures:")
        report.failed.take(10).forEach { (id, message) -> println("  script $id: $message") }
    }
    if (report.mismatched.isNotEmpty()) {
        println()
        println("First mismatches: ${report.mismatched.take(20)}")
    }
    println()
    println(if (report.ok) "PASS - every script re-encoded byte-identically." else "FAIL")
}

private fun cs2Dump(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 dump <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
    println()
    script.instructions.forEachIndexed { index, instruction ->
        println("%5d  %s".format(index, instruction))
    }
    script.switchTables.forEachIndexed { table, cases ->
        println()
        println("switch table $table:")
        cases.forEach { println("  case ${it.key} -> +${it.offset}") }
    }
}

private fun cs2Info(cache: Cache, scriptId: Int?) {
    if (scriptId == null) {
        println("usage: cs2 info <scriptId>")
        return
    }
    val script = Cs2Cache(cache).load(scriptId)
    if (script == null) {
        println("No clientscript $scriptId in the cache.")
        return
    }
    printHeader(scriptId, script)
}

private fun printHeader(scriptId: Int, script: Cs2Script) {
    println("script $scriptId")
    println("  name:         ${script.name ?: "<none>"}")
    println("  instructions: ${script.size}")
    println("  int args:     ${script.intArgsCount}   locals: ${script.intLocalsCount}")
    println("  string args:  ${script.stringArgsCount}   locals: ${script.stringLocalsCount}")
    println("  long args:    ${script.longArgsCount}   locals: ${script.longLocalsCount}")
    println("  switch tables: ${script.switchTables.size}")
}

/**
 * Reports how much of the corpus comes out as real control flow.
 *
 * A `goto` is always correct and always unpleasant, so the two numbers worth
 * watching are how many scripts contain one at all and how many are rendered
 * block-by-block because the structurer could not reproduce their layout. Pass a
 * script id to see where that script's own jumps survive.
 */
private fun cs2Structure(cache: Cache, scriptId: Int?) {
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    if (scriptId != null) {
        val script = analyzer.script(scriptId) ?: run {
            println("No clientscript $scriptId in the cache.")
            return
        }
        val body = Cs2Structurer(script, scriptId, analyzer).structure().body
        val jumps = countJumps(body)
        println("script $scriptId: ${cfgBlocks(script)} blocks, ${jumps.first} gotos, ${jumps.second} labels")
        println(if (jumps.first == 0) "fully structured" else "still jumping")
        return
    }

    var scripts = 0
    var withGotos = 0
    var gotos = 0
    val fellBack = ArrayList<Int>()
    val divergences = HashMap<String, Int>()
    for (id in analyzer.ids) {
        val script = analyzer.script(id) ?: continue
        val raw = try {
            Cs2Cache(cache).raw(id)
        } catch (e: Exception) {
            null
        }
        val function = try {
            Cs2Structurer(script, id, analyzer).structure()
        } catch (e: Exception) {
            continue
        }
        scripts++
        val count = countJumps(function.body).first
        if (count > 0) {
            withGotos++
            gotos += count
        }
        if (raw == null) continue
        var failure: String? = null
        val rebuilt = try {
            val source = Cs2Emitter(Cs2Retype(analyzer.operandTypes()).apply(function)).emit()
            Cs2CodeGen(Cs2Parser(source, analyzer).parse(), analyzer).generate()
        } catch (e: Exception) {
            failure = "threw: " + (e.message ?: e::class.simpleName).orEmpty().take(60)
            null
        }
        if (rebuilt != null && Cs2Codec.encode(rebuilt).contentEquals(raw)) continue
        fellBack.add(id)
        val where = rebuilt?.let { firstDivergence(script, it) } ?: failure ?: "did not re-compile"
        divergences[where] = (divergences[where] ?: 0) + 1
    }
    println()
    println("scripts:            $scripts")
    println("with a goto:        $withGotos")
    println("gotos in total:     $gotos")
    println("literal fallback:   ${fellBack.size}")
    if (divergences.isNotEmpty()) {
        println()
        println("Where the structured rendering first diverges:")
        divergences.entries.sortedByDescending { it.value }.take(20)
            .forEach { (where, count) -> println("  %-52s %d".format(where, count)) }
        println()
        println("Falling back: ${fellBack.take(40)}")
    }
}

private fun cfgBlocks(script: Cs2Script): Int = Cs2Cfg.build(script).blocks.size

/** Gotos and labels anywhere in a body, however deeply nested. */
private fun countJumps(body: List<Stmt>): Pair<Int, Int> {
    var gotos = 0
    var labels = 0
    fun walk(statements: List<Stmt>) {
        for (statement in statements) {
            when (statement) {
                is Goto -> gotos++
                is Label -> labels++
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
    return gotos to labels
}

/**
 * Decompiles every script, re-compiles the TypeScript, and checks the bytes
 * come back unchanged. This is the end-to-end guarantee: anything that does not
 * round-trip is a decompiler or code-generator bug, not a formatting choice.
 */
private fun cs2RoundTrip(cache: Cache, limit: Int?) {
    println("Decompiling and re-compiling every clientscript...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()

    var identical = 0
    val mismatched = ArrayList<Int>()
    val failed = ArrayList<Pair<Int, String>>()
    val divergences = HashMap<String, Int>()
    val lostWork = ArrayList<Int>()
    var viaFallback = 0
    val started = System.currentTimeMillis()
    val ids = if (limit != null) analyzer.ids.take(limit) else analyzer.ids.toList()

    for (id in ids) {
        val raw = try {
            Cs2Cache(cache).raw(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        try {
            val script = Cs2Codec.decode(raw)
            script.returnSignature = analyzer.script(id)?.returnSignature
            val source = Cs2Emitter(Cs2Decompile.function(script, id, analyzer)).emit()
            val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer).parse(), analyzer).generate()
            if (Cs2Codec.encode(rebuilt).contentEquals(raw)) {
                identical++
            } else if (faithfulMatches(raw, script, id, analyzer)) {
                identical++
                viaFallback++
            } else {
                mismatched.add(id)
                firstDivergence(script, rebuilt)?.let { divergences[it] = (divergences[it] ?: 0) + 1 }
                if (!sameWork(script, rebuilt)) lostWork.add(id)
            }
        } catch (e: Exception) {
            failed.add(id to (e.message ?: e::class.simpleName ?: "unknown"))
        }
    }

    println()
    println("scripts:    ${ids.size}")
    println("identical:  $identical  (${viaFallback} of them via the faithful fallback)")
    println("mismatched: ${mismatched.size}")
    println("failed:     ${failed.size}")
    println("elapsed:    ${System.currentTimeMillis() - started}ms")

    if (failed.isNotEmpty()) {
        println()
        println("Failed scripts: ${failed.take(12).map { it.first }}")
        println("Failure causes:")
        failed.groupingBy { it.second.take(70) }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .forEach { (message, count) -> println("  %-72s %d".format(message, count)) }
    }
    println(
        if (lostWork.isEmpty()) "every mismatch is layout-only; no instructions gained or lost"
        else "WARNING: ${lostWork.size} scripts changed their actual work: ${lostWork.take(10)}",
    )
    if (divergences.isNotEmpty()) {
        println()
        println("Where the opcode sequence first diverges:")
        divergences.entries.sortedByDescending { it.value }.take(15)
            .forEach { (where, count) -> println("  %-52s %d".format(where, count)) }
    }
    if (mismatched.isNotEmpty()) println("\nFirst mismatches: ${mismatched.take(20)}")
    println()
    println(if (mismatched.isEmpty() && failed.isEmpty()) "PASS" else "FAIL")
}

/**
 * Shows the original and re-compiled instruction listings side by side.
 *
 * `faithful` renders the literal block-by-block fallback instead, which is the
 * only way to see why a script the structurer cannot express fails to re-encode.
 */
private fun cs2Diff(cache: Cache, scriptId: Int?, faithful: Boolean, shapeReading: Cs2ShapeReading) {
    if (scriptId == null) {
        println("usage: cs2 diff <scriptId>")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val original = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }
    val source = Cs2Emitter(Cs2Decompile.function(original, scriptId, analyzer, faithful), shapeReading).emit()
    val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer).parse(), analyzer).generate()

    println(source)
    println("header: locals ${original.intLocalsCount}/${original.stringLocalsCount}/${original.longLocalsCount} " +
        "vs ${rebuilt.intLocalsCount}/${rebuilt.stringLocalsCount}/${rebuilt.longLocalsCount}")
    println("switch tables: ${original.switchTables.size} vs ${rebuilt.switchTables.size}")
    println("instructions: ${original.size} vs ${rebuilt.size}")
    original.switchTables.forEachIndexed { index, table ->
        val other = rebuilt.switchTables.getOrNull(index)
        if (table != other) {
            println("switch table $index differs:")
            println("  original:   ${table.take(12)}")
            println("  recompiled: ${other?.take(12)}")
        }
    }
    println()
    reportWorkDifference(original, rebuilt)
    println("%-4s %-40s %s".format("#", "original", "recompiled"))
    val count = maxOf(original.size, rebuilt.size)
    var shown = 0
    for (index in 0 until count) {
        val left = original.instructions.getOrNull(index)
        val right = rebuilt.instructions.getOrNull(index)
        // Jump distances shift as soon as anything else differs, so only flag
        // places where the actual opcode sequence diverges.
        val jump = left?.op?.let { Cs2Cfg.isConditionalBranch(it) || Cs2Cfg.isUnconditionalBranch(it) } == true
        val same = if (jump) left?.op?.opName == right?.op?.opName else left?.toString() == right?.toString()
        if (same) continue
        println("%-4d %-40s %-40s".format(index, left?.toString() ?: "", right?.toString() ?: ""))
        if (++shown > 12) {
            println("... stopping after the first dozen opcode differences")
            break
        }
    }
    if (shown == 0) {
        println("(the opcode sequence matches)")
        for (index in 0 until count) {
            val left = original.instructions.getOrNull(index) ?: continue
            val right = rebuilt.instructions.getOrNull(index) ?: continue
            if (left.toString() == right.toString()) continue
            println("%-4d %-40s %-40s  target %d vs %d".format(
                index, left, right, index + left.intOperand + 1, index + right.intOperand + 1))
            if (++shown > 8) break
        }
    }
}

/** The first place two instruction lists stop agreeing, as `original -> rebuilt`. */
private fun firstDivergence(original: Cs2Script, rebuilt: Cs2Script): String? {
    for (index in 0 until maxOf(original.size, rebuilt.size)) {
        val left = original.instructions.getOrNull(index)
        val right = rebuilt.instructions.getOrNull(index)
        val jump = left?.op?.let { Cs2Cfg.isConditionalBranch(it) || Cs2Cfg.isUnconditionalBranch(it) } == true
        val same = if (jump) left?.op?.opName == right?.op?.opName else left?.toString() == right?.toString()
        if (same) continue
        return "${left?.op?.opName ?: "<end>"} -> ${right?.op?.opName ?: "<end>"}"
    }
    return null
}

/**
 * True when both scripts do the same work, ignoring layout. Jumps are excluded
 * because their distances shift with any layout change; everything else must
 * match exactly, so a mismatch here means the decompiler dropped or invented
 * instructions rather than merely arranging them differently.
 */
private fun sameWork(original: Cs2Script, rebuilt: Cs2Script): Boolean {
    fun census(script: Cs2Script) = script.instructions
        .filterNot { Cs2Cfg.isConditionalBranch(it.op) || Cs2Cfg.isUnconditionalBranch(it.op) }
        .groupingBy { it.toString() }
        .eachCount()
    return census(original) == census(rebuilt)
}

/** Lists instructions the rebuilt script gained or lost, ignoring jumps. */
private fun reportWorkDifference(original: Cs2Script, rebuilt: Cs2Script) {
    fun census(script: Cs2Script) = script.instructions
        .filterNot { Cs2Cfg.isConditionalBranch(it.op) || Cs2Cfg.isUnconditionalBranch(it.op) }
        .groupingBy { it.toString() }
        .eachCount()
    val before = census(original)
    val after = census(rebuilt)
    val changed = (before.keys + after.keys)
        .mapNotNull { key ->
            val delta = (after[key] ?: 0) - (before[key] ?: 0)
            if (delta == 0) null else key to delta
        }
    if (changed.isEmpty()) {
        println("work: unchanged (layout only)")
        return
    }
    println("work changed:")
    changed.sortedByDescending { abs(it.second) }.take(10).forEach { (key, delta) ->
        println("  %+d  %s".format(delta, key))
    }
}

/** Runs a script on the virtual machine with a do-nothing host, for smoke testing. */
private fun cs2Run(cache: Cache, scriptId: Int?, args: List<String>) {
    if (scriptId == null) {
        println("usage: cs2 run <scriptId> [intArgs...]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val script = analyzer.script(scriptId) ?: run {
        println("No clientscript $scriptId in the cache.")
        return
    }

    val calls = ArrayList<String>()
    val host = object : NoOpCs2Host() {
        override fun invoke(op: Cs2Op, operand: Int, values: Cs2Values): Cs2Values {
            calls.add("${op.opName}(${values.ints.joinToString(", ")}${
                if (values.strings.isEmpty()) "" else ", " + values.strings.joinToString(", ") { "\"$it\"" }
            })")
            return Cs2Host.defaults(op)
        }

        override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) {
            calls.add("${op.opName} on $component -> ${hook?.scriptId ?: "none"} args=${hook?.args?.ints}")
        }

        override fun onError(message: String) = println("  error: $message")
    }

    val ints = args.mapNotNull { it.toIntOrNull() }
    val result = Cs2Vm(analyzer, host).execute(script, ints, scriptId = scriptId)
    println("ran script $scriptId with ints=$ints")
    println("returned: ints=${result.ints} strings=${result.strings} longs=${result.longs}")
    println("host calls (${calls.size}):")
    calls.take(40).forEach { println("  $it") }
}

/**
 * Re-decompiles with structuring disabled and checks that form round-trips.
 *
 * Structured output is the goal, but fidelity comes first: where the structurer
 * cannot reproduce a script's exact branch layout, the literal block-by-block
 * rendering can, and that is what gets written out for those scripts.
 */
private fun faithfulMatches(raw: ByteArray, script: Cs2Script, id: Int, analyzer: Cs2Analyzer): Boolean =
    try {
        val source = Cs2Emitter(Cs2Decompile.function(script, id, analyzer, faithful = true)).emit()
        val rebuilt = Cs2CodeGen(Cs2Parser(source, analyzer).parse(), analyzer).generate()
        Cs2Codec.encode(rebuilt).contentEquals(raw)
    } catch (e: Exception) {
        false
    }

/**
 * Seeds a source folder: one script, or the ambient declarations it needs.
 *
 * A folder with no `cs2.d.ts` will not type-check in an editor even though it
 * compiles here, so exporting nothing but a script is rarely what is wanted.
 */
private fun cs2Export(cache: Cache, directory: String?, scriptId: Int?, shapeReading: Cs2ShapeReading) {
    if (directory == null) {
        println("usage: cs2 export <dir> [scriptId]")
        return
    }
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val project = Cs2SourceProject(cache, analyzer, File(directory), shapeReading)

    if (scriptId == null) {
        println(project.exportDeclarations().message)
        println("pass a script id to export one script, or use `cs2 decompile-all $directory` for the lot")
        return
    }
    val result = project.exportSource(scriptId, overwrite = true)
    println(result.message)
    if (result.ok) println(project.verify(scriptId).summary)
}

/**
 * Compiles every source file in a folder and reports how many still encode to
 * exactly the cached bytes.
 *
 * This is the number that decides how much hot reload can be trusted: a script
 * that does not round-trip untouched will not round-trip edited either, and the
 * developer needs to know that before wondering why their change misbehaves.
 */
private fun cs2VerifySources(cache: Cache, directory: String?, limit: Int?) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    if (!folder.isDirectory) {
        println("no such folder: ${folder.absolutePath}")
        return
    }
    println("Analysing the cache...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder))

    println("Compiling ${folder.absolutePath}...")
    val started = System.currentTimeMillis()
    val sweep = reload.sweepFolder(limit, installEdits = false) { done, total ->
        if (done % 500 == 0) println("  $done / $total")
    }

    println()
    println("compiled:        ${sweep.compiled}")
    println("byte-identical:  ${sweep.identical}  (%.2f%%)".format(sweep.exactRate * 100))
    println("differing:       ${sweep.differing}")
    println("failed:          ${sweep.failed}")
    println("elapsed:         ${System.currentTimeMillis() - started}ms")

    if (sweep.differed.isNotEmpty()) {
        println()
        println("First differing: ${sweep.differed.take(30)}")
    }
    if (sweep.failures.isNotEmpty()) {
        println()
        println("First failures:")
        sweep.failures.take(10).forEach { println("  $it") }
        println()
        println("Grouped:")
        sweep.failures.groupingBy { it.message.take(70) }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
            .forEach { (message, count) -> println("  %-72s %d".format(message, count)) }
        println()
        println("located: ${sweep.failures.count { it.located }} of ${sweep.failures.size} have a line")
    }
}

/**
 * Runs one script as the cache has it, then as the source folder has it.
 *
 * This is the whole hot reload path in one command - compile, install, dispatch
 * through [Cs2HookDispatcher] against the overlay context - which is what proves
 * an edit reaches the running virtual machine rather than merely compiling. The
 * dispatcher is deliberately built *before* the reload, mirroring the editor,
 * where it is constructed once when the interface opens.
 */
private fun cs2Hotswap(cache: Cache, directory: String?, scriptId: Int?, args: List<String>, shapeReading: Cs2ShapeReading) {
    if (scriptId == null) {
        println("usage: cs2 hotswap <dir> <scriptId> [intArgs...]")
        return
    }
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder, shapeReading))

    val calls = ArrayList<String>()
    val host = object : NoOpCs2Host() {
        override fun invoke(op: Cs2Op, operand: Int, values: Cs2Values): Cs2Values {
            calls.add("${op.opName}(${(values.ints.map { it.toString() } + values.strings.map { "\"$it\"" }).joinToString(", ")})")
            return Cs2Host.defaults(op)
        }

        override fun onError(message: String) = println("  error: $message")
    }
    val dispatcher = Cs2HookDispatcher(reload, host)
    val hook = Cs2Hook(scriptId, Cs2Values(ints = args.mapNotNull { it.toIntOrNull() }))

    fun run(label: String) {
        calls.clear()
        val result = dispatcher.dispatch(hook, Cs2Event())
        println("$label:")
        println("  returned: ints=${result?.ints} strings=${result?.strings} longs=${result?.longs}")
        println("  host calls (${calls.size}):")
        calls.take(20).forEach { println("    $it") }
    }

    run("cached")
    println()
    val event = reload.reload(scriptId)
    println("reload: $event")
    event.diagnostic?.let { println("  ${it.location}") }
    println()
    run("from source")
}

/**
 * Runs the hot reload watcher without a window.
 *
 * Same pipeline the editor uses, so a reload that works here works there. With a
 * duration it runs unattended, which is how it gets tested; without one it takes
 * commands on stdin.
 */
private fun cs2Watch(cache: Cache, directory: String?, seconds: Int?, shapeReading: Cs2ShapeReading) {
    val folder = File(directory ?: Cs2SourceProject.DEFAULT_FOLDER)
    println("Analysing the cache...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val reload = Cs2HotReload(cache, analyzer, Cs2SourceProject(cache, analyzer, folder, shapeReading))

    if (!reload.start()) {
        println("cannot watch: ${reload.watcherError}")
        return
    }
    println("watching ${reload.watchedFolder?.absolutePath}")
    println("save a clientscript-<id>.ts to reload it. Commands: r <id>, v <id>, l, q")

    val drained = ArrayList<Cs2HotReload.Event>()
    val printer = Thread {
        try {
            while (true) {
                reload.drainInto(drained)
                drained.forEach { event ->
                    println("  $event")
                    val diagnostic = event.diagnostic
                    if (diagnostic != null && diagnostic.located) println("    at ${diagnostic.location}")
                    if (event.staleCallers.isNotEmpty()) {
                        println("    stale callers: ${event.staleCallers.take(20)}")
                    }
                }
                drained.clear()
                Thread.sleep(100)
            }
        } catch (e: InterruptedException) {
            // Shutting down.
        }
    }
    printer.isDaemon = true
    printer.start()

    if (seconds != null) {
        Thread.sleep(seconds * 1000L)
    } else {
        var interactive = false
        while (true) {
            val line = readlnOrNull()?.trim() ?: break
            interactive = true
            val parts = line.split(' ')
            when (parts[0]) {
                "q", "quit", "exit" -> break
                "l", "list" -> println("  overridden: ${reload.overridden}")
                "r" -> parts.getOrNull(1)?.toIntOrNull()?.let { println("  ${reload.reload(it)}") }
                "v" -> parts.getOrNull(1)?.toIntOrNull()?.let { println("  ${reload.project.verify(it).summary}") }
                "" -> Unit
                else -> println("  commands: r <id>, v <id>, l, q")
            }
        }
        // `gradle run` does not forward stdin, so an immediate EOF means there is
        // no console rather than a developer asking to stop.
        if (!interactive) {
            println("no console input; watching until interrupted")
            printer.join()
        }
    }
    printer.interrupt()
    reload.close()
    println("stopped. ${reload.log.count { !it.ok }} failures in ${reload.log.size} events")
}

/**
 * Compiles an edited script back from disk and reports whether it still matches
 * the cache. Names are resolved through the folder's declarations, so a renamed
 * variable or function compiles to exactly what it did before.
 */
private fun cs2Compile(cache: Cache, directory: String?, scriptId: Int?) {
    if (directory == null || scriptId == null) {
        println("usage: cs2 compile <dir> <scriptId>")
        return
    }
    val folder = File(directory)
    val file = folder.resolve("clientscript-$scriptId.ts")
    if (!file.isFile) {
        println("No ${file.path}")
        return
    }

    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val symbols = Cs2SymbolTable.read(folder)

    val rebuilt = try {
        Cs2CodeGen(Cs2Parser(file.readText(), analyzer, symbols).parse(), analyzer).generate()
    } catch (e: Exception) {
        println("compile failed: ${e.message}")
        return
    }
    val bytes = Cs2Codec.encode(rebuilt)
    val original = Cs2Cache(cache).raw(scriptId)

    println("compiled ${file.name}: ${bytes.size} bytes, ${rebuilt.size} instructions")
    println(
        when {
            original == null -> "no original in the cache to compare against"
            bytes.contentEquals(original) -> "identical to the cached script"
            else -> "DIFFERS from the cached script (${original.size} bytes)"
        },
    )
}

/**
 * Attaches the names recovered from the client to the installed opcode table.
 *
 * Provenance is kept per name rather than collapsed into one string: the client
 * text and this toolchain's split of it are separate fields on the opcode, and
 * an opcode with neither keeps its number.
 */
/**
 * Installs the stack effects read out of the client's own handlers.
 *
 * This replaces whatever the table held rather than merging into it: a corpus
 * solve and a handler read are two accounts of the same thing, and keeping the
 * leftovers of one under the other would leave a table nothing vouches for.
 */
private fun cs2Effects(cache: Cache, path: String?) {
    val file = File(path ?: Cs2StackEffectImport.file().path)
    if (!file.isFile) {
        println("usage: cs2 effects <stack-effects.csv> (default ${Cs2StackEffectImport.file().path})")
        return
    }
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` first.")
        return
    }
    val candidates = Cs2StackEffectImport.candidates(file)
    val read = Cs2OpcodeTable.reeffect(entries, Cs2StackEffectImport.read(file), candidates.keys)
    // An opcode the read left several readings for carries none of them, and a
    // table with no effect under it reads as one that touches no stack at all -
    // the one reading the handler rules out. So it is settled here, not left to
    // a separate command someone has to remember on the day the ids move.
    val updated = settleVariants(cache, read, candidates) ?: read
    Cs2OpcodeTable.update(cache, updated)
    Cs2OpcodeTable.install(updated)

    println()
    updated.groupingBy { it.confidence }.eachCount().entries.sortedBy { it.key?.ordinal ?: 99 }
        .forEach { (confidence, count) ->
            println("  %-20s %5d".format(confidence?.name?.lowercase() ?: "absent", count))
        }
    val families = updated.filter { it.kind != OpKind.NORMAL }.groupingBy { it.kind }.eachCount()
    println("  dynamic families:    $families")
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}

/**
 * Solves the stack effects and writes them back into the opcode table.
 *
 * The report separates the counts the corpus proves from the ones it only bounds,
 * because the arithmetic pins an opcode's net effect but never says how much of
 * it was a pop rather than a smaller push.
 */
private fun cs2StackEffects(cache: Cache) {
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` or `cs2 calibrate` first.")
        return
    }
    Cs2OpcodeTable.install(entries)
    println("Solving stack effects from the corpus (nothing assumed but the language core)...")
    val started = System.currentTimeMillis()
    val result = Cs2StackSolver.solve(cache) { println("  $it") }

    val unclassified = result.dynamic.filterValues { it == null }.keys
    println()
    println("opcodes:              ${entries.size}")
    println("  proved              ${result.forced.size}")
    println("  bounded by depth    ${result.bounded.size}")
    println("  dynamic, family put ${result.dynamic.size - unclassified.size}")
    println("  dynamic, unmatched  ${unclassified.size}")
    println("  exercised, unsolved ${result.unsolved.size} (${result.netless.size} with no net, the rest never reached)")
    println("  never exercised     ${result.unseen.size}")
    println("scripts:              ${result.scripts} walked, ${result.unreadable.size} unreadable")
    println("elapsed:              ${System.currentTimeMillis() - started}ms")

    val families = result.dynamic.values.filterNotNull().groupingBy { it }.eachCount()
    if (families.isNotEmpty()) {
        println()
        println("dynamic families: $families")
    }
    printByOccurrence("Unmatched dynamic opcodes", unclassified, result)
    printByOccurrence("Exercised but unsolved", result.unsolved, result)

    // The handler read covers opcodes no script reaches and is the better account
    // where both speak, so a solve never overwrites it - it only checks it.
    if (entries.any { it.confidence != null }) {
        crossCheckHandlerEffects(entries, result)
        Cs2OpcodeTable.install(entries)
        return
    }
    val updated = entries.map { entry ->
        entry.copy(kind = result.kinds[entry.id] ?: OpKind.NORMAL, effect = result.effects[entry.id])
    }
    Cs2OpcodeTable.update(cache, updated)
    Cs2OpcodeTable.install(updated)
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")

    val report = Cs2Analyzer(cache).analyse()
    println()
    println("whole-cache replay: ${report.analysed - report.problems.size} of ${report.analysed} scripts balance")
}

/**
 * Two independent accounts of the same thing, so the disagreements are the whole
 * point of running both.
 */
private fun crossCheckHandlerEffects(entries: List<Cs2OpcodeEntry>, result: Cs2StackSolver.Result) {
    val handler = entries.mapNotNull { entry -> entry.effect?.let { entry.id to it } }.toMap()
    val shared = result.effects.keys.filter { it in handler }
    val differing = shared.filter { handler.getValue(it) != result.effects.getValue(it) }
    println()
    println("Cross-check against the effects read from the handlers:")
    println("  both cover:          ${shared.size}")
    println("  agreeing:            ${shared.size - differing.size}")
    println("  disagreeing:         ${differing.size}")
    differing.sortedByDescending { result.occurrences[it] ?: 0 }.take(20).forEach { id ->
        println(
            "    %5d %-36s corpus %s, handler %s".format(
                id, Cs2Opcodes[id].tsName,
                result.effects.getValue(id).counts.joinToString("/"),
                handler.getValue(id).counts.joinToString("/"),
            ),
        )
    }
    println()
    println("Keeping the handler-derived effects; the solve was not written.")
}

private fun printByOccurrence(title: String, opcodes: Set<Int>, result: Cs2StackSolver.Result) {
    if (opcodes.isEmpty()) return
    println()
    println("$title (${opcodes.size}), most used first:")
    opcodes.sortedByDescending { result.occurrences[it] ?: 0 }.take(40).forEach { id ->
        val op = Cs2Opcodes[id]
        println("  %5d  %-44s %d scripts".format(id, op.tsName, result.occurrences[id] ?: 0))
    }
}

private fun cs2Names(cache: Cache, path: String?) {
    val file = File(path ?: Cs2OpcodeTable.namesFile().path)
    if (!file.isFile) {
        println("usage: cs2 names <opcode-names.csv> (default ${Cs2OpcodeTable.namesFile().path})")
        return
    }
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` or `cs2 calibrate` first.")
        return
    }
    val renamed = Cs2OpcodeTable.rename(entries, Cs2NameImport.read(file))
    Cs2OpcodeTable.update(cache, renamed)
    Cs2OpcodeTable.install(renamed)
    reportNames(renamed)
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}

/**
 * Installs what reading the handlers established: names for opcodes the client
 * carries no string for, and what each argument means.
 *
 * Argument types are what turns an id into the dev name Jagex uses for it, so
 * the count of arguments that actually render as one is the measure of this.
 */
private fun cs2Behaviour(cache: Cache, path: String?) {
    val file = File(path ?: Cs2BehaviourImport.file().path)
    if (!file.isFile) {
        println("usage: cs2 behaviour <opcode-behaviour.csv> (default ${Cs2BehaviourImport.file().path})")
        return
    }
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` or `cs2 calibrate` first.")
        return
    }
    val behaviour = Cs2BehaviourImport.read(file)
    val updated = Cs2OpcodeTable.rebehave(entries, behaviour)
    Cs2OpcodeTable.update(cache, updated)
    Cs2OpcodeTable.install(updated)

    println()
    println("read:               ${behaviour.size} opcodes")
    println("  named            ${behaviour.values.count { it.name != null }}")
    println("  argument types   ${behaviour.values.count { it.argTypes.isNotEmpty() }}")
    println("  fully typed      ${behaviour.values.count { it.argTypes.isNotEmpty() && ArgType.INT !in it.argTypes }}")
    behaviour.values.groupingBy { it.confidence }.eachCount().entries.sortedBy { it.key }
        .forEach { (confidence, count) -> println("  %-16s %5d".format(confidence, count)) }
    reportNames(updated)
    reportCoverage(cache, updated)
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}

/**
 * Installs the names Jagex's own debug symbols carry for handlers this build
 * strips, matched onto this build's opcodes by what they do.
 *
 * The names are Jagex's but the build they came from is not this one, so they
 * are kept in their own tier: a reader has to be able to tell a name this build
 * carries itself from one matched in from another. Where an opcode already
 * carries a name, only a structural one - this toolchain's own reading - gives
 * way; a client string from this build is left where it stands.
 */
private fun cs2Reference(cache: Cache, path: String?) {
    val file = File(path ?: Cs2ReferenceImport.file().path)
    if (!file.isFile) {
        println("usage: cs2 reference <opcode-reference-names.csv> (default ${Cs2ReferenceImport.file().path})")
        return
    }
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` or `cs2 calibrate` first.")
        return
    }
    val recovered = Cs2ReferenceImport.read(file)
    val existing = entries.associateBy { it.id }
    val kept = recovered.filterKeys { existing[it]?.naming?.canonical != null }
    val applied = recovered - kept.keys

    reportReferenceCollisions(recovered, existing)
    val updated = Cs2OpcodeTable.rereference(entries, applied)
    Cs2OpcodeTable.update(cache, updated)
    Cs2OpcodeTable.install(updated)

    println()
    println("read:               ${recovered.size} opcodes")
    println("  installed        ${applied.size}")
    println("  left to a client string of this build: ${kept.size}")
    recovered.values.groupingBy { it.confidence }.eachCount().entries.sortedBy { it.key }
        .forEach { (confidence, count) -> println("  %-16s %5d".format(confidence, count)) }

    val proposed = recovered.values.sumOf { (it.argTypes?.size ?: 0) + it.unapplied.size }
    val installed = applied.values.sumOf { it.argTypes?.size ?: 0 }
    println("argument slots proposed: $proposed")
    println("  installed              $installed")
    println("  no argument of their own: ${recovered.values.flatMap { it.unapplied }.groupingBy { it }.eachCount()}")

    reportNames(updated)
    reportCoverage(cache, updated)
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}

/** Opcodes the reference names would displace a name from, and which of the two is taken. */
private fun reportReferenceCollisions(
    recovered: Map<Int, Cs2Reference>,
    existing: Map<Int, Cs2OpcodeEntry>,
) {
    val collisions = recovered.values
        .filter { displaced(existing[it.id]?.naming) != null }
        .sortedBy { it.id }
    if (collisions.isEmpty()) return
    println()
    println("opcodes that already carried a name: ${collisions.size}")
    for (reference in collisions) {
        val naming = existing.getValue(reference.id).naming
        val held = displaced(naming)
        val outcome =
            if (naming.canonical != null) "kept over ${reference.name}" else "replaced by ${reference.name}"
        println("  %-6d %-24s %s".format(reference.id, held, outcome))
    }
}

/** The name a reference name has to answer for, which is never one it installed itself. */
private fun displaced(naming: Cs2Naming?): String? = naming?.canonical ?: naming?.structural

/**
 * How much of the corpus the installed names and argument types actually reach.
 *
 * An argument is counted where the constant that supplies it sits in the run of
 * pushes immediately before the call, which is the shape the compiler emits
 * whenever the argument is a literal. Anything computed further back is left out
 * rather than guessed at, so this undercounts rather than flatters.
 */
private fun reportCoverage(cache: Cache, entries: List<Cs2OpcodeEntry>) {
    val scripts = Cs2Cache(cache)
    val typed = entries.filter { it.argTypes.isNotEmpty() }.associate { it.id to it.argTypes }
    val volume = HashMap<Cs2NameOrigin, Int>()
    var instructions = 0
    var unnamed = 0
    var arguments = 0
    var named = 0

    for (id in scripts.scriptIds()) {
        val script = try {
            scripts.load(id) ?: continue
        } catch (e: Exception) {
            continue
        }
        for ((index, instruction) in script.instructions.withIndex()) {
            instructions++
            val origin = instruction.op.naming.origin
            volume[origin] = (volume[origin] ?: 0) + 1
            if (instruction.op.naming.isEmpty) unnamed++
            val kinds = typed[instruction.op.id] ?: continue
            for ((position, kind) in kinds.withIndex()) {
                if (kind == ArgType.INT || Cs2Gamevals.table(kind) == null) continue
                val constant = script.instructions
                    .getOrNull(index - kinds.size + position)?.intConstant ?: continue
                arguments++
                if (Cs2Gamevals.member(kind, constant) != null) named++
            }
        }
    }

    println()
    println("instructions:       $instructions")
    println("  unnamed opcode    $unnamed  (%.3f%%)".format(unnamed * 100.0 / instructions.coerceAtLeast(1)))
    println("  named opcode      ${instructions - unnamed}  (%.3f%%)".format((instructions - unnamed) * 100.0 / instructions.coerceAtLeast(1)))
    for (origin in Cs2NameOrigin.entries) {
        val count = volume[origin] ?: 0
        println("    %-12s %9d  (%.3f%%)".format(origin.name.lowercase(), count, count * 100.0 / instructions.coerceAtLeast(1)))
    }
    println("literal arguments with a gameval table: $arguments")
    println("  rendering as a gameval member         $named")
}

private fun reportNames(entries: List<Cs2OpcodeEntry>) {
    val byOrigin = entries.groupingBy { it.naming.origin }.eachCount()
    println()
    println("opcodes:            ${entries.size}")
    for (origin in Cs2NameOrigin.entries) {
        println("  %-12s %5d".format(origin.name.lowercase(), byOrigin[origin] ?: 0))
    }
    val shared = entries.mapNotNull { it.naming.canonical }
        .groupingBy { it }.eachCount().filterValues { it > 1 }
    println("client strings carried by more than one opcode: ${shared.size} covering ${shared.values.sum()} opcodes")
    val unnamed = entries.count { it.naming.canonical == null }
    println("opcodes with no client string at all: $unnamed")
}

/** Re-settles the opcodes whose handler read stopped at a set of candidates. */
private fun cs2Variants(cache: Cache, path: String?) {
    val file = File(path ?: Cs2StackEffectImport.file().path)
    if (!file.isFile) {
        println("usage: cs2 variants <stack-effects.csv> (default ${Cs2StackEffectImport.file().path})")
        return
    }
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println("No opcode table for this cache - run `cs2 import` first.")
        return
    }
    val updated = settleVariants(cache, entries, Cs2StackEffectImport.candidates(file)) ?: return
    Cs2OpcodeTable.update(cache, updated)
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}

/**
 * Chooses between the readings a handler read stopped at, and returns the table
 * to keep - or null when the corpus gave no reason to change it.
 *
 * The whole-cache replay before and after is the check: the corpus is what
 * chooses, so it has to be better off for the choice, and a table that made it
 * worse is not kept.
 */
private fun settleVariants(
    cache: Cache,
    entries: List<Cs2OpcodeEntry>,
    candidates: Map<Int, List<StackEffect>>,
): List<Cs2OpcodeEntry>? {
    Cs2OpcodeTable.install(entries)
    val before = Cs2Analyzer(cache).analyse().problems.size
    println("whole-cache replay before: $before scripts fail validation")

    val (updated, choices) = Cs2VariantSolver.solve(cache, entries, candidates) { println("  $it") }

    println()
    println("candidates offered:  ${candidates.size} opcodes")
    println("settled by the corpus: ${choices.size}")
    println("left open:           ${candidates.size - choices.size}")

    Cs2OpcodeTable.install(updated)
    val after = Cs2Analyzer(cache).analyse().problems.size
    println("whole-cache replay after:  $after scripts fail validation")

    if (after >= before) {
        Cs2OpcodeTable.install(entries)
        println("no improvement; the table was left as it was")
        return null
    }
    return updated
}

/**
 * What the corpus-wide type flow determined, and what it left alone.
 *
 * The number that matters is typed *switches*, not named labels: a switch has one subject with one
 * type, so typing it is the single decision, and every label follows from it.
 */
private fun cs2Types(cache: Cache, value: Int?) {
    println("Analysing clientscripts...")
    val analyzer = Cs2Analyzer(cache)
    analyzer.analyse()
    val flow = analyzer.operandTypes()
    if (value != null) {
        println("$value has been handed to these determined positions:")
        flow.sightings(value).forEach { (site, type) -> println("  %-44s %s".format(site, type)) }
        return
    }
    val tally = Cs2TypeTally()
    val retype = Cs2Retype(flow, tally)
    val components = Cs2ComponentTally()
    var failed = 0
    for (id in analyzer.ids) {
        val script = analyzer.script(id) ?: continue
        try {
            val emitter = Cs2Emitter(retype.apply(Cs2Structurer(script, id, analyzer).structure()))
            emitter.emit()
            components.add(emitter.components)
        } catch (e: Exception) {
            failed++
        }
    }

    val flowReport = flow.report
    println()
    println("corpus")
    println("  scripts lifted        ${flowReport.scripts}  (${failed} could not be re-lifted)")
    println("  carriers determined   ${flowReport.determinedCarriers}")
    println("  carriers conflicted   ${flowReport.conflictedCarriers}")
    println("  declared types typed  ${flowReport.declaredTypes}")
    println("  opcode results typed  ${flowReport.opcodeResults}")
    println("  argument slots typed  ${flowReport.argumentSlots}")
    println("  constants witnessed   ${flowReport.witnessedConstants}")

    println()
    println("switches")
    println("  total                 ${tally.switches}")
    println("  typed                 ${tally.switchesTyped}")
    println("  subject type unknown  ${tally.switches - tally.switchesTyped - tally.switchesUnnamed}")
    println("  type known, unnamed   ${tally.switchesUnnamed}  (${tally.switchesSentinelOnly} blocked only by -1)")

    println()
    println("case labels")
    println("  total                 ${tally.labels}")
    println("  named                 ${tally.labelsNamed}")
    println("  subject type unknown  ${tally.labelsUnknownSubject}")
    println("  type known, unnamed   ${tally.labelsUnnamed}")

    println()
    println("typed switches by how the subject was typed")
    tally.switchSources.entries.sortedByDescending { it.value }
        .forEach { (source, count) -> println("  %-22s %d".format(source.name.lowercase(), count)) }
    println()
    println("typed switches by table")
    tally.switchTypes.entries.sortedByDescending { it.value }
        .forEach { (type, count) -> println("  %-22s %d".format(type.name.lowercase(), count)) }

    println()
    println("declared script-variable types the corpus pinned to a table")
    flow.declaredTables().entries.sortedBy { it.key }.forEach { (id, type) ->
        val declaration = Cs2VarTypes.ofId(id)
        println("  %-4d %-6s %s".format(id, declaration?.printable ?: "?", type.name.lowercase()))
    }

    println()
    println("opcode results the corpus pinned to a table")
    flow.resultTables().entries.sortedBy { it.key.opcode }.forEach { (key, type) ->
        val op = Cs2Opcodes.find(key.opcode)
        println("  %-40s %s".format((op?.tsName ?: key.opcode.toString()) + "[${key.index}]", type.name.lowercase()))
    }

    println()
    println("opcode argument slots the corpus pinned to a table")
    flow.argumentTables().entries.sortedBy { it.key.opcode }.forEach { (key, type) ->
        val op = Cs2Opcodes.find(key.opcode)
        println("  %-40s %s".format((op?.tsName ?: key.opcode.toString()) + "(${key.position})", type.name.lowercase()))
    }

    println()
    println("constants the lifter already spelled out from an opcode argument slot: ${tally.argumentSlots}")
    println()
    println("constants this pass spelled out: ${tally.constants}")
    tally.constantSources.entries.sortedByDescending { it.value }
        .forEach { (source, count) -> println("  %-22s %d".format(source.name.lowercase(), count)) }
    println()
    println("values that read as a named component and as a position, in a position this pass types")
    println("  seen                  ${tally.ambiguousSeen}")
    println("  typed component       ${tally.ambiguousComponent}")
    println("  typed coord           ${tally.ambiguousCoord}")
    println("  left to the shape rule ${tally.ambiguousSeen - tally.ambiguousComponent - tally.ambiguousCoord}")

    println()
    println("those constants by table")
    tally.constantTypes.entries.sortedByDescending { it.value }
        .forEach { (type, count) -> println("  %-22s %d".format(type.name.lowercase(), count)) }

    println()
    println("component renderings the position contradicted, and this pass suppressed")
    println("  packed database column ${tally.suppressedColumns}")
    println("  determined constant    ${tally.suppressedConstants}")
    println("  determined case label  ${tally.suppressedLabels}")
    println("  bound hook argument    ${tally.suppressedHookArgs}")

    val rendered = components.unambiguous + components.determined + components.shaped
    println()
    println("component renderings emitted, by what vouches for them")
    println("  total                 $rendered")
    println("  unambiguous           ${components.unambiguous}  (no other reading of the integer is valid)")
    println("  type determined       ${components.determined}")
    println("  shape alone           ${components.shaped}  (left numeric; $SHAPE_HEURISTIC emits them marked /* unverified */)")
}

private fun cs2VarTypes() {
    val types = Cs2VarTypes.all.sortedBy { it.id }
    if (types.isEmpty()) {
        println("No script variable types installed - expected ${Cs2VarTypes.file().path}")
        return
    }
    val evidence = Cs2VarTypes.evidence()
    val tables = Cs2VarTypes.tables()
    println("${types.size} script variable types")
    println("  %-4s %-6s %-7s %-16s %-6s %s".format("id", "tag", "base", "default", "ids", "indexes"))
    for (type in types) {
        val seen = evidence[type.tag]
        println(
            "  %-4d %-6s %-7s %-16s %-6s %s".format(
                type.id, type.printable, type.base.name.lowercase(), type.default,
                seen?.samples ?: 0,
                tables[type.tag] ?: seen?.covering?.joinToString("|").orEmpty(),
            ),
        )
    }
    println()
    println("${tables.size} of them index a gameval table the cache's own enums prove out")
}

private fun installScriptVarTypes() {
    val file = Cs2VarTypes.file()
    if (!file.isFile) return
    Cs2VarTypes.install(Cs2VarTypes.read(file))
}

private fun installSolvedOpcodes(cache: Cache) {
    val entries = Cs2OpcodeTable.load(cache)
    if (entries == null) {
        println(
            "No solved opcode table for index-12 crc ${Cs2OpcodeTable.indexCrc(cache)} - " +
                "run `cs2 calibrate` first. Falling back to the pre-RS3 table.",
        )
        return
    }
    Cs2OpcodeTable.install(entries)
    println("Opcode table: ${entries.size} opcodes, source ${Cs2OpcodeTable.source(cache)}")
}

private fun cs2Calibrate(cache: Cache) {
    Cs2Opcodes.restoreLegacy()
    println("Solving operand encodings from the corpus (nothing assumed about the opcode set)...")
    val started = System.currentTimeMillis()
    val result = Cs2Calibrator.calibrate(cache) { println("  $it") }
    val solved = result.solved
    val ambiguous = result.ambiguous

    println()
    println("opcodes seen:    ${result.candidates.size}")
    println("solved:          ${solved.size}")
    println("ambiguous:       ${ambiguous.size}")
    println("scripts unsolved:${result.unparsed.size}")
    println("elapsed:         ${System.currentTimeMillis() - started}ms")
    println()
    println("by encoding:")
    solved.values.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }
        .forEach { (operand, count) -> println("  %-8s %d".format(operand, count)) }

    if (result.cardinalityForced.isNotEmpty()) {
        println()
        println("pinned by the global cardinality rule: ${result.cardinalityForced.toSortedMap()}")
    }
    println()
    println("switch-opcode candidates (operand always indexes the footer tables): ${result.switchCandidates.sorted()}")

    if (ambiguous.isNotEmpty()) {
        println()
        println("Unresolved - the corpus does not pin these:")
        ambiguous.entries.sortedByDescending { result.occurrences[it.key] ?: 0 }.forEach { (id, widths) ->
            println("  %5d  %-32s %d occurrences".format(id, widths.sorted().joinToString("|"), result.occurrences[id] ?: 0))
        }
    }

    val entries = solved.map { (id, operand) -> Cs2OpcodeEntry(id, operand) }
    val installed = Cs2OpcodeTable.load(cache)?.takeIf {
        Cs2OpcodeTable.source(cache)?.startsWith("binary") == true
    }
    if (installed != null) {
        crossCheckDispatchTable(solved, ambiguous, installed)
        Cs2OpcodeTable.install(installed)
        cs2StackEffects(cache)
        return
    }
    Cs2OpcodeTable.save(
        cache = cache,
        source = "calibrate",
        entries = entries,
        unresolved = ambiguous.keys.associateWith { result.occurrences[it] ?: 0 },
    )
    Cs2OpcodeTable.install(entries)
    println()
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
    cs2StackEffects(cache)
}

/**
 * The corpus solve reads widths, so it cannot name a form that only the client's
 * own decode path distinguishes: a var and a wide varbit both occupy four bytes
 * and come back as [Cs2Operand.INT]. Reporting that as a disagreement hides the
 * real ones, so only a differing WIDTH counts.
 */
private fun sameWidthUnsolvableTag(known: Cs2Operand, solved: Cs2Operand): Boolean =
    solved == Cs2Operand.INT && known.fixedBytes == Cs2Operand.INT.fixedBytes

/**
 * The stored table came from the client's dispatch table, so the solve is the
 * check and not the other way round; it is left in place either way.
 */
private fun crossCheckDispatchTable(
    solved: Map<Int, Cs2Operand>,
    ambiguous: Map<Int, Set<Cs2Operand>>,
    installed: List<Cs2OpcodeEntry>,
) {
    val authoritative = installed.associate { it.id to it.operand }
    val disagreements = solved.filter { (id, operand) ->
        val known = authoritative[id] ?: return@filter true
        known != operand && !sameWidthUnsolvableTag(known, operand)
    }
    println()
    println("Cross-check against the dispatch-table export (${authoritative.size} opcodes):")
    println("  solved and agreeing: ${solved.size - disagreements.size} of ${solved.size}")
    println("  disagreeing:         ${disagreements.size}")
    disagreements.forEach { (id, operand) -> println("    $id solved $operand, table says ${authoritative[id]}") }
    println("  left ambiguous:      ${ambiguous.size}")
    println(
        "  of those the table calls " +
            ambiguous.keys.groupingBy { authoritative[it] }.eachCount().toString(),
    )
    println()
    println("Keeping the dispatch-table export installed; the solve was not written.")
}

private fun cs2Import(cache: Cache, path: String?) {
    val file = File(path ?: Cs2OpcodeTable.file(cache).parentFile.resolve("dispatch-table.csv").path)
    if (!file.isFile) {
        println("usage: cs2 import <dispatch-table.csv>")
        return
    }
    val entries = Cs2DispatchTableImport.read(file)
    Cs2OpcodeTable.save(cache, source = "binary:${file.name}", entries = entries, unresolved = emptyMap())
    Cs2OpcodeTable.install(entries)
    println("imported ${entries.size} opcodes from ${file.absolutePath}")
    entries.groupingBy { it.operand }.eachCount().entries.sortedByDescending { it.value }
        .forEach { (operand, count) -> println("  %-8s %d".format(operand, count)) }
    println("wrote ${Cs2OpcodeTable.file(cache).absolutePath}")
}
