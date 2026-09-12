package com.projectx.game.cs2

import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.memory.SafeNativeAccess
import com.projectx.game.nxt.OFunctions
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

object CS2Executor {
    private object HookContext {
        const val SCRIPT_ID = 0x00L
        const val ARGS_START = 0x08L
        const val ARGS_END = 0x10L
        const val ARGS_CAPACITY = 0x18L
        const val INLINE_ARGS = 0x30L
        const val INLINE_ARGS_END = 0xF0L
        const val EVENT_OPBASE = 0x138L
        const val EVENT_OPBASE_SSO = 0x14FL
        const val EVENT_TEXT = 0x158L
        const val EVENT_TEXT_SSO = 0x16FL
        const val MAX_STEPS = 0x170L
        const val SIZE = 0x178L
    }

    private object Arg {
        const val SIZE = 0x20L
        const val TYPE_OFFSET = 0x18L

        const val TYPE_INT: Byte = 0x00
        const val TYPE_LONG: Byte = 0x01
        const val TYPE_STRING: Byte = 0x02
        const val TYPE_UNINITIALIZED: Byte = 0xFF.toByte()
    }

    /**
     * ClientScriptState operand-stack layout. Each operand stack is an inline array of 1000 elements
     * followed by its depth counter; a finished script leaves its return values here, not in the
     * locals vectors. ExecuteScript resets all three SPs to 0.
     */
    private object ScriptState {
        const val INT_DATA = 0x100L
        const val INT_SP = 0x10a0L
        const val STRING_DATA = 0x10a8L
        const val STRING_SP = 0x8da8L
        const val STRING_STRIDE = 0x20L
        const val LONG_DATA = 0x8db0L
        const val LONG_SP = 0xacf0L
        const val STATE_SPAN = 0xC420L

        const val INT_LOCALS_BEGIN = 0x28L
        const val INT_LOCALS_END = 0x30L
        const val STRING_LOCALS_BEGIN = 0x40L
        const val STRING_LOCALS_END = 0x48L
        const val LONG_LOCALS_BEGIN = 0x58L
        const val LONG_LOCALS_END = 0x60L
    }

    private const val CLIENTSCRIPT_ID = 0x10L

    /** Args captured at a script's entry; completed with return values once it finishes. */
    data class TracePending(
        val scriptId: Int,
        val argInts: IntArray,
        val argLongs: LongArray,
        val argStrings: List<String>,
    )

    /** Read a script's id and entry arguments (locals) for the trace, before its body runs. */
    fun beginTrace(clientScript: MemorySegment, state: MemorySegment): TracePending {
        val scriptId = clientScript.reinterpret(CLIENTSCRIPT_ID + 4).get(JAVA_INT, CLIENTSCRIPT_ID)
        val s = state.reinterpret(ScriptState.STATE_SPAN)
        return TracePending(
            scriptId,
            readHeapInts(s.get(JAVA_LONG, ScriptState.INT_LOCALS_BEGIN), s.get(JAVA_LONG, ScriptState.INT_LOCALS_END)),
            readHeapLongs(s.get(JAVA_LONG, ScriptState.LONG_LOCALS_BEGIN), s.get(JAVA_LONG, ScriptState.LONG_LOCALS_END)),
            readHeapStrings(s.get(JAVA_LONG, ScriptState.STRING_LOCALS_BEGIN), s.get(JAVA_LONG, ScriptState.STRING_LOCALS_END)),
        )
    }

    /** Complete a trace entry with the values the finished script left on its operand stacks. */
    fun endTrace(pending: TracePending, state: MemorySegment) {
        val r = readStacks(state.reinterpret(ScriptState.STATE_SPAN))
        CS2Trace.record(
            CS2Trace.Entry(
                CS2Trace.nextSeq(), pending.scriptId,
                pending.argInts, pending.argLongs, pending.argStrings,
                r.ints, r.longs, r.strings,
            )
        )
    }

    private fun readHeapInts(begin: Long, end: Long): IntArray {
        val count = (end - begin) / 4
        if (begin == 0L || count !in 1..4096) return IntArray(0)
        val seg = MemorySegment.ofAddress(begin).reinterpret(count * 4)
        return IntArray(count.toInt()) { seg.getAtIndex(JAVA_INT, it.toLong()) }
    }

