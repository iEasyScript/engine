package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentA
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentB
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentC
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentD
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentRecord
import world.gregs.voidps.cache.type.data.ModelRt7Point
import world.gregs.voidps.cache.type.data.ModelRt7Submesh
import world.gregs.voidps.cache.type.data.ModelRt7Type
import world.gregs.voidps.cache.type.data.ModelRt7Vertices

/**
 * The lossless read of one RT7 model file - `re-resources/docs/cache/rt7-model-format.md`.
 *
 * The format is little-endian apart from the three streams the document names, and it is positional
 * rather than opcode driven, so a model that does not consume its file exactly says so by throwing.
 */
object ModelRt7Decoder {

    fun decode(id: Int, data: ByteArray): ModelRt7Type {
        val buffer = BufferReader(data)
        val model = ModelRt7Type(id)
        model.version = buffer.readUnsignedByte()
        model.format = buffer.readUnsignedByte()
        require(model.format == ModelRt7Type.MODERN_FORMAT) {
            "Model $id is format ${model.format}, which this decoder does not read."
        }
        model.fieldOpaque = buffer.readUnsignedByte()
        val submeshCount = buffer.readUnsignedShortLittle()
        val countA = buffer.readUnsignedByte()
        val countB = buffer.readUnsignedByte()
        val countC = buffer.readUnsignedByte()
        val countD = buffer.readUnsignedByte()
        model.vertexFlags = buffer.readIntLittle()
        readVertices(model, buffer)
        readSubmeshes(model, buffer, submeshCount)
        readAttachmentsA(model, buffer, countA)
        readAttachmentsB(model, buffer, countB)
        readAttachmentsC(model, buffer, countC)
        readAttachmentsD(model, buffer, countD)
        require(buffer.remaining == 0) { "Model $id left ${buffer.remaining} bytes unread." }
        return model
    }

    private fun readVertices(model: ModelRt7Type, buffer: Reader) {
        val count = buffer.readIntLittle()
        require(count >= 0) { "Model ${model.id} declares $count vertices." }
        val vertices = ModelRt7Vertices(count)
        model.vertices = vertices
        if (count == 0) {
            return
        }
        vertices.positions = FloatArray(count * 3)
        if (model.floatPositions) {
            for (index in vertices.positions.indices) {
                vertices.positions[index] = Float.fromBits(buffer.readInt())
            }
        } else {
            for (index in vertices.positions.indices) {
                vertices.positions[index] = buffer.readShortLittle().toFloat()
            }
        }
        vertices.normals = ByteArray(count * 3) { buffer.readByte().toByte() }
        vertices.colours = ByteArray(count * 4) { buffer.readByte().toByte() }
        vertices.textures = ShortArray(count * 2) { buffer.readUnsignedShort().toShort() }
        if (model.vertexFlags and ModelRt7Type.STREAM_A != 0) {
            vertices.streamA = ShortArray(count) { buffer.readUnsignedShortLittle().toShort() }
        }
        if (model.skinned) {
            readSkins(model, vertices, buffer)
        }
        if (model.vertexFlags and ModelRt7Type.STREAM_B != 0) {
            vertices.streamB = ShortArray(count) { buffer.readUnsignedShortLittle().toShort() }
        }
        vertices.byteStream = ByteArray(count) { buffer.readByte().toByte() }
        if (model.vertexFlags and ModelRt7Type.STREAM_C != 0) {
            vertices.streamC = ShortArray(count) { buffer.readUnsignedShortLittle().toShort() }
        }
    }

    private fun readSkins(model: ModelRt7Type, vertices: ModelRt7Vertices, buffer: Reader) {
        val bones = Array(vertices.count) { ShortArray(0) }
        val weights = Array(vertices.count) { ByteArray(0) }
        for (vertex in 0 until vertices.count) {
            val boneCount = buffer.readUnsignedShortLittle()
            require(boneCount * 2 <= buffer.remaining) { "Model ${model.id} vertex $vertex claims $boneCount bones." }
            bones[vertex] = ShortArray(boneCount) { buffer.readUnsignedShortLittle().toShort() }
            val weightCount = buffer.readUnsignedShortLittle()
            require(weightCount <= buffer.remaining) { "Model ${model.id} vertex $vertex claims $weightCount weights." }
            weights[vertex] = ByteArray(weightCount) { buffer.readByte().toByte() }
        }
        vertices.bones = bones
        vertices.weights = weights
    }

