package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.Binary
import world.gregs.voidps.cache.cs2.ir.Callback
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.ExprStmt
import world.gregs.voidps.cache.cs2.ir.IntConst
import world.gregs.voidps.cache.cs2.ir.Join
import world.gregs.voidps.cache.cs2.ir.LocalAssign
import world.gregs.voidps.cache.cs2.ir.LocalRef
import world.gregs.voidps.cache.cs2.ir.LongConst
import world.gregs.voidps.cache.cs2.ir.MultiAssign
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.cs2.ir.ParallelAssign
import world.gregs.voidps.cache.cs2.ir.Return
import world.gregs.voidps.cache.cs2.ir.ScriptCall
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.StrConst
import world.gregs.voidps.cache.cs2.ir.TempRef
import world.gregs.voidps.cache.cs2.ir.TypedConst
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace

/** What a block does, once its instructions are folded back into expressions. */
class LiftedBlock(
    val statements: List<Stmt>,
    /** Set when the block ends in a conditional branch. */
    val condition: Compare?,
    /** Set when the block ends in a `SWITCH`. */
    val switchSubject: Expr?,
    val switchTable: Int,
    val returns: List<Expr>?,
    /** Values still on the stacks at the end, handed to the successor blocks. */
    val exitStack: StackState,
)

/** Expressions left on the three operand stacks at a control-flow edge. */
data class StackState(
    val ints: List<Expr> = emptyList(),
    val strings: List<Expr> = emptyList(),
    val longs: List<Expr> = emptyList(),
) {
    val isEmpty: Boolean get() = ints.isEmpty() && strings.isEmpty() && longs.isEmpty()
}

/**
 * Folds a basic block's instructions back into expression trees.
 *
 * CS2 is a stack machine emitted by an expression compiler, so replaying the
 * stack recovers the original expressions almost exactly: each opcode pops its
 * arguments in reverse order and pushes its results.
 */
