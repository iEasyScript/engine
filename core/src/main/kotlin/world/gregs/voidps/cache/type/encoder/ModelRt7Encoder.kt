package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentB
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentD
import world.gregs.voidps.cache.type.data.ModelRt7AttachmentRecord
import world.gregs.voidps.cache.type.data.ModelRt7Point
import world.gregs.voidps.cache.type.data.ModelRt7Type

/** The RT7 model decoder backwards. */
class ModelRt7Encoder : TypeEncoder<ModelRt7Type> {

    fun encode(model: ModelRt7Type): ByteArray {
        val writer = BufferWriter(size(model))
        with(this) { writer.encode(model) }
        return writer.toArray()
    }

    override fun Writer.encode(model: ModelRt7Type) {
        writeByte(model.version)
        writeByte(model.format)
        writeByte(model.fieldOpaque)
        writeShortLittle(model.submeshes.size)
        writeByte(model.attachmentsA.size)
        writeByte(model.attachmentsB.size)
        writeByte(model.attachmentsC.size)
        writeByte(model.attachmentsD.size)
        writeIntLittle(model.vertexFlags)
        encodeVertices(model)
        encodeSubmeshes(model)
        for (attachment in model.attachmentsA) {
            writeByte(attachment.fieldA)
            writeShortLittle(attachment.fieldB)
            writeShortLittle(attachment.fieldC)
            writeShortLittle(attachment.records.size)
            for (record in attachment.records) {
                encodeRecord(record)
            }
        }
        for (attachment in model.attachmentsB) {
            writeShortLittle(attachment.id)
            for (point in attachment.points) {
                encodePoint(point)
            }
        }
        for (attachment in model.attachmentsC) {
            writeShortLittle(attachment.id)
            encodePoint(attachment.point)
        }
        for (attachment in model.attachmentsD) {
            writeByte(attachment.tag)
            if (attachment.tag == ModelRt7AttachmentD.NAMED_TAG) {
                writeString(attachment.name ?: "")
            }
            writeBytes(attachment.record)
        }
    }

    private fun Writer.encodeVertices(model: ModelRt7Type) {
        val vertices = model.vertices
        writeIntLittle(vertices.count)
        if (vertices.count == 0) {
            return
        }
        if (model.floatPositions) {
            for (position in vertices.positions) {
                writeInt(position.toRawBits())
            }
        } else {
            for (position in vertices.positions) {
                writeShortLittle(position.toInt())
            }
        }
        writeBytes(vertices.normals)
        writeBytes(vertices.colours)
        for (coordinate in vertices.textures) {
            writeShort(coordinate.toInt() and 0xffff)
        }
        writeStream(vertices.streamA)
        val bones = vertices.bones
        val weights = vertices.weights
        if (bones != null && weights != null) {
            for (vertex in 0 until vertices.count) {
                writeShortLittle(bones[vertex].size)
                for (bone in bones[vertex]) {
                    writeShortLittle(bone.toInt() and 0xffff)
                }
                writeShortLittle(weights[vertex].size)
                writeBytes(weights[vertex])
            }
        }
        writeStream(vertices.streamB)
        writeBytes(vertices.byteStream)
        writeStream(vertices.streamC)
    }

    private fun Writer.writeStream(stream: ShortArray?) {
        if (stream == null) {
            return
        }
        for (value in stream) {
            writeShortLittle(value.toInt() and 0xffff)
        }
    }

    private fun Writer.encodeSubmeshes(model: ModelRt7Type) {
        val wide = model.wideIndices
        for (submesh in model.submeshes) {
            writeIntLittle(submesh.flags)
            writeByte(submesh.fieldA)
            writeShortLittle(submesh.fieldB)
            writeByte(submesh.fieldC)
            val indices = submesh.indices
            require(indices.size % 3 == 0) { "Model ${model.id} submesh has ${indices.size} indices." }
            require(indices.size <= ModelRt7Type.MAX_INDICES) {
                "Model ${model.id} submesh has ${indices.size} indices, past what the file's count field holds."
            }
            writeShortLittle(indices.size)
            for (index in indices) {
                if (wide) writeInt(index) else writeShortLittle(index)
            }
        }
    }

    private fun Writer.encodeRecord(record: ModelRt7AttachmentRecord) {
        writeFloatLittle(record.x)
        writeFloatLittle(record.y)
        writeFloatLittle(record.z)
        writeFloatLittle(record.fieldD)
        writeFloatLittle(record.fieldE)
        writeShortLittle(record.fieldF)
        writeByte(record.fieldG)
        for (id in record.ids) {
            writeShortLittle(id)
        }
        writeByte(record.fieldH)
    }

    private fun Writer.encodePoint(point: ModelRt7Point) {
        writeFloatLittle(point.x)
        writeFloatLittle(point.y)
        writeFloatLittle(point.z)
        writeShortLittle(point.fieldA)
        writeShortLittle(point.fieldB)
    }

    private fun Writer.writeFloatLittle(value: Float) = writeIntLittle(value.toRawBits())

    private fun size(model: ModelRt7Type): Int {
        val vertices = model.vertices
        var size = HEADER_SIZE + VERTEX_COUNT_SIZE
        if (vertices.count > 0) {
            size += vertices.positions.size * (if (model.floatPositions) 4 else 2)
            size += vertices.normals.size + vertices.colours.size + vertices.textures.size * 2
            size += vertices.byteStream.size
            for (stream in listOf(vertices.streamA, vertices.streamB, vertices.streamC)) {
                size += (stream?.size ?: 0) * 2
            }
            val bones = vertices.bones
            val weights = vertices.weights
            if (bones != null && weights != null) {
                size += vertices.count * 4
                for (vertex in 0 until vertices.count) {
                    size += bones[vertex].size * 2 + weights[vertex].size
                }
            }
        }
        val indexSize = if (model.wideIndices) 4 else 2
        for (submesh in model.submeshes) {
            size += SUBMESH_HEADER_SIZE + submesh.indices.size * indexSize
        }
        for (attachment in model.attachmentsA) {
            size += ATTACHMENT_A_HEADER_SIZE + attachment.records.size * ATTACHMENT_A_RECORD_SIZE
        }
        size += model.attachmentsB.size * (ID_SIZE + POINT_SIZE * ModelRt7AttachmentB.POINTS)
        size += model.attachmentsC.size * (ID_SIZE + POINT_SIZE)
        for (attachment in model.attachmentsD) {
            size += 1 + ModelRt7AttachmentD.RECORD_SIZE + ((attachment.name?.length ?: 0) + 1)
        }
        return size
    }

    private companion object {
        private const val HEADER_SIZE = 13
        private const val VERTEX_COUNT_SIZE = 4
        private const val SUBMESH_HEADER_SIZE = 10
        private const val ATTACHMENT_A_HEADER_SIZE = 7
        private const val ATTACHMENT_A_RECORD_SIZE = 32
        private const val ID_SIZE = 2
        private const val POINT_SIZE = 16
    }
}
