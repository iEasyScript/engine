package org.projectx.tools.cs2

import java.io.File
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.cs2.Cs2Analyzer
import world.gregs.voidps.cache.cs2.Cs2Cache
import world.gregs.voidps.cache.cs2.Cs2Codec
import world.gregs.voidps.cache.cs2.Cs2Declarations
import world.gregs.voidps.cache.cs2.Cs2Decompile
import world.gregs.voidps.cache.cs2.Cs2Emitter
import world.gregs.voidps.cache.cs2.Cs2Script
import world.gregs.voidps.cache.cs2.Cs2ShapeReading
import world.gregs.voidps.cache.cs2.Cs2SymbolTable
import world.gregs.voidps.cache.cs2.compile.Cs2CodeGen
import world.gregs.voidps.cache.cs2.compile.Cs2Lexer
import world.gregs.voidps.cache.cs2.compile.Cs2Parser

/**
 * A folder of decompiled clientscripts, and the path from a file on disk back to
 * bytecode.
 *
 * The folder is the same shape `cs2 decompile-all` writes: one
 * `clientscript-<id>.ts` per script, plus `cs2.d.ts` for the opcode signatures
 * and `vars.d.ts` binding renamed variables to their ids. Two facts about that
 * layout drive everything here:
 *
 *  - **The `// clientscript <id>` header is the script's identity**, not the file
 *    name and not the function name. [Cs2Parser] reads it, so a renamed function
 *    still compiles to the right id, and a file can be copied around freely as
 *    long as the header comes with it.
 *  - **Renamed identifiers only resolve through the folder's declarations.**
 *    [Cs2SymbolTable.read] scans `vars.d.ts` and every script header, so it is
 *    folder-scoped state that has to be re-read whenever those files change.
 *
 * Compilation happens on whichever thread asks - in practice the hot reload
 * watcher thread - so the mutable state here ([folder], [symbols]) is volatile
 * and replaced wholesale rather than mutated in place.
 */
