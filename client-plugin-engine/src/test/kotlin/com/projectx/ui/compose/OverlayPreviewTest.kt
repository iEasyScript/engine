package com.projectx.ui.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestStep
import com.projectx.quest.editor.QuestActionFactory
import com.projectx.quest.editor.QuestConditionFactory
import com.projectx.quest.editor.QuestEditorState
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.InfoDisplayConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptMetadata
import com.projectx.script.StringConfigItem
import com.projectx.ui.ScriptSource
import com.projectx.ui.UIState
import com.projectx.ui.compose.library.DetailTab
import com.projectx.ui.compose.library.LibraryEntry
import com.projectx.ui.compose.library.LibraryModel
import com.projectx.util.EngineLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.projectx.core.game.skill.Skill
import java.io.File
import kotlin.test.Test

/**
 * Renders every page of the overlay offscreen with sample data, the way the client does, so a layout can be looked
 * at without injecting and a page that throws while composing fails here instead. Images land in
 * build/compose-preview.
 */
class OverlayPreviewTest {
    private class Agility : Script() { override suspend fun loop() {} }
    private class Archaeology : Script() { override suspend fun loop() {} }
    private class Divination : Script() { override suspend fun loop() {} }
    private class Gates : Script() { override suspend fun loop() {} }

    private enum class Station { Workbench, Fletcher, Crafter, Brazier }

    private class Portables : Script(), ConfigurableScript {
        private val station = EnumConfigItem("Station", "The portable to work at.", Station.entries.toTypedArray(), Station.Crafter)
        private val preset = IntConfigItem("Bank preset", "The preset to load when out of materials. 0 loads the last one.", 0, 0, 9)
        private val stopWhenOut = BooleanConfigItem("Stop when the bank runs dry", "Stop once a preset restocks nothing.", true)
        private val advanced = ConfigSection("Advanced")
        private val note = StringConfigItem("Note", "Shown in the log when the run starts.", "Crafter at Fort")
        private val loads = InfoDisplayConfigItem("Loads this run", "", "14")
        override suspend fun loop() {}
    }

    private fun entry(type: Class<out Script>, name: String, version: String, category: ScriptCategory, description: String, running: Boolean = false, favorite: Boolean = false) =
        LibraryEntry(ScriptMetadata(type, name, version, "Cryptic", description), category, ScriptSource.OFFICIAL, running, favorite,
            if (running) System.currentTimeMillis() - 2_537_000 else null)

    @Test
    fun renderEveryPage() {
        LibraryModel.entries = listOf(
            entry(Portables::class.java, "Portables", "1.5.0", ScriptCategory.CRAFTING,
                "Works a portable station - workbench, fletcher, range, well, crafter or brazier - at any of its jobs, " +
                    "restocking from a bank preset. Stand where the station and a bank are both in reach.", running = true),
            entry(Archaeology::class.java, "AIO Archaeology", "2.4.1", ScriptCategory.ARCHAEOLOGY, "Excavates any dig site.", favorite = true),
            entry(Agility::class.java, "AIO Agility", "1.1.0", ScriptCategory.AGILITY, "Runs the best agility course for your level."),
            entry(Divination::class.java, "AIO Divination", "1.1.0", ScriptCategory.DIVINATION, "Harvests wisps and converts memories."),
            entry(Gates::class.java, "Gates of Elidinis", "3.2.0", ScriptCategory.BOSSES, "Kills the Gates of Elidinis."),
        )
        UIState.xpData[Skill.CRAFTING] = System.currentTimeMillis() - 1_800_000 to 184_220
        UIState.xpData[Skill.PRAYER] = System.currentTimeMillis() - 900_000 to 21_040
        listOf(
            "[2026-09-23 19:05:11.543] [Engine] commit 13c837f0db pid 31796",
            "[2026-09-23 19:05:24.062] [Overlay] This bootstrap has no mouse wheel export",
            "[2026-09-23 19:06:13.796] java.lang.NullPointerException",
            "[2026-09-23 19:06:13.796] \tat com.projectx.game.memory.NativeAccess.readByte(NativeAccess.kt:211)",
            "[2026-09-23 19:07:02.110] [Portables] Crafter at a Crafter for CRAFTING",
        ).forEach { EngineLog.lines += it }

        for (page in Page.entries) {
            OverlayNavigation.open(page)
            OverlayModels.refreshPage(page)
            render(page.name.lowercase())
        }

        OverlayNavigation.open(Page.Library)
        LibraryModel.selectedId = Portables::class.java.name
        LibraryModel.detailTab = DetailTab.Settings
        render("library-settings")
        LibraryModel.detailTab = DetailTab.Overview

        QuestEditorState.beginEdit(
            Quest(
                slug = "cooks-assistant",
                name = "Cook's Assistant",
                steps = listOf(
                    QuestStep(
                        title = "Talk to the Cook",
                        text = "Speak to the Cook in the kitchen of Lumbridge Castle.",
                        actions = listOf(QuestActionFactory.direction(3208, 3214), QuestActionFactory.conversationHighlight()),
                        postconditions = listOf(QuestConditionFactory.inventoryContains()),
                    ),
                    QuestStep(title = "Collect an egg"),
                    QuestStep(title = "Collect a bucket of milk"),
                ),
            ),
        )
        OverlayNavigation.open(Page.Quests)
        render("quest-editor")
        QuestEditorState.discard()
    }

    private fun render(name: String) {
        val scene = ImageComposeScene(1400, 900, Density(1f)) { OverlayApp() }
        try {
            scene.constraints = Constraints.fixed(1040, 680)
            scene.render(0)
            scene.render(500_000_000)
            val image = scene.render(1_000_000_000)
            val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(1040, 680)) }
            image.readPixels(bitmap, 0, 0)
            val cropped = Image.makeFromBitmap(bitmap)
            val out = File("build/compose-preview/$name.png").apply { parentFile.mkdirs() }
            out.writeBytes(cropped.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
