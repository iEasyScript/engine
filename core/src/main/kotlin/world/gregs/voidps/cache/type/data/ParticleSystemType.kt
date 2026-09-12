package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** One knot of a particle curve: a time and as many components as the curve has dimensions. */
data class ParticleCurveKey(val time: Float, val values: List<Float>)

/**
 * [count] is the byte the file leads with rather than a length: one is a constant-value curve of a
 * single key, anything else is that many bezier segments and so twice as many keys.
 */
data class ParticleCurve(val count: Int, val keys: List<ParticleCurveKey>)

/** An emitter spawn volume. [kind] decides how many components of [extents] the file carries. */
data class ParticleShape(
    val kind: Int,
    val flag: Int,
    val position: List<Float>,
    val rotation: List<Float>,
    val extents: List<Float>,
)

/**
 * One emitter modifier. [type] fixes the order its payload is read in, and each field lands in the
 * list of its own kind in that order, so nothing about the payload is lost and nothing is merged.
 */
data class ParticleModifier(
    val type: Int,
    val bytes: List<Int>,
    val integers: List<Int>,
    val floats: List<Float>,
    val shapes: List<ParticleShape>,
    val curves: List<ParticleCurve>,
)

/** [vectorC] and [vectorD] are combined into a rotation matrix; which is the rotation is unsettled. */
data class ParticleEmitter(
    val name: String,
    val fieldA: Int,
    val fieldB: Int,
    val fieldC: Int,
    val flags: Int,
    val material: Int,
    val particleCap: Int,
    val uvColumns: Int,
    val uvRows: Int,
    val vectorA: List<Float>,
    val vectorB: List<Float>,
    val fieldG: Float,
    val fieldJ: Int,
    val fieldK: Float,
    val fieldL: Float,
    val fieldM: Float,
    val vectorC: List<Float>,
    val vectorD: List<Float>,
    val modifiers: List<ParticleModifier>,
)

data class ParticleSystemType(
    override var id: Int = -1,
    var version: Int = -1,
    var name: String = "",
    var flags: Int = 0,
    var emitters: List<ParticleEmitter> = emptyList(),
    /** Trailing zeroes: every group of this index is padded out to the same ceiling. */
    var padding: Int = 0,
) : CacheType
