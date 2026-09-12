package world.gregs.voidps.cache.cs2.ir

import world.gregs.voidps.cache.cs2.ArgType
import world.gregs.voidps.cache.cs2.Cs2Op

/** The three value types CS2 tracks, one per operand stack. */
enum class Cs2Type { INT, STRING, LONG }

// ---------------------------------------------------------------- expressions

sealed interface Expr {
    val type: Cs2Type
}

/**
 * [dedicated] marks the constant as having come from the standalone int-push
 * opcode rather than the tagged push RS3 spells nearly every constant with.
 * Which one the source used has to survive to re-compilation.
 */
data class IntConst(val value: Int, val dedicated: Boolean = false) : Expr {
    override val type get() = Cs2Type.INT
}

data class StrConst(val value: String) : Expr {
    override val type get() = Cs2Type.STRING
}

data class LongConst(val value: Long) : Expr {
    override val type get() = Cs2Type.LONG
}

/**
 * A constant whose meaning is known: a component hash, a packed coordinate, an
 * item id and so on. It prints in that form and packs straight back to [value],
 * so nothing is lost.
 */
data class TypedConst(val value: Int, val kind: ArgType) : Expr {
    override val type get() = Cs2Type.INT
}

/** A script local. Args occupy the lowest slots of each type. */
data class LocalRef(val slot: Int, override val type: Cs2Type) : Expr

/** One of the five global int arrays. */
data class ArrayRef(val array: Int, val index: Expr) : Expr {
    override val type get() = Cs2Type.INT
}

/**
 * Which variable space a [VarRef] addresses.
 *
 * [domain] is the byte the domain-tagged var opcodes carry in their operand; the
 * spaces without one are addressed by a dedicated opcode instead.
 */
enum class VarSpace(val prefix: String, val domain: Int = NO_DOMAIN) {
    VARP("varp", 0),
    VARN("varn", 1),
    VARC("varc", 2),
    VARW("varw", 3),
    VARMAP("varmap", 4),
    VAROBJ("varobj", 5),
    VARCLAN("varclan", 6),
    VARCLANSETTING("varclansetting", 7),
    VARUNK("varunk", 8),
    VARGROUP("vargroup", 9),
    VARBIT("varbit"),
    VARNBIT("varnbit"),
    VARC_STRING("varcstring"),
    CLAN("clanvar"),
    CLAN_SETTING("clansetting");

    companion object {
        const val NO_DOMAIN = -1

        private val byDomain = entries.filter { it.domain != NO_DOMAIN }.associateBy { it.domain }

        private val byPrefix = entries.sortedByDescending { it.prefix.length }

        fun ofDomain(domain: Int): VarSpace? = byDomain[domain]

        /** The space whose prefix starts [name], longest first so `varclan` beats `varc`. */
        fun ofPrefix(name: String): VarSpace? = byPrefix.firstOrNull { name.startsWith(it.prefix) }
    }
}

data class VarRef(
    val space: VarSpace,
    val id: Int,
    override val type: Cs2Type,
    /** Set for the clan spaces, which have bit and long flavours. */
    val variant: String? = null,
    /** Trailing operand byte the dispatcher ignores but re-encoding cannot. */
    val padding: Int = 0,
) : Expr

/** A binary operator that reads better as an operator than as a call. */
data class Binary(val symbol: String, val left: Expr, val right: Expr, override val type: Cs2Type) : Expr

/** `JOIN_STRING`: template-literal concatenation. */
data class Join(val parts: List<Expr>) : Expr {
    override val type get() = Cs2Type.STRING
}

/**
 * A plain opcode call used as a value.
 *
 * [operand] is the instruction's own byte, which several opcodes use to pick
 * which active-component register they act on.
 */
data class OpCall(
    val op: Cs2Op,
    val args: List<Expr>,
    override val type: Cs2Type,
    val operand: Int = 0,
) : Expr

/** A call to another clientscript used as a value. */
data class ScriptCall(val scriptId: Int, val args: List<Expr>, override val type: Cs2Type) : Expr

/**
 * One result of a call that produces several values. The producing call is
 * emitted once as a [MultiAssign]; each result is then referenced by name.
 */
data class TempRef(val name: String, override val type: Cs2Type) : Expr

/**
 * A callback passed to a hook setter: the script to run, its bound arguments,
 * and the trigger list that decides when it fires.
 */
data class Callback(
    /**
     * The script to run. Usually a literal id, but a script is free to compute
     * one, and the client reads it off the stack either way.
     */
    val target: Expr,
    /**
     * The compiler's type spec, one character per bound argument. The client
     * only distinguishes `s` and the long marker, but the other characters name
     * real types (`I` component, `o` obj, `d` ...), so they are kept verbatim.
     */
    val format: String,
    val args: List<Expr>,
    val triggers: List<Expr>,
) : Expr {
    val hasTriggerList: Boolean get() = format.endsWith("Y")

    /** A hook cleared by a literal -1, which binds nothing and runs nothing. */
    val cleared: Boolean get() = (target as? IntConst)?.value == -1 && args.isEmpty() && !hasTriggerList

    override val type get() = Cs2Type.INT
}

// ----------------------------------------------------------------- statements

sealed interface Stmt

