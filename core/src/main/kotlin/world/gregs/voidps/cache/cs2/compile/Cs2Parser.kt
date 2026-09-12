package world.gregs.voidps.cache.cs2.compile

import world.gregs.voidps.cache.cs2.Cs2Op
import world.gregs.voidps.cache.cs2.ArgType
import world.gregs.voidps.cache.cs2.Cs2Opcodes
import world.gregs.voidps.cache.cs2.Cs2SymbolTable
import world.gregs.voidps.cache.cs2.Cs2EventArg
import world.gregs.voidps.cache.cs2.Cs2Gamevals
import world.gregs.voidps.cache.cs2.Cs2Symbols
import world.gregs.voidps.cache.cs2.Cs2Packing
import world.gregs.voidps.cache.cs2.Cs2DbFields
import world.gregs.voidps.cache.cs2.Cs2VarBase
import world.gregs.voidps.cache.cs2.Cs2VarTypes
import world.gregs.voidps.cache.cs2.OpKind
import world.gregs.voidps.cache.cs2.Cs2Strings
import world.gregs.voidps.cache.cs2.ir.ArrayAssign
import world.gregs.voidps.cache.cs2.ir.ArrayDefine
import world.gregs.voidps.cache.cs2.ir.ArrayRef
import world.gregs.voidps.cache.cs2.ir.Binary
import world.gregs.voidps.cache.cs2.ir.Break
import world.gregs.voidps.cache.cs2.ir.Callback
import world.gregs.voidps.cache.cs2.ir.AndAll
import world.gregs.voidps.cache.cs2.ir.Compare
import world.gregs.voidps.cache.cs2.ir.Condition
import world.gregs.voidps.cache.cs2.ir.OrAny
import world.gregs.voidps.cache.cs2.ir.negate
import world.gregs.voidps.cache.cs2.ir.Continue
import world.gregs.voidps.cache.cs2.ir.Cs2Function
import world.gregs.voidps.cache.cs2.Cs2Context
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.Expr
import world.gregs.voidps.cache.cs2.ir.ExprStmt
import world.gregs.voidps.cache.cs2.ir.Goto
import world.gregs.voidps.cache.cs2.ir.If
import world.gregs.voidps.cache.cs2.ir.IntConst
import world.gregs.voidps.cache.cs2.ir.Join
import world.gregs.voidps.cache.cs2.ir.Label
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
import world.gregs.voidps.cache.cs2.ir.Switch
import world.gregs.voidps.cache.cs2.ir.SwitchCase
import world.gregs.voidps.cache.cs2.ir.TempRef
import world.gregs.voidps.cache.cs2.ir.TypedConst
import world.gregs.voidps.cache.cs2.ir.VarAssign
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace
import world.gregs.voidps.cache.cs2.ir.While
import world.gregs.voidps.gameval.Gameval

/**
 * Parses the TypeScript subset the decompiler emits back into the IR.
 *
 * Local slots are assigned by declaration order within each type - parameters
 * first, then `let` declarations - so locals can be renamed freely without
 * changing which slot they compile to.
 */
