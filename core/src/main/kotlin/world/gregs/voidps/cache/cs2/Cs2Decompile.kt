package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.compile.Cs2CodeGen
import world.gregs.voidps.cache.cs2.compile.Cs2Parser
import world.gregs.voidps.cache.cs2.ir.Cs2Function

/**
 * The source written out for one cached script.
 *
 * Structured output is the goal, but fidelity comes first: where the structurer
 * cannot reproduce a script's exact branch layout the literal block-by-block
 * rendering can, and re-compiling is the only thing that tells the two apart.
 * A script the cache has no bytes for, or that neither form reproduces, keeps
 * the structured rendering - it is the one worth reading.
 */
object Cs2Decompile {

    /** What a script decompiled to, and whether that source re-compiles to the cached bytes. */
    class Decompiled(val source: String, val function: Cs2Function, val exact: Boolean)

    fun source(
        script: Cs2Script,
        scriptId: Int,
        context: Cs2Context,
        cached: ByteArray?,
        shapeReading: Cs2ShapeReading = Cs2ShapeReading.NUMERIC,
    ): String = decompile(script, scriptId, context, cached, shapeReading).source

    fun decompile(
        script: Cs2Script,
        scriptId: Int,
        context: Cs2Context,
        cached: ByteArray?,
        shapeReading: Cs2ShapeReading = Cs2ShapeReading.NUMERIC,
    ): Decompiled {
        val structured = function(script, scriptId, context, faithful = false)
        val structuredSource = Cs2Emitter(structured, shapeReading).emit()
        if (cached == null) return Decompiled(structuredSource, structured, exact = false)
        if (encodes(structuredSource, context, cached)) return Decompiled(structuredSource, structured, exact = true)
        val faithful = try {
            function(script, scriptId, context, faithful = true)
        } catch (e: Exception) {
            return Decompiled(structuredSource, structured, exact = false)
        }
        val faithfulSource = try {
            Cs2Emitter(faithful, shapeReading).emit()
        } catch (e: Exception) {
            return Decompiled(structuredSource, structured, exact = false)
        }
        if (encodes(faithfulSource, context, cached)) return Decompiled(faithfulSource, faithful, exact = true)
        return Decompiled(structuredSource, structured, exact = false)
    }

    /**
     * The lifted script with every operand the corpus could type spelled out. Every rendering it
     * adds packs back to the integer it replaced, so the result still re-compiles byte for byte.
     */
    fun function(
        script: Cs2Script,
        scriptId: Int,
        context: Cs2Context,
        faithful: Boolean = false,
        tally: Cs2TypeTally = Cs2TypeTally(),
    ): Cs2Function =
        Cs2Retype(context.operandTypes(), tally).apply(Cs2Structurer(script, scriptId, context, faithful).structure())

    private fun encodes(source: String, context: Cs2Context, cached: ByteArray): Boolean = try {
        val rebuilt = Cs2CodeGen(Cs2Parser(source, context).parse(), context).generate()
        Cs2Codec.encode(rebuilt).contentEquals(cached)
    } catch (e: Exception) {
        false
    }
}