class Cs2Lifter(
    private val script: Cs2Script,
    private val context: Cs2Context,
) {
    private var tempCounter = 0

    /**
     * What each temporary was bound to. A constant that outlives the statements
     * after it is bound to a temporary, and the opcodes that read their result
     * type off a constant argument have to see through that binding.
     */
    private val bound = HashMap<String, Expr>()

    private val binaryOperators = mapOf(
        "ADD" to "+", "SUB" to "-", "MULTIPLY" to "*", "DIVIDE" to "/",
        "MODULO" to "%", "AND" to "&", "OR" to "|",
    )

    fun lift(block: Cs2Cfg.Block, entry: StackState): LiftedBlock {
        val ints = ArrayList(entry.ints)
        val strings = ArrayList(entry.strings)
        val longs = ArrayList(entry.longs)
        val statements = ArrayList<Stmt>()

        // The three stacks are independent, so their contents alone cannot say
        // which value was pushed first. Return values have to go back out in the
        // original order, so live values are also tracked in one ordered list.
        val live = ArrayList<Expr>(entry.ints + entry.strings + entry.longs)

        // Where each live value was produced, as a position in [statements].
        // A value that outlives the statements after it has to be bound there,
        // or re-compiling would move the work that produced it past them.
        val producedAt = ArrayList<Int>(List(live.size) { 0 })

        fun stackFor(type: Cs2Type) = when (type) {
            Cs2Type.INT -> ints
            Cs2Type.STRING -> strings
            Cs2Type.LONG -> longs
        }

        fun pop(type: Cs2Type): Expr {
            val stack = stackFor(type)
            require(stack.isNotEmpty()) { "$type stack underflow in block ${block.id}" }
            val value = stack.removeAt(stack.size - 1)
            val position = live.indexOfLast { it.type == type }
            if (position >= 0) {
                live.removeAt(position)
                producedAt.removeAt(position)
            }
            return value
        }

        /** Pops `count` values, restoring source order. */
        fun popMany(type: Cs2Type, count: Int): List<Expr> {
            val out = ArrayList<Expr>(count)
            repeat(count) { out.add(pop(type)) }
            out.reverse()
            return out
        }

        fun push(value: Expr) {
            stackFor(value.type).add(value)
            live.add(value)
            producedAt.add(statements.size)
        }

        /**
         * The top `count` live values in the order they were pushed. Arguments
         * are spread across three stacks, so their relative order is only
         * visible here - and it has to survive to re-compilation.
         */
        fun argumentsInPushOrder(count: Int): List<Expr> = live.takeLast(count)

        /**
         * Binds every value produced before [limit] to a temporary there.
         *
         * A value still on a stack when a statement is written out was produced
         * before it, and folding that value into whatever finally consumes it
         * would move its work past everything in between.
         */
        fun bindProducedBefore(limit: Int) {
            var inserted = 0
            for (position in live.indices) {
                val value = live[position]
                if (value is TempRef || producedAt[position] >= limit) continue
                val temp = TempRef(tempName(value.type), value.type)
                bound[temp.name] = value
                statements.add(producedAt[position] + inserted, MultiAssign(listOf(temp), value))
                inserted++
                val stack = stackFor(value.type)
                stack[live.take(position).count { it.type == value.type }] = temp
                live[position] = temp
                producedAt[position] = statements.size
            }
        }

        /**
         * Binds everything still live, so nothing a block computes is left for a
         * successor to fold in. A value folded across an edge takes the work that
         * produced it with it, which re-orders the instructions and makes the
         * block a second predecessor could not reach at all.
         */
        fun bindLiveValues() = bindProducedBefore(statements.size + 1)

        var index = block.start
        while (index < block.end) {
            val instruction = script[index]
            val op = instruction.op
            val operand = instruction.intOperand

            // A run of stores with nothing pushed between them is one parallel
            // assignment: every value was computed before any was stored.
            val run = storeRunLength(index, block.end)
            if (run > 1) {
                // The first store takes the value pushed last, so both lists are
                // reversed to put them back in source order.
                val stores = ArrayList<Expr>(run)
                val popped = ArrayList<Expr>(run)
                for (offset in 0 until run) {
                    val store = script[index + offset]
                    val type = storeType(store)!!
                    stores.add(storeTarget(store, type))
                    popped.add(pop(type))
                }
                val before = statements.size
                statements.add(ParallelAssign(stores.reversed(), popped.reversed()))
                bindProducedBefore(before + 1)
                index += run
                continue
            }

            when {
                Cs2Cfg.isReturn(op) -> {
                    bindProducedBefore(statements.size)
                    return LiftedBlock(statements, null, null, -1, live.toList(), StackState())
                }

                Cs2Cfg.isConditionalBranch(op) -> {
                    val condition = liftCondition(op, ::pop)
                    bindLiveValues()
                    return LiftedBlock(
                        statements, condition, null, -1, null,
                        StackState(ints.toList(), strings.toList(), longs.toList()),
                    )
                }

                Cs2Cfg.isSwitch(op) -> {
                    val subject = pop(Cs2Type.INT)
                    bindLiveValues()
                    return LiftedBlock(
                        statements, null, subject, operand, null,
                        StackState(ints.toList(), strings.toList(), longs.toList()),
                    )
                }

                Cs2Cfg.isUnconditionalBranch(op) -> Unit

                else -> {
                    val before = statements.size
                    liftInstruction(
                        instruction, statements, ::pop, ::popMany, ::push, ::argumentsInPushOrder,
                    )
                    if (statements.size > before) bindProducedBefore(before + 1)
                }
            }
            index++
        }

        bindLiveValues()
        return LiftedBlock(
            statements, null, null, -1, null,
            StackState(ints.toList(), strings.toList(), longs.toList()),
        )
    }

    private fun tempName(type: Cs2Type): String {
        val prefix = when (type) {
            Cs2Type.INT -> "tmpInt"
            Cs2Type.STRING -> "tmpStr"
            Cs2Type.LONG -> "tmpLong"
        }
        return "$prefix${tempCounter++}"
    }

    /** The stack a single-value store consumes, or null when it is not one. */
    private fun storeType(instruction: Cs2Instruction): Cs2Type? = when (instruction.op.opName) {
        "POP_INT_LOCAL" -> Cs2Type.INT
        "POP_STRING_LOCAL", "STORE_VARC_STRING" -> Cs2Type.STRING
        "POP_LONG_LOCAL" -> Cs2Type.LONG
        "POP_VAR", "POP_VARBIT", "STORE_VARC" -> stackType(instruction, instruction.intOperand)
        else -> null
    }

    /** Where a single-value store puts its value. */
    private fun storeTarget(instruction: Cs2Instruction, type: Cs2Type): Expr =
        when (instruction.op.opName) {
            "POP_INT_LOCAL", "POP_STRING_LOCAL", "POP_LONG_LOCAL" ->
                LocalRef(instruction.intOperand, type)
            "POP_VAR" -> variable(instruction, VarSpace.VARP)
            "POP_VARBIT" -> variable(instruction, VarSpace.VARBIT)
            "STORE_VARC" -> variable(instruction, VarSpace.VARC)
            "STORE_VARC_STRING" -> variable(instruction, VarSpace.VARC_STRING, Cs2Type.STRING)
            else -> error("Not a store: ${instruction.op.opName}")
        }

    /**
     * Takes a variable operand apart.
     *
     * The domain-tagged opcodes name their space in the operand's leading byte,
     * so reading only the id would attribute every variable to the player. The
     * trailing byte carries nothing the dispatcher uses, but re-encoding has to
     * put it back, so it rides along on the reference.
     */
    private fun variable(
        instruction: Cs2Instruction,
        fallback: VarSpace,
        type: Cs2Type? = null,
    ): VarRef {
        val operand = instruction.intOperand
        val space: VarSpace
        val id: Int
        val padding: Int
        when (instruction.op.operand) {
            Cs2Operand.VAR -> {
                val domain = Cs2Packing.varDomainOf(operand)
                space = VarSpace.ofDomain(domain) ?: error("Unknown variable domain $domain")
                id = Cs2Packing.varIdOf(operand)
                padding = Cs2Packing.operandPaddingOf(operand)
            }
            Cs2Operand.TRIBYTE, Cs2Operand.WIDE_VARBIT -> {
                space = fallback
                id = Cs2Packing.varbitIdOf(operand)
                padding = Cs2Packing.operandPaddingOf(operand)
            }
            else -> {
                space = fallback
                id = operand
                padding = 0
            }
        }
        return VarRef(space, id, type ?: stackType(instruction, operand), padding = padding)
    }

    /** Domain-tagged variables live on whichever stack their own type names. */
    private fun stackType(instruction: Cs2Instruction, operand: Int): Cs2Type =
        when (instruction.op.kind) {
            OpKind.TYPED_PUSH, OpKind.TYPED_POP -> when (context.variableStack(operand)) {
                Cs2VarBase.STRING -> Cs2Type.STRING
                Cs2VarBase.LONG -> Cs2Type.LONG
                else -> Cs2Type.INT
            }
            else -> Cs2Type.INT
        }

    /**
     * How many consecutive local stores of the *same* type start at [index].
     *
     * Same-type runs pop in exact reverse of the push order, so the pairing is
     * unambiguous. A mixed-type run draws from different stacks and its push
     * order cannot be recovered positionally, so it is left as separate stores.
     */
    private fun storeRunLength(index: Int, end: Int): Int {
        val type = storeType(script[index]) ?: return 0
        var length = 0
        while (index + length < end && storeType(script[index + length]) == type) length++
        return length
    }

    private fun liftCondition(op: Cs2Op, pop: (Cs2Type) -> Expr): Compare {
        // `BRANCH_IF_TRUE` / `BRANCH_IF_FALSE` compare a single value against a
        // literal; everything else pops both sides.
        val longs = op.opName.startsWith("LONG_")
        val type = if (longs) Cs2Type.LONG else Cs2Type.INT
        return when (op.opName) {
            "BRANCH_IF_TRUE" -> Compare(op, pop(type), IntConst(1), negated = false)
            "BRANCH_IF_FALSE" -> Compare(op, pop(type), IntConst(0), negated = false)
            else -> {
                val right = pop(type)
                val left = pop(type)
                Compare(op, left, right, negated = false)
            }
        }
    }

    private fun liftInstruction(
        instruction: Cs2Instruction,
        statements: MutableList<Stmt>,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        push: (Expr) -> Unit,
        argumentsInPushOrder: (Int) -> List<Expr>,
    ) {
        val op = instruction.op
        val operand = instruction.intOperand

        if (op.operand == Cs2Operand.TAGGED) {
            when (instruction.pushTag) {
                Cs2PushTag.INT -> push(IntConst(operand))
                Cs2PushTag.LONG -> push(LongConst(instruction.longOperand))
                Cs2PushTag.STRING -> push(StrConst(instruction.strOperand ?: ""))
            }
            return
        }
        when (op.opName) {
            // Only worth marking where the opcode set also has a tagged push to
            // tell it apart from; otherwise it is the only form there is.
            "PUSH_CONSTANT_INT" -> push(IntConst(operand, dedicated = Cs2Opcodes.taggedPush() != null))
            "PUSH_CONSTANT_STRING" -> push(StrConst(instruction.strOperand ?: ""))
            "PUSH_LONG_CONSTANT" -> push(LongConst(instruction.longOperand))

            "PUSH_INT_LOCAL" -> push(LocalRef(operand, Cs2Type.INT))
            "PUSH_STRING_LOCAL" -> push(LocalRef(operand, Cs2Type.STRING))
            "PUSH_LONG_LOCAL" -> push(LocalRef(operand, Cs2Type.LONG))

            "POP_INT_LOCAL" -> statements.add(LocalAssign(operand, Cs2Type.INT, pop(Cs2Type.INT), false))
            "POP_STRING_LOCAL" -> statements.add(LocalAssign(operand, Cs2Type.STRING, pop(Cs2Type.STRING), false))
            "POP_LONG_LOCAL" -> statements.add(LocalAssign(operand, Cs2Type.LONG, pop(Cs2Type.LONG), false))

            "POP_INT_DISCARD" -> statements.add(ExprStmt(pop(Cs2Type.INT)))
            "POP_STRING_DISCARD" -> statements.add(ExprStmt(pop(Cs2Type.STRING)))
            "POP_LONG_DISCARD" -> statements.add(ExprStmt(pop(Cs2Type.LONG)))

            "PUSH_VAR" -> push(variable(instruction, VarSpace.VARP))
            "POP_VAR" -> assign(statements, variable(instruction, VarSpace.VARP), pop)
            "PUSH_VARBIT" -> push(variable(instruction, VarSpace.VARBIT))
            "POP_VARBIT" -> assign(statements, variable(instruction, VarSpace.VARBIT), pop)
            "LOAD_VARC" -> push(variable(instruction, VarSpace.VARC))
            "STORE_VARC" -> assign(statements, variable(instruction, VarSpace.VARC), pop)
            "LOAD_VARC_STRING" -> push(variable(instruction, VarSpace.VARC_STRING, Cs2Type.STRING))
            "STORE_VARC_STRING" ->
                assign(statements, variable(instruction, VarSpace.VARC_STRING, Cs2Type.STRING), pop)
            "GET_VARP_OLD" -> push(old(instruction, VarSpace.VARP))
            "GET_VARPBIT_OLD" -> push(old(instruction, VarSpace.VARBIT))
            "GET_VARN_OLD" -> push(old(instruction, VarSpace.VARN))
            "GET_VARNBIT_OLD" -> push(old(instruction, VarSpace.VARNBIT))

            "DEFINE_ARRAY" ->
                statements.add(ArrayDefine(operand shr 16, operand and 0xFFFF, pop(Cs2Type.INT)))
            "PUSH_ARRAY_INT" -> push(ArrayRef(operand, pop(Cs2Type.INT)))
            "POP_ARRAY_INT" -> {
                val value = pop(Cs2Type.INT)
                val index = pop(Cs2Type.INT)
                statements.add(ArrayAssign(operand, index, value))
            }

            "JOIN_STRING" -> push(Join(popMany(Cs2Type.STRING, operand)))

            DB_GETFIELD -> {
                val args = argumentsInPushOrder(DB_FIELD_ARGS)
                popMany(Cs2Type.INT, DB_FIELD_ARGS)
                val results = dbFieldResults(args)
                val signature = Cs2DbFields.signatureOf(results)
                emitResults(
                    OpCall(op, applyArgTypes(op, args), resultType(signature), instruction.intOperand),
                    signature, statements, push, results,
                )
            }

            "GOSUB_WITH_PARAMS" -> {
                val callee = context.script(operand) ?: error("GOSUB to missing script $operand")
                val args = argumentsInPushOrder(
                    callee.intArgsCount + callee.stringArgsCount + callee.longArgsCount,
                )
                popMany(Cs2Type.LONG, callee.longArgsCount)
                popMany(Cs2Type.STRING, callee.stringArgsCount)
                popMany(Cs2Type.INT, callee.intArgsCount)
                val returns = callee.returnSignature ?: ReturnSignature.EMPTY
                emitResults(
                    ScriptCall(operand, args, resultType(returns)),
                    returns, statements, push, callee.returnOrder,
                )
            }

            else -> liftGenericOp(instruction, statements, pop, popMany, push, argumentsInPushOrder)
        }
    }

    private fun dbFieldResults(args: List<Expr>): List<Cs2Type> {
        val column = intConstant(args.getOrNull(1)) ?: error("$DB_GETFIELD needs a constant column")
        return Cs2DbFields.results(column)
    }

    private fun assign(statements: MutableList<Stmt>, target: VarRef, pop: (Cs2Type) -> Expr) {
        statements.add(VarAssign(target, pop(target.type)))
    }

    private fun old(instruction: Cs2Instruction, fallback: VarSpace): VarRef =
        variable(instruction, fallback).copy(variant = "old")

    private fun liftGenericOp(
        instruction: Cs2Instruction,
        statements: MutableList<Stmt>,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        push: (Expr) -> Unit,
        argumentsInPushOrder: (Int) -> List<Expr>,
    ) {
        val op = instruction.op

        if (op.kind == OpKind.HOOK) {
            val signature = ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
            emitResults(liftHook(instruction, pop, popMany, resultType(signature)), signature, statements, push)
            return
        }

        if (op.kind == OpKind.TAG_SELECTED_POP) {
            liftTagSelectedPop(instruction, statements, pop, popMany, push, argumentsInPushOrder)
            return
        }

        val symbol = binaryOperators[op.opName]
        if (symbol != null && op.popInt == 2 && op.pushInt == 1) {
            val right = pop(Cs2Type.INT)
            val left = pop(Cs2Type.INT)
            push(Binary(symbol, left, right, Cs2Type.INT))
            return
        }

        val raw = argumentsInPushOrder(op.popInt + op.popStr + op.popLong)
        popMany(Cs2Type.LONG, op.popLong)
        popMany(Cs2Type.STRING, op.popStr)
        val intArgs = popMany(Cs2Type.INT, op.popInt)
        val args = applyArgTypes(op, raw)

        val signature = pushCounts(instruction, intArgs)
        if (signature.total == 0) {
            statements.add(ExprStmt(OpCall(op, args, Cs2Type.INT, instruction.intOperand)))
            return
        }
        emitResults(
            OpCall(op, args, resultType(signature), instruction.intOperand),
            signature, statements, push,
        )
    }

    /**
     * A tag-selected pop takes its type tag off the top of the int stack and one
     * value off whichever stack that tag names, before the rest of its ints.
     */
    private fun liftTagSelectedPop(
        instruction: Cs2Instruction,
        statements: MutableList<Stmt>,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        push: (Expr) -> Unit,
        argumentsInPushOrder: (Int) -> List<Expr>,
    ) {
        val op = instruction.op
        val args = argumentsInPushOrder(op.popInt + 1)
        val tag = intConstant(args.last())
            ?: error("${'$'}{op.opName} needs a constant type tag")
        val valueType = tagSelectedStack(tag)
        pop(Cs2Type.INT)
        pop(valueType)
        popMany(Cs2Type.INT, op.popInt - 1)
        val signature = ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        emitResults(
            OpCall(op, args, resultType(signature), instruction.intOperand),
            signature, statements, push,
        )
    }

    /** The type a hook format character names, where it is known. */
    private fun hookArgType(spec: Char): ArgType? = when (spec) {
        'I' -> ArgType.COMPONENT
        else -> null
    }

    /**
     * Replaces constant arguments with typed ones where the opcode says what
     * they mean. Only literals are rewritten - an expression keeps its shape.
     */
    private fun applyArgTypes(op: Cs2Op, args: List<Expr>): List<Expr> {
        if (op.argTypes.isEmpty()) return args
        return args.mapIndexed { position, argument ->
            if (argument.type != Cs2Type.INT) return@mapIndexed argument
            val kind = op.argTypes.getOrNull(position) ?: ArgType.INT
            if (kind == ArgType.INT || argument !is IntConst || argument.dedicated) argument
            else TypedConst(argument.value, kind)
        }
    }

    /**
     * Result counts for one instruction.
     *
     * `ENUM` is self-describing: its second argument is the value type. The
     * other polymorphic opcodes take the param id as their last int argument and
     * the type comes from the param definition.
     */
    private fun pushCounts(instruction: Cs2Instruction, intArgs: List<Expr>): ReturnSignature {
        val op = instruction.op
        if (op.kind == OpKind.TYPED_PUSH) {
            return when (context.variableStack(instruction.intOperand)) {
                Cs2VarBase.STRING -> ReturnSignature(0, 1, 0)
                Cs2VarBase.LONG -> ReturnSignature(0, 0, 1)
                else -> ReturnSignature(1, 0, 0)
            }
        }
        if (op.kind != OpKind.PARAM) {
            return ReturnSignature(op.pushInt, op.pushStr, op.pushLong)
        }
        val yieldsString = if (op.opName == "ENUM") {
            Cs2VarTypes.baseOfId(intConstant(intArgs.getOrNull(1)) ?: -1) == Cs2VarBase.STRING
        } else {
            val paramId = intConstant(intArgs.lastOrNull())
                ?: error("${op.opName} needs a constant param id")
            context.paramIsString(paramId)
        }
        return if (yieldsString) ReturnSignature(0, 1, 0) else ReturnSignature(1, 0, 0)
    }

    private fun unbound(expr: Expr?): Expr? =
        if (expr is TempRef) bound[expr.name]?.let(::unbound) else expr

    private fun intConstant(expr: Expr?): Int? = when (val value = unbound(expr)) {
        is IntConst -> value.value
        is TypedConst -> value.value
        else -> null
    }

    private fun resultType(signature: ReturnSignature): Cs2Type = when {
        signature.strings > 0 && signature.ints == 0 && signature.longs == 0 -> Cs2Type.STRING
        signature.longs > 0 && signature.ints == 0 && signature.strings == 0 -> Cs2Type.LONG
        else -> Cs2Type.INT
    }

    /**
     * Pushes a call's results. A single result becomes the expression itself; a
     * call producing several binds them all in one statement so the emitted
     * TypeScript can destructure it.
     */
    private fun emitResults(
        call: Expr,
        signature: ReturnSignature,
        statements: MutableList<Stmt>,
        push: (Expr) -> Unit,
        /** The callee's push order, when known; results are bound in it. */
        order: List<Cs2Type> = emptyList(),
    ) {
        if (signature.total == 0) {
            statements.add(ExprStmt(call))
            return
        }
        if (signature.total == 1) {
            push(call)
            return
        }
        // The name carries the type: a destructured binding has no annotation,
        // so this is how the compiler knows which stack each result came off.
        val types = if (order.size == signature.total) order else {
            List(signature.ints) { Cs2Type.INT } +
                List(signature.strings) { Cs2Type.STRING } +
                List(signature.longs) { Cs2Type.LONG }
        }
        val names = types.map { type -> TempRef(tempName(type), type) }
        statements.add(MultiAssign(names, call))
        names.forEach(push)
    }

    /**
     * Hook setters take a callback described by a format string: one character
     * per bound argument, optionally followed by `Y` for a trigger list.
     */
    private fun liftHook(
        instruction: Cs2Instruction,
        pop: (Cs2Type) -> Expr,
        popMany: (Cs2Type, Int) -> List<Expr>,
        type: Cs2Type,
    ): Expr {
        val op = instruction.op
        val component = if (op.popInt - op.hookTrailingPops == 1) pop(Cs2Type.INT) else null
        val formatExpr = pop(Cs2Type.STRING)
        val format = (unbound(formatExpr) as? StrConst)?.value
            ?: error("${op.opName} needs a constant format string")

        var spec = format
        var triggers = emptyList<Expr>()
        if (op.hasTriggerArray && spec.endsWith("Y")) {
            spec = spec.dropLast(1)
            val count = (pop(Cs2Type.INT) as? IntConst)?.value
                ?: error("${op.opName} needs a constant trigger-list size")
            triggers = popMany(Cs2Type.INT, count)
        }

        // Bound arguments were pushed in order, so pop them back to front. The
        // spec character also names the type: `I` marks a component.
        val args = arrayOfNulls<Expr>(spec.length)
        for (position in spec.indices.reversed()) {
            args[position] = when (hookArgStack(spec[position])) {
                Cs2Type.STRING -> pop(Cs2Type.STRING)
                Cs2Type.LONG -> pop(Cs2Type.LONG)
                else -> {
                    val value = pop(Cs2Type.INT)
                    val kind = hookArgType(spec[position])
                    if (kind != null && value is IntConst && !value.dedicated) TypedConst(value.value, kind) else value
                }
            }
        }
        val target = pop(Cs2Type.INT)
        val trailing = popMany(Cs2Type.INT, op.hookTrailingPops)

        val callback = Callback(target, format, args.filterNotNull(), triggers)
        val callArgs = trailing + callback + listOfNotNull(component)
        return OpCall(op, callArgs, type, instruction.intOperand)
    }
}
