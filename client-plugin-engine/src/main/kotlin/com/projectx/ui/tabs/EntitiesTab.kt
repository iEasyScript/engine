package com.projectx.ui.tabs

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.*
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.game.spotAnimLabel
import com.projectx.game.spotAnimName
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags

object EntitiesTab {
    fun ChildScope.render() {
        section("Track")
        textWrapped("Draws the overlay and fills the list below.")
        checkboxGrid(
            "entity-track",
            listOf(
                "Scene Objects" to UIState.showSceneObjects,
                "NPCs" to UIState.showNpcs,
                "Players" to UIState.showPlayers,
                "Spot Animations" to UIState.showSpotAnims,
                "Projectiles" to UIState.showProjectiles,
                "Ground Items" to UIState.showGroundItems,
                "Clickboxes" to UIState.showClickboxes,
            )
        )

        section("Filter")
        properties("entity-filter") {
            row("Range") { sliderInt("##entityRange", UIState.entityRange, 1, 100) }
            row("Search") { inputText("##entitySearch", UIState.entitySearchText) }
        }

        collapsingHeader("Appearance") {
            properties("entity-appearance") {
                row("Overlay text") {
                    colorEdit4(
                        label = "##EntityTextColor",
                        r = UIState.entityTextColorR,
                        g = UIState.entityTextColorG,
                        b = UIState.entityTextColorB,
                        a = UIState.entityTextColorA
                    )
                }
                row("Clickbox") {
                    colorEdit3(
                        label = "##ClickboxColor",
                        r = UIState.clickboxColorR,
                        g = UIState.clickboxColorG,
                        b = UIState.clickboxColorB,
                    )
                }
                row("Outline mode") {
                    combo(
                        label = "##ClickboxOutline",
                        currentItem = UIState.clickboxOutlineMode,
                        items = listOf("Off", "Solid", "Outline", "Glow"),
                    )
                }
                row("Intensity") { sliderInt("##ClickboxIntensity", UIState.clickboxIntensity, 0, 255) }
            }
        }

        section("Results")

        if (Bootstrap.client.mainState != MainState.LOGGED_IN) {
            text("Not logged in")
            return
        }
        
        val searchText = UIState.entitySearchText.value.lowercase()
        val range = UIState.entityRange.value
        val playerTile = localPlayer.tile
        
        table(
            id = "EntitiesTable",
            columns = 5,
            flags = ImGuiTableFlags.SizingStretchSame or ImGuiTableFlags.BordersInner
        ) {
            setupColumn("Type", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("ID", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Name", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Location", flags = ImGuiTableColumnFlags.WidthFixed)
            setupColumn("Options")
            headersRow()
            
            if (UIState.showSceneObjects.value) {
                getAllObjectsWithinRange(range).forEach { obj ->
                    if (obj.name().isBlank()) return@forEach
                    if (searchText.isNotEmpty() && 
                        !obj.name().lowercase().contains(searchText) && 
                        !obj.id.toString().contains(searchText)) return@forEach
                    
                    nextRow()
                    nextColumn()
                    text("Object")
                    nextColumn()
                    text(obj.id.toString())
                    nextColumn()
                    text(obj.name())
                    nextColumn()
                    text("${obj.tile.x}, ${obj.tile.y}, ${obj.tile.plane}")
                    nextColumn()
                    text(obj.defs.options?.joinToString(", ") { it ?: "None" } ?: "")

                    if (obj.visibleTypeId != obj.id) {
                        val def = obj.defs
                        val controlledBy = when {
                            def.varbit != -1 -> "via varbit ${def.varbit}"
                            def.varp != -1 -> "via varp ${def.varp}"
                            else -> "via transform"
                        }
                        nextRow()
                        nextColumn()
                        text("  visible")
                        nextColumn()
                        text(obj.visibleTypeId.toString())
                        nextColumn()
                        text(controlledBy)
                        nextColumn()
                        text("")
                        nextColumn()
                        text("")
                    }
                }
            }
            
            if (UIState.showNpcs.value) {
                npcs.values.forEach { npc ->
                    val npcTile = npc.tile
                    val distance = playerTile.getDistance(npcTile)
                    if (distance > range) return@forEach
                    if (searchText.isNotEmpty() &&
                        !npc.name().lowercase().contains(searchText) &&
                        !npc.id.toString().contains(searchText)) return@forEach
                    
                    nextRow()
                    nextColumn()
                    text("NPC")
                    nextColumn()
                    text(npc.id.toString())
                    nextColumn()
                    text(npc.name())
                    nextColumn()
                    text("${npcTile.x}, ${npcTile.y}, ${npcTile.plane}")
                    nextColumn()
                    text(npc.getDef().options.filterNotNull().joinToString(", ") { it.ifEmpty { "None" } })

                    val spots = npc.spotAnims
                    if (spots.isNotEmpty()) {
                        nextRow()
                        nextColumn()
                        text("  spotAnims")
                        nextColumn()
                        text(spots.size.toString())
                        nextColumn()
                        text(spots.joinToString(", ") { "${spotAnimLabel(it.id)} (${it.timeAliveMillis}ms)" })
                        nextColumn()
                        text("")
                        nextColumn()
                        text("")
                    }
                }
            }

            if (UIState.showPlayers.value) {
                players.forEach { player ->
                    val playerEntityTile = player.tile
                    val distance = playerTile.getDistance(playerEntityTile)
                    if (distance > range) return@forEach
                    if (searchText.isNotEmpty() &&
                        !player.name.lowercase().contains(searchText) &&
                        !player.serverIndex.toString().contains(searchText)) return@forEach

                    nextRow()
                    nextColumn()
                    text("Player")
                    nextColumn()
                    text(player.serverIndex.toString())
                    nextColumn()
                    text(player.name)
                    nextColumn()
                    text("${playerEntityTile.x}, ${playerEntityTile.y}, ${playerEntityTile.plane}")
                    nextColumn()
                    text("N/A")

                    val spots = player.spotAnims
                    if (spots.isNotEmpty()) {
                        nextRow()
                        nextColumn()
                        text("  spotAnims")
                        nextColumn()
                        text(spots.size.toString())
                        nextColumn()
                        text(spots.joinToString(", ") { "${spotAnimLabel(it.id)} (${it.timeAliveMillis}ms)" })
                        nextColumn()
                        text("")
                        nextColumn()
                        text("")
                    }
                }
            }
            
            if (UIState.showSpotAnims.value) {
                spotAnims.forEach { spotAnim ->
                    val spotAnimTile = spotAnim.tile
                    val distance = playerTile.getDistance(spotAnimTile)
                    if (distance > range) return@forEach
                    if (searchText.isNotEmpty() &&
                        !spotAnim.id.toString().contains(searchText)) return@forEach
                    
                    nextRow()
                    nextColumn()
                    text("SpotAnim")
                    nextColumn()
                    text(spotAnim.id.toString())
                    nextColumn()
                    text(spotAnimName(spotAnim.id) ?: "SpotAnim ${spotAnim.id}")
                    nextColumn()
                    text("${spotAnimTile.x}, ${spotAnimTile.y}, ${spotAnimTile.plane}")
                    nextColumn()
                    text("N/A")
                }
            }
            
            if (UIState.showProjectiles.value) {
                projectiles.forEach { projectile ->
                    val projectileTile = projectile.tile
                    val distance = playerTile.getDistance(projectileTile)
                    if (distance > range) return@forEach
                    if (searchText.isNotEmpty() &&
                        !projectile.id.toString().contains(searchText)) return@forEach
                    
                    nextRow()
                    nextColumn()
                    text("Projectile")
                    nextColumn()
                    text(projectile.id.toString())
                    nextColumn()
                    text(spotAnimName(projectile.id) ?: "Projectile ${projectile.id}")
                    nextColumn()
                    text("${projectileTile.x}, ${projectileTile.y}, ${projectileTile.plane}")
                    nextColumn()
                    text("Target: ${projectile.lockedToServerIndex}")
                }
            }

            if (UIState.showGroundItems.value) {
                groundItems.forEach { gi ->
                    val giTile = gi.tile
                    if (playerTile.getDistance(giTile) > range) return@forEach
                    val name = gi.name
                    if (searchText.isNotEmpty() &&
                        !name.lowercase().contains(searchText) &&
                        !gi.id.toString().contains(searchText)) return@forEach

                    nextRow()
                    nextColumn()
                    text("Ground Item")
                    nextColumn()
                    text(gi.id.toString())
                    nextColumn()
                    text(if (gi.amount > 1) "$name x${gi.amount}" else name)
                    nextColumn()
                    text("${giTile.x}, ${giTile.y}, ${giTile.plane}")
                    nextColumn()
                    text(gi.groundOps.filterNotNull().joinToString(", ").ifEmpty { "None" })
                }
            }
        }
    }
}