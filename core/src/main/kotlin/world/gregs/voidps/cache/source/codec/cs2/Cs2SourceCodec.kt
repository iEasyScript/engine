package world.gregs.voidps.cache.source.codec.cs2

import world.gregs.voidps.cache.cs2.Cs2Analyzer
import world.gregs.voidps.cache.cs2.Cs2Codec
import world.gregs.voidps.cache.cs2.Cs2Declarations
import world.gregs.voidps.cache.cs2.Cs2Decompile
import world.gregs.voidps.cache.cs2.Cs2Gamevals
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import world.gregs.voidps.cache.cs2.Cs2Records
import world.gregs.voidps.cache.cs2.Cs2SymbolTable
import world.gregs.voidps.cache.cs2.Cs2VarDeclarations
import world.gregs.voidps.cache.cs2.Cs2VarTypes
import world.gregs.voidps.cache.cs2.Cs2Variables
import world.gregs.voidps.cache.cs2.compile.Cs2CodeGen
import world.gregs.voidps.cache.cs2.compile.Cs2Parser
import world.gregs.voidps.cache.cs2.ir.Cs2Function
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index 12 as TypeScript: the source tree's clientscript codec.
 *
 * The index directory is the project `cs2 decompile-all` writes, laid out by [Cs2Layout] - one
 * `.ts` per script at its gameval name's path - plus the project files an editor needs, which are
 * a property of the corpus rather than of any archive and so come out of [unpackIndex].
 *
 * Every script is compiled straight back as it is decompiled. Where the bytes differ the shipped
 * ones are kept beside the source as `<name>.ts.pristine`, guarded by the source's SHA-256 in
 * `index.json`; [pack] serves the sidecar while the guard holds and compiles the source once it
 * does not. [exact] is therefore true - the fallback is assembled here, so what is on disk always
 * packs back to the bytes it came from.
 *
 * Both directions need the corpus analysed: decompiling recovers argument order from callers, and
 * compiling resolves a call against its callee's signature. The analysis is built once per cache
 * and shared, which is why the cache reaches this codec through [cachePath] and [cache] rather
 * than through any archive.
 */
object Cs2SourceCodec : SourceCodec {

    const val OPCODES_FILE = "cs2.d.ts"
    const val VARS_FILE = "vars.d.ts"
    const val TSCONFIG_FILE = "tsconfig.json"

    private const val FILE = 0

    private val PROJECT_FILES = setOf(OPCODES_FILE, VARS_FILE, TSCONFIG_FILE)

    private val DEFAULT_CACHE: (Path?) -> Cache = { path ->
        if (path == null) Cache.get() else SQLiteCache.load(path, readOnly = true)
    }

    /** The packed cache the corpus is analysed from; null means the process's own [Cache.get]. */
    @Volatile
    var cachePath: Path? = null

    /** How a [cachePath] is opened. Read-only: nothing here writes a cache. */
    @Volatile
    var cache: (Path?) -> Cache = DEFAULT_CACHE

    override val id: String
        get() = "cs2-source"

