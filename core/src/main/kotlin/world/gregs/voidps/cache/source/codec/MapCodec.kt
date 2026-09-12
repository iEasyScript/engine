package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.type.decoder.MapSquareEnvironmentDecoder
import world.gregs.voidps.cache.type.decoder.MapSquareLocDecoder
import world.gregs.voidps.cache.type.decoder.MapSquarePatchDecoder
import world.gregs.voidps.cache.type.decoder.MapSquarePointLightDecoder
import world.gregs.voidps.cache.type.decoder.MapSquareTerrainDecoder
import world.gregs.voidps.cache.type.decoder.MapSquareNpcDecoder
import world.gregs.voidps.cache.type.decoder.MapSquareTileDecoder
import world.gregs.voidps.cache.type.encoder.MapSquareEnvironmentFileEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquareLocEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquarePatchEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquarePointLightEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquareTerrainEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquareNpcEncoder.encode
import world.gregs.voidps.cache.type.encoder.MapSquareTileEncoder.encode
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Index 5 - the maps - laid out by region as editable JSON.
 *
 * A map square is one archive of up to nine files and they are edited together, so the region is
 * the directory and each file is one file in it:
 *
 * ```
 * maps/50_50/locs.json                 file 0   the placed locations
 * maps/50_50/underwater_locs.json      file 1   a second location layer
 * maps/50_50/npcs.json                 file 2   the npc spawns
 * maps/50_50/legacy_terrain.json       file 3   4 levels of tiles, then the environment block
 * maps/50_50/legacy_underwater.json    file 4   1 level of tiles
 * maps/50_50/terrain.json              file 5   the 66 by 66 grid the client reads
 * maps/50_50/environment.json          file 6
 * maps/50_50/lights.json               file 7   the point lights
 * maps/50_50/patches.json              file 8
 * ```
 *
 * Only the files a region has are written. Files 2, 3 and 4 are the ones the NXT client never
 * requests - files 5, 6 and 7 supersede the last two - but they ship in the cache, so they are
 * decoded like everything else.
 *
 * ## Where the region comes from
 *
 * Index 5's reference table carries no names at all - not even hashes - because the NXT client
 * derives the group from the region coordinates and never looks one up. The region is therefore
 * read straight off the archive id ([MapSquare]), which is why this codec answers [archiveOf] from
 * the path alone and needs nothing out of `index.json`.
 *
 * ## Parity
 *
 * Every file is checked on the way out - decoded, written as JSON, read straight back and
 * re-encoded - and one that does not come out identical keeps its shipped bytes in a
 * `<name>.pristine` sidecar, exactly as [DefinitionJsonCodec] does.
 */
object MapCodec : SourceCodec {

    override val id: String
        get() = "map-json"

    /** The file ids are in the file names, so nothing has to carry them. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = false

    /**
     * False: JSON is not the shipped bytes, so the unpacker packs the tree back and proves it
     * anyway. [unpack] has already fallen back to a pristine sidecar for anything that did not
     * survive the round trip, so this is a second, independent check rather than the only one.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val directoryName = MapSquare.directory(archive.archive)
        val files = ArrayList<SourceFile>(archive.files.size * 2)
        val pristine = HashMap<Int, String>(0)
        for ((position, id) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val kind = MapSquare.File.of(id)
            if (kind == null) {
                files.add(SourceFile("$directoryName/$id$RAW", data))
                continue
            }
            val path = "$directoryName/${kind.file}$JSON"
            val text = write(kind, data, archive.archive)
            files.add(SourceFile(path, text))
            if (!compile(kind, text).contentEquals(data)) {
                files.add(SourceFile(SourceFiles.pristine(path), data))
                pristine[id] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val folder = directory.resolve(MapSquare.directory(archive))
        if (!Files.isDirectory(folder)) {
            throw IOException("Map archive $archive has no directory at ${folder.toAbsolutePath()}.")
        }
        val ids = fileIds(folder)
        check(ids.isNotEmpty()) { "Map archive $archive has no files in ${folder.toAbsolutePath()}." }
        val files = ids.map { bytes(folder, archive, it, metadata) }
        return PackedArchive(ArchiveGroup.join(files, null, metadata.layout, metadata.layoutVersion), ids)
    }

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        if (fileId(SourceFiles.guarded(path) ?: path, separator + 1) == null) {
            return null
        }
        return MapSquare.archiveOf(path.substring(0, separator))
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(MapSquare.directory(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList()
        }
    }

    /** [data] as the text of [kind]'s file. */
    private fun write(kind: MapSquare.File, data: ByteArray, archive: Int): ByteArray = when (kind) {
        MapSquare.File.LEGACY_TILES, MapSquare.File.LEGACY_UNDERWATER_TILES ->
            MapJson.writeTiles(MapSquareTileDecoder.decode(data, kind.levels), archive)
        MapSquare.File.LOCS, MapSquare.File.UNDERWATER_LOCS ->
            MapJson.writeLocs(MapSquareLocDecoder.decode(data), archive)
        MapSquare.File.NPCS -> MapJson.writeNpcs(MapSquareNpcDecoder.decode(data), archive)
        MapSquare.File.TERRAIN -> MapJson.writeTerrain(MapSquareTerrainDecoder.decode(data), archive)
        MapSquare.File.ENVIRONMENT ->
            MapJson.writeEnvironmentFile(MapSquareEnvironmentDecoder.decode(data), archive)
        MapSquare.File.LIGHTS -> MapJson.writePointLights(MapSquarePointLightDecoder.decode(data), archive)
        MapSquare.File.PATCHES -> MapJson.writePatches(MapSquarePatchDecoder.decode(data), archive)
    }

