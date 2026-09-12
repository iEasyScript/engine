package com.projectx.ui.backend.dsl.utils

import com.projectx.ui.backend.dsl.utils.ImGuiColors.hex
import com.projectx.ui.backend.native.NativeBridge

/**
 * The overlay's global look, pushed for the frame and popped after it.
 *
 * Held as data rather than a run of calls so the pops are derived from the pushes - an unbalanced style stack
 * corrupts every window drawn after it, and a hand-counted pop is one forgotten line away from that.
 */
object ModernTheme {
    private val colors: List<Pair<ImGuiCol, Int>> = listOf(
        ImGuiCol.Text to ImGuiColors.TEXT_PRIMARY,
        ImGuiCol.TextDisabled to ImGuiColors.TEXT_DISABLED,

        ImGuiCol.WindowBg to hex("#120C0D"),
        ImGuiCol.ChildBg to hex("#1B121426"),
        ImGuiCol.PopupBg to hex("#150E0F"),
        ImGuiCol.Border to hex("#6D4A4C80"),
        ImGuiCol.BorderShadow to hex("#00000040"),

        ImGuiCol.FrameBg to hex("#241719"),
        ImGuiCol.FrameBgHovered to hex("#4A3234"),
        ImGuiCol.FrameBgActive to hex("#5C3C3E"),

        ImGuiCol.TitleBg to hex("#1B1214"),
        ImGuiCol.TitleBgActive to hex("#452D2F"),
        ImGuiCol.TitleBgCollapsed to hex("#120C0D"),

        ImGuiCol.MenuBarBg to hex("#24171960"),

        ImGuiCol.ScrollbarBg to hex("#100A0B55"),
        ImGuiCol.ScrollbarGrab to hex("#8A2F0C"),
        ImGuiCol.ScrollbarGrabHovered to hex("#E4611D"),
        ImGuiCol.ScrollbarGrabActive to hex("#FFAB61"),

        ImGuiCol.CheckMark to ImGuiColors.ACCENT_PRIMARY,
        ImGuiCol.SliderGrab to ImGuiColors.ACCENT_PRIMARY,
        ImGuiCol.SliderGrabActive to ImGuiColors.ACCENT_PRIMARY_HOVER,

        ImGuiCol.Button to hex("#452D2F"),
        ImGuiCol.ButtonHovered to hex("#5C3C3E"),
        ImGuiCol.ButtonActive to hex("#6D4A4C"),

        ImGuiCol.Header to hex("#24171933"),
        ImGuiCol.HeaderHovered to hex("#FF8A3D25"),
        ImGuiCol.HeaderActive to hex("#FF8A3D18"),

        ImGuiCol.Separator to ImGuiColors.BORDER_DEFAULT,
        ImGuiCol.SeparatorHovered to hex("#E4611D55"),
        ImGuiCol.SeparatorActive to ImGuiColors.ACCENT_PRIMARY,

        ImGuiCol.ResizeGrip to hex("#6D4A4C33"),
        ImGuiCol.ResizeGripHovered to hex("#E4611D55"),
        ImGuiCol.ResizeGripActive to ImGuiColors.ACCENT_PRIMARY,

        // Tabs read as tabs only if the strip separates from the panel and the active one is unmistakable, so
        // these stay opaque and the active overline runs at the same full accent as a checkmark or slider grab.
        ImGuiCol.Tab to hex("#1B1214"),
        ImGuiCol.TabHovered to hex("#5C3C3E"),
        ImGuiCol.TabSelected to hex("#452D2F"),
        ImGuiCol.TabSelectedOverline to ImGuiColors.ACCENT_PRIMARY,
        ImGuiCol.TabDimmed to hex("#180F11"),
        ImGuiCol.TabDimmedSelected to hex("#3A2527"),
        ImGuiCol.TabDimmedSelectedOverline to hex("#E4611D"),

        ImGuiCol.TableHeaderBg to hex("#452D2F44"),
        ImGuiCol.TableBorderStrong to ImGuiColors.BORDER_DEFAULT,
        ImGuiCol.TableBorderLight to hex("#5C3C3E40"),
        ImGuiCol.TableRowBg to hex("#24171933"),
        ImGuiCol.TableRowBgAlt to hex("#24171955"),

        ImGuiCol.TextSelectedBg to hex("#FF8A3D33"),
        ImGuiCol.DragDropTarget to hex("#FF8A3D55"),
        ImGuiCol.NavWindowingHighlight to hex("#FF8A3D55"),
        ImGuiCol.NavWindowingDimBg to ImGuiColors.OVERLAY_DARK,
        ImGuiCol.ModalWindowDimBg to ImGuiColors.OVERLAY_DARK,
    )

    private val floatVars: List<Pair<ImGuiStyleVar, Float>> = listOf(
        ImGuiStyleVar.Alpha to 1.0f,
        ImGuiStyleVar.WindowRounding to 2f,
        ImGuiStyleVar.WindowBorderSize to 2f,
        ImGuiStyleVar.ChildRounding to 1f,
        ImGuiStyleVar.ChildBorderSize to 1f,
        ImGuiStyleVar.PopupRounding to 2f,
        ImGuiStyleVar.PopupBorderSize to 1f,
        ImGuiStyleVar.FrameRounding to 2f,
        ImGuiStyleVar.FrameBorderSize to 1f,
        ImGuiStyleVar.IndentSpacing to 20f,
        ImGuiStyleVar.ScrollbarSize to 14f,
        ImGuiStyleVar.ScrollbarRounding to 2f,
        ImGuiStyleVar.GrabMinSize to 12f,
        ImGuiStyleVar.GrabRounding to 2f,
        ImGuiStyleVar.TabRounding to 2f,
        ImGuiStyleVar.TabBarBorderSize to 1f,
        ImGuiStyleVar.TabBarOverlineSize to 3f,
        ImGuiStyleVar.SeparatorTextBorderSize to 1f,
    )

    private val vec2Vars: List<Triple<ImGuiStyleVar, Float, Float>> = listOf(
        Triple(ImGuiStyleVar.WindowPadding, 12f, 12f),
        Triple(ImGuiStyleVar.FramePadding, 8f, 6f),
        Triple(ImGuiStyleVar.ItemSpacing, 8f, 6f),
        Triple(ImGuiStyleVar.ItemInnerSpacing, 6f, 4f),
        Triple(ImGuiStyleVar.CellPadding, 6f, 4f),
        Triple(ImGuiStyleVar.ButtonTextAlign, 0.5f, 0.5f),
        Triple(ImGuiStyleVar.SelectableTextAlign, 0f, 0.5f),
        Triple(ImGuiStyleVar.SeparatorTextAlign, 0f, 0.5f),
        Triple(ImGuiStyleVar.SeparatorTextPadding, 0f, 6f),
    )

    fun applyModernTheme() {
        with(NativeBridge) {
            colors.forEach { (slot, color) -> pushStyleColor(slot, color) }
            floatVars.forEach { (slot, value) -> pushStyleVarFloat(slot, value) }
            vec2Vars.forEach { (slot, x, y) -> pushStyleVarVec2(slot, x, y) }
        }
    }

    fun removeModernTheme() {
        with(NativeBridge) {
            popStyleColor(colors.size)
            popStyleVar(floatVars.size + vec2Vars.size)
        }
    }

    inline fun <T> withModernTheme(block: () -> T): T {
        applyModernTheme()
        try {
            return block()
        } finally {
            removeModernTheme()
        }
    }
}
