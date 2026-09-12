package world.gregs.voidps.cache.source.codec.image

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.type.data.PalettedGraphic
import world.gregs.voidps.cache.type.data.RawGraphic
import world.gregs.voidps.cache.type.data.GraphicFrame
import world.gregs.voidps.cache.type.data.GraphicType
import world.gregs.voidps.cache.type.decoder.GraphicDecoder
import world.gregs.voidps.cache.type.encoder.GraphicEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A graphic index as standard images: one PNG per frame and nothing else.
 *
 * ```
 * graphics/1234/0.png     frame 0, RGBA at the group's canvas size
 * graphics/1234/1.png     frame 1
 * ```
 *
 * Every frame is written at the group's canvas size with the frame drawn at its own offset and the
 * rest fully transparent, so the pixels alone say where the frame is (trim the transparent border),
 * which colours it uses and how opaque each pixel is. A PNG made or re-saved by any image editor
 * packs; see [compile] for the derivation, rule by rule. What the pixels under-determine - the
 * palette's order, a box wider than the drawing in it, the column major storage bit, an alpha plane
 * that is entirely opaque, and which of the two layouts the group is - rides in the private PNG
 * chunks [GraphicImage] writes.
 *
 * An archive that is not a graphic group at all, and one no encoder here reproduces from its own
 * pixels, keeps its shipped bytes: the first as `<archive>.jpg` or `<archive>.dat`, the second as a
 * pristine sidecar beside the frames. Parity never depends on the derivation being perfect.
 */
object GraphicCodec : SourceCodec {

    private val decoder = GraphicDecoder()

    private val encoder = GraphicEncoder()

    override val id: String
        get() = "graphic-png"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    /**
     * False, so the unpacker packs every archive back and compares it with the bytes it came from.
     * [unpack] has already fallen back where it had to, so this is a second, independent check of
     * the parity guarantee rather than the only one.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val data = archive.files.singleOrNull() ?: return verbatim(archive.archive, archive.group)
        val files = try {
            frames(archive.archive, data)
        } catch (e: Exception) {
            return verbatim(archive.archive, data)
        }
        if (compiled(archive.archive, files).contentEquals(data)) {
            return UnpackedArchive(files)
        }
        return UnpackedArchive(
            files + SourceFile(SourceFiles.pristine("${archive.archive}/$PRISTINE"), data),
            mapOf(archive.fileIds[0] to guard(files))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one graphic group but its metadata lists ${ids.size} files." }
        kept(directory, archive)?.let {
            return PackedArchive(Files.readAllBytes(it), ids)
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            throw IOException("Archive $archive has no frames in ${directory.toAbsolutePath()}.")
        }
        val files = read(folder, archive)
        val sidecar = SourceFiles.pristine(folder.resolve(PRISTINE))
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (guard(files) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(archive, files), ids)
    }

    override fun archiveOf(path: String): Int? {
        val name = SourceFiles.guarded(path) ?: path
        val separator = name.indexOf('/')
        if (separator < 0) {
            for (extension in KEPT) {
                if (name.endsWith(extension)) {
                    return name.dropLast(extension.length).toIntOrNull()
                }
            }
            return null
        }
        if (name.indexOf('/', separator + 1) >= 0) {
            return null
        }
        return name.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        kept(directory, archive)?.let { return listOf(it) }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    /** The shipped bytes of an archive that is not a graphic group, named for what they are. */
    private fun verbatim(archive: Int, data: ByteArray): UnpackedArchive =
        UnpackedArchive(listOf(SourceFile("$archive${extension(data)}", data)))

    private fun extension(data: ByteArray): String {
        val jpeg = data.size > 2 && (data[0].toInt() and 0xff) == 0xff && (data[1].toInt() and 0xff) == 0xd8
        return if (jpeg) JPEG_EXTENSION else DATA_EXTENSION
    }

    private fun kept(directory: Path, archive: Int): Path? =
        KEPT.map { directory.resolve("$archive$it") }.firstOrNull { Files.isRegularFile(it) }

    /** [data] as the files it is stored in: one PNG per frame, in frame order. */
    private fun frames(archive: Int, data: ByteArray): List<SourceFile> {
        val type = GraphicType(archive)
        decoder.readLoop(type, BufferReader(data))
        val frames = type.frames ?: throw IOException("Graphic $archive decoded to no frames.")
        check(frames.isNotEmpty()) { "Graphic $archive has no frames." }
        val canvasWidth = maxOf(type.maxWidth, frames.maxOf { it.offsetX + it.width }, 1)
        val canvasHeight = maxOf(type.maxHeight, frames.maxOf { it.offsetY + it.height }, 1)
        return frames.mapIndexed { index, frame ->
            SourceFile("$archive/$index$EXTENSION", png(archive, index, type, frame, canvasWidth, canvasHeight))
        }
    }

