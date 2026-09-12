package world.gregs.voidps.cache

import it.unimi.dsi.fastutil.ints.IntArrayList
import org.projectx.core.Logger.logError
import org.projectx.core.Logger.logInfo
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.OpcodeOrdered
import java.nio.BufferUnderflowException

abstract class TypeDecoder<T : CacheType>(val index: Int) {

    /** Set to collect decode telemetry; leave null to keep the decoder on its zero-overhead path. */
    var report: DecodeReport? = null

    protected val typeName: String by lazy { this::class.simpleName ?: "TypeDecoder" }

    abstract fun create(size: Int): Array<T>

    open fun load(definitions: Array<T>, reader: Reader) {
        val id = readId(reader)
        read(definitions, id, reader)
    }

    open fun readId(reader: Reader) = reader.readInt()

    open fun load(cache: Cache): Array<T> {
        val start = System.currentTimeMillis()
        val size = size(cache) + 1
        val definitions = create(size)
        for (id in 0 until size) {
            try {
                load(definitions, cache, id)
            } catch (e: BufferUnderflowException) {
                val report = report
                if (report == null) {
                    logError("Error reading definition $id", e)
                    throw e
                }
                report.failure(typeName, id, e)
            }
        }
        logInfo("$size ${this::class.simpleName} definitions loaded in ${System.currentTimeMillis() - start}ms")
        return definitions
    }

    open fun size(cache: Cache): Int {
        return cache.lastArchiveId(index) * 256 + (cache.fileCount(index, cache.lastArchiveId(index)))
    }

    open fun load(definitions: Array<T>, cache: Cache, id: Int) {
        val archive = getArchive(id)
        val file = getFile(id)
        val data = cache.data(index, archive, file) ?: return
        read(definitions, id, BufferReader(data))
    }

    open fun getFile(id: Int) = id

    open fun getArchive(id: Int) = id

    protected fun read(definitions: Array<T>, id: Int, reader: Reader) {
        val definition = definitions[id]
        readLoop(definition, reader)
        changeValues(definitions, definition)
    }

    /**
     * Reads records until the 0 terminator.
     *
     * A definition that implements [OpcodeOrdered] also has recorded on it what the decoded fields cannot
     * hold - the order the file listed its records in, and the payload of every record a later record of
     * the same opcode overwrote - which is what lets its encoder write the file back byte for byte.
     *
     * The recording lives here, in the read path, rather than in [changeValues]: that derives values which
     * are not in the file at all, and a definition re-encoded after it no longer matches its file.
     */
    open fun readLoop(definition: T, buffer: Reader) {
        val ordered = definition as? OpcodeOrdered
        val report = report
        if (ordered == null && report == null) {
            readOpcodes(definition, buffer)
            return
        }
        val order = if (ordered == null) null else IntArrayList()
        val bounds = if (ordered == null) null else IntArrayList()
        var zeroAdvance: MutableList<DecodeReport.Opcode>? = null
        try {
            while (true) {
                val opcode = buffer.readUnsignedByte()
                if (opcode == 0) {
                    break
                }
                val start = buffer.position()
                definition.read(opcode, buffer)
                val end = buffer.position()
                if (report != null && end == start) {
                    val list = zeroAdvance ?: ArrayList<DecodeReport.Opcode>().also { zeroAdvance = it }
                    list.add(DecodeReport.Opcode(opcode, start))
                }
                if (order != null) {
                    order.add(opcode)
                    bounds!!.add(start)
                    bounds.add(end)
                }
            }
        } catch (e: RuntimeException) {
            if (report == null) {
                throw e
            }
            report.failure(typeName, definition.id, e)
            return
        }
        if (ordered != null) {
            recordLayout(definition, ordered, buffer, order!!.toIntArray(), bounds!!)
        }
        report?.record(typeName, definition.id, zeroAdvance ?: emptyList(), buffer.remaining)
    }

    private fun readOpcodes(definition: T, buffer: Reader) {
        while (true) {
            val opcode = buffer.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            definition.read(opcode, buffer)
        }
    }

    /**
     * The opcodes this type's encoder writes for [definition] on its own, which is what decides whether
     * the file's own order has to be kept; null for a type with no byte exact encoder, which keeps it
     * whatever it was.
     */
    protected open fun canonicalOpcodes(definition: T): IntArray? = null

