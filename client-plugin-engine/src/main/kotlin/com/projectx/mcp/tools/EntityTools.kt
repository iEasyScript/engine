package com.projectx.mcp.tools

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.OMeshComponent
import com.projectx.game.nxt.OMeshData
import com.projectx.game.nxt.ORenderModel
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.mcp.tools.MemoryTools.safeCall
import com.projectx.mcp.tools.MemoryTools.validateAddress
import world.gregs.voidps.gameval.Gameval
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_SHORT

object EntityTools {

    fun register(server: Server): Int {
        registerGetEntityInfo(server)
        registerReadMeshData(server)
        return 2
    }

    private fun findNpc(serverIndex: Int? = null, nameQuery: String? = null): NPC? {
        val mgr = Bootstrap.client.npcManager
        return mgr.indices
            .asSequence()
            .filter { it > 0 }
            .mapNotNull { idx ->
                val addr = mgr[idx] ?: return@mapNotNull null
                if (addr.address() == 0L) return@mapNotNull null
                NPC(addr)
            }
            .filter { it.exists() }
            .firstOrNull { npc ->
                when {
                    serverIndex != null -> npc.serverIndex == serverIndex
                    nameQuery != null -> npc.name.contains(nameQuery, ignoreCase = true)
                    else -> false
                }
            }
    }

    private fun registerGetEntityInfo(server: Server) {
        server.addTool(
            name = "get_entity_info",
            description = "Get diagnostic info for an NPC by server_index or name (partial match). " +
                "Returns: address, name, tile, world-fine pos, pick data, render model, animation, AABB bounds.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("server_index") {
                        put("type", "integer")
                        put("description", "NPC server index (from NPC manager)")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Partial name match (case-insensitive)")
                    }
                }
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val sid = args["server_index"]?.jsonPrimitive?.content?.toIntOrNull()
                val name = args["name"]?.jsonPrimitive?.content

                if (sid == null && name == null) error("Provide either server_index or name")

                val npc = findNpc(serverIndex = sid, nameQuery = name)
                    ?: return@safeCall "NPC not found (server_index=$sid, name=$name)"

                val result = StringBuilder()
                result.appendLine("=== NPC Entity Info ===")
                result.appendLine("Address: 0x${npc.ptr.address().toString(16)}")
                result.appendLine("Server Index: ${npc.serverIndex}")
                result.appendLine("Name: ${npc.name}")
                result.appendLine("NPC ID: ${Gameval.npcLabel(npc.id)}")
                result.appendLine("Type ID: ${Gameval.npcLabel(npc.typeId)}")

                try {
                    val tile = npc.tile
                    result.appendLine("Tile: (${tile.x}, ${tile.y}, plane=${tile.plane})")
                } catch (e: Throwable) {
                    result.appendLine("Tile: ERROR (${e.message})")
                }

                try {
                    val gn = npc.graphNode
                    val scene = gn.scene
                    result.appendLine("World-Fine Pos: (${scene.x}, ${scene.y}, ${scene.z})")
                    result.appendLine("GraphNode Flags: 0x${gn.flags.toString(16)}")
                    if (gn.hasBounds) {
                        val min = gn.boundsMin
                        val max = gn.boundsMax
                        result.appendLine("AABB Min: (${min.x}, ${min.y}, ${min.z})")
                        result.appendLine("AABB Max: (${max.x}, ${max.y}, ${max.z})")
                    } else {
                        result.appendLine("AABB: no bounds (HAS_BOUNDS flag not set)")
                    }
                } catch (e: Throwable) {
                    result.appendLine("GraphNode: ERROR (${e.message})")
                }

                try {
                    result.appendLine("Pick Type: ${npc.pickType}")
                    result.appendLine("Screen Center: (${npc.screenCenterX}, ${npc.screenCenterY})")
                    result.appendLine("Point Radius: ${npc.pointRadius}")
                } catch (e: Throwable) {
                    result.appendLine("Pick Data: ERROR (${e.message})")
                }

                try {
                    val rmAddr = npc.ptr.get(ADDRESS, OEntity.RENDER_MODEL).address()
                    result.appendLine("Render Model: 0x${rmAddr.toString(16)}" + if (rmAddr == 0L) " (NULL)" else "")
                } catch (e: Throwable) {
                    result.appendLine("Render Model: ERROR (${e.message})")
                }

                try {
                    result.appendLine("Animation ID: ${Gameval.seqLabel(npc.animationId)}")
                    val anim = npc.animation
                    if (anim != null) {
                        result.appendLine("Animation: id=${Gameval.seqLabel(anim.id)}, frame=${anim.currentFrame}")
                    }
                } catch (e: Throwable) {
                    result.appendLine("Animation: ERROR (${e.message})")
                }