class Cs2SourceProject(
    private val cache: Cache,
    private val analyzer: Cs2Analyzer,
    folder: File = File(DEFAULT_FOLDER),
    private val shapeReading: Cs2ShapeReading = Cs2ShapeReading.NUMERIC,
) {

    @Volatile
    var folder: File = folder
        private set

    @Volatile
    var symbols: Cs2SymbolTable = Cs2SymbolTable.EMPTY
        private set

    private val scripts = Cs2Cache(cache)

    init {
        reloadSymbols()
    }

    /**
     * Points the project at a different folder and re-reads its declarations.
     * Returns the folder actually in use, which is unchanged when it is missing.
     */
    fun useFolder(directory: File): File {
        if (!directory.isDirectory) return folder
        folder = directory
        reloadSymbols()
        return folder
    }

    /** Re-reads `vars.d.ts` and the per-script name bindings. */
    fun reloadSymbols() {
        symbols = try {
            if (folder.isDirectory) Cs2SymbolTable.read(folder) else Cs2SymbolTable.EMPTY
        } catch (e: Exception) {
            Cs2SymbolTable.EMPTY
        }
    }

    // -------------------------------------------------------------- diagnostics

    /**
     * A compile failure with somewhere to jump to.
     *
     * The lexer and parser signal problems with plain `error(...)`, so there is no
     * structured position to read: [line] and [column] are recovered from the
     * message and the token stream on a best-effort basis (see [locate]) and
     * default to the top of the file when nothing is recoverable.
     */
    data class Diagnostic(
        val file: File?,
        val scriptId: Int,
        val message: String,
        val line: Int,
        val column: Int,
        /** True when [line] and [column] were actually recovered rather than guessed. */
        val located: Boolean,
    ) {
        /** `file:line:col`, the form editors and terminals both understand. */
        val location: String
            get() = "${file?.path ?: "<unknown>"}:$line:$column"

        override fun toString(): String = "$location: $message"
    }

    /** A successful compile: the script, and the bytes it encodes to. */
    class Compiled(
        val scriptId: Int,
        val file: File,
        val script: Cs2Script,
        val bytes: ByteArray,
    )

    sealed class CompileResult {
        class Ok(val result: Compiled) : CompileResult()
        class Failed(val error: Diagnostic) : CompileResult()

        val compiled: Compiled? get() = (this as? Ok)?.result
        val diagnostic: Diagnostic? get() = (this as? Failed)?.error
    }

    // ------------------------------------------------------------------- paths

    fun fileFor(scriptId: Int): File = folder.resolve("clientscript-$scriptId.ts")

    fun hasSource(scriptId: Int): Boolean = fileFor(scriptId).isFile

    /** The id a file declares in its header, which is the only authority on it. */
    fun scriptIdOf(file: File): Int? = try {
        HEADER.find(file.readText())?.groupValues?.get(1)?.toIntOrNull()
    } catch (e: Exception) {
        null
    }

    /** True when a file is one of the folder's scripts rather than a declaration file. */
    fun isScriptFile(file: File): Boolean =
        file.name.startsWith("clientscript-") && file.name.endsWith(".ts")

    /** True when a file changing invalidates the symbol table for the whole folder. */
    fun isDeclarationFile(file: File): Boolean =
        file.name == "vars.d.ts" || file.name == "cs2.d.ts"

    /** Every script id the folder has a source file for, ascending. */
    fun sourceIds(): List<Int> =
        folder.listFiles().orEmpty()
            .filter { isScriptFile(it) }
            .mapNotNull { SOURCE_NAME.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .sorted()

    fun cachedBytes(scriptId: Int): ByteArray? = try {
        scripts.raw(scriptId)
    } catch (e: Exception) {
        null
    }

    // ----------------------------------------------------------------- compile

    fun compile(scriptId: Int): CompileResult {
        val file = fileFor(scriptId)
        if (!file.isFile) {
            return CompileResult.Failed(
                Diagnostic(file, scriptId, "no source file", 1, 1, located = false),
            )
        }
        return compileFile(file)
    }

    /**
     * Parses and lowers one file.
     *
     * The analyzer is used twice over: as the parser's param-type oracle (a
     * `*_PARAM` opcode pushes an int or a string depending on the param's declared
     * type, which the source cannot say) and as the code generator's context, so a
     * statement-position call discards exactly what its callee leaves behind.
     */
    fun compileFile(file: File): CompileResult {
        val source = try {
            file.readText()
        } catch (e: Exception) {
            return CompileResult.Failed(
                Diagnostic(file, -1, "cannot read: ${e.message}", 1, 1, located = false),
            )
        }
        val headerId = HEADER.find(source)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return try {
            val function = Cs2Parser(source, analyzer, symbols).parse()
            val script = Cs2CodeGen(function, analyzer).generate()
            CompileResult.Ok(Compiled(function.scriptId, file, script, Cs2Codec.encode(script)))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "compile failed"
            CompileResult.Failed(locate(file, headerId, source, message))
        }
    }

    // ------------------------------------------------------------------ verify

    /**
     * Whether a file still compiles to the bytes the cache holds.
     *
     * Worth doing before editing: the decompiler round-trips most scripts exactly
     * but not all of them, and knowing an untouched file is byte-exact is what
     * separates "my edit broke this" from "this script never round-tripped".
     */
    data class VerifyResult(
        val scriptId: Int,
        val identical: Boolean,
        val compiledSize: Int,
        val cachedSize: Int,
        val diagnostic: Diagnostic?,
    ) {
        val summary: String
            get() = when {
                diagnostic != null -> "compile failed: ${diagnostic.message}"
                cachedSize == 0 -> "compiled $compiledSize bytes; nothing in the cache to compare against"
                identical -> "byte-identical to the cached script ($compiledSize bytes)"
                else -> "DIFFERS from the cached script ($compiledSize bytes vs $cachedSize)"
            }
    }

    fun verify(scriptId: Int): VerifyResult {
        val result = compile(scriptId)
        val compiled = result.compiled
            ?: return VerifyResult(scriptId, false, 0, 0, result.diagnostic)
        val original = cachedBytes(compiled.scriptId)
        return VerifyResult(
            scriptId = compiled.scriptId,
            identical = original != null && compiled.bytes.contentEquals(original),
            compiledSize = compiled.bytes.size,
            cachedSize = original?.size ?: 0,
            diagnostic = null,
        )
    }

    // ------------------------------------------------------------------ export

    data class ExportResult(val file: File?, val message: String) {
        val ok: Boolean get() = file != null
    }

    /**
     * Decompiles a cached script into the folder so it can be edited.
     *
     * Uses the same structurer and emitter the CLI does; where the structurer
     * cannot reproduce a script's branch layout exactly the faithful rendering
     * can, so that is tried as a fallback rather than emitting source that will
     * not compile back.
     */
    fun exportSource(scriptId: Int, overwrite: Boolean = false): ExportResult {
        val target = fileFor(scriptId)
        if (target.isFile && !overwrite) {
            return ExportResult(target, "already present at ${target.path}")
        }
        val script = analyzer.cachedScript(scriptId)
            ?: return ExportResult(null, "no clientscript $scriptId in the cache")
        val source = try {
            Cs2Decompile.source(script, scriptId, analyzer, cachedBytes(scriptId), shapeReading)
        } catch (e: Exception) {
            try {
                emit(script, scriptId, faithful = true)
            } catch (fallback: Exception) {
                return ExportResult(null, "decompile failed: ${fallback.message}")
            }
        }
        return try {
            folder.mkdirs()
            target.writeText(source)
            reloadSymbols()
            ExportResult(target, "wrote ${target.path}")
        } catch (e: Exception) {
            ExportResult(null, "cannot write ${target.path}: ${e.message}")
        }
    }

    private fun emit(script: Cs2Script, scriptId: Int, faithful: Boolean): String =
        Cs2Emitter(Cs2Decompile.function(script, scriptId, analyzer, faithful), shapeReading).emit()

    /**
     * Writes the ambient declarations a fresh folder needs for the editor to
     * resolve opcode names. `vars.d.ts` is deliberately left alone: it is produced
     * by a whole-cache decompile and carries the developer's renames.
     */
    fun exportDeclarations(): ExportResult = try {
        folder.mkdirs()
        folder.resolve("cs2.d.ts").writeText(Cs2Declarations.generate())
        folder.resolve("tsconfig.json").writeText(Cs2Declarations.tsconfig())
        ExportResult(folder, "wrote cs2.d.ts and tsconfig.json to ${folder.path}")
    } catch (e: Exception) {
        ExportResult(null, "cannot write declarations: ${e.message}")
    }

    // --------------------------------------------------------------- positions

    /**
     * Best-effort position for a compiler message.
     *
     * Neither the lexer nor the parser carries a position into its errors, but
     * both leave a trail:
     *
     *  - the lexer's own message ends in `on line N`, and every parser message
     *    built from a a parser token ends in `at line N`,
     *    because that is what `Token.toString` prints;
     *  - the rest quote the offending identifier (`Unknown identifier 'foo'`),
     *    which can be found in the token stream, giving the line of its first
     *    occurrence.
     *
     * The column is then the offset of that text on the line, or the first
     * non-blank column. Both handles are all that is available without editing the
     * lexer and parser, which are owned elsewhere.
     */
    private fun locate(file: File, scriptId: Int, source: String, message: String): Diagnostic {
        val lines = source.lines()
        val reported = LINE_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val quoted = QUOTED.find(message)?.groupValues?.get(1)

        var line = reported
        if (line == null && quoted != null) {
            line = try {
                Cs2Lexer(source).tokenise().firstOrNull { it.text == quoted }?.line
            } catch (e: Exception) {
                null
            }
        }
        if (line == null && quoted != null) {
            val index = lines.indexOfFirst { it.contains(quoted) }
            if (index >= 0) line = index + 1
        }

        val resolved = (line ?: 1).coerceIn(1, maxOf(1, lines.size))
        val text = lines.getOrNull(resolved - 1) ?: ""
        val column = when {
            quoted != null && text.contains(quoted) -> text.indexOf(quoted) + 1
            else -> text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 1 else it + 1 }
        }
        return Diagnostic(file, scriptId, message, resolved, column, located = line != null)
    }

    companion object {
        /**
         * Resolved against the working directory, which for `gradle :tools:run` is
         * the repository root.
         */
        const val DEFAULT_FOLDER = "cs2-dump"

        private val HEADER = Regex("""^// clientscript (\d+)( \[[^\]]*])?$""", RegexOption.MULTILINE)
        private val SOURCE_NAME = Regex("""^clientscript-(\d+)\.ts$""")
        private val LINE_IN_MESSAGE = Regex("""(?:at|on) line (\d+)""")
        private val QUOTED = Regex("""'([^']+)'""")
    }
}
