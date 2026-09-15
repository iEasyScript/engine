package com.projectx.game.memory

import com.projectx.game.nxt.types.SharedPointer
import java.io.File
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType

object NativeAccess {

    lateinit var BASE_ADDR: MemorySegment

    /** False outside an injected client - unit tests and tools - where there is no client image. */
    val isAttached: Boolean get() = ::BASE_ADDR.isInitialized

    private val symbols = mutableMapOf<String, MemorySegment>()
    private val functions = mutableMapOf<String, MethodHandle>()
    private val lookups = mutableMapOf<String, SymbolLookup>()
    private val linker = Linker.nativeLinker()

    /**
     * Per-engine-load native arena for hook upcall stubs + every other allocation whose lifetime is
     * exactly one engine load (funchook target slots, UI state buffers, DoAction scratch). Fresh per
     * classloader, closed in [teardown] AFTER funchook uninstall+quiesce. The upcall stubs hold
     * MethodHandles bound to the engine's hook methods; on Arena.global they would pin this
     * classloader's metaspace forever, so a hot-reload could never reclaim it. Closing this arena on
     * unload releases the stubs, letting the old classloader (and its classes) be GC'd.
     */
    val engineArena: Arena = Arena.ofShared()

    fun init(baseAddr: MemorySegment) {
        BASE_ADDR = baseAddr
        // Resolve the already-resident bootstrap .so by absolute path rather than
        // System.loadLibrary: the latter throws UnsatisfiedLinkError when a *second*
        // classloader (a hot-reload) loads the same library in the process. libraryLookup
        // just dlopen()s the already-loaded .so (RTLD_NODELETE), valid from any loader.
        addLookup("projectxbootstrap", bootstrapLookup())
        addLookup("loaderLookup", SymbolLookup.loaderLookup())
    }

    private fun bootstrapLookup(): SymbolLookup {
        val dir = System.getenv("PROJECTX_HOME_DIR")
            ?: System.getProperty("java.library.path")?.split(File.pathSeparator)?.firstOrNull()
            ?: "."
        return SymbolLookup.libraryLookup("$dir/$BOOTSTRAP_LIBRARY", Arena.global())
    }

    private val BOOTSTRAP_LIBRARY: String
        get() = if (System.getProperty("os.name").startsWith("Windows")) "projectxbootstrap.dll"
        else "libprojectxbootstrap.so"

    fun addLookup(name: String, lookup: SymbolLookup) {
        if (lookups[name] == null)
            lookups[name] = lookup
    }

    fun addPathLookup(symbolFile: String) {
        addLookup(symbolFile, SymbolLookup.libraryLookup(symbolFile, Arena.global()))
    }

    fun getSymbol(name: String): MemorySegment {
        return symbols.getOrPut(name) {
            lookups.values.asSequence()
                .mapNotNull { it.find(name).orElse(null) }
                .firstOrNull()
                ?: error("Symbol $name not found")
        }
    }

    fun getFunction(name: String, desc: () -> FunctionDescriptor): MethodHandle {
        if (functions.containsKey(name))
            return functions[name]!!
        val ptr = getSymbol(name)
        val handle = ptr.toFunctionHandle(desc())
        functions[name] = handle
        return handle
    }

    fun getLibraryFunction(library: String, name: String, desc: () -> FunctionDescriptor): MethodHandle {
        if (functions.containsKey(name))
            return functions[name]!!
        addPathLookup(library)
        val ptr = getSymbol(name)
        val handle = ptr.toFunctionHandle(desc())
        functions[name] = handle
        return handle
    }

    fun MemorySegment.toFunctionHandle(desc: FunctionDescriptor): MethodHandle {
        return linker.downcallHandle(this, desc)
    }

    fun MethodHandle.toEngineUpcallStub(): MemorySegment {
        return Linker.nativeLinker().upcallStub(this, this.type().toDescriptor(), engineArena)
    }

    /**
     * Release every per-engine-load native allocation so this classloader can be GC'd on hot-reload.
     * MUST be called only AFTER [Funchook.uninstall] + a quiesce + [Funchook.destroy]: closing the
     * arena frees the hook upcall stubs, which is fatal if any game thread is still executing one.
     */
    fun teardown() {
        runCatching { engineArena.close() }
        symbols.clear()
        functions.clear()
        lookups.clear()
    }

