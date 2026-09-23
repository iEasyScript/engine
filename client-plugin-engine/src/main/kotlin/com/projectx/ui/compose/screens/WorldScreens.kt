package com.projectx.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.game.spotAnimLabel
import com.projectx.game.spotAnimName
import com.projectx.markers.MarkerGroup
import com.projectx.markers.TileMarker
import com.projectx.markers.TileMarkerState
import com.projectx.markers.TileMarkerStore
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.script.api.npcs
import com.projectx.script.api.players
import com.projectx.script.api.projectiles
import com.projectx.script.api.spotAnims
import com.projectx.ui.UIState
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.ChipGroup
import com.projectx.ui.compose.components.ColorField
import com.projectx.ui.compose.components.DataTable
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.IntSlider
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TableColumn
import com.projectx.ui.compose.components.TableRowScope
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.Toggle
import com.projectx.ui.compose.components.ToggleRow
import com.projectx.ui.compose.theme.Palette
import com.projectx.ui.compose.theme.fromImGuiColor

// ---------- Entities ----------

data class EntityRow(val kind: String, val id: String, val name: String, val location: String, val detail: String, val sub: Boolean = false)

data class EntitySnapshot(val loggedIn: Boolean, val rows: List<EntityRow>)

object EntitiesModel {
    private const val MAX_ROWS = 400

    val feed = Feed(300) {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return@Feed EntitySnapshot(false, emptyList())
        val search = UIState.entitySearchText.value.trim().lowercase()
        val range = UIState.entityRange.value
        val me = localPlayer.tile
        fun hit(vararg fields: String) = search.isEmpty() || fields.any { it.lowercase().contains(search) }
        val rows = mutableListOf<EntityRow>()

        if (UIState.showSceneObjects.value) {
            getAllObjectsWithinRange(range).forEach { obj ->
                val name = obj.name()
                if (name.isBlank() || !hit(name, obj.id.toString())) return@forEach
                rows += EntityRow("Object", "${obj.id}", name, "${obj.tile.x}, ${obj.tile.y}, ${obj.tile.plane}",
                    obj.defs.options?.filterNotNull()?.joinToString(", ").orEmpty())
                if (obj.visibleTypeId != obj.id) {
                    val def = obj.defs
                    val via = when {
                        def.varbit != -1 -> "via varbit ${def.varbit}"
                        def.varp != -1 -> "via varp ${def.varp}"
                        else -> "via transform"
                    }
                    rows += EntityRow("", "${obj.visibleTypeId}", "shown as", "", via, sub = true)
                }
            }
        }
        if (UIState.showNpcs.value) {
            npcs.values.forEach { npc ->
                val tile = npc.tile
                if (me.getDistance(tile) > range || !hit(npc.name(), npc.id.toString())) return@forEach
                rows += EntityRow("NPC", "${npc.id}", npc.name(), "${tile.x}, ${tile.y}, ${tile.plane}",
                    npc.getDef().options.filterNotNull().filter { it.isNotEmpty() }.joinToString(", "))
                npc.spotAnims.takeIf { it.isNotEmpty() }?.let { spots ->
                    rows += EntityRow("", "${spots.size}", "spot anims", "", spots.joinToString(", ") { "${spotAnimLabel(it.id)} (${it.timeAliveMillis}ms)" }, sub = true)
                }
            }
        }
        if (UIState.showPlayers.value) {
            players.forEach { player ->
                val tile = player.tile
                if (me.getDistance(tile) > range || !hit(player.name, player.serverIndex.toString())) return@forEach
                rows += EntityRow("Player", "${player.serverIndex}", player.name, "${tile.x}, ${tile.y}, ${tile.plane}", "")
                player.spotAnims.takeIf { it.isNotEmpty() }?.let { spots ->
                    rows += EntityRow("", "${spots.size}", "spot anims", "", spots.joinToString(", ") { "${spotAnimLabel(it.id)} (${it.timeAliveMillis}ms)" }, sub = true)
                }
            }
        }
        if (UIState.showSpotAnims.value) {
            spotAnims.forEach { spot ->
                val tile = spot.tile
                if (me.getDistance(tile) > range || !hit(spot.id.toString())) return@forEach
                rows += EntityRow("Spot anim", "${spot.id}", spotAnimName(spot.id) ?: "Spot anim ${spot.id}", "${tile.x}, ${tile.y}, ${tile.plane}", "")
            }
        }
        if (UIState.showProjectiles.value) {
            projectiles.forEach { projectile ->
                val tile = projectile.tile
                if (me.getDistance(tile) > range || !hit(projectile.id.toString())) return@forEach
                rows += EntityRow("Projectile", "${projectile.id}", spotAnimName(projectile.id) ?: "Projectile ${projectile.id}",
                    "${tile.x}, ${tile.y}, ${tile.plane}", "target ${projectile.lockedToServerIndex}")
            }
        }
        if (UIState.showGroundItems.value) {
            groundItems.forEach { item ->
                val tile = item.tile
                val name = item.name
                if (me.getDistance(tile) > range || !hit(name, item.id.toString())) return@forEach
                rows += EntityRow("Ground item", "${item.id}", if (item.amount > 1) "$name x${item.amount}" else name,
                    "${tile.x}, ${tile.y}, ${tile.plane}", item.groundOps.filterNotNull().joinToString(", "))
            }
        }
        EntitySnapshot(true, rows.take(MAX_ROWS))
    }
}

