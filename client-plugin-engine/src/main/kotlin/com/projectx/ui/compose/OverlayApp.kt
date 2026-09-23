package com.projectx.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.projectx.BuildInfo
import com.projectx.ui.compose.components.Divider
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.PillTabs
import com.projectx.ui.compose.components.animatedColor
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.library.LibraryModel
import com.projectx.ui.compose.library.LibraryScreen
import com.projectx.ui.compose.screens.CollisionScreen
import com.projectx.ui.compose.screens.Cs2TraceScreen
import com.projectx.ui.compose.screens.EffectsScreen
import com.projectx.ui.compose.screens.EntitiesScreen
import com.projectx.ui.compose.screens.FarmingScreen
import com.projectx.ui.compose.screens.InputRecordingScreen
import com.projectx.ui.compose.screens.InterfacesScreen
import com.projectx.ui.compose.screens.InventionScreen
import com.projectx.ui.compose.screens.InventoryScreen
import com.projectx.ui.compose.screens.LogsScreen
import com.projectx.ui.compose.screens.PacketLogScreen
import com.projectx.ui.compose.screens.QuestsScreen
import com.projectx.ui.compose.screens.SettingsScreen
import com.projectx.ui.compose.screens.StoreScreen
import com.projectx.ui.compose.screens.TileMarkersScreen
import com.projectx.ui.compose.screens.VariablesScreen
import com.projectx.ui.compose.screens.XpScreen
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.OverlayTheme
import com.projectx.ui.compose.theme.Palette
import org.jetbrains.skia.Image as SkiaImage

private val shape = RoundedCornerShape(14.dp)

@Composable
fun OverlayApp() {
    OverlayTheme {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Palette.ground)
                .border(1.dp, Palette.line, shape)
                .pointerInput(Unit) {
                    // Sees every press first, so a click anywhere but the focused field ends typing into it.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        OverlayKeyboard.blurUnlessInside(down.position.x, down.position.y, "panel")
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Header()
                Divider()
                val section = OverlayNavigation.section
                if (section.pages.size > 1) {
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
                        PillTabs(section.pages, OverlayNavigation.page, { OverlayNavigation.open(it) }, { it.label }, { if (it == Page.Library) LibraryModel.entries.size else null })
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { PageContent(OverlayNavigation.page) }
            }
            ResizeGrip(Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun PageContent(page: Page) = when (page) {
    Page.Library -> LibraryScreen()
    Page.Store -> StoreScreen()
    Page.Quests -> QuestsScreen()
    Page.Xp -> XpScreen()
    Page.Inventory -> InventoryScreen()
    Page.Effects -> EffectsScreen()
    Page.Invention -> InventionScreen()
    Page.Farming -> FarmingScreen()
    Page.Entities -> EntitiesScreen()
    Page.Collision -> CollisionScreen()
    Page.TileMarkers -> TileMarkersScreen()
    Page.Logs -> LogsScreen()
    Page.PacketLog -> PacketLogScreen()
    Page.InputRecording -> InputRecordingScreen()
    Page.Variables -> VariablesScreen()
    Page.Interfaces -> InterfacesScreen()
    Page.Cs2Trace -> Cs2TraceScreen()
    Page.Settings -> SettingsScreen()
}

@Composable
private fun Header() {
    val type = LocalType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                // Anything in the header a control has not claimed moves the panel; the host follows the mouse
                // until the button is released.
                awaitEachGesture {
                    awaitFirstDown()
                    ComposeOverlay.beginDrag()
                }
            }
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Brand()
        Spacer(Modifier.width(24.dp))
        Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Section.entries.forEach { NavTab(it.label, OverlayNavigation.section == it) { OverlayNavigation.section = it } }
        }
        Spacer(Modifier.weight(1f))
        val running = LibraryModel.runningCount
        if (running > 0) Pill("$running running", Palette.running, dot = true) else Pill("Idle", Palette.faint)
        Spacer(Modifier.width(12.dp))
        BasicText(engineVersion(), style = type.dataSmall.copy(color = Palette.faint))
        Spacer(Modifier.width(8.dp))
        IconButton(Glyph.Close, { ComposeOverlay.hide() }, size = 30.dp)
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Logo.bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
        BasicText("Project X", style = LocalType.current.heading)
    }
}

@Composable
private fun NavTab(label: String, active: Boolean, onClick: () -> Unit) {
    val hover = rememberHover()
    Box(
        Modifier
            .fillMaxHeight()
            .press(hover, onClick)
            .drawBehind {
                if (active) drawRect(Palette.amber, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = LocalType.current.label.copy(color = animatedColor(if (active || hover.hovered) Palette.text else Palette.muted)))
    }
}

@Composable
private fun ResizeGrip(modifier: Modifier) {
    val hover = rememberHover()
    Box(
        modifier
            .size(18.dp)
            .press(hover) {}
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    ComposeOverlay.beginResize()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Resize, if (hover.hovered) Palette.muted else Palette.faint, 10.dp)
    }
}

private val versionPattern = Regex("""(\d+\.\d+\.\d+)""")

private fun engineVersion(): String {
    val build = runCatching { BuildInfo.VERSION }.getOrDefault("")
    return versionPattern.find(build.substringBefore('@'))?.value?.let { "v$it" } ?: "dev build"
}

private const val LOGO_PATH = "/icons/logo_alpha.png"

private object Logo {
    val bitmap: ImageBitmap? by lazy {
        runCatching {
            val bytes = Logo::class.java.getResourceAsStream(LOGO_PATH)!!.use { it.readBytes() }
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
}
