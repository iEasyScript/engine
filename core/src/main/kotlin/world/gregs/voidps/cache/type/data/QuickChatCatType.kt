package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class QuickChatCatType(
    override var id: Int = -1,
    var title: String? = null,
    var subCategoryIds: IntArray? = null,
    var subCategoryKeys: CharArray? = null,
    var phraseIds: IntArray? = null,
    var phraseKeys: CharArray? = null,
    var flag: Boolean = false,
) : CacheType, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    fun subCategory(key: Char) = lookup(subCategoryIds, subCategoryKeys, key)

    fun phrase(key: Char) = lookup(phraseIds, phraseKeys, key)

    private fun lookup(ids: IntArray?, keys: CharArray?, key: Char): Int {
        if (ids == null || keys == null) {
            return -1
        }
        for (entry in ids.indices) {
            if (keys[entry] == key) {
                return ids[entry]
            }
        }
        return -1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QuickChatCatType

        if (id != other.id) return false
        if (title != other.title) return false
        if (flag != other.flag) return false
        if (!subCategoryIds.contentEquals(other.subCategoryIds)) return false
        if (!subCategoryKeys.contentEquals(other.subCategoryKeys)) return false
        if (!phraseIds.contentEquals(other.phraseIds)) return false
        if (!phraseKeys.contentEquals(other.phraseKeys)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + flag.hashCode()
        result = 31 * result + (subCategoryIds?.contentHashCode() ?: 0)
        result = 31 * result + (subCategoryKeys?.contentHashCode() ?: 0)
        result = 31 * result + (phraseIds?.contentHashCode() ?: 0)
        result = 31 * result + (phraseKeys?.contentHashCode() ?: 0)
        return result
    }
}
