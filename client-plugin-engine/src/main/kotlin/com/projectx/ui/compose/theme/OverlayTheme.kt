package com.projectx.ui.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.projectx.script.ScriptCategory
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/** Ink ground, amber for the one action that matters on a screen, and state colours kept apart from it. */
object Palette {
    val ground = Color(0xF20D1016)
    val surface = Color(0xFF131720)
    val raised = Color(0xFF1A1F2B)
    val hover = Color(0xFF212736)
    val line = Color(0xFF252C3B)
    val lineStrong = Color(0xFF333C4F)

    val text = Color(0xFFE9ECF3)
    val muted = Color(0xFF8C95A8)
    val faint = Color(0xFF5A6377)

    val amber = Color(0xFFF2A93B)
    val amberHover = Color(0xFFF7BD62)
    val onAmber = Color(0xFF1A1206)

    val running = Color(0xFF3FD48E)
    val stop = Color(0xFFF0716C)
}

/** A tint per skill family, so a script reads as combat, gathering or artisan before its name is read. */
fun ScriptCategory.tint(): Color = when (this) {
    ScriptCategory.COMBAT, ScriptCategory.MAGIC, ScriptCategory.PRAYER, ScriptCategory.SUMMONING,
    ScriptCategory.NECROMANCY, ScriptCategory.SLAYER, ScriptCategory.BOSSES -> Color(0xFFE0685F)

    ScriptCategory.MINING, ScriptCategory.FISHING, ScriptCategory.WOODCUTTING, ScriptCategory.FARMING,
    ScriptCategory.HUNTER, ScriptCategory.DIVINATION, ScriptCategory.ARCHAEOLOGY -> Color(0xFF55C48A)

    ScriptCategory.SMITHING, ScriptCategory.HERBLORE, ScriptCategory.COOKING, ScriptCategory.CRAFTING,
    ScriptCategory.FIREMAKING, ScriptCategory.FLETCHING, ScriptCategory.RUNECRAFTING,
    ScriptCategory.CONSTRUCTION, ScriptCategory.INVENTION -> Color(0xFFE6A04A)

    ScriptCategory.AGILITY, ScriptCategory.THIEVING, ScriptCategory.DUNGEONEERING -> Color(0xFF6FA8F0)

    ScriptCategory.QUESTS -> Color(0xFFB48AF0)
    ScriptCategory.OTHER -> Color(0xFF8C95A8)
}

object Fonts {
    val sans: FontFamily by lazy {
        FontFamily(
            font("IBMPlexSans-Regular", FontWeight.Normal),
            font("IBMPlexSans-Medium", FontWeight.Medium),
            font("IBMPlexSans-SemiBold", FontWeight.SemiBold),
            font("IBMPlexSans-Bold", FontWeight.Bold),
        )
    }

    val mono: FontFamily by lazy {
        FontFamily(
            font("IBMPlexMono-Regular", FontWeight.Normal),
            font("IBMPlexMono-Medium", FontWeight.Medium),
        )
    }

    // Read through this class's loader: Compose's resource lookup goes through the thread's context loader,
    // which on the render thread is not the engine's.
    private fun font(name: String, weight: FontWeight) = Font(
        identity = name,
        data = Fonts::class.java.getResourceAsStream("/fonts/$name.ttf")!!.use { it.readBytes() },
        weight = weight,
    )
}

class OverlayType(
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val eyebrow: TextStyle,
    val data: TextStyle,
    val dataSmall: TextStyle,
)

private fun sans(size: TextUnit, weight: FontWeight, lineHeight: TextUnit, spacing: TextUnit = 0.sp) = TextStyle(
    fontFamily = Fonts.sans, fontSize = size, fontWeight = weight, lineHeight = lineHeight,
    letterSpacing = spacing, color = Palette.text,
)

private fun mono(size: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = Fonts.mono, fontSize = size, fontWeight = weight, lineHeight = size * 1.35f, color = Palette.muted,
)

val LocalType = staticCompositionLocalOf<OverlayType> { error("OverlayTheme is not applied") }

@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    val type = OverlayType(
        title = sans(24.sp, FontWeight.SemiBold, 30.sp, (-0.2).sp),
        heading = sans(15.sp, FontWeight.SemiBold, 20.sp),
        body = sans(13.5.sp, FontWeight.Normal, 20.sp).copy(color = Palette.muted),
        bodyStrong = sans(13.5.sp, FontWeight.Medium, 18.sp),
        label = sans(12.5.sp, FontWeight.Medium, 16.sp),
        eyebrow = sans(10.5.sp, FontWeight.SemiBold, 14.sp, 1.1.sp).copy(color = Palette.faint),
        data = mono(12.5.sp, FontWeight.Medium),
        dataSmall = mono(11.sp, FontWeight.Normal),
    )
    CompositionLocalProvider(LocalType provides type, content = content)
}

/** ImGui packs colours as ABGR; these convert at the boundary so the panel only ever handles [Color]. */
fun Int.fromImGuiColor(): Color =
    Color(red = this and 0xFF, green = (this ushr 8) and 0xFF, blue = (this ushr 16) and 0xFF, alpha = (this ushr 24) and 0xFF)

fun Color.toImGuiColor(): Int = ImGuiColors.rgba(red, green, blue, alpha)
