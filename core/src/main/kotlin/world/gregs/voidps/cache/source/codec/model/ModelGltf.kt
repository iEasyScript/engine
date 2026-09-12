package world.gregs.voidps.cache.source.codec.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentA
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentB
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentC
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentD
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentRecord
import world.gregs.voidps.cache.type.data.ModelRt7Point
import world.gregs.voidps.cache.type.data.ModelRt7Submesh
import world.gregs.voidps.cache.type.data.ModelRt7Type
import world.gregs.voidps.cache.type.data.ModelRt7Vertices
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One RT7 model as a glTF 2.0 binary file - a `.glb`, which Blender opens and saves natively.
 *
 * The geometry is real glTF: `POSITION`, `NORMAL`, `COLOR_0` and `TEXCOORD_0` accessors and one
 * primitive per submesh with its own index accessor, so any glTF tool draws the model without
 * knowing anything about RuneScape. The streams glTF has no name for - the three bit-gated
 * per-vertex shorts, the unconditional per-vertex byte, the variable length bone and weight lists -
 * are accessors too, because the index is five gigabytes and spelling them as JSON numbers would
 * cost more than the model itself.
 *
 * **`extras.rs` is what the re-encoder reads.** It carries every scalar the file holds and names,
 * by accessor, where each stream went; nothing in the glTF proper is a second copy of it. Positions
 * are the cache's own numbers, unscaled and un-reoriented, written as `FLOAT` because that is what
 * the core specification allows for `POSITION` - which is lossless for the `i16` form and keeps the
 * exact bits of the `f32` one.
 */
internal object ModelGltf {

    fun write(model: ModelRt7Type): ByteArray {
        val streams = Streams()
        val vertices = model.vertices
        if (vertices.count > 0) {
            streams.vertices(vertices)
        }
        for (submesh in model.submeshes) {
            streams.indices(submesh.indices, model.wideIndices)
        }
        return Glb.assemble(asset(model, streams), streams.binary())
    }