private val outlineModes = listOf("Off", "Solid", "Outline", "Glow")

@Composable
fun EntitiesScreen() {
    val snapshot = EntitiesModel.feed.value
    val tracking = listOf(
        "Scene objects" to UIState.showSceneObjects,
        "NPCs" to UIState.showNpcs,
        "Players" to UIState.showPlayers,
        "Spot animations" to UIState.showSpotAnims,
        "Projectiles" to UIState.showProjectiles,
        "Ground items" to UIState.showGroundItems,
        "Clickboxes" to UIState.showClickboxes,
    )
    ScreenScroll {
        Section("Track", note = "What to label in the world. Only tracked kinds are listed below.") {
            ChipGroup(tracking)
            SettingRow("Range", "How far from you to look, in tiles.") {
                IntSlider(UIState.entityRange.value, 1..100, { UIState.entityRange.value = it }, suffix = " tiles")
            }
        }
        Section("Appearance") {
            SettingRow("Label colour") {
                ColorField(
                    Color(UIState.entityTextColorR.value, UIState.entityTextColorG.value, UIState.entityTextColorB.value, UIState.entityTextColorA.value),
                    {
                        UIState.entityTextColorR.value = it.red; UIState.entityTextColorG.value = it.green
                        UIState.entityTextColorB.value = it.blue; UIState.entityTextColorA.value = it.alpha
                    },
                    withAlpha = true,
                )
            }
            SettingRow("Clickbox colour") {
                ColorField(Color(UIState.clickboxColorR.value, UIState.clickboxColorG.value, UIState.clickboxColorB.value), {
                    UIState.clickboxColorR.value = it.red; UIState.clickboxColorG.value = it.green; UIState.clickboxColorB.value = it.blue
                })
            }
            SettingRow("Clickbox style") {
                Dropdown(UIState.clickboxOutlineMode.value, outlineModes.indices.toList(), { UIState.clickboxOutlineMode.value = it }, width = 160.dp) { outlineModes[it] }
            }
            SettingRow("Clickbox intensity") {
                IntSlider(UIState.clickboxIntensity.value, 0..255, { UIState.clickboxIntensity.value = it })
            }
        }
        Section(
            "Nearby",
            note = when {
                snapshot == null -> null
                !snapshot.loggedIn -> "Log in to see what is around you."
                else -> "${snapshot.rows.count { !it.sub }} within ${UIState.entityRange.value} tiles"
            },
            actions = { SearchField(UIState.entitySearchText, "Name or id", 200.dp) },
        ) {
            DataTable(
                listOf(TableColumn("Kind", width = 96.dp), TableColumn("Id", width = 70.dp, mono = true), TableColumn("Name", 1.4f), TableColumn("Tile", width = 130.dp, mono = true), TableColumn("Options", 2f)),
                snapshot?.rows.orEmpty(),
                emptyText = if (tracking.none { it.second.value }) "Pick what to track above." else "Nothing nearby matches.",
            ) { row ->
                if (row.sub) text("") else cell { Pill(row.kind, kindColor(row.kind)) }
                text(row.id)
                text(row.name, if (row.sub) Palette.muted else null)
                text(row.location)
                text(row.detail, Palette.muted)
            }
        }
    }
}

private fun kindColor(kind: String) = when (kind) {
    "NPC" -> Color(0xFFF7D154)
    "Player" -> Color(0xFF6FA8F0)
    "Object" -> Color(0xFF55C48A)
    "Ground item" -> Palette.amber
    else -> Palette.muted
}

// ---------- Collision ----------

