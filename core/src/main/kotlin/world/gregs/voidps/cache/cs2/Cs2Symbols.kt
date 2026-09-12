package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.Cs2Type
import world.gregs.voidps.cache.cs2.ir.VarRef
import world.gregs.voidps.cache.cs2.ir.VarSpace

/**
 * The default names for the things scripts refer to by id.
 *
 * Variables and scripts are emitted as ordinary identifiers rather than indexed
 * lookups so an editor can rename them. A variable Jagex has a dev name for is
 * spelled with it; anything else falls back to the space prefix and the id,
 * which is what lets a file compile on its own. A renamed symbol is resolved
 * through the declarations written alongside the scripts instead.
 */
object Cs2Symbols {

    private const val OLD = "Old"

    private const val PAD = "__pad"

    private val generated = Regex("^(int|str|long|tmpInt|tmpStr|tmpLong|array|script)\\d")

    fun variableName(value: VarRef): String {
        val suffix = (if (value.variant == "old") OLD else "") +
            (if (value.padding != 0) "$PAD${value.padding}" else "")
        return "${baseName(value)}$suffix"
    }

    /** The variable an identifier names, or null when it is not one. */
    fun variableOf(name: String): VarRef? {
        val marker = name.lastIndexOf(PAD)
        val padding = if (marker < 0) 0 else name.substring(marker + PAD.length).toIntOrNull() ?: 0
        val unpadded = if (padding != 0) name.substring(0, marker) else name
        devNameOf(unpadded, padding)?.let { return it }

        val old = unpadded.endsWith(OLD)
        val bare = if (old) unpadded.dropLast(OLD.length) else unpadded
        devNameOf(bare, padding, old)?.let { return it }

        val space = VarSpace.ofPrefix(bare) ?: return null
        val id = bare.removePrefix(space.prefix).toIntOrNull() ?: return null
        return VarRef(space, id, typeOf(space, id), if (old) "old" else null, padding)
    }

    fun scriptName(id: Int): String = Cs2ScriptNames.identifier(id) ?: "script$id"

    /** The script an identifier names, or null when it is not one. */
    fun scriptOf(name: String): Int? =
        if (name.startsWith("script")) name.removePrefix("script").toIntOrNull() ?: Cs2ScriptNames.idOf(name)
        else Cs2ScriptNames.idOf(name)

    /**
     * A dev name is only used where it cannot be mistaken for something else the
     * emitter produces - a local, a temporary, an array, a script or an opcode.
     */
    private fun baseName(value: VarRef): String {
        val name = Cs2Gamevals.varName(value.space, value.id)
        if (name != null && usable(name)) return name
        return "${value.space.prefix}${value.id}"
    }

    private fun devNameOf(name: String, padding: Int, old: Boolean = false): VarRef? {
        if (!usable(name)) return null
        val (space, id) = Cs2Gamevals.varOf(name) ?: return null
        return VarRef(space, id, typeOf(space, id), if (old) "old" else null, padding)
    }

    private fun usable(name: String): Boolean =
        Cs2Gamevals.identifierSafe(name) &&
            !generated.containsMatchIn(name) &&
            Cs2Opcodes.byTsName(name) == null &&
            Cs2EventArg.named(name) == null

    /** A variable's stack is its declared type's; the compiler has no corpus to fall back on. */
    private fun typeOf(space: VarSpace, id: Int): Cs2Type {
        if (space == VarSpace.VARC_STRING) return Cs2Type.STRING
        if (space.domain == VarSpace.NO_DOMAIN) return Cs2Type.INT
        return when (Cs2VarDeclarations.baseOf(space.domain, id)) {
            Cs2VarBase.STRING -> Cs2Type.STRING
            Cs2VarBase.LONG -> Cs2Type.LONG
            else -> Cs2Type.INT
        }
    }
}