                try {
                    result.appendLine("Health: ${npc.currentHealth}/${npc.maxHealth}")
                } catch (e: Throwable) {
                    result.appendLine("Health: ERROR (${e.message})")
                }

                result.toString()
            }
        }
    }

    private fun registerReadMeshData(server: Server) {
        server.addTool(
            name = "read_mesh_data",
            description = "Dump mesh projection data for an NPC: render model, world transform, mesh components, " +
                "vertex data (skinned/unskinned), index buffers. Key tool for debugging MeshProjection.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("server_index") {
                        put("type", "integer")
                        put("description", "NPC server index")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Partial name match (case-insensitive)")
                    }
                    putJsonObject("max_vertices") {
                        put("type", "integer")
                        put("description", "Max vertices to dump per mesh (default 5)")
                    }
                }
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val sid = args["server_index"]?.jsonPrimitive?.content?.toIntOrNull()
                val name = args["name"]?.jsonPrimitive?.content
                val maxVerts = args["max_vertices"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

                if (sid == null && name == null) error("Provide either server_index or name")

                val npc = findNpc(serverIndex = sid, nameQuery = name)
                    ?: return@safeCall "NPC not found (server_index=$sid, name=$name)"

                val result = StringBuilder()
                result.appendLine("=== Mesh Data for '${npc.name}' (sid=${npc.serverIndex}) ===")
                result.appendLine("Entity ptr: 0x${npc.ptr.address().toString(16)}")

                validateAddress(npc.ptr.address(), OEntity.RENDER_MODEL + 8)
                val renderModelAddr = npc.ptr.get(ADDRESS, OEntity.RENDER_MODEL).address()
                result.appendLine("RenderModel ptr (Entity+0x${OEntity.RENDER_MODEL.toString(16)}): 0x${renderModelAddr.toString(16)}")
                if (renderModelAddr == 0L) {
                    result.appendLine("  (NULL - no render model, entity may not be rendered)")
                    return@safeCall result.toString()
                }

                validateAddress(renderModelAddr, 0x300L)
                val rm = MemorySegment.ofAddress(renderModelAddr).reinterpret(0x300L)

                result.appendLine()
                result.appendLine("World Transform (RM+0x${ORenderModel.WORLD_TRANSFORM.toString(16)}):")
                val m = FloatArray(16) { rm.get(JAVA_FLOAT, ORenderModel.WORLD_TRANSFORM + it * 4L) }
                for (row in 0..3) {
                    result.appendLine("  [%12.6f %12.6f %12.6f %12.6f]".format(
                        m[row], m[row + 4], m[row + 8], m[row + 12]
                    ))
                }

                result.appendLine()
                val ptr1 = rm.get(ADDRESS, ORenderModel.MESH_COMPONENTS).address()
                result.appendLine("MeshComponents ptr (RM+0x${ORenderModel.MESH_COMPONENTS.toString(16)}): 0x${ptr1.toString(16)}")
                if (ptr1 == 0L) {
                    result.appendLine("  (NULL)")
                    return@safeCall result.toString()
                }

                validateAddress(ptr1, 8L)
                val ptr2 = MemorySegment.ofAddress(ptr1).reinterpret(8L).get(ADDRESS, 0L).address()
                result.appendLine("  -> deref: 0x${ptr2.toString(16)}")
                if (ptr2 == 0L) {
                    result.appendLine("  (NULL after deref)")
                    return@safeCall result.toString()
                }

                validateAddress(ptr2 + 8, 0x10L)
                val meshVec = MemorySegment.ofAddress(ptr2 + 8).reinterpret(0x10L)
                val meshListStart = meshVec.get(ADDRESS, 0L).address()
                val meshListEnd = meshVec.get(ADDRESS, 8L).address()
                result.appendLine("  Mesh vector: start=0x${meshListStart.toString(16)}, end=0x${meshListEnd.toString(16)}")

                if (meshListStart == 0L || meshListEnd == 0L || meshListEnd <= meshListStart) {
                    result.appendLine("  (invalid mesh vector)")
                    return@safeCall result.toString()
                }

                val meshCount = ((meshListEnd - meshListStart) / 8).toInt().coerceAtMost(32)
                result.appendLine("  Mesh count: $meshCount")

                for (meshIdx in 0 until meshCount) {
                    result.appendLine()
                    result.appendLine("--- Mesh $meshIdx ---")

                    validateAddress(meshListStart, (meshCount * 8).toLong())
                    val meshCompAddr = MemorySegment.ofAddress(meshListStart).reinterpret((meshCount * 8).toLong())
                        .get(ADDRESS, meshIdx * 8L).address()
                    result.appendLine("  MeshComponent ptr: 0x${meshCompAddr.toString(16)}")
                    if (meshCompAddr == 0L) {
                        result.appendLine("  (NULL)")
                        continue
                    }

                    validateAddress(meshCompAddr, 0x400L)
                    val meshComp = MemorySegment.ofAddress(meshCompAddr).reinterpret(0x400L)
                    val meshDataAddr = meshComp.get(ADDRESS, OMeshComponent.MESH_DATA).address()
                    result.appendLine("  MeshData ptr (comp+0x${OMeshComponent.MESH_DATA.toString(16)}): 0x${meshDataAddr.toString(16)}")
                    if (meshDataAddr == 0L) {
                        result.appendLine("  (NULL)")
                        continue
                    }

                    validateAddress(meshDataAddr, 0x200L)
                    val meshData = MemorySegment.ofAddress(meshDataAddr).reinterpret(0x200L)

                    val indexStart = meshData.get(ADDRESS, OMeshData.INDEX_BUFFER).address()
                    val indexEnd = meshData.get(ADDRESS, OMeshData.INDEX_BUFFER_END).address()
                    val indexCount = if (indexStart != 0L && indexEnd > indexStart) ((indexEnd - indexStart) / 2).toInt() else 0
                    result.appendLine("  Index buffer: start=0x${indexStart.toString(16)}, end=0x${indexEnd.toString(16)}, count=$indexCount")

                    val skinningPtr = meshData.get(ADDRESS, OMeshData.SKINNING_DATA).address()
                    val isSkinned = skinningPtr != 0L
                    result.appendLine("  Skinning data: 0x${skinningPtr.toString(16)} (${if (isSkinned) "SKINNED" else "UNSKINNED"})")

                    val vertexScale = meshData.get(JAVA_FLOAT, OMeshData.VERTEX_SCALE)
                    result.appendLine("  Vertex scale (meshData+0x2C): $vertexScale")

                    val vertexPtr: Long
                    val vertexStride: Long

                    if (isSkinned) {
                        vertexPtr = meshData.get(ADDRESS, OMeshData.SKINNED_VERTEX_DATA).address()
                        vertexStride = 8L
                        result.appendLine("  Skinned vertex data (meshData+0x${OMeshData.SKINNED_VERTEX_DATA.toString(16)}): 0x${vertexPtr.toString(16)}")
                        result.appendLine("  Stride: $vertexStride (short3 + padding)")
                    } else {
                        vertexPtr = meshData.get(ADDRESS, OMeshData.VERTEX_POSITIONS).address()
                        vertexStride = 0x10L
                        result.appendLine("  Float4 vertex data (meshData+0x${OMeshData.VERTEX_POSITIONS.toString(16)}): 0x${vertexPtr.toString(16)}")
                        result.appendLine("  Stride: $vertexStride (float4)")
                    }

                    if (vertexPtr == 0L) {
                        result.appendLine("  (NULL vertex pointer)")
                        continue
                    }

                    validateAddress(vertexPtr, maxVerts * vertexStride + 16)
                    val vertexSeg = MemorySegment.ofAddress(vertexPtr).reinterpret(maxVerts * vertexStride + 16)
                    val toDump = maxVerts.coerceAtMost(
                        if (indexCount > 0) indexCount.coerceAtMost(1000) else maxVerts
                    )

                    result.appendLine("  First $toDump vertices (raw + scaled):")
                    for (i in 0 until toDump) {
                        val off = i * vertexStride
                        if (isSkinned) {
                            val sx = vertexSeg.get(JAVA_SHORT, off)
                            val sy = vertexSeg.get(JAVA_SHORT, off + 2)
                            val sz = vertexSeg.get(JAVA_SHORT, off + 4)
                            val fx = sx.toFloat() * vertexScale
                            val fy = sy.toFloat() * vertexScale
                            val fz = sz.toFloat() * vertexScale
                            result.appendLine("    [$i] raw=($sx, $sy, $sz) scaled=(${"%.4f".format(fx)}, ${"%.4f".format(fy)}, ${"%.4f".format(fz)})")
                        } else {
                            val fx = vertexSeg.get(JAVA_FLOAT, off)
                            val fy = vertexSeg.get(JAVA_FLOAT, off + 4)
                            val fz = vertexSeg.get(JAVA_FLOAT, off + 8)
                            result.appendLine("    [$i] (${"%.4f".format(fx)}, ${"%.4f".format(fy)}, ${"%.4f".format(fz)})")
                        }
                    }
                }

                result.toString()
            }
        }
    }
}
