package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.Index
import java.nio.file.Files
import java.nio.file.Path

/**
 * The keys that open a cache's encrypted archives, asked for one archive at a time.
 *
 * Only the legacy map index is ever encrypted, and its keys are filed by region rather than by
 * archive, so the lookup is given everything a caller can know about an archive - its index, id
 * and name hash - and works out the rest.
 */
fun interface XteaKeys {

    /** The key that opens [archive] of [index], or null when it needs none or nobody has one. */
    fun keys(index: Int, archive: Int, nameHash: Int): IntArray?

    companion object {

        /** No archive is encrypted, which is true of every NXT cache. */
        val NONE = XteaKeys { _, _, _ -> null }

        /**
         * Region keys as the legacy servers file them: a JSON object of `"<regionId>": [k0, k1, k2, k3]`
         * where the region id is `regionX shl 8 or regionY`.
         *
         * The map index's archive names are a formula - `l<x>_<y>` and `n<x>_<y>` for the locations
         * and npc spawns of a region - so the name hash the reference table carries is turned back
         * into its region by hashing every name the formula can build, once.
         */
        fun regions(keys: Map<Int, IntArray>): XteaKeys {
            val byHash = HashMap<Int, Int>(keys.size * 4)
            for (region in keys.keys) {
                val regionX = region shr 8
                val regionY = region and 0xff
                byHash["l${regionX}_$regionY".hashCode()] = region
                byHash["n${regionX}_$regionY".hashCode()] = region
            }
            return XteaKeys { index, _, nameHash ->
                if (index != Index.MAPS) null else byHash[nameHash]?.let { keys[it] }
            }
        }

        /** Read a legacy `xteaKeys.json` file. */
        fun regions(file: Path): XteaKeys {
            if (!Files.isRegularFile(file)) {
                return NONE
            }
            val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
            val keys = HashMap<Int, IntArray>(root.size * 2)
            for ((region, key) in root) {
                val id = region.toIntOrNull() ?: continue
                keys[id] = key.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            }
            return regions(keys)
        }
    }
}