/** A call evaluated for its effect. */
data class ExprStmt(val call: Expr) : Stmt

data class LocalAssign(val slot: Int, val type: Cs2Type, val value: Expr, val declare: Boolean) : Stmt

data class VarAssign(val target: VarRef, val value: Expr) : Stmt

data class ArrayAssign(val array: Int, val index: Expr, val value: Expr) : Stmt

data class ArrayDefine(val array: Int, val elementType: Int, val size: Expr) : Stmt

/**
 * `[a, b] = [x, y]` - both values are computed before either is stored, which
 * is how CS2 compiles assignments that read each other.
 */
data class ParallelAssign(
    /** Each target is a [LocalRef] or a [VarRef]. */
    val targets: List<Expr>,
    val values: List<Expr>,
) : Stmt

/** Binds every result of a multi-value call at once. */
data class MultiAssign(val names: List<TempRef>, val call: Expr) : Stmt

data class Return(val values: List<Expr>) : Stmt

data class If(val condition: Condition, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt

/** [condition] is null for a loop that only leaves through a `break`. */
data class While(val condition: Condition?, val body: List<Stmt>) : Stmt

data class SwitchCase(val keys: List<Int>, val body: List<Stmt>)

data class Switch(
    val subject: Expr,
    val cases: List<SwitchCase>,
    val default: List<Stmt>,
    /** Index of the switch table this came from, so re-compilation keeps its slot. */
    val table: Int,
    /**
     * True when the default arm sits directly after the switch and the cases
     * follow it; false when a jump leads to a default arm laid out elsewhere.
     * Both shapes occur, and TypeScript can express either by where `default:`
     * goes.
     */
    val defaultFirst: Boolean = false,
    /**
     * How many case arms are laid out before the default arm, for the shape a
     * jump leads to. The compiler puts the default wherever the source did, which
     * is not always last, and TypeScript allows `default:` in the same place.
     */
    val defaultAt: Int = Int.MAX_VALUE,
    /**
     * What the case labels index, where the corpus determines it. Null leaves the labels to
     * whatever the emitter can read off them, which is all an undetermined subject allows;
     * [ArgType.INT] says the subject was determined and indexes nothing, so they stay numbers.
     */
    val keyType: ArgType? = null,
) : Stmt

/** A goto the structurer could not fold into a loop or conditional. */
data class Goto(val label: String) : Stmt

data class Label(val name: String) : Stmt

data class Break(val label: String? = null) : Stmt

data class Continue(val label: String? = null) : Stmt

/**
 * A branch condition.
 *
 * CS2 has no boolean values: a comparison *is* a branch, and `&&` / `||` are
 * chains of them sharing a target. Keeping the chain structure is what lets the
 * compiler lay the branches back out the way it found them.
 */
sealed interface Condition

/**
 * A single comparison. [comparison] keeps the opcode that produced it so
 * re-compilation emits the same one back.
 */
data class Compare(
    val comparison: Cs2Op,
    val left: Expr,
    val right: Expr,
    /** True when the source branch jumped on the condition being false. */
    val negated: Boolean,
) : Condition {
    /** The TypeScript operator this reads as, taking [negated] into account. */
    val symbol: String get() = if (negated) invert(naturalSymbol) else naturalSymbol

    /** The operator the opcode itself means, ignoring [negated]. */
    val naturalSymbol: String
        get() {
            return when (comparison.opName.removePrefix("LONG_")) {
                "BRANCH_EQUALS" -> "=="
                "BRANCH_NOT" -> "!="
                "BRANCH_LESS_THAN" -> "<"
                "BRANCH_GREATER_THAN" -> ">"
                "BRANCH_LESS_THAN_OR_EQUALS" -> "<="
                "BRANCH_GREATER_THAN_OR_EQUALS" -> ">="
                "BRANCH_IF_TRUE" -> "=="
                "BRANCH_IF_FALSE" -> "=="
                else -> error("Not a comparison: ${comparison.opName}")
            }
        }

    private fun invert(symbol: String) = when (symbol) {
        "==" -> "!="
        "!=" -> "=="
        "<" -> ">="
        ">" -> "<="
        "<=" -> ">"
        ">=" -> "<"
        else -> error("Cannot invert $symbol")
    }
}

/** `a && b && ...` - every term branches past the body on failure. */
data class AndAll(val terms: List<Condition>) : Condition

/** `a || b || ...` - every term branches into the body on success. */
data class OrAny(val terms: List<Condition>) : Condition

/** Negates a condition, pushing the negation down to the comparisons. */
fun Condition.negate(): Condition = when (this) {
    is Compare -> copy(negated = !negated)
    is AndAll -> OrAny(terms.map { it.negate() })
    is OrAny -> AndAll(terms.map { it.negate() })
}

/** A whole decompiled script. */
class Cs2Function(
    val scriptId: Int,
    val name: String?,
    val intArgs: Int,
    val stringArgs: Int,
    val longArgs: Int,
    val intLocals: Int,
    val stringLocals: Int,
    val longLocals: Int,
    val body: List<Stmt>,
    val returns: List<Cs2Type>,
    /** Parameter types in the order callers push them, when observed. */
    val argumentOrder: List<Cs2Type> = emptyList(),
)
