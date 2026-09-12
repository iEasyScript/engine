package world.gregs.voidps.gameval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which served cache the component names in `component.json` were re-keyed against.
 *
 * Index 67 is beta-only, so component ids start life as beta slots; the exporter aligns them onto the
 * interface index of the cache the server serves and records that index here. [interfacesCrc] is the
 * served interface reference table's CRC, the same value the JS5 master index advertises, so any download
 * that changes an interface changes it. [unaligned] lists the interfaces beta rebuilt outright, whose ids
 * are still beta slots and must not be trusted against the served cache.
 */
@Serializable
data class ComponentAlignmentStamp(
    @SerialName("interfacesCrc") val interfacesCrc: Int,
    @SerialName("unaligned") val unaligned: List<Int> = emptyList(),
)
