package com.projectx.script.impl.devin.aiobozocombat.rotations

import com.projectx.script.impl.devin.aiobozocombat.RotationProvider
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

enum class Style { MELEE, RANGED, MAGIC, NECROMANCY }

class RotationEntry(
    val label: String,
    val style: Style,
    /** The generic for [style], used when the picker has nothing more specific. */
    val fallback: Boolean = false,
    val create: () -> RotationProvider
)

/**
 * Every rotation the overlay can install.
 *
 * Without this a rotation is an orphan class: `Rotation.install` is the only way in, so a rotation
 * nothing registers can never be selected, however correct it is. A boss script installs its own
 * provider directly and does not need an entry here.
 */
object RotationCatalog {

    const val NONE = "none"
    const val AUTO = "auto - match the target"

    private val entries: List<RotationEntry> by lazy {
        listOf(
            RotationEntry("melee", Style.MELEE, fallback = true) { MeleeRotation() }
        )
    }

    val choices: Array<String> by lazy {
        (listOf(NONE, AUTO) + entries.map { it.label }).toTypedArray()
    }

    fun byLabel(label: String): RotationEntry? = entries.firstOrNull { it.label == label }

    fun fallbackFor(style: Style): RotationEntry? =
        entries.firstOrNull { it.fallback && it.style == style }

    /**
     * The style the player is set up for, read from which ability book their barred abilities come
     * from rather than from the equipped weapon: the weapon's own `combat_style` param has no
     * necromancy value at all, while every ability belongs to exactly one book.
     *
     * Ties and empty bars answer null, and a null answer installs nothing - a generic rotation for
     * the wrong style would suggest abilities the player does not have.
     */
    fun styleOnBar(barredStructs: Collection<Int>): Style? {
        if (barredStructs.isEmpty()) return null
        val counts = Style.entries.associateWith { style ->
            barredStructs.count { it in book(style) }
        }
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value == 0) return null
        return if (counts.count { it.value == best.value } > 1) null else best.key
    }

    private val books = HashMap<Style, Set<Int>>()

    private fun book(style: Style): Set<Int> = books.getOrPut(style) {
        val name = when (style) {
            Style.MELEE -> "combatv2_abilities_melee"
            Style.RANGED -> "combatv2_abilities_ranged"
            Style.MAGIC -> "combatv2_abilities_magic"
            Style.NECROMANCY -> "combatv2_abilities_necromancy"
        }
        runCatching {
            val id = Gameval.id(Gameval.ENUM, name) ?: return@runCatching emptySet<Int>()
            Cache.enum(id)?.values?.values?.filterIsInstance<Int>()?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }
}