    /** [text] read back and encoded, which is what a pack emits. */
    private fun compile(kind: MapSquare.File, text: ByteArray): ByteArray {
        var capacity = CAPACITY
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                when (kind) {
                    MapSquare.File.LEGACY_TILES, MapSquare.File.LEGACY_UNDERWATER_TILES ->
                        writer.encode(MapJson.readTiles(text, kind.levels))
                    MapSquare.File.LOCS, MapSquare.File.UNDERWATER_LOCS ->
                        writer.encode(MapJson.readLocs(text))
                    MapSquare.File.NPCS -> writer.encode(MapJson.readNpcs(text))
                    MapSquare.File.TERRAIN -> writer.encode(MapJson.readTerrain(text))
                    MapSquare.File.ENVIRONMENT -> writer.encode(MapJson.readEnvironmentFile(text))
                    MapSquare.File.LIGHTS -> writer.encode(MapJson.readPointLights(text))
                    MapSquare.File.PATCHES -> writer.encode(MapJson.readPatches(text))
                }
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "${kind.file} does not encode into $LIMIT bytes." }
            }
        }
    }

    /**
     * One file's bytes: the pristine copy while the JSON beside it still hashes to what
     * `index.json` recorded, and the compiled JSON otherwise. A sidecar whose JSON has moved on is
     * deleted rather than left to confuse the next reader.
     */
    private fun bytes(folder: Path, archive: Int, file: Int, metadata: ArchiveMetadata): ByteArray {
        val kind = MapSquare.File.of(file)
        if (kind == null) {
            return Files.readAllBytes(folder.resolve("$file$RAW"))
        }
        val path = folder.resolve("${kind.file}$JSON")
        if (!Files.isRegularFile(path)) {
            throw IOException("Map archive $archive has no ${kind.file} JSON at ${path.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[file]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        return compile(kind, text)
    }

    private fun fileIds(folder: Path): IntArray {
        val ids = ArrayList<Int>(MapSquare.File.entries.size)
        Files.list(folder).use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                if (!SourceFiles.isPristine(name)) {
                    fileId(name, 0)?.let { ids.add(it) }
                }
            }
        }
        ids.sort()
        return ids.toIntArray()
    }

    /** The file id the name starting at [start] of [path] belongs to, or null when it names none. */
    private fun fileId(path: String, start: Int): Int? {
        val dot = path.lastIndexOf('.')
        if (dot <= start) {
            return null
        }
        val name = path.substring(start, dot)
        val extension = path.substring(dot)
        val kind = MapSquare.File.ofFile(name)
        if (kind != null) {
            return if (extension == JSON) kind.id else null
        }
        return if (extension == RAW) name.toIntOrNull() else null
    }

    /** Bigger than any map file in the cache; doubled on overflow all the same. */
    private const val CAPACITY = 128 * 1024

    private const val LIMIT = 8 * 1024 * 1024

    private const val JSON = ".json"

    private const val RAW = ".dat"
}
