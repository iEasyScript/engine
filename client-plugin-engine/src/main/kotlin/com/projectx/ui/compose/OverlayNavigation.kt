package com.projectx.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Page(val label: String) {
    Library("Library"),
    Store("Store"),
    Quests("Quest helper"),
    Xp("XP"),
    Inventory("Inventory"),
    Effects("Effects"),
    Invention("Invention"),
    Farming("Farming"),
    Entities("Entities"),
    Collision("Collision"),
    TileMarkers("Tile markers"),
    Logs("Logs"),
    PacketLog("Packet log"),
    InputRecording("Input recording"),
    Variables("Variables"),
    Interfaces("Interfaces"),
    Cs2Trace("CS2 trace"),
    Settings("Settings"),
}

enum class Section(val label: String, val pages: List<Page>) {
    Scripts("Scripts", listOf(Page.Library, Page.Store)),
    Quests("Quests", listOf(Page.Quests)),
    Character("Character", listOf(Page.Xp, Page.Inventory, Page.Effects, Page.Invention, Page.Farming)),
    World("World", listOf(Page.Entities, Page.Collision, Page.TileMarkers)),
    Developer("Developer", listOf(Page.Logs, Page.PacketLog, Page.InputRecording, Page.Variables, Page.Interfaces, Page.Cs2Trace)),
    Settings("Settings", listOf(Page.Settings)),
}

/** Where the panel is. Each section remembers its own page, so coming back to one lands where it was left. */
object OverlayNavigation {
    var section by mutableStateOf(Section.Scripts)
    private val pages = mutableStateMapOf<Section, Page>()

    val page: Page get() = pages[section] ?: section.pages.first()

    fun open(page: Page) {
        val owner = Section.entries.first { page in it.pages }
        pages[owner] = page
        section = owner
    }
}