    override fun exact(archive: Int): Boolean = true

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Clientscript ${archive.archive} has ${archive.files.size} files; index ${Index.CLIENT_SCRIPTS} holds one script per archive."
        }
        val bytes = archive.files[FILE]
        val path = layout().pathOf(archive.archive)
        val decompiled = decompile(corpus(), archive.archive, bytes, path)
        val source = decompiled.source.toByteArray(Charsets.UTF_8)
        decompiled.function?.let { Cs2Variables.collect(it.body, state(directory).variables) }

        val files = ArrayList<SourceFile>(2)
        files.add(SourceFile(path, source))
        if (decompiled.exact) {
            return UnpackedArchive(files)
        }
        files.add(SourceFile(SourceFiles.pristine(path), bytes))
        return UnpackedArchive(files, mapOf(FILE to SourceFiles.sha256(source)))
    }

    override fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> {
        if (archives.isEmpty()) {
            return emptyList()
        }
        corpus()
        val variables = state(directory).drainVariables()
        return listOf(
            SourceFile(OPCODES_FILE, Cs2Declarations.generate().toByteArray(Charsets.UTF_8)),
            SourceFile(VARS_FILE, Cs2Declarations.variables(variables).toByteArray(Charsets.UTF_8)),
            SourceFile(TSCONFIG_FILE, Cs2Declarations.tsconfig().toByteArray(Charsets.UTF_8))
        )
    }

    /**
     * The corpus is built here, on the one thread that calls this, and never by a packing worker.
     *
     * Compiling a script resolves its calls against the analysed corpus, so every worker needs it -
     * and a worker that built it would hold this codec's monitor while decoding config records,
     * which is how a parallel pack deadlocked against the cache it was still producing.
     */
    override fun packIndex(directory: Path, index: Int, archives: IntArray, changedIndexFiles: List<String>) {
        corpus()
        state(directory).invalidateSymbols()
        state(directory).symbols()
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve(layout().pathOf(archive))
        if (!Files.isRegularFile(file)) {
            throw IOException("Clientscript $archive has no source at ${file.toAbsolutePath()}.")
        }
        val source = Files.readAllBytes(file)
        val ids = metadata.fileIds()
        check(ids.size == 1 && ids[FILE] == FILE) {
            "Clientscript $archive is one file on disk but its metadata lists ${ids.joinToString()}."
        }
        val guard = metadata.pristine[FILE]
        if (guard != null && guard == SourceFiles.sha256(source)) {
            val sidecar = SourceFiles.pristine(file)
            if (!Files.isRegularFile(sidecar)) {
                throw IOException(
                    "Clientscript $archive is recorded as pristine in index.json but ${sidecar.toAbsolutePath()} is missing; " +
                        "restore the sidecar or drop the archive's \"pristine\" entry to compile ${file.fileName} instead."
                )
            }
            return PackedArchive(Files.readAllBytes(sidecar), ids)
        }
        return PackedArchive(compile(directory, file, archive, String(source, Charsets.UTF_8)), ids)
    }

    private fun compile(directory: Path, file: Path, archive: Int, source: String): ByteArray {
        val declared = Cs2Layout.headerScriptId(source)
        if (declared != null && declared != archive) {
            throw IOException(
                "${file.toAbsolutePath()} says it is clientscript $declared but sits where clientscript $archive belongs."
            )
        }
        val analyzer = corpus().analyzer
        val symbols = state(directory).symbols()
        return try {
            Cs2Codec.encode(Cs2CodeGen(Cs2Parser(source, analyzer, symbols).parse(), analyzer).generate())
        } catch (e: Exception) {
            throw IOException("${file.toAbsolutePath()}: ${e.message ?: e::class.simpleName}", e)
        }
    }

    override fun archiveOf(path: String): Int? =
        if (Cs2Layout.isScript(path)) layout().scriptIdOf(path) else null

    override fun archiveOf(directory: Path, path: String): Int? {
        if (!Cs2Layout.isScript(path)) {
            return null
        }
        val file = directory.resolve(path)
        if (Files.isRegularFile(file)) {
            Cs2Layout.headerScriptId(Files.readString(file))?.let { return it }
        }
        return archiveOf(path)
    }

    override fun owner(directory: Path, path: String): Owner {
        if (path in PROJECT_FILES) {
            return Owner.Index
        }
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val file = directory.resolve(layout().pathOf(archive))
        if (!Files.exists(file)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(file)
        return if (Files.exists(sidecar)) listOf(file, sidecar) else listOf(file)
    }

    /** Forget every directory's state and the analysed corpus, and put the seams back to their defaults. */
    fun reset() {
        states.clear()
        current = null
        layouts = null
        cachePath = null
        cache = DEFAULT_CACHE
    }

    private class Rendering(val source: String, val function: Cs2Function?, val exact: Boolean)

    private fun decompile(corpus: Corpus, scriptId: Int, bytes: ByteArray, path: String): Rendering {
        val script = try {
            corpus.analyzer.script(scriptId) ?: Cs2Codec.decode(bytes)
        } catch (e: Exception) {
            return placeholder(scriptId, path, e.message ?: "undecodable")
        }
        val decompiled = try {
            Cs2Decompile.decompile(script, scriptId, corpus.analyzer, bytes)
        } catch (e: Exception) {
            return placeholder(scriptId, path, e.message ?: e::class.simpleName ?: "failed")
        }
        return Rendering(Cs2Layout.relocateReference(decompiled.source, path), decompiled.function, decompiled.exact)
    }

    /** A script nothing here can read still gets a file that binds to its archive, beside its pristine bytes. */
    private fun placeholder(scriptId: Int, path: String, reason: String): Rendering {
        val source = Cs2Layout.relocateReference(
            "/// <reference path=\"./cs2.d.ts\" />\n// clientscript $scriptId\n// not decompiled: ${reason.lineSequence().first()}\n",
            path
        )
        return Rendering(source, null, exact = false)
    }

    private fun layout(): Cs2Layout {
        val catalogs = SourceCodecs.context.gamevals
        layouts?.takeIf { it.first === catalogs }?.let { return it.second }
        val layout = Cs2Layout.of(catalogs)
        layouts = catalogs to layout
        return layout
    }

    @Volatile
    private var layouts: Pair<GamevalCatalogs, Cs2Layout>? = null

    /**
     * The analysed cache, built once and shared by every directory: a lazily opened cache, the
     * opcode table it was solved against installed, and the whole corpus analysed.
     */
    private class Corpus(val path: Path?) {

        val cache: Cache = Cs2SourceCodec.cache(path)

        val analyzer: Cs2Analyzer

        init {
            Cs2Records.install(cache)
            Cs2Gamevals.forget()
            Cs2VarDeclarations.forget()
            val entries = Cs2OpcodeTable.load(cache)
                ?: throw IOException(
                    "No solved opcode table for index-12 crc ${Cs2OpcodeTable.indexCrc(cache)}; run `cs2 calibrate` " +
                        "or export the build's table under re-resources/cs2."
                )
            Cs2OpcodeTable.install(entries)
            Cs2VarTypes.file().takeIf { it.isFile }?.let { Cs2VarTypes.install(Cs2VarTypes.read(it)) }
            Cs2Records.warm()
            analyzer = Cs2Analyzer(cache)
            analyzer.analyse()
        }
    }

    @Volatile
    private var current: Corpus? = null

    private fun corpus(): Corpus {
        val path = cachePath
        current?.takeIf { it.path == path }?.let { return it }
        return synchronized(this) {
            current?.takeIf { it.path == path } ?: Corpus(path).also { current = it }
        }
    }

    private class State(val directory: Path) {

        val variables: MutableSet<VarRef> = ConcurrentHashMap.newKeySet()

        @Volatile
        private var symbols: Cs2SymbolTable? = null

        fun symbols(): Cs2SymbolTable =
            symbols ?: synchronized(this) {
                symbols ?: Cs2SymbolTable.read(directory.toFile()).also { symbols = it }
            }

        fun invalidateSymbols() {
            synchronized(this) { symbols = null }
        }

        fun drainVariables(): Set<VarRef> {
            val drained = HashSet(variables)
            variables.clear()
            return drained
        }
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun state(directory: Path): State {
        val normalized = directory.toAbsolutePath().normalize()
        return states.computeIfAbsent(normalized.toString()) { State(normalized) }
    }
}