    private fun readSubmeshes(model: ModelRt7Type, buffer: Reader, count: Int) {
        val wide = model.wideIndices
        for (position in 0 until count) {
            val submesh = ModelRt7Submesh(
                flags = buffer.readIntLittle(),
                fieldA = buffer.readUnsignedByte(),
                fieldB = buffer.readUnsignedShortLittle(),
                fieldC = buffer.readUnsignedByte()
            )
            val indices = indexCount(buffer.readUnsignedShortLittle())
            submesh.indices = IntArray(indices) { if (wide) buffer.readInt() else buffer.readUnsignedShortLittle() }
            model.submeshes.add(submesh)
        }
    }

    /**
     * The count is a `u16` that six shipped assets overflowed, and the wire keeps no high bits: the
     * only reading that lands on the file's own end is the smallest wrap above the field that
     * divides by three, which is also the divisibility the client itself insists on.
     */
    private fun indexCount(declared: Int): Int {
        var count = declared
        while (count % 3 != 0) {
            count += WRAP
        }
        return count
    }

    private fun readAttachmentsA(model: ModelRt7Type, buffer: Reader, count: Int) {
        for (position in 0 until count) {
            val attachment = ModelRt7AttachmentA(
                fieldA = buffer.readUnsignedByte(),
                fieldB = buffer.readUnsignedShortLittle(),
                fieldC = buffer.readUnsignedShortLittle()
            )
            val records = buffer.readUnsignedShortLittle()
            for (record in 0 until records) {
                attachment.records.add(
                    ModelRt7AttachmentRecord(
                        x = buffer.readFloatLittle(),
                        y = buffer.readFloatLittle(),
                        z = buffer.readFloatLittle(),
                        fieldD = buffer.readFloatLittle(),
                        fieldE = buffer.readFloatLittle(),
                        fieldF = buffer.readUnsignedShortLittle(),
                        fieldG = buffer.readUnsignedByte(),
                        ids = IntArray(ModelRt7AttachmentRecord.IDS) { buffer.readUnsignedShortLittle() },
                        fieldH = buffer.readUnsignedByte()
                    )
                )
            }
            model.attachmentsA.add(attachment)
        }
    }

    private fun readAttachmentsB(model: ModelRt7Type, buffer: Reader, count: Int) {
        for (position in 0 until count) {
            model.attachmentsB.add(
                ModelRt7AttachmentB(
                    id = buffer.readUnsignedShortLittle(),
                    points = Array(ModelRt7AttachmentB.POINTS) { readPoint(buffer) }
                )
            )
        }
    }

    private fun readAttachmentsC(model: ModelRt7Type, buffer: Reader, count: Int) {
        for (position in 0 until count) {
            model.attachmentsC.add(ModelRt7AttachmentC(buffer.readUnsignedShortLittle(), readPoint(buffer)))
        }
    }

    private fun readAttachmentsD(model: ModelRt7Type, buffer: Reader, count: Int) {
        for (position in 0 until count) {
            val tag = buffer.readUnsignedByte()
            val name = if (tag == ModelRt7AttachmentD.NAMED_TAG) buffer.readString() else null
            val record = ByteArray(ModelRt7AttachmentD.RECORD_SIZE)
            buffer.readBytes(record)
            model.attachmentsD.add(ModelRt7AttachmentD(tag, name, record))
        }
    }

    private fun readPoint(buffer: Reader) = ModelRt7Point(
        x = buffer.readFloatLittle(),
        y = buffer.readFloatLittle(),
        z = buffer.readFloatLittle(),
        fieldA = buffer.readUnsignedShortLittle(),
        fieldB = buffer.readUnsignedShortLittle()
    )

    private fun Reader.readFloatLittle(): Float = Float.fromBits(readIntLittle())

    private const val WRAP = 0x10000
}
