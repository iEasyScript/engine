package world.gregs.voidps.cache.cs2

import java.io.File

/**
 * How well an opcode's stack effect is known.
 *
 * [CANONICAL_FLAGGED] is deliberately not folded into [CANONICAL]: the effect was
 * read out of the handler either way, but on a flagged one an indirect call could
 * not be followed while a stack pointer was live, so a path may have been missed.
 */
enum class Cs2Confidence {
    CANONICAL,
    CANONICAL_FLAGGED,
    DYNAMIC,
    UNRESOLVED,

    /** One of the candidates the read left open, picked by which one the corpus walks under. */
    CORPUS_CHOSEN,

    /**
     * The handler reads nothing at all - it is a stub for a feature this build
     * dropped - so only the scripts compiled against the command still carry its
     * signature.
     */
    CORPUS_TYPED,
}

/**
 * The stack effects read out of the client's own handlers.
 *
 * This is the source of record. It covers opcodes no clientscript ever reaches,
 * which no corpus solve can, and where the two disagree the handler wins - the
 * corpus can only see an opcode through the scripts that happen to use it, and a
 * pop it never witnesses at its own depth reads as a pop that is not there.
 * [Cs2StackSolver] stays the way a new build is bootstrapped before this exists.
 */
object Cs2StackEffectImport {

    private val families = mapOf(
        "HOOK" to OpKind.HOOK,
        "PARAM_TYPED_RESULT" to OpKind.PARAM,
        "TYPED_PUSH" to OpKind.TYPED_PUSH,
        "TYPED_POP" to OpKind.TYPED_POP,
        "TAG_SELECTED_POP" to OpKind.TAG_SELECTED_POP,
        "ERROR_STUB" to OpKind.ERROR_STUB,
        "VARARG" to OpKind.VARARG,
    )

    fun file(): File = File("./data/cs2/stack-effects.csv")

    fun read(file: File): Map<Int, Cs2OpcodeEntry> {
        val out = HashMap<Int, Cs2OpcodeEntry>()
        for (row in Cs2Csv.read(file)) {
            val id = row.getValue("opcode").toInt()
            val confidence = when (val declared = row.getValue("confidence")) {
                "canonical" -> Cs2Confidence.CANONICAL
                "canonical-flagged" -> Cs2Confidence.CANONICAL_FLAGGED
                "dynamic" -> Cs2Confidence.DYNAMIC
                "unresolved" -> Cs2Confidence.UNRESOLVED
                "corpus-typed" -> Cs2Confidence.CORPUS_TYPED
                else -> error("Unknown confidence '$declared' for opcode $id")
            }
            // A computed count is already modelled from the operand or the callee,
            // so the family only confirms what the toolchain does with it.
            // A family may carry a parameter, as `HOOK(trail=6)` does: the shape
            // is the family's, the number is this opcode's.
            val declaredFamily = row.getValue("dynamic")
            val family = declaredFamily.substringBefore('(')
            val kind = if (family == "COMPUTED_COUNT") null else families[family]
            val trailing = FAMILY_TRAIL.find(declaredFamily)?.groupValues?.get(1)?.toInt() ?: 0
            // A dynamic family still carries the fixed prefix its caller pushes;
            // the rest of its shape is worked out per call site.
            val fixed = row["popInt"]?.toIntOrNull()?.let {
                StackEffect(
                    popInt = it,
                    pushInt = row["pushInt"]?.toIntOrNull() ?: 0,
                    pushStr = row["pushString"]?.toIntOrNull() ?: 0,
                    pushLong = row["pushLong"]?.toIntOrNull() ?: 0,
                )
            }
            out[id] = Cs2OpcodeEntry(
                id = id,
                operand = Cs2Operand.BYTE,
                kind = kind ?: OpKind.NORMAL,
                effect = row.effect() ?: fixed.takeIf { confidence == Cs2Confidence.DYNAMIC },
                confidence = confidence,
                hookTrailingPops = trailing,
            )
        }
        return out
    }

    /**
     * Signatures the read is not certain of, as the set of readings it allows.
     *
     * Two kinds of entry qualify. An unresolved one stopped at several candidate
     * signatures and chose none. A flagged one settled on a signature but records
     * that it could not follow every path *and* that the corpus reads it
     * differently - which is the same open question written the other way round,
     * so both readings are offered. A handler whose paths were all followed and
     * whose answer nothing disputes offers nothing here.
     */
    fun candidates(file: File): Map<Int, List<StackEffect>> =
        candidates(Cs2Csv.read(file).associateBy { it.getValue("opcode").toInt() })

    /** The same read, keyed by whichever build's numbering the caller holds. */
    fun candidates(rows: Map<Int, Map<String, String>>): Map<Int, List<StackEffect>> = rows.entries.mapNotNull { (id, row) ->
        val evidence = row.getValue("evidence")
        val options = when (row.getValue("confidence")) {
            // A dynamic family whose rule is a runtime guard rather than a
            // computable count offers its readings the same way an unresolved
            // one does: the corpus is the only thing that can choose.
            "unresolved", "dynamic" ->
                VARIANTS.find(evidence)?.groupValues?.get(1)?.split(';').orEmpty()
            "canonical-flagged" ->
                listOfNotNull(DISPUTED.find(evidence)?.groupValues?.get(1))
                    .flatMap { listOf(it, row.effect()?.counts?.joinToString(",") ?: return@mapNotNull null) }
            else -> emptyList()
        }
        id to options.map { counts -> counts.split(',').map(String::toInt).toEffect() }
    }.filter { it.second.size > 1 }.toMap()

    private fun List<Int>.toEffect() = StackEffect(this[0], this[1], this[2], this[3], this[4], this[5])

    private val FAMILY_TRAIL = Regex("""trail=(\d+)""")

    private val VARIANTS = Regex("""variants=([0-9,;]+)""")
    private val DISPUTED = Regex("""corpus=differs\(([0-9,]+)\)""")

    private fun Map<String, String>.effect(): StackEffect? {
        val counts = COLUMNS.map { this[it]?.toIntOrNull() ?: return null }
        return StackEffect(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])
    }

    private val COLUMNS =
        listOf("popInt", "popString", "popLong", "pushInt", "pushString", "pushLong")
}
