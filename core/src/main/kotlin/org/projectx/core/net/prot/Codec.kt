package org.projectx.core.net.prot

import io.ktor.utils.io.*
import kotlinx.io.Source
import org.projectx.core.Logger.logError
import org.projectx.core.net.prot.decode.DecodedPacket
import org.projectx.core.net.prot.decode.ProtSchema
import org.projectx.core.net.prot.decode.StructuredDecoder
import org.projectx.core.Logger.logInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.primaryConstructor

class Codec {
    val serverProts = mutableMapOf<KClass<out ServerProt>, ServerProtCodec>()
    val clientProtsByOpcode = mutableMapOf<Int, ClientProtCodec<*>>()
    private val opcodeToClassMap = mutableMapOf<Int, KClass<out ClientProt>>()

    /**
     * S->C structured decoders, keyed by opcode — the mirror of [clientProtsByOpcode] for the
     * OTHER direction. A server decoder reads a packet's payload (reversing that opcode's encoder)
     * and returns a human-readable, gameval-linked line for the packet dumper. The result is a
     * display String (not a reconstructed [ServerProt]) — the dumper only needs a readable line.
     */
    val serverDecodersByOpcode = mutableMapOf<Int, suspend Source.(Int) -> String>()

    /** Register an S->C display decoder for [opcode] (mirror of the [clientProt] decoder registration). */
    internal fun serverDecode(opcode: Int, decoder: suspend Source.(Int) -> String) {
        serverDecodersByOpcode[opcode] = decoder
    }

    /**
     * Structured decoders, keyed by direction and opcode. These are the ones that produce queryable
     * fields; [serverDecodersByOpcode] is the older display-only form and is derived from these
     * where both exist, so the text dump and the field data can never disagree.
     */
    val structuredDecoders = mutableMapOf<Pair<Int, Int>, StructuredDecoder>()

    internal fun decodeStructured(
        dir: Int,
        opcode: Int,
        schema: ProtSchema,
        reconstruct: ((DecodedPacket) -> Any?)? = null,
        decoder: suspend Source.(Int) -> DecodedPacket,
    ) {
        structuredDecoders[dir to opcode] = StructuredDecoder(schema, decoder, reconstruct)
    }

    fun structuredDecoder(dir: Int, opcode: Int): StructuredDecoder? = structuredDecoders[dir to opcode]

    /** Every declared schema in this codec, for the field dictionary a query database builds. */
    fun schemas(): Map<Pair<Int, Int>, ProtSchema> = structuredDecoders.mapValues { it.value.schema }

    /**
     * Decoder-less packets (e.g. the per-second Ping keepalive) are stateless singletons —
     * resolve the reflective instance once per class and reuse it for every packet.
     */
    private val decoderlessInstances = ConcurrentHashMap<KClass<out ClientProt>, ClientProt>()

    /** ServerProt types already reported as lacking an encoder in this codec (log once, not per send). */
    private val reportedUnsupportedServerProts = ConcurrentHashMap.newKeySet<KClass<out ServerProt>>()

    /** Opcode-indexed metadata for ALL server prots (name + size), for proxy/debug use. */
    val serverProtInfo = mutableMapOf<Int, ProtInfo>()
    /** Opcode-indexed metadata for ALL client prots (name + size), for proxy/debug use. */
    val clientProtInfo = mutableMapOf<Int, ProtInfo>()

    data class ProtInfo(val name: String, val size: ProtSize)

    data class ServerProtCodec(
        val opcode: Int,
        val size: ProtSize,
        val encoder: (suspend ServerProt.(ByteWriteChannel) -> Unit)?
    )

    data class ClientProtCodec<T : ClientProt>(
        val size: ProtSize,
        val decoder: (suspend Source.(Int) -> T)?,
        val protClass: KClass<T>
    )

