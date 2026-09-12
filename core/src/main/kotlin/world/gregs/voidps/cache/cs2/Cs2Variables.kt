package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.AndAll
import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.Binary
import world.gregs.voidps.cache.cs2.ir.Callback
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Condition
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.ExprStmt
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.Join
import world.gregs.voidps.cache.cs2.ir.LocalAssign
import world.gregs.voidps.cache.cs2.ir.MultiAssign
import world.gregs.voidps.cache.cs2.ir.OpCall
import world.gregs.voidps.cache.cs2.ir.OrAny
import world.gregs.voidps.cache.cs2.ir.ParallelAssign
import world.gregs.voidps.cache.cs2.ir.Return
import world.gregs.voidps.cache.cs2.ir.ScriptCall
import world.gregs.voidps.cache.cs2.ir.Stmt
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.While

/** Every game variable a script body touches, which is what `vars.d.ts` declares. */
object Cs2Variables {

    fun collect(statements: List<Stmt>, into: MutableSet<VarRef>) {
        fun walkExpr(value: Expr) {
            when (value) {
                is VarRef -> into.add(value)
                is Binary -> { walkExpr(value.left); walkExpr(value.right) }
                is Join -> value.parts.forEach { walkExpr(it) }
                is ArrayRef -> walkExpr(value.index)
                is OpCall -> value.args.forEach { walkExpr(it) }
                is ScriptCall -> value.args.forEach { walkExpr(it) }
                is Callback -> { value.args.forEach { walkExpr(it) }; value.triggers.forEach { walkExpr(it) } }
                else -> Unit
            }
        }
        fun walkCondition(condition: Condition) {
            when (condition) {
                is Compare -> { walkExpr(condition.left); walkExpr(condition.right) }
                is AndAll -> condition.terms.forEach { walkCondition(it) }
                is OrAny -> condition.terms.forEach { walkCondition(it) }
            }
        }
        for (statement in statements) {
            when (statement) {
                is ExprStmt -> walkExpr(statement.call)
                is LocalAssign -> walkExpr(statement.value)
                is VarAssign -> { into.add(statement.target); walkExpr(statement.value) }
                is ParallelAssign -> {
                    statement.targets.forEach { walkExpr(it) }
                    statement.values.forEach { walkExpr(it) }
                }
                is ArrayAssign -> { walkExpr(statement.index); walkExpr(statement.value) }
                is ArrayDefine -> walkExpr(statement.size)
                is MultiAssign -> walkExpr(statement.call)
                is Return -> statement.values.forEach { walkExpr(it) }
                is If -> {
                    walkCondition(statement.condition)
                    collect(statement.then, into)
                    collect(statement.otherwise, into)
                }
                is While -> {
                    statement.condition?.let { walkCondition(it) }
                    collect(statement.body, into)
                }
                is Switch -> {
                    walkExpr(statement.subject)
                    statement.cases.forEach { collect(it.body, into) }
                    collect(statement.default, into)
                }
                else -> Unit
            }
        }
    }
}
