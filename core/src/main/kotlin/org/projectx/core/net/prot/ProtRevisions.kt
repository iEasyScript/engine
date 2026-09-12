package org.projectx.core.net.prot

import org.projectx.core.net.prot.revision.rev949.register949
import org.projectx.core.net.prot.revision.rev950.register950

/**
 * Every protocol revision this build can speak.
 *
 * ⛔ The only place a revision number is written down. Captures are stored against the revision they
 * were taken under and outlive the build that took them, so reading one back means constructing the
 * codec it was framed by - not whichever one happens to be current. Anything that hardcodes a
 * revision has quietly decided that old captures are unreadable.
 *
 * Adding a revision is one line here. Nothing outside this library changes: a recorder asks for
 * [current], a reader asks for the revision written on the session, and both get a codec.
 *
 * Keeping the old ones registered is also what makes a prot change legible. Two revisions side by
 * side say exactly what moved between them, which a single current table cannot.
 */
object ProtRevisions {

    private val registrars: Map<Int, () -> Codec> = mapOf(
        949 to ::register949,
        950 to ::register950,
    )

    init {
        for ((revision, registrar) in registrars) Codec.registerRevision(revision, registrar)
    }

    /** Revisions that can be constructed, newest last. */
    val known: List<Int> = registrars.keys.sorted()

    /**
     * The revision a capture taken now is recorded under.
     *
     * Derived rather than declared: a build that can speak 950 records 950, and forgetting to
     * update a constant somewhere cannot leave captures mislabelled.
     */
    val current: Int = known.max()

    /** The codec for [revision], or null if this build does not know it. */
    fun codec(revision: Int): Codec? = Codec.forRevision(revision)

    fun currentCodec(): Codec = codec(current) ?: error("no codec registered for revision $current")

    /** True when a capture taken under [revision] can be read by this build. */
    fun canRead(revision: Int): Boolean = revision in registrars
}
