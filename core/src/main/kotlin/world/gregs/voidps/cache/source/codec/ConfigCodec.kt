package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.CacheEra
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The config index, whose archives are dozens of unrelated definition types rather than dozens
 * more of the same thing, so each of them gets a directory of its own and a codec of its own.
 *
 * The directory is the archive's canonical name - `struct`, `inv`, `varp` - in the gameval
 * vocabulary where the type has one, because those are the names everything else already calls
 * them by. An archive nothing has a name for is `archive_<id>`, which keeps the tree complete
 * without inventing a name; naming one later is a directory rename and a line here. Which archive
 * holds which type moved between the legacy and NXT clients, so the table is per era.
 *
 * Sub-codecs are registered by archive id the same way index codecs are registered by index id -
 * see [SourceCodecs] - and are called with their own directory, so they lay files out inside it
 * without repeating the archive id ([RawFilesCodec.FLAT], [DefinitionLayout.Flat]).
 */
object ConfigCodec : SourceCodec {

    private val legacyNames = mapOf(
        1 to "underlay",
        3 to "idk",
        4 to "overlay",
        5 to "inv",
        11 to "param",
        12 to "seq",
        15 to "varcstr",
        16 to "varp",
        19 to "varc",
        26 to "struct",
        29 to "skybox",
        31 to "light",
        32 to "bas",
        33 to "cursor",
        34 to "mapscene",
        35 to "quest",
        36 to "mapelement",
        46 to "hitmark",
        47 to "varclan",
        54 to "varclansetting",
        60 to "varplayer",
        61 to "varnpc",
        62 to "varclient",
        69 to "varbit",
        72 to "headbar",
        77 to "animflowcontrol",
        80 to "vargroup"
    )

    private val nxtNames = mapOf(
        Config.FLOOR_UNDERLAY to "underlay",
        Config.IDENTITY_KIT to "idk",
        Config.FLOOR_OVERLAY to "overlay",
        Config.INVENTORIES to "inv",
        Config.PARAMS to "param",
        Config.SEQUENCES to "seq",
        Config.VARC_STRINGS to "varcstr",
        Config.VARP to "varp",
        Config.VARC to "varc",
        Config.STRUCTS to "struct",
        Config.SEQ_GROUP to "seqgroup",
        Config.LIGHT_INTENSITY to "light",
        Config.RENDER_ANIMATIONS to "bas",
        Config.CURSORS to "cursor",
        Config.MAP_SCENES to "mapscene",
        Config.QUESTS to "quest",
        Config.MAP_ELEMENTS to "mapelement",
        Config.DBTABLE to "dbtable",
        Config.DBROW to "dbrow",
        Config.HIT_SPLATS to "hitmark",
        Config.VAR_PLAYER to "varplayer",
        Config.VAR_NPC to "varnpc",
        Config.VAR_CLIENT to "varclient",
        Config.VAR_WORLD to "varworld",
        Config.VAR_MAP_SQUARE to "varmapsquare",
        Config.VAR_OBJECT to "varobject",
        Config.VAR_CLAN to "varclan",
        Config.VAR_CLAN_SETTINGS to "varclansetting",
        Config.VAR_CONTROLLER to "varcontroller",
        Config.VAR_BIT to "varbit",
        Config.HIT_BARS to "headbar",
        Config.VAR_GLOBAL to "varglobal",
        Config.WATER to "water",
        Config.VAR_GROUP to "vargroup",
        Config.WORLD_AREAS to "worldarea"
    )

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    override val id: String
        get() = "config"

    private val names: Map<Int, String>
        get() = if (SourceCodecs.context.era == CacheEra.LEGACY) legacyNames else nxtNames

    /** [archive]'s directory name under `config/`. */
    fun name(archive: Int): String = names[archive] ?: "$UNNAMED_PREFIX$archive"

    /** The archive [name] is the directory of, or -1. */
    fun archive(name: String): Int {
        for ((archive, candidate) in names) {
            if (candidate == name) {
                return archive
            }
        }
        if (!name.startsWith(UNNAMED_PREFIX)) {
            return -1
        }
        return name.substring(UNNAMED_PREFIX.length).toIntOrNull() ?: -1
    }

    /** Give [archive] its own codec. Call before a tree is read or written; see [SourceCodecs]. */
    fun register(archive: Int, codec: SourceCodec) {
        codecs[archive] = codec
    }

    /** Forget every registered sub-codec, for a switch of era. */
    fun clear() {
        codecs.clear()
    }

    /** [archive]'s codec, raw files by default. */
    fun codec(archive: Int): SourceCodec = codecs[archive] ?: RawFilesCodec.FLAT

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        val unpacked = codec(archive.archive).unpack(directory.resolve(name), archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(directory.resolve(name(archive)), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0) {
            return null
        }
        val archive = archive(path.substring(0, separator))
        return if (archive < 0) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(name(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }

    /** The prefix a config archive nobody has named yet gets its directory from. */
    const val UNNAMED_PREFIX = "archive_"
}
