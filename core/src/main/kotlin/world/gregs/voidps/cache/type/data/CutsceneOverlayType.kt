package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

data class CutsceneScalarKey(val time: Float, val value: Float)

data class CutsceneVectorKey(val time: Float, val x: Float, val y: Float)

/** An animated image layer. Which channel is which of opacity, position and scale is unsettled. */
data class CutsceneTrack(
    val asset: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val graphic: Int,
    val firstScalar: List<CutsceneScalarKey>,
    val secondScalar: List<CutsceneScalarKey>,
    val firstVector: List<CutsceneVectorKey>,
    val secondVector: List<CutsceneVectorKey>,
)

data class CutsceneSound(val name: String, val sound: Int)

/** [ordinal] is a one-based index into the enum the overlay names, and [key] the English fallback. */
data class CutsceneSubtitle(val key: String, val ordinal: Int, val start: Float, val end: Float)

data class CutsceneElement(
    val name: String,
    val start: Float,
    val end: Float,
    val tracks: List<CutsceneTrack>,
    val sounds: List<CutsceneSound>,
    val subtitles: List<CutsceneSubtitle>,
)

data class CutsceneOverlayType(
    override var id: Int = -1,
    var version: Int = -1,
    var width: Int = 0,
    var height: Int = 0,
    var subtitleEnum: Int = 0,
    var elements: List<CutsceneElement> = emptyList(),
    /** Trailing zeroes: every group of this index is padded out to the same ceiling. */
    var padding: Int = 0,
) : CacheType
