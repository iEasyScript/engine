package world.gregs.voidps.cache.cs2

/**
 * Generates the ambient TypeScript declarations that make decompiled scripts
 * type-check and autocomplete in an editor.
 *
 * Every opcode becomes a function whose parameters and return type follow its
 * stack signature, so an editor can tell that `ifGetText` yields a string while
 * `ifGetWidth` yields a number. The handful of opcodes with runtime-dependent
 * shapes - the hook setters, the param lookups, `JOIN_STRING` - are declared
 * loosely rather than wrongly.
 */
import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace

object Cs2Declarations {

    /**
     * The project file an editor needs. Without it every script is opened as an
     * isolated program, so neither `cs2.d.ts` nor any sibling script resolves and
     * every name comes back unknown.
     */
    fun tsconfig(): String = """
        {
          "compilerOptions": {
            "target": "es2020",
            "lib": ["es2020"],
            "noEmit": true,
            "noLib": false,
            "allowJs": false,
            "strict": false,
            "skipLibCheck": true,
            "disableSizeLimit": true
          },
          "include": ["**/*.ts"]
        }
    """.trimIndent() + "\n"

    /**
     * Declares every variable the scripts touch, one named global each.
     *
     * The id lives in the doc comment rather than only in the name, so an editor
     * can rename the symbol across the whole project and the compiler still
     * knows which variable it is.
     */
    fun variables(references: Collection<VarRef>): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - names may be edited; the ids in the comments are what bind them.")
        out.appendLine()
        val seen = LinkedHashSet<String>()
        for (reference in references.sortedWith(compareBy({ it.space.ordinal }, { it.id }, { it.variant }))) {
            val name = Cs2Symbols.variableName(reference)
            if (!seen.add(name)) continue
            val variant = if (reference.variant == "old") " old" else ""
            val padding = if (reference.padding != 0) " pad${reference.padding}" else ""
            val type = if (reference.type == Cs2Type.STRING) "string" else "number"
            val table = Cs2Gamevals.varTable(reference.space)?.let { " ($it)" } ?: ""
            out.appendLine("/** ${reference.space.prefix} ${reference.id}$variant$padding$table */")
            out.appendLine("declare let $name: $type;")
        }
        return out.toString()
    }

    fun generate(): String {
        val out = StringBuilder()
        out.appendLine("// GENERATED - do not edit by hand.")
        out.appendLine("// Ambient declarations for decompiled CS2 clientscripts.")
        out.appendLine("// Regenerate with: tools cs2 declarations <dir>")
        out.appendLine()
        appendPreamble(out)

        appendVarTypes(out)
        appendGamevalTables(out)
        appendEventArgs(out)

        for (op in Cs2Opcodes.all.sortedBy { it.tsName }) {
            if (op.opName in HANDWRITTEN) continue
            appendOpcode(out, op)
        }
        return out.toString()
    }

    /**
     * Opcodes the language itself spells out - a store, a jump, an operator -
     * which are declared by hand above or emitted as syntax rather than as calls.
     */
    private val HANDWRITTEN = setOf(
        "PUSH_CONSTANT_INT", "PUSH_CONSTANT_STRING", "PUSH_LONG_CONSTANT",
        "PUSH_INT_LOCAL", "PUSH_STRING_LOCAL", "PUSH_LONG_LOCAL",
        "POP_INT_LOCAL", "POP_STRING_LOCAL", "POP_LONG_LOCAL",
        "POP_INT_DISCARD", "POP_STRING_DISCARD", "POP_LONG_DISCARD",
        "PUSH_VAR", "POP_VAR", "PUSH_VARBIT", "POP_VARBIT",
        "LOAD_VARC", "STORE_VARC", "LOAD_VARC_STRING", "STORE_VARC_STRING",
        "GET_VARP_OLD", "GET_VARPBIT_OLD", "GET_VARN_OLD", "GET_VARNBIT_OLD",
        "DEFINE_ARRAY", "PUSH_ARRAY_INT", "POP_ARRAY_INT", "JOIN_STRING",
        "BRANCH", "BRANCH_NOT", "BRANCH_EQUALS", "BRANCH_LESS_THAN", "BRANCH_GREATER_THAN",
        "BRANCH_LESS_THAN_OR_EQUALS", "BRANCH_GREATER_THAN_OR_EQUALS",
        "BRANCH_IF_TRUE", "BRANCH_IF_FALSE", "SWITCH", "RETURN", "GOSUB_WITH_PARAMS",
        "LONG_BRANCH_NOT", "LONG_BRANCH_EQUALS", "LONG_BRANCH_LESS_THAN", "LONG_BRANCH_GREATER_THAN",
        "LONG_BRANCH_LESS_THAN_OR_EQUALS", "LONG_BRANCH_GREATER_THAN_OR_EQUALS",
        "ADD", "SUB", "MULTIPLY", "DIVIDE", "MODULO", "AND", "OR",
    )

    /**
     * The gameval tables a decompiled script can name ids out of, declared as
     * objects so `obj.coins` type-checks and completes in an editor.
     */
    private fun appendGamevalTables(out: StringBuilder) {
        val tables = ArgType.entries.mapNotNull(Cs2Gamevals::table).distinct().sorted()
        if (tables.isEmpty()) return
        out.appendLine("/** Jagex dev names, from the cache's own gameval tables. */")
        for (table in tables) out.appendLine("declare const $table: Record<string, number>;")
        out.appendLine()
    }

    /**
     * The script variable types, as the type characters that actually appear in
     * decompiled code - an enum's key and value type, a hook's argument spec.
     * The client holds no names for them, so the character is the identity and a
     * gameval table is only named where the cache's own enums prove it.
     */
    private fun appendVarTypes(out: StringBuilder) {
        val types = Cs2VarTypes.all
        if (types.isEmpty()) return
        val tables = Cs2VarTypes.tables()
        out.appendLine("/**")
        out.appendLine(" * Script variable types: tag, stack, gameval table where the cache proves one.")
        for (type in types.sortedBy { it.id }) {
            val table = tables[type.tag]?.let { "  $it" } ?: ""
            out.appendLine(" *   %-6s %-6s%s".format(type.printable, type.base.name.lowercase(), table))
        }
        out.appendLine(" */")
        out.appendLine()
    }

    /**
     * The placeholders a hook binds in place of live event data, declared as
     * constants so a bound argument reads as what the client will substitute
     * rather than as a number at the bottom of the int range.
     */
    private fun appendEventArgs(out: StringBuilder) {
        out.appendLine("/**")
        out.appendLine(" * Hook event data. Each of these is a placeholder the client replaces when the")
        out.appendLine(" * hook fires, and only ever in an int argument.")
        out.appendLine(" *")
        out.appendLine(" * What they resolve to is read off the client; the spelling is this toolchain's,")
        out.appendLine(" * following the two names the client does carry - `${Cs2EventArg.OPTION_NAME}` and")
        out.appendLine(" * `event_text`, the string flavour of the same mechanism.")
        out.appendLine(" */")
        for (argument in Cs2EventArg.entries) {
            out.appendLine("/** ${argument.meaning} */")
            out.appendLine("declare const ${argument.identifier}: number;")
        }
        out.appendLine()
    }

    private fun appendPreamble(out: StringBuilder) {
        out.appendLine(
            """
            /** The five global integer arrays scripts share. */
            declare const array0: number[];
            declare const array1: number[];
            declare const array2: number[];
            declare const array3: number[];
            declare const array4: number[];

            /** Allocates one of the global arrays. */
            declare function defineArray(array: number, elementType: number, size: number): void;

            /**
             * Binds a callback to a component event.
             *
             * `spec` is the compiler's type string, one character per bound
             * argument - `i` int, `s` string, `I` component, `o` obj, and so on -
             * with a trailing `Y` when a trigger list follows.
             */
            declare function hook(
                script: Function,
                spec: string,
                args: unknown[],
                triggers?: number[],
            ): unknown;

            /** Clears a component event. */
            declare function noHook(spec: string): unknown;

            /** Throws away a value a call left on the stack. */
            declare function discard(value: unknown): void;

            /** `x != 0`, compiled as the dedicated truthiness branch. */
            declare function isTruthy(value: number): boolean;

            /** `x == 0`, compiled as the dedicated truthiness branch. */
            declare function isFalsy(value: number): boolean;

            /** An unstructured jump, used where a script's shape has no direct form. */
            declare function goto(label: string): void;

            /**
             * Composite values, spelled out rather than left as packed integers.
             * Each one packs straight back to the integer the bytecode holds.
             *
             * A component marked `unverified` reads equally well as a map position, and nothing
             * but its shape picked the component; every other one is a reading the bytecode's own
             * types, or the value itself, leave no alternative to.
             */
            declare function component(interfaceId: number, component?: number): number;
            declare function tile(x: number, y?: number, plane?: number): number;
            declare function item(id: number): number;
            declare function npc(id: number): number;
            declare function loc(id: number): number;
            declare function seq(id: number): number;
            declare function spotanim(id: number): number;
            declare function enumId(id: number): number;
            declare function struct(id: number): number;
            declare function param(id: number): number;
            declare function inv(id: number): number;
            declare function skill(id: number): number;
            declare function scriptId(id: number): number;
            declare function colour(rgb: number): number;
            declare function graphic(id: number): number;
            declare function model(id: number): number;
            declare function quest(id: number): number;
            declare function fontmetrics(id: number): number;
            declare function interfaceId(id: number): number;
            declare function varbitId(id: number): number;
            declare function cursor(id: number): number;
            declare function sound(id: number): number;
            declare function achievement(id: number): number;
            declare function material(id: number): number;
            declare function bas(id: number): number;
            declare function mapelement(id: number): number;
            declare function idkit(id: number): number;

            /**
             * The standalone int push, kept apart from the tagged push the
             * compiler otherwise uses so a script re-compiles to the same bytes.
             */
            declare function pushConstantInt(value: number): number;
            """.trimIndent(),
        )
        out.appendLine()
    }

    private fun appendOpcode(out: StringBuilder, op: Cs2Op) {
        out.appendLine("/** ${provenance(op)} */")
        // The type parameter is what lets an instruction's own operand ride along
        // as `ccCreate<1>(...)`; it is never used as a type.
        out.appendLine("declare function ${op.tsName}<Operand = void>(${parameters(op)}): ${returnType(op)};")
    }

    /**
     * Where the declared name came from, so a reader can tell the client's own
     * text apart from anything this toolchain worked out.
     */
    private fun provenance(op: Cs2Op): String {
        val naming = op.naming
        val parts = ArrayList<String>()
        parts.add("opcode ${op.id}")
        naming.canonical?.let { parts.add("client name `$it`") }
        naming.derived?.let {
            parts.add(
                if (naming.canonical != null) "`$it` split out by which invoker the entry routes through"
                else "`$it` worked out here from Jagex's vocabulary; no client string names this opcode",
            )
        }
        naming.reference?.let { parts.add("Jagex's name `$it`, from another build's symbols, matched by behaviour") }
        naming.structural?.let { parts.add("named from its role in the language, not from client text") }
        if (naming.isEmpty) parts.add("no name recovered")
        if (op.tsName.endsWith("_op${op.id}")) parts.add("numbered to keep the identifier unique")
        return parts.joinToString(", ")
    }

    private fun parameters(op: Cs2Op): String {
        if (op.kind == OpKind.HOOK) {
            val component = if (op.popInt == 1) ", component: number" else ""
            return "callback: unknown$component"
        }
        // An opcode drawing from more than one stack receives its arguments in
        // the order the compiler pushed them, which can interleave the types, so
        // those parameters are widened rather than declared in a fixed order.
        val stacks = listOf(op.popInt, op.popStr, op.popLong).count { it > 0 }
        if (stacks > 1) {
            val total = op.popInt + op.popStr + op.popLong
            return (0 until total).joinToString(", ") { "arg$it: number | string | bigint | boolean" }
        }
        val names = ArrayList<String>()
        repeat(op.popInt) {
            val kind = op.argTypes.getOrNull(names.size)
            // A flag is still an int underneath - `true`/`false` is only a nicer
            // spelling for the literals, and a local holding one is a number.
            val type = if (kind == ArgType.BOOLEAN) "number | boolean" else "number"
            names.add("arg${names.size}: $type")
        }
        repeat(op.popStr) { names.add("arg${names.size}: string") }
        repeat(op.popLong) { names.add("arg${names.size}: bigint") }
        return names.joinToString(", ")
    }

    private fun returnType(op: Cs2Op): String {
        // A param lookup yields an int or a string depending on which param was
        // asked for, so it cannot be narrowed here; `any` keeps it assignable to
        // whichever the caller uses it as.
        if (op.kind == OpKind.PARAM) return "any"

        val parts = ArrayList<String>()
        repeat(op.pushInt) { parts.add("number") }
        repeat(op.pushStr) { parts.add("string") }
        repeat(op.pushLong) { parts.add("bigint") }
        return when (parts.size) {
            0 -> "void"
            1 -> parts.first()
            else -> "[" + parts.joinToString(", ") + "]"
        }
    }
}