    fun read(bytes: ByteArray, id: Int): ModelRt7Type {
        val chunks = Glb.chunks(bytes)
        val root = Glb.root(chunks.first)
        val binary = chunks.second
        val node = root["nodes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalArgumentException("Model $id has no glTF node.")
        val rs = node["extras"]?.jsonObject?.get("rs")?.jsonObject
            ?: throw IllegalArgumentException("Model $id carries no rs extras; it is not a cache model.")
        val model = ModelRt7Type(
            id = id,
            version = rs.getValue("version").jsonPrimitive.int,
            format = rs.getValue("format").jsonPrimitive.int,
            fieldOpaque = rs.getValue("opaque").jsonPrimitive.int,
            vertexFlags = rs.getValue("vertexFlags").jsonPrimitive.int
        )
        model.vertices = readVertices(root, binary, rs, model)
        readSubmeshes(root, binary, rs, model)
        readAttachments(rs, model)
        return model
    }

    /** The `.glb`'s JSON chunk, for a report or a test that wants to show what a file says. */
    fun json(bytes: ByteArray): String = Glb.text(bytes)

    // --- reading -------------------------------------------------------------------------------

    private fun readVertices(root: JsonObject, binary: ByteArray, rs: JsonObject, model: ModelRt7Type): ModelRt7Vertices {
        val count = rs.getValue("vertexCount").jsonPrimitive.int
        val vertices = ModelRt7Vertices(count)
        if (count == 0) {
            return vertices
        }
        vertices.positions = Glb.floats(root, binary, rs.getValue("positions").jsonPrimitive.int, 3)
        vertices.normals = bytes(Glb.integers(root, binary, rs.getValue("normals").jsonPrimitive.int, 3))
        vertices.colours = bytes(Glb.integers(root, binary, rs.getValue("colours").jsonPrimitive.int, 4))
        vertices.textures = shorts(Glb.integers(root, binary, rs.getValue("textures").jsonPrimitive.int, 2))
        vertices.streamA = stream(root, binary, rs["streamA"])
        vertices.streamB = stream(root, binary, rs["streamB"])
        vertices.streamC = stream(root, binary, rs["streamC"])
        vertices.byteStream = bytes(Glb.integers(root, binary, rs.getValue("byteStream").jsonPrimitive.int, 1))
        if (model.skinned) {
            readSkins(root, binary, rs, vertices)
        }
        return vertices
    }

    private fun readSkins(root: JsonObject, binary: ByteArray, rs: JsonObject, vertices: ModelRt7Vertices) {
        val boneCounts = Glb.integers(root, binary, rs.getValue("boneCounts").jsonPrimitive.int, 1)
        val bones = Glb.integers(root, binary, rs.getValue("bones").jsonPrimitive.int, 1)
        val weightCounts = Glb.integers(root, binary, rs.getValue("weightCounts").jsonPrimitive.int, 1)
        val weights = Glb.integers(root, binary, rs.getValue("weights").jsonPrimitive.int, 1)
        var bone = 0
        var weight = 0
        vertices.bones = Array(vertices.count) { vertex ->
            ShortArray(boneCounts[vertex]) { bones[bone++].toShort() }
        }
        vertices.weights = Array(vertices.count) { vertex ->
            ByteArray(weightCounts[vertex]) { weights[weight++].toByte() }
        }
    }

    private fun readSubmeshes(root: JsonObject, binary: ByteArray, rs: JsonObject, model: ModelRt7Type) {
        for (record in rs.getValue("submeshes").jsonArray) {
            val fields = record.jsonArray
            val accessor = fields[SUBMESH_INDICES].jsonPrimitive.int
            model.submeshes.add(
                ModelRt7Submesh(
                    flags = fields[0].jsonPrimitive.int,
                    fieldA = fields[1].jsonPrimitive.int,
                    fieldB = fields[2].jsonPrimitive.int,
                    fieldC = fields[3].jsonPrimitive.int,
                    indices = if (accessor < 0) IntArray(0) else Glb.integers(root, binary, accessor, 1)
                )
            )
        }
    }

    private fun readAttachments(rs: JsonObject, model: ModelRt7Type) {
        for (element in rs["attachmentsA"]?.jsonArray.orEmpty()) {
            val attachment = element.jsonObject
            val records = attachment.getValue("records").jsonArray.mapTo(ArrayList()) { readRecord(it.jsonObject) }
            model.attachmentsA.add(
                ModelRt7AttachmentA(
                    fieldA = attachment.getValue("a").jsonPrimitive.int,
                    fieldB = attachment.getValue("b").jsonPrimitive.int,
                    fieldC = attachment.getValue("c").jsonPrimitive.int,
                    records = records
                )
            )
        }
        for (element in rs["attachmentsB"]?.jsonArray.orEmpty()) {
            val attachment = element.jsonObject
            val points = attachment.getValue("points").jsonArray
            model.attachmentsB.add(
                ModelRt7AttachmentB(
                    id = attachment.getValue("id").jsonPrimitive.int,
                    points = Array(ModelRt7AttachmentB.POINTS) { readPoint(points[it].jsonObject) }
                )
            )
        }
        for (element in rs["attachmentsC"]?.jsonArray.orEmpty()) {
            val attachment = element.jsonObject
            model.attachmentsC.add(
                ModelRt7AttachmentC(
                    id = attachment.getValue("id").jsonPrimitive.int,
                    point = readPoint(attachment.getValue("point").jsonObject)
                )
            )
        }
        for (element in rs["attachmentsD"]?.jsonArray.orEmpty()) {
            val attachment = element.jsonObject
            model.attachmentsD.add(
                ModelRt7AttachmentD(
                    tag = attachment.getValue("tag").jsonPrimitive.int,
                    name = attachment["name"]?.jsonPrimitive?.content,
                    record = Glb.hex(attachment.getValue("record").jsonPrimitive.content)
                )
            )
        }
    }

    private fun readRecord(record: JsonObject) = ModelRt7AttachmentRecord(
        x = decimal(record.getValue("x")),
        y = decimal(record.getValue("y")),
        z = decimal(record.getValue("z")),
        fieldD = decimal(record.getValue("d")),
        fieldE = decimal(record.getValue("e")),
        fieldF = record.getValue("f").jsonPrimitive.int,
        fieldG = record.getValue("g").jsonPrimitive.int,
        ids = record.getValue("ids").jsonArray.let { ids -> IntArray(ids.size) { ids[it].jsonPrimitive.int } },
        fieldH = record.getValue("h").jsonPrimitive.int
    )

    private fun readPoint(point: JsonObject) = ModelRt7Point(
        x = decimal(point.getValue("x")),
        y = decimal(point.getValue("y")),
        z = decimal(point.getValue("z")),
        fieldA = point.getValue("a").jsonPrimitive.int,
        fieldB = point.getValue("b").jsonPrimitive.int
    )

    /** A float the file wrote as a number, or as the hex of its bits when JSON cannot spell it. */
    private fun decimal(element: JsonElement): Float {
        val primitive = element.jsonPrimitive
        return if (primitive.isString) Float.fromBits(Glb.hex(primitive.content).fold(0) { bits, byte -> (bits shl 8) or (byte.toInt() and 0xff) }) else primitive.float
    }

    private fun stream(root: JsonObject, binary: ByteArray, element: JsonElement?): ShortArray? {
        if (element == null) {
            return null
        }
        return shorts(Glb.integers(root, binary, element.jsonPrimitive.int, 1))
    }

    private fun bytes(values: IntArray) = ByteArray(values.size) { values[it].toByte() }

    private fun shorts(values: IntArray) = ShortArray(values.size) { values[it].toShort() }

    // --- writing -------------------------------------------------------------------------------

    /**
     * Every accessor a model needs, and the bytes behind them.
     *
     * One buffer view per accessor, each aligned to four bytes, which is what the specification asks
     * of a vertex attribute and is simpler than sharing a view between accessors.
     */
    private class Streams {
        private val views = ArrayList<View>()

        var positions = -1
        var normals = -1
        var colours = -1
        var textures = -1
        var streamA = -1
        var streamB = -1
        var streamC = -1
        var byteStream = -1
        var boneCounts = -1
        var bones = -1
        var weightCounts = -1
        var weights = -1
        val submeshes = ArrayList<Int>()

        fun vertices(vertices: ModelRt7Vertices) {
            positions = add(floats(vertices.positions), Glb.FLOAT, vertices.count, "VEC3", Glb.ARRAY_BUFFER, bounds = bounds(vertices.positions))
            normals = add(padded(vertices.normals), Glb.BYTE, vertices.count, "VEC3", Glb.ARRAY_BUFFER, normalized = true, stride = 4)
            colours = add(vertices.colours.copyOf(), Glb.UNSIGNED_BYTE, vertices.count, "VEC4", Glb.ARRAY_BUFFER, normalized = true)
            textures = add(shorts(vertices.textures), Glb.UNSIGNED_SHORT, vertices.count, "VEC2", Glb.ARRAY_BUFFER, normalized = true)
            vertices.streamA?.let { streamA = scalars(it) }
            vertices.streamB?.let { streamB = scalars(it) }
            vertices.streamC?.let { streamC = scalars(it) }
            byteStream = add(vertices.byteStream.copyOf(), Glb.UNSIGNED_BYTE, vertices.count, "SCALAR")
            skins(vertices)
        }

        fun indices(values: IntArray, wide: Boolean) {
            if (values.isEmpty()) {
                submeshes.add(-1)
                return
            }
            val buffer = buffer(values.size * if (wide) 4 else 2)
            for (index in values) {
                if (wide) buffer.putInt(index) else buffer.putShort(index.toShort())
            }
            val type = if (wide) Glb.UNSIGNED_INT else Glb.UNSIGNED_SHORT
            submeshes.add(add(buffer.array(), type, values.size, "SCALAR", Glb.ELEMENT_ARRAY_BUFFER))
        }

        fun binary(): ByteArray {
            val out = ByteArray(length())
            var offset = 0
            for (view in views) {
                view.bytes.copyInto(out, offset)
                offset = align(offset + view.bytes.size)
            }
            return out
        }

        fun length(): Int {
            var offset = 0
            for (view in views) {
                offset = align(offset + view.bytes.size)
            }
            return offset
        }

        fun accessors(out: StringBuilder) {
            out.append(",\"accessors\":[")
            for ((index, view) in views.withIndex()) {
                if (index != 0) {
                    out.append(',')
                }
                out.append("{\"bufferView\":").append(index)
                out.append(",\"componentType\":").append(view.componentType)
                out.append(",\"count\":").append(view.count)
                if (view.normalized) {
                    out.append(",\"normalized\":true")
                }
                out.append(",\"type\":\"").append(view.type).append('"')
                view.bounds?.let { out.append(it) }
                out.append('}')
            }
            out.append(']')
        }

        fun bufferViews(out: StringBuilder) {
            out.append(",\"bufferViews\":[")
            var offset = 0
            for ((index, view) in views.withIndex()) {
                if (index != 0) {
                    out.append(',')
                }
                out.append("{\"buffer\":0,\"byteLength\":").append(view.bytes.size)
                out.append(",\"byteOffset\":").append(offset)
                if (view.stride != 0) {
                    out.append(",\"byteStride\":").append(view.stride)
                }
                if (view.target != 0) {
                    out.append(",\"target\":").append(view.target)
                }
                out.append('}')
                offset = align(offset + view.bytes.size)
            }
            out.append(']')
        }

        val empty: Boolean
            get() = views.isEmpty()

        private fun skins(vertices: ModelRt7Vertices) {
            val bone = vertices.bones ?: return
            val weight = vertices.weights ?: return
            boneCounts = scalars(ShortArray(vertices.count) { bone[it].size.toShort() })
            bones = scalars(flatten(bone))
            weightCounts = scalars(ShortArray(vertices.count) { weight[it].size.toShort() })
            val flat = ByteArray(weight.sumOf { it.size })
            var offset = 0
            for (values in weight) {
                values.copyInto(flat, offset)
                offset += values.size
            }
            weights = add(flat, Glb.UNSIGNED_BYTE, flat.size, "SCALAR")
        }

        private fun flatten(lists: Array<ShortArray>): ShortArray {
            val flat = ShortArray(lists.sumOf { it.size })
            var offset = 0
            for (values in lists) {
                values.copyInto(flat, offset)
                offset += values.size
            }
            return flat
        }

        private fun scalars(values: ShortArray) = add(shorts(values), Glb.UNSIGNED_SHORT, values.size, "SCALAR")

        private fun add(
            bytes: ByteArray,
            componentType: Int,
            count: Int,
            type: String,
            target: Int = 0,
            normalized: Boolean = false,
            stride: Int = 0,
            bounds: String? = null
        ): Int {
            views.add(View(bytes, componentType, count, type, normalized, target, stride, bounds))
            return views.size - 1
        }

        private fun floats(values: FloatArray): ByteArray {
            val buffer = buffer(values.size * 4)
            for (value in values) {
                buffer.putFloat(value)
            }
            return buffer.array()
        }

        private fun shorts(values: ShortArray): ByteArray {
            val buffer = buffer(values.size * 2)
            for (value in values) {
                buffer.putShort(value)
            }
            return buffer.array()
        }

        /** A three byte element has to sit on a four byte stride to be a legal vertex attribute. */
        private fun padded(values: ByteArray): ByteArray {
            val out = ByteArray(values.size / 3 * 4)
            for (element in 0 until values.size / 3) {
                values.copyInto(out, element * 4, element * 3, element * 3 + 3)
            }
            return out
        }

        private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        private fun align(offset: Int) = offset + Glb.padding(offset)
    }

    private class View(
        val bytes: ByteArray,
        val componentType: Int,
        val count: Int,
        val type: String,
        val normalized: Boolean,
        val target: Int,
        val stride: Int,
        val bounds: String?
    )

    /** The glTF asset, compact and in a fixed key order so the same model is the same bytes. */
    private fun asset(model: ModelRt7Type, streams: Streams): String {
        val out = StringBuilder(1024 + model.submeshes.size * 64)
        val name = "model_${model.id}"
        out.append("{\"asset\":{\"generator\":\"").append(Glb.GENERATOR).append("\",\"version\":\"2.0\"}")
        out.append(",\"scene\":0,\"scenes\":[{\"nodes\":[0]}]")
        out.append(",\"nodes\":[{")
        if (model.vertices.count > 0) {
            out.append("\"mesh\":0,")
        }
        out.append("\"name\":\"").append(name).append("\",\"extras\":{\"rs\":")
        extras(out, model, streams)
        out.append("}}]")
        if (model.vertices.count > 0) {
            mesh(out, model, streams, name)
        }
        if (!streams.empty) {
            streams.accessors(out)
            streams.bufferViews(out)
            out.append(",\"buffers\":[{\"byteLength\":").append(streams.length()).append("}]")
        }
        out.append('}')
        return out.toString()
    }

    private fun mesh(out: StringBuilder, model: ModelRt7Type, streams: Streams, name: String) {
        out.append(",\"meshes\":[{\"name\":\"").append(name).append("\",\"primitives\":[")
        if (model.submeshes.isEmpty()) {
            primitive(out, streams, indices = -1)
        } else {
            for ((position, accessor) in streams.submeshes.withIndex()) {
                if (position != 0) {
                    out.append(',')
                }
                primitive(out, streams, accessor)
            }
        }
        out.append("]}]")
    }

    private fun primitive(out: StringBuilder, streams: Streams, indices: Int) {
        out.append("{\"attributes\":{\"COLOR_0\":").append(streams.colours)
        out.append(",\"NORMAL\":").append(streams.normals)
        out.append(",\"POSITION\":").append(streams.positions)
        out.append(",\"TEXCOORD_0\":").append(streams.textures).append('}')
        if (indices >= 0) {
            out.append(",\"indices\":").append(indices)
        }
        out.append(",\"mode\":").append(if (indices >= 0) Glb.TRIANGLES else Glb.POINTS).append('}')
    }

    private fun extras(out: StringBuilder, model: ModelRt7Type, streams: Streams) {
        out.append("{\"id\":").append(model.id)
        out.append(",\"version\":").append(model.version)
        out.append(",\"format\":").append(model.format)
        out.append(",\"opaque\":").append(model.fieldOpaque)
        out.append(",\"vertexFlags\":").append(model.vertexFlags)
        out.append(",\"vertexCount\":").append(model.vertices.count)
        accessor(out, "positions", streams.positions)
        accessor(out, "normals", streams.normals)
        accessor(out, "colours", streams.colours)
        accessor(out, "textures", streams.textures)
        accessor(out, "streamA", streams.streamA)
        accessor(out, "streamB", streams.streamB)
        accessor(out, "streamC", streams.streamC)
        accessor(out, "byteStream", streams.byteStream)
        accessor(out, "boneCounts", streams.boneCounts)
        accessor(out, "bones", streams.bones)
        accessor(out, "weightCounts", streams.weightCounts)
        accessor(out, "weights", streams.weights)
        out.append(",\"submeshes\":[")
        for ((position, submesh) in model.submeshes.withIndex()) {
            if (position != 0) {
                out.append(',')
            }
            out.append('[').append(submesh.flags).append(',').append(submesh.fieldA).append(',')
                .append(submesh.fieldB).append(',').append(submesh.fieldC).append(',')
                .append(streams.submeshes[position]).append(']')
        }
        out.append(']')
        attachmentsA(out, model)
        attachmentsB(out, model)
        attachmentsC(out, model)
        attachmentsD(out, model)
        out.append('}')
    }

    private fun attachmentsA(out: StringBuilder, model: ModelRt7Type) {
        if (model.attachmentsA.isEmpty()) {
            return
        }
        out.append(",\"attachmentsA\":[")
        for ((position, attachment) in model.attachmentsA.withIndex()) {
            if (position != 0) {
                out.append(',')
            }
            out.append("{\"a\":").append(attachment.fieldA)
            out.append(",\"b\":").append(attachment.fieldB)
            out.append(",\"c\":").append(attachment.fieldC)
            out.append(",\"records\":[")
            for ((index, record) in attachment.records.withIndex()) {
                if (index != 0) {
                    out.append(',')
                }
                out.append("{\"x\":")
                decimal(out, record.x)
                out.append(",\"y\":")
                decimal(out, record.y)
                out.append(",\"z\":")
                decimal(out, record.z)
                out.append(",\"d\":")
                decimal(out, record.fieldD)
                out.append(",\"e\":")
                decimal(out, record.fieldE)
                out.append(",\"f\":").append(record.fieldF)
                out.append(",\"g\":").append(record.fieldG)
                out.append(",\"ids\":[")
                for ((slot, id) in record.ids.withIndex()) {
                    if (slot != 0) {
                        out.append(',')
                    }
                    out.append(id)
                }
                out.append("],\"h\":").append(record.fieldH).append('}')
            }
            out.append("]}")
        }
        out.append(']')
    }

    private fun attachmentsB(out: StringBuilder, model: ModelRt7Type) {
        if (model.attachmentsB.isEmpty()) {
            return
        }
        out.append(",\"attachmentsB\":[")
        for ((position, attachment) in model.attachmentsB.withIndex()) {
            if (position != 0) {
                out.append(',')
            }
            out.append("{\"id\":").append(attachment.id).append(",\"points\":[")
            for ((index, point) in attachment.points.withIndex()) {
                if (index != 0) {
                    out.append(',')
                }
                point(out, point)
            }
            out.append("]}")
        }
        out.append(']')
    }

    private fun attachmentsC(out: StringBuilder, model: ModelRt7Type) {
        if (model.attachmentsC.isEmpty()) {
            return
        }
        out.append(",\"attachmentsC\":[")
        for ((position, attachment) in model.attachmentsC.withIndex()) {
            if (position != 0) {
                out.append(',')
            }
            out.append("{\"id\":").append(attachment.id).append(",\"point\":")
            point(out, attachment.point)
            out.append('}')
        }
        out.append(']')
    }

    private fun attachmentsD(out: StringBuilder, model: ModelRt7Type) {
        if (model.attachmentsD.isEmpty()) {
            return
        }
        out.append(",\"attachmentsD\":[")
        for ((position, attachment) in model.attachmentsD.withIndex()) {
            if (position != 0) {
                out.append(',')
            }
            out.append("{\"tag\":").append(attachment.tag)
            attachment.name?.let { out.append(",\"name\":\"").append(escape(it)).append('"') }
            out.append(",\"record\":\"").append(Glb.hex(attachment.record)).append("\"}")
        }
        out.append(']')
    }

    private fun point(out: StringBuilder, point: ModelRt7Point) {
        out.append("{\"x\":")
        decimal(out, point.x)
        out.append(",\"y\":")
        decimal(out, point.y)
        out.append(",\"z\":")
        decimal(out, point.z)
        out.append(",\"a\":").append(point.fieldA)
        out.append(",\"b\":").append(point.fieldB).append('}')
    }

    private fun accessor(out: StringBuilder, name: String, index: Int) {
        if (index >= 0) {
            out.append(",\"").append(name).append("\":").append(index)
        }
    }

    /** JSON has no spelling for a NaN or an infinity, so those keep their bits as a hex string. */
    private fun decimal(out: StringBuilder, value: Float) {
        if (value.isFinite()) {
            out.append(value)
        } else {
            out.append('"').append(Glb.hex(bytes(value.toRawBits()))).append('"')
        }
    }

    private fun bytes(bits: Int) = ByteArray(4) { (bits shr (24 - it * 8)).toByte() }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        for (character in value) {
            when {
                character == '"' || character == '\\' -> out.append('\\').append(character)
                character < ' ' -> out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /** The `POSITION` accessor's bounds, which glTF requires and every viewer uses to frame a model. */
    private fun bounds(positions: FloatArray): String {
        val least = FloatArray(3) { Float.POSITIVE_INFINITY }
        val most = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (index in positions.indices) {
            val value = positions[index]
            if (!value.isFinite()) {
                continue
            }
            val axis = index % 3
            if (value < least[axis]) {
                least[axis] = value
            }
            if (value > most[axis]) {
                most[axis] = value
            }
        }
        val out = StringBuilder(64)
        out.append(",\"min\":")
        axes(out, least)
        out.append(",\"max\":")
        axes(out, most)
        return out.toString()
    }

    private fun axes(out: StringBuilder, values: FloatArray) {
        out.append('[')
        for ((axis, value) in values.withIndex()) {
            if (axis != 0) {
                out.append(',')
            }
            out.append(if (value.isFinite()) value else 0f)
        }
        out.append(']')
    }

    private const val SUBMESH_INDICES = 4
}
