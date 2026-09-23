package com.projectx.ui.compose.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.projectx.script.ComposePanel
import com.projectx.script.LiveValues
import com.projectx.script.Script
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.ProgressBar
import com.projectx.ui.compose.components.Stat
import com.projectx.ui.compose.theme.Palette
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.projectx.game.math.Vector2f
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.dsl.scopes.button
import com.projectx.ui.backend.dsl.scopes.checkbox
import com.projectx.ui.backend.dsl.scopes.collapsingHeader
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.properties
import com.projectx.ui.backend.dsl.scopes.row
import com.projectx.ui.backend.dsl.scopes.sameLine
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.smallButton
import com.projectx.ui.backend.dsl.scopes.tabBar
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.textWrapped
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.rendering.CommandRenderer
import com.projectx.ui.compose.LocalSurface
import com.projectx.ui.setting
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds script windows and world drawing with the real ImGui DSL, reads them back the way the client does, and renders
 * both overlay layers to build/compose-preview - so the translation can be checked without a script or a client.
 */
class HudPreviewTest {
    private val stopWhenOut = setting(true)
    private var clicks = 0

    private fun recordFrame(): List<ImGuiCommand> {
        val commands = mutableListOf<ImGuiCommand>()
        CommandRenderer.withCapture(commands) {
            ImGuiDsl.window("Portables") {
                section("Station")
                text("Crafter  -  CRAFTING")
                text("Preset: last used")
                separator()
                section("Progress")
                text("Status: Using the crafter")
                text("Loads: 14")
                text("XP/hr: 368,217")
                progressBar(0.62f, overlay = "Level 97  (62%)")
                checkbox("Stop when the bank runs dry", stopWhenOut)
                button("Pause") { clicks++ }
                sameLine()
                smallButton("Skip load") { clicks++ }
            }
            ImGuiDsl.window("Gates of Elidinis##gates") {
                properties("gates-stats") {
                    row("Kills") { text("212") }
                    row("Kills/hr") { text("38") }
                    row("Loot") { text("41.2M") }
                }
                collapsingHeader("Drops") {
                    textWrapped("Elidinis' blessing x3, Crystal key x11")
                }
                tabBar("gates-tabs") {
                    tabItem("Phase") { text("Phase 2 - adds up") }
                    tabItem("Timers") { text("Next wave in 4s") }
                }
                text("Warning: low prayer")
            }
            ImGuiDsl.backgroundDrawList {
                rect(Vector2f(400f, 300f), Vector2f(470f, 380f), ImGuiColors.rgba(0, 255, 255), thickness = 2f)
                text(Vector2f(405f, 282f), ImGuiColors.rgba(255, 255, 255), "Portable crafter")
                circleFilled(Vector2f(620f, 420f), 6f, ImGuiColors.rgba(242, 169, 59))
                line(Vector2f(620f, 420f), Vector2f(470f, 340f), ImGuiColors.rgba(242, 169, 59), 2f)
            }
        }
        return commands
    }

    private class Miner : Script(), ComposePanel {
        var ore by mutableStateOf("Runite")
        val xp by live(12_345) { 67_890 }

        override suspend fun loop() {}

        @Composable
        override fun Panel() {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("XP gained", "%,d".format(xp))
                Stat("Ore", ore, color = Palette.running)
            }
            ProgressBar(0.4f, "Level 88  (40%)")
            Dropdown(ore, listOf("Runite", "Orichalcite", "Drakolith"), { ore = it })
            ActionButton("Bank now", {})
        }
    }

    @Test
    fun rendersComposePanels() {
        val miner = Miner()
        assertEquals(12_345, miner.xp)
        LiveValues.refresh(miner)
        assertEquals(67_890, miner.xp, "live values refresh on the game thread")

        HudState.windows = emptyList()
        HudState.world = emptyList()
        HudState.panels = listOf(ScriptPanel("compose:miner", "AIO Mining", miner))
        render("hud-compose-panel") { HudCards() }
        HudState.panels = emptyList()
    }

    @Test
    fun translatesAndRendersScriptWindows() {
        val actions = HudActions()
        val frame = HudParser(recordFrame(), actions).parse()
        assertEquals(listOf("Portables", "Gates of Elidinis"), frame.windows.map { it.title })
        assertTrue(frame.world.size >= 4, "world drawing was recorded")

        // An identical frame reads back equal, which is what keeps an unchanged panel from redrawing.
        assertEquals(frame.windows, HudParser(recordFrame(), HudActions()).parse().windows)

        actions.clicks.entries.first { it.key.contains("Pause") }.value.invoke()
        assertEquals(1, clicks)
        actions.booleans.values.first().invoke(false)
        assertEquals(false, stopWhenOut.value)

        HudState.windows = frame.windows
        HudState.world = frame.world
        HudState.actions = actions
        render("hud-cards") { HudCards() }
        render("hud-world") { WorldLayer() }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            CompositionLocalProvider(LocalSurface provides name) { content() }
        }
        try {
            scene.constraints = Constraints.fixed(1280, 800)
            scene.render(0)
            scene.render(500_000_000)
            val image = scene.render(1_000_000_000)
            // Over a mid-grey standing in for the game, so the transparent layers read as they would in the client.
            val backdrop = Surface.makeRasterN32Premul(1280, 800)
            backdrop.canvas.clear(0xFF3A4150.toInt())
            backdrop.canvas.drawImage(image, 0f, 0f)
            val composited = backdrop.makeImageSnapshot()
            val out = File("build/compose-preview/$name.png").apply { parentFile.mkdirs() }
            out.writeBytes(composited.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