    internal inline fun <reified T : ServerProt> serverProt(
        opcode: Int,
        size: ProtSize = ProtSize.Fixed(0),
        noinline encoder: (suspend T.(ByteWriteChannel) -> Unit)? = null
    ) {
        serverProts[T::class] = ServerProtCodec(
            opcode = opcode,
            size = size,
            encoder = encoder?.let { { output -> (this as T).it(output) } }
        )
        serverProtInfo[opcode] = ProtInfo(displayName<T>(opcode), size)
    }

    internal inline fun <reified T : ServerProt> serverProt(
        opcode: Int,
        size: Int,
        noinline encoder: (suspend T.(ByteWriteChannel) -> Unit)? = null
    ) {
        val protSize = ProtSize.Fixed(size)
        serverProts[T::class] = ServerProtCodec(
            opcode = opcode,
            size = protSize,
            encoder = encoder?.let { { output -> (this as T).it(output) } }
        )
        serverProtInfo[opcode] = ProtInfo(displayName<T>(opcode), protSize)
    }

    /**
     * A zone sub-prot with no ServerProt opcode of its own: it exists only inside an
     * `UPDATE_ZONE_*` bundle, which reads the encoder and size from here and writes the zone
     * sub-opcode itself. Claiming no opcode is the point - a stray standalone send is refused
     * rather than going out under an unrelated packet's opcode.
     */
    internal inline fun <reified T : ServerProt> zoneOnlyProt(
        size: Int,
        noinline encoder: suspend T.(ByteWriteChannel) -> Unit,
    ) {
        serverProts[T::class] = ServerProtCodec(
            opcode = ZONE_ONLY_OPCODE,
            size = ProtSize.Fixed(size),
            encoder = { output -> (this as T).encoder(output) },
        )
    }

    internal inline fun <reified T : ClientProt> clientProt(opcodes: IntArray, size: ProtSize = ProtSize.Fixed(0), noinline decoder: (suspend Source.(Int) -> T)? = null) {
        val codec = ClientProtCodec(size, decoder, T::class)
        opcodes.forEach { opcode ->
            clientProtsByOpcode[opcode] = codec
            opcodeToClassMap[opcode] = T::class
            clientProtInfo[opcode] = ProtInfo(displayName<T>(opcode), size)
        }
    }

    internal inline fun <reified T : ClientProt> clientProt(opcodes: IntArray, size: Int, noinline decoder: (suspend Source.(Int) -> T)? = null) {
        val protSize = ProtSize.Fixed(size)
        val codec = ClientProtCodec(protSize, decoder, T::class)
        opcodes.forEach { opcode ->
            clientProtsByOpcode[opcode] = codec
            opcodeToClassMap[opcode] = T::class
            clientProtInfo[opcode] = ProtInfo(displayName<T>(opcode), protSize)
        }
    }

    internal inline fun <reified T : ClientProt> clientProt(opcode: Int, size: ProtSize = ProtSize.Fixed(0), noinline decoder: (suspend Source.() -> T)? = null) {
        clientProt<T>(
            opcodes = intArrayOf(opcode),
            size = size,
            decoder = decoder?.let { { _ -> this.it() } }
        )
    }