    private fun png(
        archive: Int,
        index: Int,
        type: GraphicType,
        frame: GraphicFrame,
        canvasWidth: Int,
        canvasHeight: Int
    ): ByteArray = when (frame) {
        is PalettedGraphic -> paletted(archive, index, type, frame, canvasWidth, canvasHeight)
        is RawGraphic -> raw(archive, index, type, frame, canvasWidth, canvasHeight)
    }

    /**
     * A palette frame's pixels are the colours the client renders and the alpha is its own rule:
     * the frame's plane where it has one, else opaque for every index but 0, which is the client's
     * transparent colour.
     */
    private fun paletted(
        archive: Int,
        index: Int,
        type: GraphicType,
        graphic: PalettedGraphic,
        canvasWidth: Int,
        canvasHeight: Int
    ): ByteArray {
        val lookup = GraphicImage.lookup(type.rawPalette, graphic.palette)
        val pixels = IntArray(graphic.width * graphic.height)
        var raster: ByteArray? = null
        for (pixel in pixels.indices) {
            val entry = graphic.indices[pixel].toInt() and 0xff
            val alpha = graphic.alpha?.get(pixel)?.toInt()?.and(0xff) ?: if (entry == 0) 0 else OPAQUE
            pixels[pixel] = (alpha shl 24) or graphic.palette[entry]
            // The index the colours alone would recover; where that is not the index the frame
            // holds, the raster has to ride along.
            val recovered = if (alpha == 0) 0 else lookup.getOrDefault(graphic.palette[entry], -1)
            if (recovered != entry) {
                raster = graphic.indices
            }
        }
        val kind = when {
            graphic.flags and ALPHA_PLANE == 0 -> GraphicImage.NO_PLANE
            graphic.alpha != null -> GraphicImage.PLANE
            else -> GraphicImage.OPAQUE_PLANE
        }
        val frame = GraphicImage.Frame(
            GraphicImage.PALETTED, archive, index, type.maxWidth, type.maxHeight,
            graphic.offsetX, graphic.offsetY, graphic.width, graphic.height, graphic.flags, kind
        )
        return GraphicImage.write(canvasWidth, canvasHeight, frame, pixels, type.rawPalette, raster)
    }

    private fun raw(
        archive: Int,
        index: Int,
        type: GraphicType,
        graphic: RawGraphic,
        canvasWidth: Int,
        canvasHeight: Int
    ): ByteArray {
        val pixels = IntArray(graphic.width * graphic.height)
        for (pixel in pixels.indices) {
            val colour = ((graphic.rgb[pixel * COLOUR].toInt() and 0xff) shl 16) or
                ((graphic.rgb[pixel * COLOUR + 1].toInt() and 0xff) shl 8) or
                (graphic.rgb[pixel * COLOUR + 2].toInt() and 0xff)
            val alpha = graphic.alpha?.get(pixel)?.toInt()?.and(0xff) ?: OPAQUE
            pixels[pixel] = (alpha shl 24) or colour
        }
        val plane = graphic.alpha
        val kind = when {
            plane == null -> GraphicImage.NO_PLANE
            plane.all { it == OPAQUE.toByte() } -> GraphicImage.OPAQUE_PLANE
            else -> GraphicImage.PLANE
        }
        val frame = GraphicImage.Frame(
            GraphicImage.RAW, archive, index, type.maxWidth, type.maxHeight,
            0, 0, graphic.width, graphic.height, 0, kind
        )
        return GraphicImage.write(canvasWidth, canvasHeight, frame, pixels, null, null)
    }

