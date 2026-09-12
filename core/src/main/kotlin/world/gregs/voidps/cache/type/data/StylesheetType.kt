package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** [nameHash] is the 31-based hash of a UI style property name; [value]'s type is that name's. */
data class StylesheetProperty(val kind: Int, val nameHash: Int, val value: Int)

/**
 * One widget style sheet. The client inserts each property into a key-ascending array and drops a
 * duplicate rather than replacing it, so [properties] keeps the order and the repeats the file has.
 */
data class StylesheetType(
    override var id: Int = -1,
    var parent: Int = -1,
    var properties: List<StylesheetProperty> = emptyList(),
) : CacheType
