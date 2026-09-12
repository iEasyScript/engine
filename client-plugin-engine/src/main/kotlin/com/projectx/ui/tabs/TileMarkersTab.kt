package com.projectx.ui.tabs

import com.projectx.markers.MarkerGroup
import com.projectx.markers.TileMarker
import com.projectx.markers.TileMarkerState
import com.projectx.markers.TileMarkerStore
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors

object TileMarkersTab {
    private const val BULK_LIMIT = 4096
    private const val ROW_HEIGHT = 23f
    private const val MAX_VISIBLE_ROWS = 8

    fun ChildScope.render() {
        renderToolSection()
        spacing()
        separator()
        renderGroupsSection()
        spacing()
        separator()
        renderBulkSection()
    }

    private fun ChildScope.renderToolSection() {
        checkbox("Marking tool", TileMarkerState.toolActive)
        sameLine()
        if (TileMarkerStore.canUndo()) { smallButton("Undo") { TileMarkerStore.undo() }; sameLine() }
        if (TileMarkerStore.canRedo()) { smallButton("Redo") { TileMarkerStore.redo() }; sameLine() }
        newLine()
        hint("Left-click a tile to add or remove it. The game ignores clicks while the tool is on.")
        colorEdit3("Marker color", TileMarkerState.colorR, TileMarkerState.colorG, TileMarkerState.colorB)
    }

    private fun ChildScope.renderGroupsSection() {
        val groups = TileMarkerStore.groups()
        val total = groups.sumOf { it.markers.size }
        text(if (groups.isEmpty()) "No groups yet" else "${groups.size} group(s) · $total marker(s)")

        if (groups.isEmpty()) {
            hint("Marks live in a group, so create one to begin.")
            groupControls()
            return
        }

        val names = groups.map { it.name }
        if (TileMarkerState.activeGroup.value !in names) TileMarkerState.activeGroup.value = names.first()
        val idx = names.indexOf(TileMarkerState.activeGroup.value).coerceAtLeast(0)
        combo("Active group (new marks go here)", idx, names) { TileMarkerState.activeGroup.value = names[it] }

        groupControls()
        spacing()
        groups.forEach { renderGroup(it) }
    }

    private fun ChildScope.groupControls() {
        itemWidth(160f) { inputText("##groupname", TileMarkerState.newGroupName) }
        sameLine()
        smallButton("Add group") {
            val name = TileMarkerState.newGroupName.value.trim()
            if (TileMarkerStore.addGroup(name, TileMarkerState.activeColor())) {
                TileMarkerState.activeGroup.value = name
                TileMarkerState.newGroupName.value = ""
            }
        }
        sameLine()
        smallButton("Rename selected") {
            val to = TileMarkerState.newGroupName.value
            if (TileMarkerStore.renameGroup(TileMarkerState.activeGroup.value, to)) {
                TileMarkerState.activeGroup.value = to.trim()
                TileMarkerState.newGroupName.value = ""
            }
        }
    }

    // Header label kept constant (group name only) so its child-window id stays stable across add/remove.
    private fun ChildScope.renderGroup(group: MarkerGroup) {
        collapsingHeader("${group.name}##grp-${group.name}") {
            val active = group.name == TileMarkerState.activeGroup.value
            text("${group.markers.size} tile(s)" + if (active) "   - active group" else "")
            if (!active) {
                sameLine()
                smallButton("Make active##${group.name}") { TileMarkerState.activeGroup.value = group.name }
            }

            swatch("grp-${group.name}", group.color) { TileMarkerState.loadPickerColor(group.color) }
            sameLine()
            checkbox("Visible##${group.name}", group.enabled) { TileMarkerStore.setEnabled(group.name, it) }
            sameLine()
            smallButton("Apply color##${group.name}") { TileMarkerStore.recolorGroup(group.name, TileMarkerState.activeColor()) }
            sameLine()
            smallButton("Clear##${group.name}") { TileMarkerStore.clearGroup(group.name) }
            sameLine()
            smallButton("Delete##${group.name}") { TileMarkerStore.removeGroup(group.name) }

            if (group.markers.isEmpty()) {
                hint("No tiles yet - use the marking tool or bulk add below.")
                return@collapsingHeader
            }
            val rows = group.markers.size.coerceAtMost(MAX_VISIBLE_ROWS)
            child("markers-${group.name}", 0f, rows * ROW_HEIGHT + 6f) {
                group.markers
                    .sortedWith(compareBy({ it.plane }, { it.x }, { it.y }))
                    .forEach { renderMarkerRow(group, it) }
            }
        }
    }