@Composable
fun CollisionScreen() {
    ScreenScroll {
        Section("Overlay", note = "Paints the live pathfinder collision and the map's render flags on the scene, in the overworld and in instances.") {
            ToggleRow("Show collision", UIState.collisionOverlayEnabled)
            SettingRow("Radius", "Tiles around you to paint.") {
                IntSlider(UIState.collisionOverlayRadius.value, 4..48, { UIState.collisionOverlayRadius.value = it }, suffix = " tiles")
            }
        }
        Section("Collision flags") {
            ChipGroup(
                listOf(
                    "Walls" to UIState.collisionShowWalls,
                    "Block walk" to UIState.collisionShowBlockWalk,
                    "No floor / blocked ground" to UIState.collisionShowWater,
                    "Floor decoration" to UIState.collisionShowFloorDecoration,
                    "Projectile blockers" to UIState.collisionShowProjectile,
                    "Route blockers" to UIState.collisionShowRouteBlocker,
                    "Block NPCs" to UIState.collisionShowBlockNpc,
                    "Block players" to UIState.collisionShowBlockPlayer,
                    "Roof" to UIState.collisionShowRoof,
                ),
            )
        }
        Section("Render flags", note = "Bits of each tile's settings byte.") {
            ChipGroup(
                listOf(
                    "Clipped / blocked  0x1" to UIState.renderShowClipped,
                    "Bridge / lower objects  0x2" to UIState.renderShowLowerObjects,
                    "Under roof  0x4" to UIState.renderShowUnderRoof,
                    "Force lowest level  0x8" to UIState.renderShowForceBottom,
                    "Roof  0x10" to UIState.renderShowRoof,
                    "0x20" to UIState.renderShowFlag20,
                    "0x40" to UIState.renderShowFlag40,
                    "0x80" to UIState.renderShowFlag80,
                ),
            )
        }
    }
}

// ---------- Tile markers ----------

object TileMarkersModel {
    const val BULK_LIMIT = 4096L
    val groups = Feed(250) { TileMarkerStore.groups() }

    fun changed() = groups.invalidate()

    fun useMyTile(onTile: (Int, Int) -> Unit) = GameThread.post {
        runCatching { localPlayer.tile }.getOrNull()?.let { onTile(it.x, it.y) }
    }

    fun addRectangle() = GameThread.post {
        val group = TileMarkerState.activeGroup.value
        if (group.isBlank()) return@post
        val s = TileMarkerState
        if (TileMarkerStore.rectArea(s.bulkX1.value, s.bulkY1.value, s.bulkX2.value, s.bulkY2.value) > BULK_LIMIT) return@post
        val plane = runCatching { localPlayer.tile.plane }.getOrDefault(0)
        TileMarkerStore.addRect(group, s.bulkX1.value, s.bulkY1.value, s.bulkX2.value, s.bulkY2.value, plane, color = null)
        changed()
    }
}

@Composable
fun TileMarkersScreen() {
    val groups = TileMarkersModel.groups.value.orEmpty()
    val state = TileMarkerState
    if (state.activeGroup.value !in groups.map { it.name } && groups.isNotEmpty()) state.activeGroup.value = groups.first().name
    val newName = remember { OverlayText.of(state.newGroupName, 64) }

    ScreenScroll {
        Section(
            "Marking tool",
            note = "While on, left-clicking a tile adds or removes it, and the game ignores the click.",
            actions = {
                IconButton(Glyph.ChevronLeft, { TileMarkerStore.undo(); TileMarkersModel.changed() }, tint = if (TileMarkerStore.canUndo()) Palette.muted else Palette.faint)
                IconButton(Glyph.ChevronRight, { TileMarkerStore.redo(); TileMarkersModel.changed() }, tint = if (TileMarkerStore.canRedo()) Palette.muted else Palette.faint)
            },
        ) {
            ToggleRow("Marking tool", state.toolActive)
            SettingRow("Marker colour", "Used for new groups and for Recolour.") {
                ColorField(Color(state.colorR.value, state.colorG.value, state.colorB.value), {
                    state.colorR.value = it.red; state.colorG.value = it.green; state.colorB.value = it.blue
                })
            }
            SettingRow("Active group", "New marks go here.") {
                if (groups.isEmpty()) Hint("Create a group first.")
                else Dropdown(state.activeGroup.value, groups.map { it.name }, { state.activeGroup.value = it }, width = 200.dp)
            }
            SettingRow("Group name") {
                TextInput(newName, "New group name", 180.dp)
                ActionButton("Add", {
                    val name = state.newGroupName.value.trim()
                    if (TileMarkerStore.addGroup(name, state.activeColor())) {
                        state.activeGroup.value = name
                        state.newGroupName.value = ""
                        TileMarkersModel.changed()
                    }
                }, icon = Glyph.Plus, height = 34.dp)
                ActionButton("Rename active", {
                    val to = state.newGroupName.value.trim()
                    if (TileMarkerStore.renameGroup(state.activeGroup.value, to)) {
                        state.activeGroup.value = to
                        state.newGroupName.value = ""
                        TileMarkersModel.changed()
                    }
                }, height = 34.dp)
            }
        }
        if (groups.isEmpty()) {
            EmptyState("No groups yet", "Marks live in a group. Name one above and add it to start marking.")
        }
        groups.forEach { MarkerGroupCard(it, it.name == state.activeGroup.value) }
        BulkAddCard()
    }
}