    fun MethodType.toDescriptor(): FunctionDescriptor {
        val returnLayout = if (this.returnType() == Void.TYPE) null else javaTypeToMemoryLayout(this.returnType())
        val parameterLayouts = this.parameterList().map { javaTypeToMemoryLayout(it) }

        return if (returnLayout == null) {
            FunctionDescriptor.ofVoid(*parameterLayouts.toTypedArray())
        } else {
            FunctionDescriptor.of(returnLayout, *parameterLayouts.toTypedArray())
        }
    }

    private fun javaTypeToMemoryLayout(clazz: Class<*>): ValueLayout {
        return when (clazz) {
            Int::class.java -> JAVA_INT
            Long::class.java -> JAVA_LONG
            Float::class.java -> JAVA_FLOAT
            Double::class.java -> JAVA_DOUBLE
            Short::class.java -> JAVA_SHORT
            Byte::class.java -> JAVA_BYTE
            Char::class.java -> JAVA_CHAR
            Boolean::class.java -> JAVA_BOOLEAN
            else -> ADDRESS
        }
    }

    fun MemorySegment.getByte(offset: Long = 0L): Byte {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_BYTE, offset)
    }

    fun MemorySegment.getShort(offset: Long = 0L): Short {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_SHORT, offset)
    }

    fun MemorySegment.getInt(offset: Long = 0L): Int {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_INT, offset)
    }

    fun MemorySegment.getLong(offset: Long = 0L): Long {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_LONG, offset)
    }

    fun MemorySegment.getFloat(offset: Long = 0L): Float {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_FLOAT, offset)
    }

    fun MemorySegment.getDouble(offset: Long = 0L): Double {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_DOUBLE, offset)
    }

    fun MemorySegment.getChar(offset: Long = 0L): Char {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_CHAR, offset)
    }

    fun MemorySegment.getBoolean(offset: Long = 0L): Boolean {
        if (address() == 0L) throw NullPointerException()
        return this.get(JAVA_BOOLEAN, offset)
    }

    fun MemorySegment.readInt(offset: Long = 0L): Int {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_INT).get(JAVA_INT, 0)
    }

    fun MemorySegment.readShort(offset: Long = 0L): Short {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_SHORT).get(JAVA_SHORT, 0)
    }

    fun MemorySegment.readLong(offset: Long = 0L): Long {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_LONG).get(JAVA_LONG, 0)
    }

    fun MemorySegment.readFloat(offset: Long = 0L): Float {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_FLOAT).get(JAVA_FLOAT, 0)
    }

    fun MemorySegment.readDouble(offset: Long = 0L): Double {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_DOUBLE).get(JAVA_DOUBLE, 0)
    }

    fun MemorySegment.readChar(offset: Long = 0L): Char {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_CHAR).get(JAVA_CHAR, 0)
    }

    fun MemorySegment.readBoolean(offset: Long = 0L): Boolean {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_BOOLEAN).get(JAVA_BOOLEAN, 0)
    }

    fun MemorySegment.readByte(offset: Long = 0L): Byte {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, JAVA_BYTE).get(JAVA_BYTE, 0)
    }

    fun MemorySegment.pointerAtOffset(offset: Long = 0L, size: Long): MemorySegment {
        if (address() == 0L) throw NullPointerException()
        return this.asSlice(offset, ADDRESS).reinterpret(size)
    }

    fun MemorySegment.deref(offset: Long = 0L, size: Long): MemorySegment {
        if (address() == 0L) throw NullPointerException()
        return this.get(ADDRESS, offset).reinterpret(size)
    }

    val MemorySegment.getOrNull: MemorySegment?
        get() = if (address() == 0L) null else this

    fun MemorySegment.toShared(): SharedPointer {
        if (address() == 0L) throw NullPointerException()
        return SharedPointer(this.reinterpret(0x10))
    }

    fun Long.toMemorySegment(size: Long): MemorySegment = MemorySegment.ofAddress(this).reinterpret(size)
}

/** The segment widened to [size] when a caller handed over a narrower window; a null segment stays null. */
fun MemorySegment.atLeast(size: Long): MemorySegment =
    if (address() == 0L || byteSize() >= size) this else reinterpret(size)