    /**
     * Self validating: the order is dropped only when the encoder's own order reproduces it exactly, so a
     * presence rule that is wrong costs a stored order rather than corrupting the file. A definition with
     * any shadowed payload always keeps its order, because the two are indexed against each other.
     */
    private fun recordLayout(definition: T, ordered: OpcodeOrdered, buffer: Reader, opcodes: IntArray, bounds: IntArrayList) {
        val shadowed = shadowedPayloads(ordered, buffer, opcodes, bounds)
        ordered.shadowedPayloads = shadowed
        ordered.opcodeOrder = if (shadowed == null && opcodes.contentEquals(canonicalOpcodes(definition))) null else opcodes
    }

    private fun shadowedPayloads(ordered: OpcodeOrdered, buffer: Reader, opcodes: IntArray, bounds: IntArrayList): Array<ByteArray?>? {
        val last = HashMap<Int, Int>(opcodes.size)
        var repeats = false
        for (index in opcodes.indices) {
            if (last.put(opcodes[index], index) != null) {
                repeats = true
            }
        }
        if (!repeats) {
            return null
        }
        val end = buffer.position()
        val shadowed = arrayOfNulls<ByteArray>(opcodes.size)
        var overwritten = false
        for (index in opcodes.indices) {
            val opcode = opcodes[index]
            if (ordered.repeats(opcode) || last.getValue(opcode) == index) {
                continue
            }
            val payload = payload(buffer, bounds, index)
            if (payload.contentEquals(payload(buffer, bounds, last.getValue(opcode)))) {
                continue
            }
            shadowed[index] = payload
            overwritten = true
        }
        buffer.position(end)
        return if (overwritten) shadowed else null
    }

    private fun payload(buffer: Reader, bounds: IntArrayList, index: Int): ByteArray {
        val start = bounds.getInt(index * 2)
        val payload = ByteArray(bounds.getInt(index * 2 + 1) - start)
        buffer.position(start)
        buffer.readBytes(payload)
        return payload
    }

    /**
     * The bytes [read] consumes, for a record whose payload the client itself discards: keeping them
     * verbatim is what lets the encoder put the record back without a field for every byte of it.
     */
    protected fun Reader.capture(read: Reader.() -> Unit): ByteArray {
        val start = position()
        read()
        val end = position()
        val bytes = ByteArray(end - start)
        position(start)
        readBytes(bytes)
        position(end)
        return bytes
    }

    /** An opcode this decoder has no arm for: reported when telemetry is on, fatal when it is not. */
    protected fun T.unknown(opcode: Int, buffer: Reader) {
        val report = report ?: error("Unhandled $typeName opcode $opcode in $id at ${buffer.position()}")
        report.unknown(typeName, id, opcode, buffer.position())
    }

    /** A record spread over several files reports the trailing bytes its decode leaves behind. */
    protected fun recordDecode(id: Int, decode: () -> Int) {
        val report = report
        if (report == null) {
            decode()
            return
        }
        val trailing = try {
            decode()
        } catch (e: RuntimeException) {
            report.failure(typeName, id, e)
            return
        }
        report.record(typeName, id, emptyList(), trailing)
    }

    /** A positional format has no opcode loop, so trailing bytes and throws are all the report can see. */
    protected fun recordDecode(id: Int, buffer: Reader, decode: () -> Unit) {
        val report = report
        if (report == null) {
            decode()
            return
        }
        try {
            decode()
        } catch (e: RuntimeException) {
            report.failure(typeName, id, e)
            return
        }
        report.record(typeName, id, emptyList(), buffer.remaining)
    }

    protected abstract fun T.read(opcode: Int, buffer: Reader)

    open fun changeValues(definitions: Array<T>, definition: T) {
    }

    companion object {
        fun byteToChar(b: Byte): Char {
            var i = 0xff and b.toInt()
            require(i != 0) { "Non cp1252 character 0x" + i.toString(16) + " provided" }
            if (i in 128..159) {
                var char = UNICODE_TABLE[i - 128].code
                if (char == 0) {
                    char = 63
                }
                i = char
            }
            return i.toChar()
        }

        private var UNICODE_TABLE = charArrayOf(
            '\u20ac',
            '\u0000',
            '\u201a',
            '\u0192',
            '\u201e',
            '\u2026',
            '\u2020',
            '\u2021',
            '\u02c6',
            '\u2030',
            '\u0160',
            '\u2039',
            '\u0152',
            '\u0000',
            '\u017d',
            '\u0000',
            '\u0000',
            '\u2018',
            '\u2019',
            '\u201c',
            '\u201d',
            '\u2022',
            '\u2013',
            '\u2014',
            '\u02dc',
            '\u2122',
            '\u0161',
            '\u203a',
            '\u0153',
            '\u0000',
            '\u017e',
            '\u0178'
        )
    }
}