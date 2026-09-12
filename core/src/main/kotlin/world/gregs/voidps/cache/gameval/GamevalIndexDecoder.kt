package world.gregs.voidps.cache.gameval

import org.projectx.core.Logger.logWarn
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache

class GamevalIndexDecoder {
    fun decode(cache: Cache): Map<String, Map<Int, String>> {
        val result = LinkedHashMap<String, Map<Int, String>>()
        for (archive in cache.archives(GamevalIndex.INDEX)) {
            val type = GamevalIndex.typeName(archive)
            if (!GamevalIndex.TYPE_BY_ARCHIVE.containsKey(archive)) {
                logWarn("Unknown gameval index-67 archive $archive — naming it '$type'")
            }
            val data = cache.data(GamevalIndex.INDEX, archive, 0) ?: continue
            val combined = decodeFile(data, type)
            val domain = GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive]
            if (domain == null) {
                result[type] = combined
            } else {
                val split = splitVarDomain(combined)
                result[domain.varType] = split.vars
                if (split.varbits.isNotEmpty()) result[domain.varbitType] = split.varbits
            }
        }
        return result
    }

    fun decode(cache: Cache, type: String): Map<Int, String>? {
        GamevalIndex.VAR_DOMAIN_BY_VARBIT_TYPE[type]?.let { return decodeVarbit(cache, it.domain) }
        GamevalIndex.VAR_DOMAIN_BY_VAR_TYPE[type]?.let { return decodeVar(cache, it.domain) }
        val archive = GamevalIndex.archiveId(type) ?: return null
        val data = cache.data(GamevalIndex.INDEX, archive, 0) ?: return null
        return decodeFile(data, type)
    }

    fun decodeArchive(cache: Cache, archive: Int): Map<Int, String>? {
        val data = cache.data(GamevalIndex.INDEX, archive, 0) ?: return null
        return decodeFile(data, GamevalIndex.typeName(archive))
    }

    fun decodeVar(cache: Cache, domain: String): Map<Int, String>? =
        decodeCombinedDomain(cache, domain)?.let { splitVarDomain(it).vars }

    fun decodeVarbit(cache: Cache, domain: String): Map<Int, String>? =
        decodeCombinedDomain(cache, domain)?.let { splitVarDomain(it).varbits }

    private fun decodeCombinedDomain(cache: Cache, domain: String): Map<Int, String>? {
        val varDomain = GamevalIndex.VAR_DOMAIN_BY_NAME[domain] ?: return null
        val data = cache.data(GamevalIndex.INDEX, varDomain.archive, 0) ?: return null
        return decodeFile(data, varDomain.varType)
    }

    fun splitVarDomain(combined: Map<Int, String>): VarSplit {
        val prefix = GamevalIndex.VARBIT_PREFIX
        val offset = combined.entries
            .filter { it.value.startsWith(prefix) }
            .minOfOrNull { it.key }
        val vars = LinkedHashMap<Int, String>()
        val varbits = LinkedHashMap<Int, String>()
        for ((id, name) in combined) {
            if (name.startsWith(prefix)) {
                varbits[id - offset!!] = name.removePrefix(prefix)
            } else {
                vars[id] = name
            }
        }
        return VarSplit(vars, varbits)
    }

    fun decodeComponents(cache: Cache): Map<String, String> {
        val packed = decode(cache, GamevalIndex.COMPONENT) ?: return emptyMap()
        val out = LinkedHashMap<String, String>(packed.size)
        for ((key, name) in packed) {
            val interfaceId = (key ushr 16) and 0xffff
            val componentId = key and 0xffff
            out["$interfaceId:$componentId"] = name
        }
        return out
    }

    fun decodeFile(data: ByteArray, type: String = ""): Map<Int, String> {
        val reader = BufferReader(data)
        val version = reader.readInt()
        val count = reader.readInt()
        if (count < 0) {
            logWarn("Negative gameval entry count $count for type '$type' — skipping")
            return emptyMap()
        }
        val component = type == GamevalIndex.COMPONENT
        val names = LinkedHashMap<Int, String>(count.coerceAtMost(MAX_PREALLOC))
        when (version) {
            VERSION_DENSE -> {
                val offsets = IntArray(count) { reader.readInt() }
                val blobBase = reader.position()
                for (id in 0 until count) {
                    val offset = offsets[id]
                    if (offset < 0) continue
                    reader.position(blobBase + offset)
                    names[id] = normalise(reader.readString(), component)
                }
            }
            VERSION_SPARSE -> {
                val keys = IntArray(count)
                val offsets = IntArray(count)
                for (i in 0 until count) {
                    keys[i] = reader.readInt()
                    offsets[i] = reader.readInt()
                }
                val blobBase = reader.position()
                for (i in 0 until count) {
                    val offset = offsets[i]
                    if (offset < 0) continue
                    reader.position(blobBase + offset)
                    names[keys[i]] = normalise(reader.readString(), component)
                }
            }
            else -> throw IllegalArgumentException(
                "Unknown gameval index-67 file version $version for type '$type'"
            )
        }
        return names
    }

    private fun normalise(raw: String, component: Boolean): String {
        val lower = raw.lowercase()
        return if (component) lower.replaceFirst("__", ":") else lower
    }

    data class VarSplit(val vars: Map<Int, String>, val varbits: Map<Int, String>)

    companion object {
        private const val VERSION_DENSE = 1
        private const val VERSION_SPARSE = 2

        private const val MAX_PREALLOC = 1 shl 21
    }
}
