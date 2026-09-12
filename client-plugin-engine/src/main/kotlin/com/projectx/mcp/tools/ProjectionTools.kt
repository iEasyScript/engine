package com.projectx.mcp.tools

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.mcp.tools.MemoryTools.safeCall
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object ProjectionTools {

    fun register(server: Server): Int {
        server.addTool(
            name = "eval_projection",
            description = "Project a world-fine coordinate {x, y, z} to screen space. " +
                "Uses the game's current VP matrix and viewport. " +
                "Returns clip coords, screen coords, and the VP matrix.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("x") {
                        put("type", "number")
                        put("description", "World-fine X coordinate")
                    }
                    putJsonObject("y") {
                        put("type", "number")
                        put("description", "World-fine Y coordinate")
                    }
                    putJsonObject("z") {
                        put("type", "number")
                        put("description", "World-fine Z coordinate (height)")
                    }
                },
                required = listOf("x", "y", "z")
            )
        ) { request ->
            safeCall {
                val args = request.arguments ?: error("Missing arguments")
                val x = args["x"]?.jsonPrimitive?.content?.toFloat() ?: error("Missing x")
                val y = args["y"]?.jsonPrimitive?.content?.toFloat() ?: error("Missing y")
                val z = args["z"]?.jsonPrimitive?.content?.toFloat() ?: error("Missing z")

                val world = Bootstrap.client.sceneManager.currentWorld
                    ?: return@safeCall "ERROR: No current world (not logged in?)"
                val vp = world.projectionMatrix
                if (vp.size != 16) return@safeCall "ERROR: VP matrix has ${vp.size} elements (expected 16)"

                // Compute clip coords using {X, Z, Y} axis swizzle
                val clipW = vp[3] * x + vp[7] * z + vp[11] * y + vp[15]
                val result = StringBuilder()
                result.appendLine("Input world-fine: ($x, $y, $z)")
                result.appendLine()

                if (clipW <= 0f) {
                    result.appendLine("Behind camera (clipW = $clipW)")
                } else {
                    val clipX = (vp[0] * x + vp[4] * z + vp[8] * y + vp[12]) / clipW
                    val clipY = (vp[1] * x + vp[5] * z + vp[9] * y + vp[13]) / clipW

                    val varcs = Bootstrap.client.clientVarDomain
                    val viewX = varcs.getVar(3005).coerceAtLeast(0)
                    val viewY = varcs.getVar(3006).coerceAtLeast(0)
                    val viewW = varcs.getVar(3001)
                    val viewH = varcs.getVar(3002)

                    val cx = viewW / 2.0f
                    val cy = viewH / 2.0f
                    val screenX = viewX + (clipX * cx) - clipX + cx
                    val screenY = viewY + -(clipY * cy) + clipY + cy

                    result.appendLine("Clip: X=$clipX, Y=$clipY, W=$clipW")
                    result.appendLine("Viewport: offset=($viewX, $viewY) size=($viewW x $viewH)")
                    result.appendLine("Screen: ($screenX, $screenY)")
                }

                result.appendLine()
                result.appendLine("VP Matrix (column-major):")
                for (row in 0..3) {
                    result.appendLine("  [%12.6f %12.6f %12.6f %12.6f]".format(
                        vp[row], vp[row + 4], vp[row + 8], vp[row + 12]
                    ))
                }

                result.toString()
            }
        }
        return 1
    }
}
