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

        ImGuiCol.WindowBg to ImGuiColors.BACKGROUND_PRIMARY,
        ImGuiCol.ChildBg to ImGuiColors.BACKGROUND_SECONDARY,
        ImGuiCol.PopupBg to ImGuiColors.BACKGROUND_SECONDARY,
        ImGuiCol.Border to ImGuiColors.BORDER_DEFAULT,
        ImGuiCol.BorderShadow to ImGuiColors.TRANSPARENT,

        ImGuiCol.FrameBg to ImGuiColors.BACKGROUND_SUNK,
        ImGuiCol.FrameBgHovered to ImGuiColors.BACKGROUND_HOVER,
        ImGuiCol.FrameBgActive to ImGuiColors.BACKGROUND_ACTIVE,

        ImGuiCol.TitleBg to ImGuiColors.BACKGROUND_SECONDARY,
        ImGuiCol.TitleBgActive to ImGuiColors.BACKGROUND_TERTIARY,
        ImGuiCol.TitleBgCollapsed to ImGuiColors.BACKGROUND_PRIMARY,

        ImGuiCol.MenuBarBg to ImGuiColors.BACKGROUND_SECONDARY,

        ImGuiCol.ScrollbarBg to ImGuiColors.TRANSPARENT,
        ImGuiCol.ScrollbarGrab to ImGuiColors.SCROLLBAR_GRAB,
        ImGuiCol.ScrollbarGrabHovered to ImGuiColors.SCROLLBAR_GRAB_HOVER,
        ImGuiCol.ScrollbarGrabActive to ImGuiColors.SCROLLBAR_GRAB_ACTIVE,

        ImGuiCol.CheckMark to ImGuiColors.ACCENT_PRIMARY_HOVER,
        ImGuiCol.SliderGrab to ImGuiColors.ACCENT_PRIMARY,
        ImGuiCol.SliderGrabActive to ImGuiColors.ACCENT_PRIMARY_HOVER,

        ImGuiCol.Button to ImGuiColors.BUTTON_DEFAULT,
        ImGuiCol.ButtonHovered to ImGuiColors.BUTTON_HOVER,
        ImGuiCol.ButtonActive to ImGuiColors.BUTTON_ACTIVE,

        ImGuiCol.Header to ImGuiColors.BACKGROUND_TERTIARY,
        ImGuiCol.HeaderHovered to ImGuiColors.BACKGROUND_HOVER,
        ImGuiCol.HeaderActive to hex("#2B9C9240"),

        ImGuiCol.Separator to ImGuiColors.BORDER_DEFAULT,
        ImGuiCol.SeparatorHovered to hex("#2B9C9288"),
        ImGuiCol.SeparatorActive to ImGuiColors.ACCENT_PRIMARY,

        ImGuiCol.ResizeGrip to hex("#41444D55"),
        ImGuiCol.ResizeGripHovered to hex("#2B9C9288"),
        ImGuiCol.ResizeGripActive to ImGuiColors.ACCENT_PRIMARY,

        // Tabs read as tabs only if the strip separates from the panel and the active one is unmistakable, so
        // these stay opaque and the active overline runs at the same full accent as a checkmark or slider grab.
        ImGuiCol.Tab to ImGuiColors.BACKGROUND_SUNK,
        ImGuiCol.TabHovered to ImGuiColors.BACKGROUND_HOVER,
        ImGuiCol.TabSelected to ImGuiColors.BACKGROUND_TERTIARY,
        ImGuiCol.TabSelectedOverline to ImGuiColors.ACCENT_PRIMARY,
        ImGuiCol.TabDimmed to ImGuiColors.BACKGROUND_SUNK,
        ImGuiCol.TabDimmedSelected to ImGuiColors.BACKGROUND_TERTIARY,
        ImGuiCol.TabDimmedSelectedOverline to ImGuiColors.ACCENT_PRIMARY_ACTIVE,

        ImGuiCol.TableHeaderBg to ImGuiColors.BACKGROUND_TERTIARY,
        ImGuiCol.TableBorderStrong to ImGuiColors.BORDER_DEFAULT,
        ImGuiCol.TableBorderLight to hex("#33363E99"),
        ImGuiCol.TableRowBg to ImGuiColors.TRANSPARENT,
        ImGuiCol.TableRowBgAlt to hex("#FFFFFF08"),

        ImGuiCol.TextSelectedBg to hex("#2B9C9255"),
        ImGuiCol.DragDropTarget to hex("#33B1A688"),
        ImGuiCol.NavWindowingHighlight to hex("#33B1A688"),
        ImGuiCol.NavWindowingDimBg to ImGuiColors.OVERLAY_DARK,
        ImGuiCol.ModalWindowDimBg to ImGuiColors.OVERLAY_DARK,
    )

    private val floatVars: List<Pair<ImGuiStyleVar, Float>> = listOf(
        ImGuiStyleVar.Alpha to 1.0f,
        ImGuiStyleVar.WindowRounding to 6f,
        ImGuiStyleVar.WindowBorderSize to 1f,
        ImGuiStyleVar.ChildRounding to 6f,
        ImGuiStyleVar.ChildBorderSize to 1f,
        ImGuiStyleVar.PopupRounding to 6f,
        ImGuiStyleVar.PopupBorderSize to 1f,
        ImGuiStyleVar.FrameRounding to 4f,
        ImGuiStyleVar.FrameBorderSize to 1f,
        ImGuiStyleVar.IndentSpacing to 18f,
        ImGuiStyleVar.ScrollbarSize to 10f,
        ImGuiStyleVar.ScrollbarRounding to 6f,
        ImGuiStyleVar.GrabMinSize to 10f,
        ImGuiStyleVar.GrabRounding to 4f,
        ImGuiStyleVar.TabRounding to 4f,
        ImGuiStyleVar.TabBarBorderSize to 1f,
        ImGuiStyleVar.TabBarOverlineSize to 3f,
        ImGuiStyleVar.SeparatorTextBorderSize to 1f,
    )

    private val vec2Vars: List<Triple<ImGuiStyleVar, Float, Float>> = listOf(
        Triple(ImGuiStyleVar.WindowPadding, 10f, 10f),
        Triple(ImGuiStyleVar.FramePadding, 10f, 5f),
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