    internal inline fun <reified T : ClientProt> clientProt(opcode: Int, size: Int, noinline decoder: (suspend Source.() -> T)? = null) {
        clientProt<T>(
            opcodes = intArrayOf(opcode),
            size = ProtSize.Fixed(size),
            decoder = decoder?.let { { _ -> this.it() } }
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ClientProt> createInstanceForOpcode(opcode: Int): T? {
        val protClass = opcodeToClassMap[opcode] ?: return null

        val cached = decoderlessInstances[protClass]
        if (cached != null) return cached as T

        val instance = try {
            if (protClass.isValue) {
                protClass.primaryConstructor?.let { createValueClassInstance(it) }
            } else {
                protClass.constructors.firstOrNull { it.parameters.isEmpty() }?.call()
            }
        } catch (e: Exception) {
            logError("Failed to instantiate ClientProt ${protClass.simpleName} for opcode $opcode", e)
            return null
        }
        if (instance == null) {
            logError("No usable constructor for ClientProt ${protClass.simpleName} (opcode $opcode)")
            return null
        }
        decoderlessInstances[protClass] = instance
        return instance as T
    }

    /**
     * True if this codec has an encoder entry registered for [type]. When it does not,
     * the capability gap is logged once (INFO) so callers can [gate sends][org.projectx.core.net.Session.sendIfSupported]
     * without producing per-send warn spam.
     */
    fun supportsServerProt(type: KClass<out ServerProt>): Boolean {
        if (serverProts.containsKey(type)) return true
        if (reportedUnsupportedServerProts.add(type)) {
            logInfo("ServerProt ${type.simpleName} has no encoder registered in this codec revision — sends of it will be skipped")
        }
        return false
    }

    private fun <T : Any> createValueClassInstance(constructor: KFunction<T>): T {
        val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()
        for (param in constructor.parameters) {
            when (param.type.classifier) {
                Int::class -> args[param] = 0
                String::class -> args[param] = ""
                Boolean::class -> args[param] = false
                else -> args[param] = null
            }
        }
        return constructor.callBy(args)
    }

    /**
     * Register the canonical official (UPPER_SNAKE) display name + size metadata for a server
     * opcode. Unlike a naive `putIfAbsent`, the canonical NAME wins over an encoder's class-name
     * (which is PascalCase) so [serverProtName] reports a consistent UPPER_SNAKE name for EVERY
     * opcode regardless of whether it has an encoder.
     *
     * The encoder/decoder + its authoritative size stay registered in [serverProts] (that is the
     * codec invariant — encoders win for the CODEC); only the DISPLAYED name+size metadata here is
     * reconciled:
     *  - The encoder's size (when one already registered an entry) is authoritative and preserved.
     *  - If [name] is the placeholder `UNKNOWN_<op>` AND an encoder already registered a (PascalCase)
     *    name, fall back to [pascalToScreamingSnake] of that name so the result is STILL UPPER_SNAKE
     *    rather than PascalCase.
     */
    internal fun serverProtStub(opcode: Int, name: String, size: ProtSize) {
        val existing = serverProtInfo[opcode]
        val resolvedSize = existing?.size ?: size
        val resolvedName = resolveDisplayName(opcode, name, existing?.name)
        serverProtInfo[opcode] = ProtInfo(resolvedName, resolvedSize)
    }

    internal fun serverProtStub(opcode: Int, name: String, size: Int) {
        serverProtStub(opcode, name, ProtSize.Fixed(size))
    }

    /** Client-side analogue of [serverProtStub] — canonical name wins, decoder's size is preserved. */
    internal fun clientProtStub(opcode: Int, name: String, size: ProtSize) {
        val existing = clientProtInfo[opcode]
        val resolvedSize = existing?.size ?: size
        val resolvedName = resolveDisplayName(opcode, name, existing?.name)
        clientProtInfo[opcode] = ProtInfo(resolvedName, resolvedSize)
    }

    internal fun clientProtStub(opcode: Int, name: String, size: Int) {
        clientProtStub(opcode, name, ProtSize.Fixed(size))
    }

    /**
     * Pick the display name for an opcode. The canonical official [stubName] wins, EXCEPT when it is
     * the placeholder `UNKNOWN_<op>` and an encoder/decoder already supplied a real (PascalCase)
     * [existingName] — in that case fall back to [pascalToScreamingSnake] of the existing name so the
     * displayed name is always UPPER_SNAKE, never the raw PascalCase class name.
     */
    private fun resolveDisplayName(opcode: Int, stubName: String, existingName: String?): String {
        if (stubName == "UNKNOWN_$opcode" && existingName != null) {
            return pascalToScreamingSnake(existingName)
        }
        return stubName
    }

    /** Get the size (as int: fixed=N, varByte=-1, varShort=-2) for a server opcode. */
    fun serverProtSize(opcode: Int): Int = serverProtInfo[opcode]?.size?.toInt() ?: 0

    /** Get the name for a server opcode. */
    fun serverProtName(opcode: Int): String = serverProtInfo[opcode]?.name ?: "UNKNOWN_$opcode"

    /** Get the size (as int) for a client opcode. */
    fun clientProtSize(opcode: Int): Int = clientProtInfo[opcode]?.size?.toInt() ?: 0

    /** Get the name for a client opcode. */
    fun clientProtName(opcode: Int): String = clientProtInfo[opcode]?.name ?: "UNKNOWN_$opcode"

    companion object {
        /** Marks a [zoneOnlyProt]: encodable inside a zone bundle, never sendable on its own. */
        const val ZONE_ONLY_OPCODE = -1

        /** Registration-time display name: never raw PascalCase (an official stub name still wins later). */
        inline fun <reified T : Any> displayName(opcode: Int): String =
            T::class.simpleName?.let { pascalToScreamingSnake(it) } ?: "UNKNOWN_$opcode"

        /**
         * Convert a PascalCase / camelCase identifier (e.g. an encoder class `simpleName` like
         * `VarpSmall`, `IfSetText`, `MessagePublicSend`) to SCREAMING_SNAKE_CASE
         * (`VARP_SMALL`, `IF_SET_TEXT`, `MESSAGE_PUBLIC_SEND`). Used as the display-name fallback so
         * an opcode that has an encoder but no canonical official name still prints UPPER_SNAKE.
         *
         * Runs of digits are kept attached to the preceding word (`IfSet2DAngle` -> `IF_SET2D_ANGLE`).
         */
        fun pascalToScreamingSnake(name: String): String {
            if (name.isEmpty()) return name
            val out = StringBuilder(name.length + 8)
            for (i in name.indices) {
                val c = name[i]
                if (c.isUpperCase() && i > 0) {
                    val prev = name[i - 1]
                    // Insert a separator at a lower->upper boundary, or at the end of an
                    // acronym run (e.g. "HTTPImage" -> "HTTP_IMAGE": split before the last
                    // upper that is followed by a lower).
                    val nextIsLower = i + 1 < name.length && name[i + 1].isLowerCase()
                    if (!prev.isUpperCase() || nextIsLower) {
                        out.append('_')
                    }
                }
                out.append(c.uppercaseChar())
            }
            return out.toString()
        }

        private val codecs = mutableMapOf<Int, Codec>()

        /**
         * Registrars for every revision this build knows how to construct, so an old session can be
         * decoded with the codec it was captured under rather than with whatever is current. A
         * capture outlives the build that made it; reading it back through the wrong prot table
         * would silently relabel every packet in it.
         */
        private val registrars = mutableMapOf<Int, () -> Codec>()

        fun registerRevision(revision: Int, registrar: () -> Codec) {
            registrars[revision] = registrar
        }

        /**
         * Builds the codec for [revision] on demand, or returns null if this build has no
         * registrar. Touching [ProtRevisions] first so a caller never has to remember to register
         * anything - a forgotten registration would otherwise look exactly like an unknown revision.
         */
        fun forRevision(revision: Int): Codec? {
            ProtRevisions.known
            return codecs[revision] ?: registrars[revision]?.invoke()
        }

        fun knownRevisions(): Set<Int> = registrars.keys + codecs.keys

        fun register(revision: Int, init: Codec.() -> Unit): Codec {
            // Only run init for a NEW revision — running it for an already-registered one
            // would leak global side effects (mask-encoder singletons, ActiveMaskKeys)
            // from the discarded Codec instance.
            return codecs.getOrPut(revision) { Codec().apply(init) }
        }

        fun get(revision: Int) = codecs[revision]
    }
}