    /** The archive's frames as they are on disk, in frame order, which is the order [frames] wrote. */
    private fun read(folder: Path, archive: Int): List<SourceFile> {
        val paths = HashMap<Int, Path>()
        Files.list(folder).use { stream ->
            for (path in stream) {
                val name = path.name
                if (!Files.isRegularFile(path) || SourceFiles.isPristine(name)) {
                    continue
                }
                if (!name.endsWith(EXTENSION)) {
                    throw IOException("Archive $archive has a $name; a graphic group is $EXTENSION frames only.")
                }
                val frame = name.dropLast(EXTENSION.length).toIntOrNull()
                    ?: throw IOException("Archive $archive has a $name, whose name is not a frame number.")
                paths[frame] = path
            }
        }
        if (paths.isEmpty()) {
            throw IOException("Archive $archive has no frames in ${folder.toAbsolutePath()}.")
        }
        return List(paths.size) { frame ->
            val path = paths[frame] ?: throw IOException("Archive $archive has ${paths.size} frames but no $frame$EXTENSION.")
            SourceFile("$archive/$frame$EXTENSION", Files.readAllBytes(path))
        }
    }

    private fun compiled(archive: Int, files: List<SourceFile>): ByteArray = try {
        compile(archive, files)
    } catch (e: Exception) {
        ByteArray(0)
    }

    /**
     * The frames back into a group, which is what a pack emits and what [unpack] checks itself
     * with. Every field is taken from the pixels where the pixels determine it and from the private
     * chunks where they do not, and a chunk is used only while it still agrees with the pixels: a
     * box that no longer holds every drawn pixel and a raster that would not render what the PNG
     * shows are both discarded in favour of the derivation, so a tool that keeps unknown chunks and
     * edits the image cannot silently clip or recolour it.
     */
    private fun compile(archive: Int, files: List<SourceFile>): ByteArray {
        if (files.isEmpty()) {
            throw IOException("Archive $archive has no frames.")
        }
        val images = files.map { GraphicImage.read(it.bytes, "Archive $archive's ${it.path.substringAfterLast('/')}") }
        for (frame in images.indices) {
            if (images[frame].width != images[0].width || images[frame].height != images[0].height) {
                throw IOException(
                    "Archive $archive frame $frame is ${images[frame].width}x${images[frame].height} but frame 0 is " +
                        "${images[0].width}x${images[0].height}; every frame of a group is its canvas."
                )
            }
        }
        val declared = images.firstNotNullOfOrNull { it.frame }
        val type = GraphicType(archive)
        type.frames = if (declared?.let { it.layout == GraphicImage.RAW } ?: overflows(images)) {
            type.maxWidth = images[0].width
            type.maxHeight = images[0].height
            rawFrames(images)
        } else {
            type.maxWidth = declared?.canvasWidth ?: images[0].width
            type.maxHeight = declared?.canvasHeight ?: images[0].height
            palettedFrames(archive, images, type)
        }
        val writer = BufferWriter(encoder.size(type))
        with(encoder) { writer.encode(type) }
        return writer.toArray()
    }