    private fun readHeapLongs(begin: Long, end: Long): LongArray {
        val count = (end - begin) / 8
        if (begin == 0L || count !in 1..4096) return LongArray(0)
        val seg = MemorySegment.ofAddress(begin).reinterpret(count * 8)
        return LongArray(count.toInt()) { seg.getAtIndex(JAVA_LONG, it.toLong()) }
    }

    private fun readHeapStrings(begin: Long, end: Long): List<String> {
        val count = (end - begin) / ScriptState.STRING_STRIDE
        if (begin == 0L || count !in 1..4096) return emptyList()
        return (0 until count).map { readEastlStringAt(begin + it * ScriptState.STRING_STRIDE) }
    }

    /**
     * Read an embedded EASTL string (24-byte union) at a native address entirely via
     * /proc/self/mem — a torn/garbage heap pointer returns "" instead of segfaulting the client.
     */
    private fun readEastlStringAt(addr: Long): String {
        val slot = SafeNativeAccess.snapshotInto(addr, 24) ?: return ""
        val sizeByte = slot.get(JAVA_BYTE, 0x17L).toInt() and 0xFF
        if (sizeByte and 0x80 == 0) {
            val len = 23 - sizeByte
            if (len <= 0) return ""
            return buildString { for (i in 0 until len) append((slot.get(JAVA_BYTE, i.toLong()).toInt() and 0xFF).toChar()) }
        }
        val ptr = slot.get(JAVA_LONG_UNALIGNED, 0L)
        val size = slot.get(JAVA_LONG_UNALIGNED, 0x08L)
        if (ptr == 0L || size !in 1..8192) return ""
        val data = SafeNativeAccess.snapshotInto(ptr, size) ?: return ""
        return buildString { for (i in 0 until size.toInt()) append((data.get(JAVA_BYTE, i.toLong()).toInt() and 0xFF).toChar()) }
    }

    /** Result of a script run: the values left on each operand stack, in push order. */
    data class Cs2Result(val ints: IntArray, val longs: LongArray, val strings: List<String>) {
        val int: Int? get() = ints.lastOrNull()
        val long: Long? get() = longs.lastOrNull()
        val string: String? get() = strings.lastOrNull()
        override fun toString() = "Cs2Result(ints=${ints.toList()}, longs=${longs.toList()}, strings=$strings)"
    }

    private const val SSO_EMPTY_MARKER: Byte = 0x17
    private const val MAX_INLINE_ARGS = 6
    private const val DEFAULT_MAX_STEPS = 1_000_000

    @Volatile
    private var scriptRunnerPtr: MemorySegment = MemorySegment.NULL

    private val pendingScripts = ConcurrentLinkedQueue<CS2Request>()

    // While true, the next ExecuteScript seen by the hook records its state pointer so the run's
    // return values can be read once it finishes. Only the outermost (first) script is recorded.
    @Volatile
    private var capturingResult = false

    @Volatile
    private var recordedState: MemorySegment = MemorySegment.NULL