@Composable
private fun MarkerGroupCard(group: MarkerGroup, active: Boolean) {
    val state = TileMarkerState
    Section(
        group.name,
        note = "${group.markers.size} tile(s)" + if (active) " · active group" else "",
        actions = {
            Toggle(group.enabled) { TileMarkerStore.setEnabled(group.name, it); TileMarkersModel.changed() }
            if (!active) ActionButton("Make active", { state.activeGroup.value = group.name }, height = 30.dp)
            ActionButton("Recolour", { TileMarkerStore.recolorGroup(group.name, state.activeColor()); TileMarkersModel.changed() }, height = 30.dp)
            ActionButton("Clear", { TileMarkerStore.clearGroup(group.name); TileMarkersModel.changed() }, height = 30.dp)
            IconButton(Glyph.Trash, { TileMarkerStore.removeGroup(group.name); TileMarkersModel.changed() }, activeTint = Palette.stop, size = 30.dp)
        },
    ) {
        if (group.markers.isEmpty()) {
            Hint("No tiles yet. Turn on the marking tool, or add a rectangle below.")
            return@Section
        }
        DataTable(
            listOf(TableColumn("", width = 30.dp), TableColumn("Tile", width = 150.dp, mono = true), TableColumn("Label", 1f), TableColumn("", width = 150.dp)),
            group.markers.sortedWith(compareBy({ it.plane }, { it.x }, { it.y })),
            key = { it.key },
        ) { marker -> MarkerRow(group, marker) }
    }
}

@Composable
private fun TableRowScope.MarkerRow(group: MarkerGroup, marker: TileMarker) {
    val label = remember(group.name, marker.key) {
        OverlayText(
            read = { TileMarkerStore.groups().firstOrNull { it.name == group.name }?.markers?.firstOrNull { it.key == marker.key }?.label.orEmpty() },
            write = { TileMarkerStore.setLabel(group.name, marker.key, it); TileMarkersModel.changed() },
            maxLength = 64,
        )
    }
    cell { Swatch((marker.color ?: group.color).fromImGuiColor()) }
    text("${marker.x}, ${marker.y}, ${marker.plane}")
    cell { TextInput(label, "Label", 200.dp) }
    cell {
        ActionButton("Recolour", { TileMarkerStore.recolorMarker(group.name, marker.key, TileMarkerState.activeColor()); TileMarkersModel.changed() }, height = 28.dp)
        IconButton(Glyph.Trash, { TileMarkerStore.removeMarker(group.name, marker.key); TileMarkersModel.changed() }, activeTint = Palette.stop)
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color).border(1.dp, Palette.lineStrong, RoundedCornerShape(4.dp)))
}

@Composable
private fun BulkAddCard() {
    val s = TileMarkerState
    val area = TileMarkerStore.rectArea(s.bulkX1.value, s.bulkY1.value, s.bulkX2.value, s.bulkY2.value)
    Section("Add a rectangle", note = "Marks every tile between two corners into the active group, on your current plane.") {
        CornerRow("Corner A", s.bulkX1.value, s.bulkY1.value, { s.bulkX1.value = it }, { s.bulkY1.value = it })
        CornerRow("Corner B", s.bulkX2.value, s.bulkY2.value, { s.bulkX2.value = it }, { s.bulkY2.value = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (area > TileMarkersModel.BULK_LIMIT) {
                Hint("$area tiles is over the ${TileMarkersModel.BULK_LIMIT} limit.", color = Palette.stop)
            } else {
                ActionButton("Add $area tile(s)", { TileMarkersModel.addRectangle() }, tone = ButtonTone.Primary, icon = Glyph.Plus)
            }
        }
    }
}

@Composable
private fun CornerRow(label: String, x: Int, y: Int, onX: (Int) -> Unit, onY: (Int) -> Unit) {
    SettingRow(label) {
        NumberInput(x, onX, 0..16383, width = 140.dp)
        NumberInput(y, onY, 0..16383, width = 140.dp)
        ActionButton("Use my tile", { TileMarkersModel.useMyTile { tx, ty -> onX(tx); onY(ty) } }, icon = Glyph.Crosshair, height = 34.dp)
    }
}