    /**
     * Whether the pictures hold more colours than a palette could: every colour drawn at all, plus
     * the transparent index. That is what decides the layout of a group whose frames no longer
     * carry the chunk that says which it was - a picture too colourful for a palette is kept as
     * full colour rather than quantised down to 255 entries.
     */
    private fun overflows(images: List<GraphicImage.Image>): Boolean {
        val colours = HashSet<Int>(PALETTE_LIMIT * 2)
        for (image in images) {
            for (pixel in image.pixels) {
                if (pixel ushr 24 != 0 && colours.add(pixel and RGB) && colours.size >= PALETTE_LIMIT) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Raw frames are the whole canvas, so only the plane is a decision: the group carries one when
     * any frame's pixels are not fully opaque, or when a chunk says a frame shipped an opaque one.
     */
    private fun rawFrames(images: List<GraphicImage.Image>): Array<GraphicFrame> {
        val plane = images.any { image ->
            (image.frame?.alpha ?: GraphicImage.NO_PLANE) != GraphicImage.NO_PLANE ||
                image.pixels.any { it ushr 24 != OPAQUE }
        }
        return Array(images.size) { index ->
            val image = images[index]
            val rgb = ByteArray(image.pixels.size * COLOUR)
            val alpha = if (plane) ByteArray(image.pixels.size) else null
            for (pixel in image.pixels.indices) {
                val colour = image.pixels[pixel]
                rgb[pixel * COLOUR] = (colour shr 16).toByte()
                rgb[pixel * COLOUR + 1] = (colour shr 8).toByte()
                rgb[pixel * COLOUR + 2] = colour.toByte()
                alpha?.set(pixel, (colour ushr 24).toByte())
            }
            RawGraphic(image.width, image.height, rgb, alpha)
        }
    }

    private fun palettedFrames(
        archive: Int,
        images: List<GraphicImage.Image>,
        type: GraphicType
    ): Array<GraphicFrame> {
        val colours = Colours(archive, images.firstNotNullOfOrNull { it.palette }?.entries)
        val traced = Array(images.size) { trace(images[it], box(images[it]), colours) }
        type.rawPalette = colours.entries()
        val palette = GraphicImage.normalise(type.rawPalette)
        return Array(traced.size) { index ->
            val frame = traced[index]
            PalettedGraphic(
                frame.box.minX, frame.box.minY, frame.box.width, frame.box.height,
                type.maxWidth, type.maxHeight, palette, frame.indices, frame.alpha, frame.flags
            )
        }
    }

    /** One frame: its pixels inside [box], as palette indices, an alpha plane and a format byte. */
    private fun trace(image: GraphicImage.Image, box: Box, colours: Colours): Traced {
        val area = box.width * box.height
        val alpha = ByteArray(area)
        var partial = false
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val opacity = image.pixels[(box.minX + x) + (box.minY + y) * image.width] ushr 24
                alpha[x + y * box.width] = opacity.toByte()
                if (opacity != 0 && opacity != OPAQUE) {
                    partial = true
                }
            }
        }
        // A partly transparent pixel can only be an alpha plane, whatever a chunk says; without one
        // the format byte is what says the plane was there at all.
        val kind = if (partial) GraphicImage.PLANE else image.frame?.alpha ?: GraphicImage.NO_PLANE
        val plane = if (kind == GraphicImage.PLANE) alpha else null
        val indices = ByteArray(area)
        val raster = image.indices
        if (raster != null && raster.width == box.width && raster.height == box.height &&
            agrees(raster.values, image, box, colours, plane)
        ) {
            raster.values.copyInto(indices)
        } else {
            derive(indices, image, box, colours)
        }
        val flags = ((image.frame?.flags ?: 0) and ALPHA_PLANE.inv()) or
            if (kind == GraphicImage.NO_PLANE) 0 else ALPHA_PLANE
        return Traced(box, indices, plane, flags)
    }

    /** Palette index by colour: 0 where the pixel is fully transparent, the palette's entry otherwise. */
    private fun derive(raster: ByteArray, image: GraphicImage.Image, box: Box, colours: Colours) {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                raster[x + y * box.width] = if (pixel ushr 24 == 0) 0 else colours.index(pixel and RGB).toByte()
            }
        }
    }

    /**
     * Whether [raster] would render the pixels the PNG actually shows: the opacity of every pixel,
     * and the colour of every pixel that is not fully transparent. A stale raster fails this and is
     * discarded; one that survived an edit which only moved transparent pixels around passes,
     * because the picture is the same either way.
     */
    private fun agrees(
        raster: ByteArray,
        image: GraphicImage.Image,
        box: Box,
        colours: Colours,
        plane: ByteArray?
    ): Boolean {
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                val offset = x + y * box.width
                val entry = raster[offset].toInt() and 0xff
                if (entry >= colours.size) {
                    return false
                }
                val pixel = image.pixels[(box.minX + x) + (box.minY + y) * image.width]
                val opacity = pixel ushr 24
                val expected = when {
                    plane != null -> plane[offset].toInt() and 0xff
                    entry == 0 -> 0
                    else -> OPAQUE
                }
                if (expected != opacity) {
                    return false
                }
                if (opacity != 0 && colours.colour(entry) != (pixel and RGB)) {
                    return false
                }
            }
        }
        return true
    }

    /** The frame's box: the chunk's where it still holds every drawn pixel, else the drawing's own. */
    private fun box(image: GraphicImage.Image): Box {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.pixels[x + y * image.width] ushr 24 == 0) {
                    continue
                }
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        val tight = if (maxX < 0) Box(0, 0, 0, 0) else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
        val frame = image.frame ?: return tight
        val box = Box(frame.minX, frame.minY, frame.width, frame.height)
        if (box.minX + box.width > image.width || box.minY + box.height > image.height) {
            return tight
        }
        if (tight.width == 0 || tight.height == 0) {
            return box
        }
        val inside = tight.minX >= box.minX && tight.minY >= box.minY &&
            tight.minX + tight.width <= box.minX + box.width &&
            tight.minY + tight.height <= box.minY + box.height
        return if (inside) box else tight
    }

    /** A frame's trimmed placement in the group's canvas. */
    private class Box(val minX: Int, val minY: Int, val width: Int, val height: Int)

    /** A frame as the pixels and the chunks together describe it, before the palette is settled. */
    private class Traced(val box: Box, val indices: ByteArray, val alpha: ByteArray?, val flags: Int)

    /**
     * The group's palette while a pack builds it.
     *
     * Starts as the chunk's entries where a frame still carries them and as nothing but the
     * transparent entry 0 where none does, and grows by first appearance as the frames are read -
     * which is the order Jagex's own packer writes, and the order [index] therefore reproduces for
     * a group of PNGs that came from an image editor.
     *
     * Entry 0 is deliberately unreachable by colour: it is the transparent index, and a fully
     * transparent pixel is the only thing that recovers it. So an opaque pixel that really is black
     * lands on whichever entry the file spells `000000` - which the client renders as 1 - or on a
     * new entry of its own, and a frame that genuinely draws index 0 opaque needs the raster chunk.
     */
    private class Colours(private val archive: Int, entries: IntArray?) {

        private val raw = ArrayList<Int>(PALETTE_LIMIT)

        private val colours = ArrayList<Int>(PALETTE_LIMIT)

        private val lookup = HashMap<Int, Int>(PALETTE_LIMIT * 2)

        init {
            val source = if (entries == null || entries.isEmpty()) intArrayOf(0) else entries
            if (source.size > PALETTE_LIMIT) {
                throw IOException("Archive $archive's palette has ${source.size} entries; the limit is $PALETTE_LIMIT.")
            }
            for (index in source.indices) {
                raw.add(source[index])
                colours.add(if (index > 0 && source[index] == 0) 1 else source[index])
            }
            for (index in 1 until colours.size) {
                lookup.putIfAbsent(colours[index], index)
            }
            for (index in 1 until raw.size) {
                lookup.putIfAbsent(raw[index], index)
            }
        }

        val size: Int
            get() = raw.size

        /** The palette entry [index] renders as. */
        fun colour(index: Int): Int = colours[index]

        /**
         * [colour]'s index, appending an entry for a colour the palette does not hold yet.
         *
         * A graphic group is 255 colours and a transparent index, which is the format and not a
         * limit this could lift, so a colour that arrives once the palette is full is quantised to
         * the nearest entry already in it rather than failing the build.
         */
        fun index(colour: Int): Int {
            lookup[colour]?.let { return it }
            if (raw.size >= PALETTE_LIMIT) {
                val nearest = nearest(colour)
                lookup[colour] = nearest
                return nearest
            }
            raw.add(colour)
            colours.add(if (colour == 0) 1 else colour)
            lookup[colour] = raw.size - 1
            return raw.size - 1
        }

        /** The entry [colour] is closest to, by squared distance in RGB. */
        private fun nearest(colour: Int): Int {
            var best = 1
            var distance = Int.MAX_VALUE
            for (index in 1 until colours.size) {
                val red = ((colours[index] shr 16) and 0xff) - ((colour shr 16) and 0xff)
                val green = ((colours[index] shr 8) and 0xff) - ((colour shr 8) and 0xff)
                val blue = (colours[index] and 0xff) - (colour and 0xff)
                val error = red * red + green * green + blue * blue
                if (error < distance) {
                    distance = error
                    best = index
                }
            }
            return best
        }

        /** The palette as the file spells it, entry 0 first. */
        fun entries(): IntArray = raw.toIntArray()
    }

    /**
     * The guard `index.json` records for a pristine sidecar: every editable file of the group,
     * named and hashed together, because the group is a directory rather than one file.
     */
    private fun guard(files: List<SourceFile>): String {
        val out = StringBuilder()
        for (file in files.sortedBy { it.path }) {
            out.append(file.path).append(':').append(SourceFiles.sha256(file.bytes)).append('\n')
        }
        return SourceFiles.sha256(out.toString().toByteArray(Charsets.UTF_8))
    }

    private const val EXTENSION = ".png"

    private const val JPEG_EXTENSION = ".jpg"

    private const val DATA_EXTENSION = ".dat"

    /** The archives kept as their shipped bytes, whichever they were named. */
    private val KEPT = listOf(JPEG_EXTENSION, DATA_EXTENSION)

    /**
     * The name a group's pristine sidecar takes. A group is a directory rather than one file, so
     * the sidecar cannot be named after the file it guards the way other codecs' are.
     */
    private const val PRISTINE = "group"

    private const val ALPHA_PLANE = 0x2

    private const val OPAQUE = 0xff

    private const val RGB = 0xffffff

    private const val COLOUR = 3

    /** The palette size is stored as `size - 1` in one byte. */
    private const val PALETTE_LIMIT = 256
}
