package com.projectx.script.impl.bp.misc

import world.gregs.voidps.type.Tile
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.varps
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.spacing
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.utils.ImGuiColors

@ScriptDescription(
    name = "Barrows Tunnel Highlighter",
    version = "1.2.0",
    author = "BP",
    description = "Highlights the tunnel mound and slain brothers at Barrows.",
    category = ScriptCategory.BOSSES
)
class BarrowsTunnelHighlighter : Script() {
    private var tunnelLocation: Mound? = null
    private var slainMounds: Set<Mound> = emptySet()
    private var lastTunnelData: Int = 0
    private var lastKillData: Int = 0

    override suspend fun loop() {
        val tunnelData = varps.getVar(TUNNEL_VARP)
        val killData = varps.getVar(KILL_VARP)

        lastTunnelData = tunnelData
        lastKillData = killData

        tunnelLocation = Mound.fromTunnelVarp(tunnelData)
        slainMounds = Mound.fromKillVarp(killData)

        delay(250)
    }

    override fun render() {
        val location = tunnelLocation

        backgroundDrawList {
            slainMounds.forEach { mound ->
                val fineTile = mound.findSpadeFineTile() ?: return@forEach
                tile(fineTile, SLAIN_COLOR)
                drawLabel(fineTile, "${mound.displayName} slain", ImGuiColors.GREEN)
            }

            val tunnelFineTile = location?.findSpadeFineTile()
            if (tunnelFineTile != null) {
                tile(tunnelFineTile, TUNNEL_COLOR)
                drawLabel(tunnelFineTile, "Tunnel: ${location.displayName}", ImGuiColors.ORANGE)
            }
        }
    }

    private fun Mound.findSpadeFineTile(): Vector3f? {
        val surface = surfaceTile ?: return null
        val spade = getAllObjectsWithinRange(surface, 3).firstOrNull { obj ->
            SPADE_NAME_REGEX.matches(obj.name()) && obj.tile == surface
        } ?: return null

        return spade.graphNode?.tileFine
    }

    private fun BackgroundDrawListScope.drawLabel(tileFine: Vector3f, label: String, color: Int) {
        val screen: Vector2f = WorldToScreen.getEstimatedTileCenter(tileFine) ?: return
        text(screen.transform(0f, -14f), color, label)
    }

    private enum class Mound(
        val displayName: String,
        val bitIndex: Int,
        val surfaceTile: Tile?
    ) {
        AHRIM("Ahrim", 0, Tile.of(3567, 3288, 0)),
        DHAROK("Dharok", 1, Tile.of(3575, 3298, 0)),
        GUTHAN("Guthan", 2, Tile.of(3576, 3281, 0)),
        KARIL("Karil", 3, Tile.of(3564, 3277, 0)),
        TORAG("Torag", 4, Tile.of(3554, 3282, 0)),
        VERAC("Verac", 5, Tile.of(3557, 3298, 0)),
        AKRISAE("Akrisae", -1, null);

        companion object {
            fun fromTunnelVarp(tunnelData: Int): Mound? {
                return entries.firstOrNull { mound ->
                    mound.bitIndex >= 0 && tunnelData.isBitSet(mound.bitIndex)
                }
            }

            fun fromKillVarp(killData: Int): Set<Mound> {
                return entries.filter { mound ->
                    mound.bitIndex >= 0 && killData.isBitSet(mound.bitIndex)
                }.toSet()
            }
        }
    }

    private companion object {
        private const val TUNNEL_VARP = 1512
        private const val KILL_VARP = 1513
        private val SLAIN_COLOR = ImGuiColors.GREEN
        private val TUNNEL_COLOR = ImGuiColors.ORANGE
        private val SPADE_NAME_REGEX = Regex("Spades?")
    }
}

private fun Int.isBitSet(bit: Int): Boolean = (this shr bit and 1) == 1