    private fun ChildScope.renderMarkerRow(group: MarkerGroup, m: TileMarker) {
        swatch("mk-${group.name}-${m.key}", m.color ?: group.color) {
            TileMarkerState.loadPickerColor(m.color ?: group.color)
        }
        sameLine()
        text("(${m.x}, ${m.y}, ${m.plane})")
        sameLine()
        itemWidth(150f) {
            inputText("##lbl-${group.name}-${m.key}", m.label, 64) { TileMarkerStore.setLabel(group.name, m.key, it) }
        }
        sameLine()
        smallButton("Recolor##${group.name}-${m.key}") { TileMarkerStore.recolorMarker(group.name, m.key, TileMarkerState.activeColor()) }
        sameLine()
        smallButton("Remove##${group.name}-${m.key}") { TileMarkerStore.removeMarker(group.name, m.key) }
    }

    private fun ChildScope.renderBulkSection() {
        collapsingHeader("Bulk add rectangle") {
            seedCornersToPlayer()
            hint("Fills every tile in a rectangle into the active group, on your current plane.")
            cornerRow("A", TileMarkerState.bulkX1, TileMarkerState.bulkY1)
            cornerRow("B", TileMarkerState.bulkX2, TileMarkerState.bulkY2)

            val area = TileMarkerStore.rectArea(
                TileMarkerState.bulkX1.value, TileMarkerState.bulkY1.value,
                TileMarkerState.bulkX2.value, TileMarkerState.bulkY2.value,
            )
            if (area > BULK_LIMIT) {
                warn("$area tiles - over the $BULK_LIMIT limit")
            } else {
                hint("$area tile(s)")
                smallButton("Add rectangle") { addRectangle() }
            }
        }
    }

    // Seed default (0,0) corners to the player's tile so a fresh rectangle starts 1x1, not spanning from map origin.
    private fun seedCornersToPlayer() {
        val s = TileMarkerState
        val unset = s.bulkX1.value == 0 && s.bulkY1.value == 0 && s.bulkX2.value == 0 && s.bulkY2.value == 0
        if (!unset) return
        playerTile()?.let {
            s.bulkX1.value = it.first; s.bulkY1.value = it.second
            s.bulkX2.value = it.first; s.bulkY2.value = it.second
        }
    }

    private fun ChildScope.cornerRow(name: String, x: ImGuiState<Int>, y: ImGuiState<Int>) {
        text("Corner $name")
        sameLine()
        itemWidth(70f) { inputInt("##${name}x", x, step = 0, stepFast = 0) }
        sameLine()
        itemWidth(70f) { inputInt("##${name}y", y, step = 0, stepFast = 0) }
        sameLine()
        smallButton("Use my tile##$name") {
            playerTile()?.let { x.value = it.first; y.value = it.second }
        }
    }

    private fun addRectangle() {
        val group = TileMarkerState.activeGroup.value
        if (group.isBlank()) return
        val x1 = TileMarkerState.bulkX1.value; val y1 = TileMarkerState.bulkY1.value
        val x2 = TileMarkerState.bulkX2.value; val y2 = TileMarkerState.bulkY2.value
        if (TileMarkerStore.rectArea(x1, y1, x2, y2) > BULK_LIMIT) return
        TileMarkerStore.addRect(group, x1, y1, x2, y2, playerPlane(), color = null)
    }

    private fun ChildScope.hint(text: String) =
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_SECONDARY) { textWrapped(text) }

    private fun ChildScope.warn(text: String) =
        styleColor(ImGuiCol.Text, ImGuiColors.RED) { textWrapped(text) }

    private fun ChildScope.swatch(id: String, color: Int, onClick: () -> Unit) {
        styleColor(ImGuiCol.Button, color) {
            styleColor(ImGuiCol.ButtonHovered, color) {
                styleColor(ImGuiCol.ButtonActive, color) {
                    button(" ##sw-$id", 16f, 16f, onClick = onClick)
                }
            }
        }
    }

    private fun playerPlane(): Int = runCatching { localPlayer.tile.plane }.getOrDefault(0)

    private fun playerTile(): Pair<Int, Int>? =
        runCatching { val t = localPlayer.tile; t.x to t.y }.getOrNull()
}
