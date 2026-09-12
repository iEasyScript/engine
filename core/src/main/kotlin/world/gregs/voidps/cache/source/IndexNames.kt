package world.gregs.voidps.cache.source

/**
 * Jagex's own name for each index, which is what the index's directory is called in a source tree.
 *
 * The names come from the client's index constants rather than from anything in the cache - no
 * index carries its own name - so they are a fixed table per era, and a tree written by one build
 * reads back the same way in the next. [world.gregs.voidps.cache.Index] holds the same ids under
 * the names the server code uses; this is the on-disk spelling and only that.
 *
 * Two eras share the first thirty-six names. The legacy client kept its varbits in index 22 and
 * stopped at 36; the NXT client keeps structs there and adds the indices from 40 up, whose purposes
 * `re-resources/docs/cache/RS3_INDEX_CATALOGUE.md` establishes from the client - the names here
 * for 41, 42, 61, 62 and 66 are descriptive rather than Jagex's; 58, 60 and 65 are the gameval
 * table names their group ids match, and 13 is the older form of 58's records.
 */
class IndexNames private constructor(private val names: Map<Int, String>) {

    private val indices = HashMap<String, Int>(names.size * 2).also { map ->
        for ((index, name) in names) {
            map[name] = index
        }
    }

    /** [index]'s directory name. An index past the table falls back to `index<id>`. */
    fun name(index: Int): String = names[index] ?: "$FALLBACK$index"

    /** The index [name] belongs to, or -1 when it names nothing. */
    fun index(name: String): Int = indices[name] ?: fallback(name)

    private fun fallback(name: String): Int {
        if (!name.startsWith(FALLBACK)) {
            return -1
        }
        return name.substring(FALLBACK.length).toIntOrNull() ?: -1
    }

    companion object {
        private const val FALLBACK = "index"

        /** The gameval index, which the NXT beta host serves and the live host does not. */
        const val GAMEVALS = 67

        private val SHARED = mapOf(
            0 to "anims",
            1 to "bases",
            2 to "config",
            3 to "interfaces",
            4 to "synth_sounds",
            5 to "maps",
            6 to "midi_songs",
            7 to "models",
            8 to "graphics",
            9 to "textures",
            10 to "binary",
            11 to "midi_jingles",
            12 to "clientscripts",
            13 to "fontmetrics",
            14 to "vorbis",
            15 to "midi_instruments",
            16 to "config_loc",
            17 to "config_enum",
            18 to "config_npc",
            19 to "config_obj",
            20 to "config_seq",
            21 to "config_spotanim",
            23 to "worldmapdata",
            24 to "quickchat",
            25 to "quickchat_global",
            26 to "materials",
            27 to "config_particle",
            28 to "defaults",
            29 to "config_billboard",
            30 to "dlls",
            31 to "shaders",
            32 to "loading_graphics",
            33 to "loading_screens",
            34 to "loading_graphics_raw",
            35 to "cutscenes"
        )

        val LEGACY = IndexNames(SHARED + mapOf(22 to "config_varbit", 36 to "index36"))

        val NXT = IndexNames(
            SHARED + mapOf(
                13 to "fontmetrics_legacy",
                22 to "config_struct",
                40 to "audio_streams",
                41 to "worldmap_area_data",
                42 to "worldmap_area_coords",
                47 to "models_rt7",
                48 to "anims_rt7",
                49 to "dbtableindex",
                52 to "textures_dxt",
                53 to "textures_png",
                54 to "textures_png_mipped",
                55 to "textures_etc",
                56 to "anims_keyframes",
                57 to "config_achievement",
                58 to "fontmetrics",
                59 to "fonts",
                60 to "stylesheets",
                61 to "particle_effects",
                62 to "anim_state_machines",
                65 to "ui_anims",
                66 to "cutscene_overlays",
                GAMEVALS to "gamevals"
            )
        )

        /** The table for a cache of [revision]: legacy below [CacheEra.NXT_REVISION], NXT from it on. */
        fun of(revision: Int): IndexNames = if (CacheEra.of(revision) == CacheEra.LEGACY) LEGACY else NXT
    }
}

/**
 * Which family of client a cache was built for, which decides everything about it that the bytes
 * do not say for themselves: the index names, which config archive is which type, whether map
 * archives are encrypted, and which store the client reads it from.
 */
enum class CacheEra {
    /** The Java clients: sector store, XTEA encrypted map archives, varbits in index 22. */
    LEGACY,

    /** The NXT client: SQLite store, LZMA, plain map archives, structs in index 22. */
    NXT;

    companion object {
        /** The first client build whose cache is an NXT one. Nothing between the two families exists. */
        const val NXT_REVISION = 800

        fun of(revision: Int): CacheEra = if (revision < NXT_REVISION) LEGACY else NXT
    }
}