class Cs2Parser(
    source: String,
    /** Types a call's result and a `*_PARAM` opcode's; see [Cs2Context]. */
    private val context: Cs2Context? = null,
    /**
     * Resolves identifiers that have been renamed away from their default
     * names. Without one, only the generated names are understood - which is
     * enough to compile a file nobody has touched.
     */
    private val symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY,
) {

    // The header comment is the only place the script's own name survives.
    private val headerId: Int? =
        Regex("^// clientscript (\\d+)( \\[[^\\]]*])?$", RegexOption.MULTILINE).find(source)
            ?.groupValues?.get(1)?.toIntOrNull()

    private val nameComment: String? =
        Regex("^// name: (.*)$", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)

    private val tokens = Cs2Lexer(source).tokenise()
    private var at = 0

    private class Local(val slot: Int, val type: Cs2Type)

    private val locals = HashMap<String, Local>()
    private var intCount = 0
    private var stringCount = 0
    private var longCount = 0
    private var intArgs = 0
    private var stringArgs = 0
    private var longArgs = 0
    private var scriptId = -1
    private var scriptName: String? = null
    private var returns = emptyList<Cs2Type>()

    fun parse(): Cs2Function {
        parseSignature()
        val body = parseBlock()
        return Cs2Function(
            scriptId = scriptId,
            name = scriptName,
            intArgs = intArgs,
            stringArgs = stringArgs,
            longArgs = longArgs,
            intLocals = intCount,
            stringLocals = stringCount,
            longLocals = longCount,
            body = body,
            returns = returns,
        )
    }

    // ------------------------------------------------------------- signature

    private fun parseSignature() {
        scriptName = nameComment
        while (peek().kind == TokenKind.IDENT && peek().text != "function") skip()
        expectIdent("function")
        val name = expect(TokenKind.IDENT).text
        // The header comment is authoritative, so the function can be renamed.
        scriptId = headerId ?: Cs2Symbols.scriptOf(name) ?: symbols.script(name)
            ?: error("Cannot tell which clientscript '$name' is")

        expectPunct("(")
        while (!atPunct(")")) {
            val parameter = expect(TokenKind.IDENT).text
            expectPunct(":")
            val type = parseType()
            declare(parameter, type, argument = true)
            if (atPunct(",")) skip()
        }
        expectPunct(")")
        expectPunct(":")
        returns = parseReturnType()
        expectPunct("{")
    }

    private fun parseType(): Cs2Type = when (val text = expect(TokenKind.IDENT).text) {
        "number" -> Cs2Type.INT
        "string" -> Cs2Type.STRING
        "bigint" -> Cs2Type.LONG
        else -> error("Unsupported type '$text'")
    }

    private fun parseReturnType(): List<Cs2Type> {
        if (atPunct("[")) {
            skip()
            val types = ArrayList<Cs2Type>()
            while (!atPunct("]")) {
                types.add(parseType())
                if (atPunct(",")) skip()
            }
            skip()
            return types
        }
        val text = peek().text
        if (text == "void") {
            skip()
            return emptyList()
        }
        return listOf(parseType())
    }

    private fun declare(name: String, type: Cs2Type, argument: Boolean): Local {
        val slot = when (type) {
            Cs2Type.INT -> intCount++
            Cs2Type.STRING -> stringCount++
            Cs2Type.LONG -> longCount++
        }
        if (argument) {
            when (type) {
                Cs2Type.INT -> intArgs++
                Cs2Type.STRING -> stringArgs++
                Cs2Type.LONG -> longArgs++
            }
        }
        val local = Local(slot, type)
        locals[name] = local
        return local
    }

    // ------------------------------------------------------------ statements

    private fun parseBlock(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}")) {
            if (peek().kind == TokenKind.EOF) error("Unexpected end of input, expected '}'")
            out.add(parseStatement() ?: continue)
        }
        skip()
        return out
    }

    private fun parseStatement(): Stmt? {
        // An empty statement carries no instructions; it only exists so a label
        // at the end of a block is valid TypeScript.
        if (atPunct(";")) {
            skip()
            return null
        }
        val token = peek()
        if (token.kind == TokenKind.IDENT) {
            when (token.text) {
                "let", "const" -> return parseDeclaration()
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "switch" -> return parseSwitch()
                "return" -> return parseReturn()
                "goto" -> {
                    skip()
                    expectPunct("(")
                    val label = expect(TokenKind.STRING).text
                    expectPunct(")")
                    expectPunct(";")
                    return Goto(label)
                }
                "break" -> { skip(); expectPunct(";"); return Break() }
                "continue" -> { skip(); expectPunct(";"); return Continue() }
            }
            // `L12:` is a jump label, not an expression.
            if (tokens.getOrNull(at + 1)?.text == ":" && token.text.startsWith("L")) {
                skip()
                skip()
                return Label(token.text)
            }
        }
        if (atPunct("[")) return parseParallelAssign()
        return parseExpressionStatement()
    }

    private fun parseDeclaration(): Stmt {
        val keyword = expect(TokenKind.IDENT).text
        if (keyword == "const") return parseDestructuring()

        val name = expect(TokenKind.IDENT).text
        expectPunct(":")
        val type = parseType()
        val local = declare(name, type, argument = false)
        // The initialiser is the type's zero value and carries no instructions.
        if (atPunct("=")) {
            skip()
            parseExpression()
        }
        expectPunct(";")
        return LocalAssign(local.slot, local.type, IntConst(0), declare = true)
    }

    /** `const x = call();` and `const [a, b] = call();` bind a call's results. */
    private fun parseDestructuring(): Stmt {
        val names = ArrayList<String>()
        if (atPunct("[")) {
            skip()
            while (!atPunct("]")) {
                names.add(expect(TokenKind.IDENT).text)
                if (atPunct(",")) skip()
            }
            skip()
        } else {
            names.add(expect(TokenKind.IDENT).text)
        }
        expectPunct("=")
        val call = parseExpression()
        expectPunct(";")
        val temps = names.map { TempRef(it, tempType(it)) }
        temps.forEach { tempTypes[it.name] = it }
        return MultiAssign(temps, call)
    }

    private val tempTypes = HashMap<String, TempRef>()

    /**
     * Which stack an opcode's single result lands on. This decides whether `+`
     * means arithmetic or string joining, so guessing `number` would silently
     * turn a `JOIN_STRING` into an `ADD`.
     */
    private fun opResultType(op: Cs2Op, args: List<Expr>): Cs2Type {
        if (op.kind == OpKind.PARAM) {
            val yieldsString = if (op.opName == "ENUM") {
                Cs2VarTypes.baseOfId((args.getOrNull(1) as? IntConst)?.value ?: -1) == Cs2VarBase.STRING
            } else {
                val paramId = (args.lastOrNull { it.type == Cs2Type.INT } as? IntConst)?.value
                paramId != null && context?.paramIsString(paramId) == true
            }
            return if (yieldsString) Cs2Type.STRING else Cs2Type.INT
        }
        if (op.kind == OpKind.VARARG) {
            return Cs2DbFields.results(Cs2DbFields.columnArgument(args)).firstOrNull() ?: Cs2Type.INT
        }
        return when {
            op.pushStr == 1 && op.pushInt == 0 && op.pushLong == 0 -> Cs2Type.STRING
            op.pushLong == 1 && op.pushInt == 0 && op.pushStr == 0 -> Cs2Type.LONG
            else -> Cs2Type.INT
        }
    }

    /**
     * Turns a spelled-out constant back into the integer it packs. This is the
     * inverse of the decompiler's typed-constant rendering.
     */
    private fun packedConstant(name: String, args: List<Expr>): Expr? {
        val quoted = (args.singleOrNull() as? StrConst)?.value
        if (name == "component" && quoted != null) {
            return TypedConst(
                Gameval.componentHash(quoted) ?: error("Unknown component '$quoted'"),
                ArgType.COMPONENT,
            )
        }
        val values = args.map { (it as? IntConst)?.value ?: (it as? TypedConst)?.value ?: return null }
        val kind = when (name) {
            "component" -> ArgType.COMPONENT
            "tile" -> ArgType.COORD
            "item" -> ArgType.ITEM
            "npc" -> ArgType.NPC
            "loc" -> ArgType.LOC
            "seq" -> ArgType.SEQ
            "spotanim" -> ArgType.SPOTANIM
            "enumId" -> ArgType.ENUM
            "struct" -> ArgType.STRUCT
            "param" -> ArgType.PARAM
            "inv" -> ArgType.INV
            "skill" -> ArgType.STAT
            "scriptId" -> ArgType.SCRIPT
            "colour" -> ArgType.COLOUR
            "graphic" -> ArgType.GRAPHIC
            "model" -> ArgType.MODEL
            "quest" -> ArgType.QUEST
            "fontmetrics" -> ArgType.FONTMETRICS
            "interfaceId" -> ArgType.INTERFACE
            "varbitId" -> ArgType.VARBIT
            "cursor" -> ArgType.CURSOR
            "sound" -> ArgType.SOUND
            "achievement" -> ArgType.ACHIEVEMENT
            "material" -> ArgType.MATERIAL
            "bas" -> ArgType.BAS
            "mapelement" -> ArgType.MAPELEMENT
            "idkit" -> ArgType.IDKIT
            else -> return null
        }
        val packed = when {
            kind == ArgType.COMPONENT && values.size == 2 -> Cs2Packing.packComponent(values[0], values[1])
            kind == ArgType.COORD && values.size == 3 -> Cs2Packing.packCoord(values[0], values[1], values[2])
            values.size == 1 -> values[0]
            else -> error("$name takes ${values.size} arguments")
        }
        return TypedConst(packed, kind)
    }

    /**
     * Resolves a dev name written as a member access - `obj.coins`, or an
     * interface and one of its components - back to the id it stands for.
     */
    private fun gamevalMember(owner: String): Expr? {
        if (atPunct(".")) {
            skip()
            val member = expect(TokenKind.IDENT).text
            val id = Cs2Gamevals.memberId(owner, member) ?: error("Unknown gameval '$owner.$member'")
            return IntConst(id)
        }
        if (!atPunct("[") || !Cs2Gamevals.isTable(owner)) return null
        skip()
        val member = Cs2Strings.unescape(expect(TokenKind.STRING).text)
        expectPunct("]")
        return IntConst(Cs2Gamevals.memberId(owner, member) ?: error("Unknown gameval '$owner[$member]'"))
    }

    /** Destructured results carry their stack in the name. */
    private fun tempType(name: String) = when {
        name.startsWith("tmpStr") -> Cs2Type.STRING
        name.startsWith("tmpLong") -> Cs2Type.LONG
        else -> Cs2Type.INT
    }

    private fun parseIf(): Stmt {
        expectIdent("if")
        expectPunct("(")
        val condition = parseCondition()
        expectPunct(")")
        expectPunct("{")
        val then = parseBlock()
        var otherwise = emptyList<Stmt>()
        if (peek().text == "else") {
            skip()
            if (peek().text == "if") {
                otherwise = listOf(parseIf())
            } else {
                expectPunct("{")
                otherwise = parseBlock()
            }
        }
        return If(condition, then, otherwise)
    }

    private fun parseWhile(): Stmt {
        expectIdent("while")
        expectPunct("(")
        val condition = if (peek().text == "true") skip().let { null } else parseCondition()
        expectPunct(")")
        expectPunct("{")
        return While(condition, parseBlock())
    }

    private fun parseSwitch(): Stmt {
        expectIdent("switch")
        expectPunct("(")
        val subject = parseExpression()
        expectPunct(")")
        expectPunct("{")

        val cases = ArrayList<SwitchCase>()
        var default = emptyList<Stmt>()
        // With no `default:` at all, control falls straight out of the opcode
        // into whatever follows, which is the first-position layout.
        var defaultFirst = true
        var defaultAt = Int.MAX_VALUE
        while (!atPunct("}")) {
            if (peek().text == "default") {
                skip()
                expectPunct(":")
                defaultFirst = cases.isEmpty()
                defaultAt = cases.size
                default = parseCaseBody()
                continue
            }
            val keys = ArrayList<Int>()
            while (peek().text == "case") {
                skip()
                keys.add(parseCaseKey())
                expectPunct(":")
            }
            cases.add(SwitchCase(keys, parseCaseBody()))
        }
        skip()
        return Switch(subject, cases, default, table = -1, defaultFirst = defaultFirst, defaultAt = defaultAt)
    }

    /**
     * A case label, written either as the integer itself or as the component it
     * selects. Anything else is not a constant the switch table can hold.
     */
    private fun parseCaseKey(): Int {
        if (peek().kind != TokenKind.IDENT) return parseSignedInt()
        return when (val label = parseExpression()) {
            is TypedConst -> label.value
            is IntConst -> label.value
            else -> error("Case label is not a constant: $label")
        }
    }

    /**
     * One arm's statements, up to and including the `break` that ends it.
     *
     * The `break` is kept rather than dropped: an arm that ends by jumping to the
     * join and one that simply runs out compile to different bytes, and only the
     * `break` tells them apart.
     */
    private fun parseCaseBody(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (!atPunct("}") && peek().text != "case" && peek().text != "default") {
            val leaving = peek().text == "break" && tokens.getOrNull(at + 1)?.text == ";"
            out.add(parseStatement() ?: continue)
            if (leaving) break
        }
        return out
    }

    private fun parseReturn(): Stmt {
        expectIdent("return")
        if (atPunct(";")) {
            skip()
            return Return(emptyList())
        }
        val values = ArrayList<Expr>()
        if (atPunct("[")) {
            skip()
            while (!atPunct("]")) {
                values.add(parseExpression())
                if (atPunct(",")) skip()
            }
            skip()
        } else {
            values.add(parseExpression())
        }
        expectPunct(";")
        return Return(values)
    }

    /** `[a, b] = [x, y];` - a parallel assignment. */
    private fun parseParallelAssign(): Stmt {
        expectPunct("[")
        val targets = ArrayList<Expr>()
        while (!atPunct("]")) {
            val target = parseExpression()
            require(target is LocalRef || target is VarRef) { "Cannot assign to $target" }
            targets.add(target)
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct("=")
        expectPunct("[")
        val values = ArrayList<Expr>()
        while (!atPunct("]")) {
            values.add(parseExpression())
            if (atPunct(",")) skip()
        }
        skip()
        expectPunct(";")
        return ParallelAssign(targets, values)
    }

    private fun parseExpressionStatement(): Stmt {
        val target = parseExpression()
        if (atPunct("=")) {
            skip()
            val value = parseExpression()
            expectPunct(";")
            return when (target) {
                is LocalRef -> LocalAssign(target.slot, target.type, value, declare = false)
                is VarRef -> VarAssign(target, value)
                is ArrayRef -> ArrayAssign(target.array, target.index, value)
                else -> error("Cannot assign to $target")
            }
        }
        expectPunct(";")
        if (target is OpCall && target.op.opName == "DEFINE_ARRAY") {
            val array = (target.args[0] as IntConst).value
            val elementType = (target.args[1] as IntConst).value
            return ArrayDefine(array, elementType, target.args[2])
        }
        return ExprStmt(target)
    }

    // ----------------------------------------------------------- expressions

    private fun parseCondition(): Condition = parseOr()

    private fun parseOr(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseAnd())
        while (atPunct("||")) {
            skip()
            terms.add(parseAnd())
        }
        return if (terms.size == 1) terms.first() else OrAny(terms)
    }

    private fun parseAnd(): Condition {
        val terms = ArrayList<Condition>()
        terms.add(parseComparison())
        while (atPunct("&&")) {
            skip()
            terms.add(parseComparison())
        }
        return if (terms.size == 1) terms.first() else AndAll(terms)
    }

    /**
     * A leading `(` is ambiguous: it opens either a grouped condition or a
     * parenthesised operand. Try the group first and rewind if it does not
     * close cleanly.
     */
    private fun parseComparison(): Condition {
        if (atPunct("!")) {
            skip()
            expectPunct("(")
            val inner = parseOr()
            expectPunct(")")
            return when (inner) {
                is Compare -> inner.copy(negated = true)
                else -> inner.negate()
            }
        }
        if (atPunct("(")) {
            val mark = at
            try {
                skip()
                val inner = parseOr()
                if (atPunct(")")) {
                    skip()
                    return inner
                }
            } catch (e: Exception) {
                // fall through to the operand reading below
            }
            at = mark
        }

        val left = parseExpression()
        if (left is OpCall && left.op.opName in setOf("BRANCH_IF_TRUE", "BRANCH_IF_FALSE")) {
            return Compare(left.op, left.args.first(), IntConst(0), negated = false)
        }
        val symbol = expect(TokenKind.PUNCT).text
        val right = parseExpression()
        val longs = left.type == Cs2Type.LONG || right.type == Cs2Type.LONG
        val name = when (symbol) {
            "==", "===" -> "BRANCH_EQUALS"
            "!=", "!==" -> "BRANCH_NOT"
            "<" -> "BRANCH_LESS_THAN"
            ">" -> "BRANCH_GREATER_THAN"
            "<=" -> "BRANCH_LESS_THAN_OR_EQUALS"
            ">=" -> "BRANCH_GREATER_THAN_OR_EQUALS"
            else -> error("Unsupported comparison '$symbol'")
        }
        val op = Cs2Opcodes.byName(if (longs) "LONG_$name" else name)
            ?: error("No opcode for comparison '$symbol'")
        return Compare(op, left, right, negated = false)
    }

    /**
     * Which stack a called script leaves a lone result on. A comparison against
     * one picks the long opcode, so guessing INT here silently rewrites the
     * comparison.
     */
    private fun callResultType(scriptId: Int): Cs2Type {
        val returns = context?.script(scriptId)?.returnSignature ?: return Cs2Type.INT
        return when {
            returns.strings > 0 && returns.ints == 0 && returns.longs == 0 -> Cs2Type.STRING
            returns.longs > 0 && returns.ints == 0 && returns.strings == 0 -> Cs2Type.LONG
            else -> Cs2Type.INT
        }
    }

    private val binaryOperators = mapOf(
        "+" to "ADD", "-" to "SUB", "*" to "MULTIPLY", "/" to "DIVIDE",
        "%" to "MODULO", "&" to "AND", "|" to "OR",
    )

    private fun parseExpression(): Expr {
        var left = parseUnary()
        while (peek().kind == TokenKind.PUNCT && peek().text in binaryOperators) {
            val symbol = skip().text
            val right = parseUnary()
            left = if (left.type == Cs2Type.STRING || right.type == Cs2Type.STRING) {
                // String `+` is JOIN_STRING, which takes all its parts at once.
                val parts = ArrayList<Expr>()
                if (left is Join) parts.addAll(left.parts) else parts.add(left)
                parts.add(right)
                Join(parts)
            } else {
                Binary(symbol, left, right, Cs2Type.INT)
            }
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (atPunct("-")) {
            skip()
            return when (val value = parsePrimary()) {
                is IntConst -> IntConst(-value.value)
                is LongConst -> LongConst(-value.value)
                else -> Binary("-", IntConst(0), value, Cs2Type.INT)
            }
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val token = peek()
        if (atPunct("(")) {
            skip()
            val inner = parseExpression()
            expectPunct(")")
            return inner
        }
        return when (token.kind) {
            TokenKind.NUMBER -> IntConst(skip().text.let(::parseIntLiteral))
            TokenKind.BIGINT -> LongConst(parseLongLiteral(skip().text))
            TokenKind.STRING -> StrConst(Cs2Strings.unescape(skip().text))
            TokenKind.IDENT -> parseIdentifier()
            else -> error("Unexpected token $token")
        }
    }

    /** `-9223372036854775808` lexes as a magnitude one past [Long.MAX_VALUE]. */
    private fun parseLongLiteral(text: String): Long =
        text.toLongOrNull() ?: text.toULong().toLong()

    private fun parseIntLiteral(text: String): Int =
        if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2).toLong(16).toInt()
        else text.toLong().toInt()

    private fun parseSignedInt(): Int {
        val negative = atPunct("-").also { if (it) skip() }
        val value = parseIntLiteral(expect(TokenKind.NUMBER).text)
        return if (negative) -value else value
    }

    private fun parseIdentifier(): Expr {
        val name = skip().text

        if (name == "true") return TypedConst(1, ArgType.BOOLEAN)
        if (name == "false") return TypedConst(0, ArgType.BOOLEAN)

        gamevalMember(name)?.let { return it }

        locals[name]?.let { return LocalRef(it.slot, it.type) }
        tempTypes[name]?.let { return it }

        symbols.variable(name)?.let { return it }
        Cs2EventArg.named(name)?.let { return IntConst(it.value) }
        Cs2Symbols.variableOf(name)?.let { return it }

        if (name.startsWith("array") && name.drop(5).toIntOrNull() != null) {
            expectPunct("[")
            val index = parseExpression()
            expectPunct("]")
            return ArrayRef(name.drop(5).toInt(), index)
        }

        // A bare script reference, as passed to hook().
        val referenced = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (referenced != null && !atPunct("(")) {
            return ScriptCall(referenced, emptyList(), callResultType(referenced))
        }
        // `name<1>(...)` carries the instruction's own operand.
        var operand = 0
        if (atPunct("<")) {
            skip()
            operand = parseSignedInt()
            expectPunct(">")
        }
        if (atPunct("(")) return parseCall(name, operand)
        error("Unknown identifier '$name'")
    }

    private fun parseCall(name: String, operand: Int = 0): Expr {
        expectPunct("(")
        val args = ArrayList<Expr>()
        val lists = ArrayList<List<Expr>>()
        while (!atPunct(")")) {
            if (atPunct("[")) {
                skip()
                val items = ArrayList<Expr>()
                while (!atPunct("]")) {
                    items.add(parseExpression())
                    if (atPunct(",")) skip()
                }
                skip()
                lists.add(items)
                args.add(IntConst(lists.size - 1))
            } else {
                args.add(parseExpression())
            }
            if (atPunct(",")) skip()
        }
        skip()

        if (name == "hook") {
            val scriptRef = args.first()
            val target = (scriptRef as? ScriptCall)?.let { IntConst(it.scriptId) } ?: scriptRef
            val format = (args.getOrNull(1) as? StrConst)?.value
                ?: error("hook() needs its type spec")
            return Callback(target, format, lists.getOrNull(0).orEmpty(), lists.getOrNull(1).orEmpty())
        }
        // A real opcode always wins; the packing helpers only fill names no
        // opcode uses, so `coord(...)` stays the opcode it has always been.
        if (Cs2Opcodes.byTsName(name) == null) packedConstant(name, args)?.let { return it }
        if (name == "pushConstantInt") {
            return IntConst((args.first() as? IntConst)?.value ?: (args.first() as TypedConst).value, dedicated = true)
        }
        if (name == "discard") return args.first()
        if (name == "noHook") {
            val format = (args.firstOrNull() as? StrConst)?.value ?: ""
            return Callback(
                IntConst(-1), format, lists.getOrNull(0).orEmpty(), lists.getOrNull(1).orEmpty(),
            )
        }
        if (name == "defineArray") {
            return OpCall(Cs2Opcodes.byName("DEFINE_ARRAY")!!, args, Cs2Type.INT)
        }
        if (name == "isTruthy") {
            val op = Cs2Opcodes.byName("BRANCH_IF_TRUE")!!
            return OpCall(op, args, Cs2Type.INT)
        }
        if (name == "isFalsy") {
            val op = Cs2Opcodes.byName("BRANCH_IF_FALSE")!!
            return OpCall(op, args, Cs2Type.INT)
        }
        val callee = Cs2Symbols.scriptOf(name) ?: symbols.script(name)
        if (callee != null) return ScriptCall(callee, args, callResultType(callee))
        val op = Cs2Opcodes.byTsName(name) ?: error("Unknown opcode function '$name'")
        return OpCall(op, args, opResultType(op, args), operand)
    }

    // ---------------------------------------------------------------- tokens

    private fun peek() = tokens[at]

    private fun skip() = tokens[at++]

    private fun atPunct(text: String) = peek().kind == TokenKind.PUNCT && peek().text == text

    private fun expectPunct(text: String) {
        if (!atPunct(text)) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expectIdent(text: String) {
        if (peek().kind != TokenKind.IDENT || peek().text != text) error("Expected '$text' but found ${peek()}")
        at++
    }

    private fun expect(kind: TokenKind): Token {
        if (peek().kind != kind) error("Expected $kind but found ${peek()}")
        return tokens[at++]
    }
}