    private val executeHookInner: MethodHandle by lazy {
        NativeAccess.BASE_ADDR
            .asSlice(OFunctions.SCRIPTRUNNER_EXECUTEHOOKINNER)
            .reinterpret(8)
            .toFunctionHandle(
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)
            )
    }

    /**
     * Fired from [com.projectx.game.hooks.impl.ScriptRunnerCapture] on every ExecuteScript call.
     * Captures the live ScriptRunner once, and — while a result run is in flight — records the
     * outermost script's state pointer so its return stacks can be read after it completes.
     */
    fun onExecuteScript(scriptRunner: MemorySegment, state: MemorySegment) {
        if (scriptRunnerPtr.address() == 0L && scriptRunner.address() != 0L) {
            scriptRunnerPtr = scriptRunner.reinterpret(Long.MAX_VALUE)
        }
        if (capturingResult && recordedState.address() == 0L && state.address() != 0L) {
            recordedState = state.reinterpret(ScriptState.STATE_SPAN)
        }
    }

    /** Queue a CS2 script for execution on the next game tick. Thread-safe. */
    fun executeScript(scriptId: Int, vararg args: Any) {
        pendingScripts.offer(CS2Request(scriptId, args.toList()))
    }

    /** Process queued scripts on the game thread. Called from the ClientMainLogic hook. */
    fun mainLogicTick() {
        if (scriptRunnerPtr.address() == 0L) {
            return
        }

        var request = pendingScripts.poll()
        while (request != null) {
            try {
                if (request.result != null) {
                    request.result.complete(executeForResultInternal(request.scriptId, request.args))
                } else {
                    executeScriptInternal(request.scriptId, request.args)
                }
            } catch (e: Throwable) {
                request.result?.completeExceptionally(e)
                println("[CS2Executor] Error executing script ${request.scriptId}: ${e.message}")
                e.printStackTrace()
            }
            request = pendingScripts.poll()
        }
    }

    /**
     * Execute [scriptId] and return the values it leaves on the operand stacks. Thread-safe: the run
     * is queued and performed on the game thread; the caller blocks up to [timeoutMs] for the result.
     * Returns null if the ScriptRunner isn't captured yet or the run times out. Must NOT be called
     * from the game thread (it would deadlock waiting on its own tick).
     */
    fun executeScriptForResult(scriptId: Int, args: List<Any>, timeoutMs: Long = 2000): Cs2Result? {
        if (scriptRunnerPtr.address() == 0L) return null
        val future = CompletableFuture<Cs2Result>()
        pendingScripts.offer(CS2Request(scriptId, args, future))
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Synchronous result variant for callers already on the game thread (e.g. MCP `onGameTick`
     * handlers). Runs the script inline rather than queueing — queueing would deadlock, since the
     * drain that completes the queue can't run while the game thread is blocked here.
     */
    fun executeScriptForResultSync(scriptId: Int, args: List<Any>): Cs2Result? {
        if (scriptRunnerPtr.address() == 0L) return null
        return executeForResultInternal(scriptId, args)
    }

    /** Runs the script with result-capture armed, then reads the recorded state's stacks. */
    private fun executeForResultInternal(scriptId: Int, args: List<Any>): Cs2Result {
        recordedState = MemorySegment.NULL
        capturingResult = true
        try {
            executeScriptInternal(scriptId, args)
        } finally {
            capturingResult = false
        }
        return readResults()
    }

    private fun readResults(): Cs2Result {
        if (recordedState.address() == 0L) return Cs2Result(IntArray(0), LongArray(0), emptyList())
        return readStacks(recordedState)
    }

    /** Read the int/long/string operand stacks of [state] (each: data block + depth counter). */
    private fun readStacks(state: MemorySegment): Cs2Result {
        val ints = IntArray(depth(state.get(JAVA_INT, ScriptState.INT_SP))) {
            state.get(JAVA_INT, ScriptState.INT_DATA + it * 4L)
        }
        val longs = LongArray(depth(state.get(JAVA_INT, ScriptState.LONG_SP))) {
            state.get(JAVA_LONG, ScriptState.LONG_DATA + it * 8L)
        }
        val strings = (0 until depth(state.get(JAVA_INT, ScriptState.STRING_SP))).map {
            readEastlStringAt(state.address() + ScriptState.STRING_DATA + it * ScriptState.STRING_STRIDE)
        }
        return Cs2Result(ints, longs, strings)
    }

    private fun depth(sp: Int): Int = if (sp in 1..4096) sp else 0

    /** Execute a script immediately (must be on the game thread). */
    private fun executeScriptInternal(scriptId: Int, args: List<Any>) {
        Arena.ofConfined().use { arena ->
            val ctx = buildHookContext(arena, scriptId, args)
            executeHookInner.invoke(scriptRunnerPtr, ctx, DEFAULT_MAX_STEPS)
        }
    }

    private fun buildHookContext(arena: Arena, scriptId: Int, args: List<Any>): MemorySegment {
        val ctx = arena.allocate(HookContext.SIZE)
        ctx.fill(0)
        ctx.set(JAVA_INT, HookContext.SCRIPT_ID, scriptId)

        // Stage arguments. Up to MAX_INLINE_ARGS fit in the context's inline buffer; beyond that we
        // allocate a separate arena segment and point the arg vector at it. ExecuteHookInner reads
        // args_start..args_end regardless of where they live, and the confined arena keeps the
        // external storage alive through the native call.
        val argCount = args.size
        val argBase: MemorySegment
        val argsStartAddr: Long
        val argsCapacityAddr: Long
        if (argCount <= MAX_INLINE_ARGS) {
            argBase = ctx
            argsStartAddr = ctx.address() + HookContext.INLINE_ARGS
            argsCapacityAddr = ctx.address() + HookContext.INLINE_ARGS_END
            for (i in 0 until argCount) writeArgument(ctx, HookContext.INLINE_ARGS + i * Arg.SIZE, args[i])
        } else {
            argBase = arena.allocate(argCount.toLong() * Arg.SIZE)
            argBase.fill(0)
            argsStartAddr = argBase.address()
            argsCapacityAddr = argsStartAddr + argCount * Arg.SIZE
            for (i in 0 until argCount) writeArgument(argBase, i.toLong() * Arg.SIZE, args[i])
        }
        val argsEndAddr = argsStartAddr + argCount * Arg.SIZE

        ctx.set(JAVA_LONG, HookContext.ARGS_START, argsStartAddr)
        ctx.set(JAVA_LONG, HookContext.ARGS_END, argsEndAddr)
        ctx.set(JAVA_LONG, HookContext.ARGS_CAPACITY, argsCapacityAddr)

        ctx.set(JAVA_BYTE, HookContext.EVENT_OPBASE_SSO, SSO_EMPTY_MARKER)
        ctx.set(JAVA_BYTE, HookContext.EVENT_TEXT_SSO, SSO_EMPTY_MARKER)

        ctx.set(JAVA_INT, HookContext.MAX_STEPS, DEFAULT_MAX_STEPS)

        return ctx
    }

    private fun writeArgument(ctx: MemorySegment, offset: Long, value: Any) {
        when (value) {
            is Int -> {
                ctx.set(JAVA_INT, offset, value)
                ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_INT)
            }
            is Long -> {
                ctx.set(JAVA_LONG, offset, value)
                ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_LONG)
            }
            is String -> {
                writeStringArgument(ctx, offset, value)
                ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_STRING)
            }
            is Number -> {
                if (value.toLong() > Int.MAX_VALUE || value.toLong() < Int.MIN_VALUE) {
                    ctx.set(JAVA_LONG, offset, value.toLong())
                    ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_LONG)
                } else {
                    ctx.set(JAVA_INT, offset, value.toInt())
                    ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_INT)
                }
            }
            else -> {
                writeStringArgument(ctx, offset, value.toString())
                ctx.set(JAVA_BYTE, offset + Arg.TYPE_OFFSET, Arg.TYPE_STRING)
            }
        }
    }

    /** Write a string argument using EASTL SSO format (<= 22 chars inline, marker at offset 0x17). */
    private fun writeStringArgument(ctx: MemorySegment, offset: Long, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val maxSsoLen = 22

        if (bytes.size <= maxSsoLen) {
            for (i in bytes.indices) {
                ctx.set(JAVA_BYTE, offset + i, bytes[i])
            }
            if (bytes.size < maxSsoLen) {
                ctx.set(JAVA_BYTE, offset + bytes.size, 0)
            }
            val ssoMarker = (0x17 - bytes.size).toByte()
            ctx.set(JAVA_BYTE, offset + 0x17, ssoMarker)
        } else {
            println("[CS2Executor] Warning: String argument truncated to $maxSsoLen bytes")
            for (i in 0 until maxSsoLen) {
                ctx.set(JAVA_BYTE, offset + i, bytes[i])
            }
            ctx.set(JAVA_BYTE, offset + 0x17, 0)
        }
    }

    fun isReady(): Boolean = scriptRunnerPtr.address() != 0L

    fun pendingCount(): Int = pendingScripts.size

    private data class CS2Request(
        val scriptId: Int,
        val args: List<Any>,
        val result: CompletableFuture<Cs2Result>? = null
    )
}
