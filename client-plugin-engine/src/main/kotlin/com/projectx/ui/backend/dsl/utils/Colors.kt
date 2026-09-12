package com.projectx.ui.backend.dsl.utils

object ImGuiColors {
    const val TRANSPARENT = 0x00000000
    
    val BACKGROUND_PRIMARY = hex("#120C0D")
    val BACKGROUND_SECONDARY = hex("#1B1214")
    val BACKGROUND_TERTIARY = hex("#241719")
    val BACKGROUND_HOVER = hex("#332123")
    val BACKGROUND_ACTIVE = hex("#452D2F")

    val TEXT_PRIMARY = hex("#F7ECE7")
    val TEXT_SECONDARY = hex("#D6C0B8")
    val TEXT_DISABLED = hex("#A58A82")
    val TEXT_ACCENT = hex("#FFDCB4")

    val ACCENT_PRIMARY = hex("#FF8A3D")
    val ACCENT_PRIMARY_HOVER = hex("#FFAB61")
    val ACCENT_PRIMARY_ACTIVE = hex("#E4611D")
    val ACCENT_SECONDARY = hex("#FFAB61")
    val ACCENT_SUCCESS = hex("#7FC98A")
    val ACCENT_WARNING = hex("#E8B55A")
    val ACCENT_ERROR = hex("#E04A3A")

    val BORDER_DEFAULT = hex("#332123")
    val BORDER_ACTIVE = hex("#FF8A3D")
    val BORDER_FOCUS = hex("#FFAB61")
    
    val WHITE = rgba(255, 255, 255, 255)
    val BLACK = rgba(0, 0, 0, 255)
    val RED = ACCENT_ERROR
    val GREEN = ACCENT_SUCCESS
    val BLUE = hex("#0099FF")
    val YELLOW = ACCENT_WARNING
    val CYAN = hex("#00FFFF")
    val MAGENTA = hex("#FF00FF")
    
    val GRAY_LIGHTEST = hex("#5C3C3E")
    val GRAY_LIGHTER = hex("#452D2F")
    val GRAY_LIGHT = hex("#332123")
    val GRAY = hex("#241719")
    val GRAY_DARK = hex("#120C0D")
    val GRAY_DARKER = hex("#120C0D")
    val GRAY_DARKEST = hex("#0A0607")

    val BUTTON_DEFAULT = hex("#241719")
    val BUTTON_HOVER = hex("#332123")
    val BUTTON_ACTIVE = hex("#452D2F")
    val BUTTON_PRIMARY = hex("#8A2F0C")
    val BUTTON_PRIMARY_HOVER = hex("#E4611D")
    val BUTTON_PRIMARY_ACTIVE = hex("#6E2409")

    val TAB_ACTIVE = hex("#F7ECE7")
    val TAB_HOVER = hex("#452D2F")
    val TAB_INACTIVE = hex("#241719")

    val SCROLLBAR_BG = hex("#120C0D")
    val SCROLLBAR_GRAB = hex("#452D2F")
    val SCROLLBAR_GRAB_HOVER = hex("#5C3C3E")
    val SCROLLBAR_GRAB_ACTIVE = hex("#6D4A4C")
    
    val ORANGE = hex("#FF8A3D")
    val PURPLE = hex("#B070B0")
    val BROWN = hex("#C08A6E")
    val PINK = hex("#D080A0")
    
    val OVERLAY_LIGHT = withAlpha(WHITE, 20)
    val OVERLAY_DARK = withAlpha(BLACK, 180)
    val ACCENT_PRIMARY_SEMI = withAlpha(ACCENT_PRIMARY, 128)
    val ACCENT_SUCCESS_SEMI = withAlpha(ACCENT_SUCCESS, 128)
    val ACCENT_WARNING_SEMI = withAlpha(ACCENT_WARNING, 128)
    val ACCENT_ERROR_SEMI = withAlpha(ACCENT_ERROR, 128)
    
    val ORANGE_SEMI = withAlpha(ORANGE, 128)
    val WHITE_SEMI = withAlpha(WHITE, 128)
    val BLACK_SEMI = withAlpha(BLACK, 128)
    val RED_SEMI = withAlpha(RED, 128)
    val GREEN_SEMI = withAlpha(GREEN, 128)
    val BLUE_SEMI = withAlpha(BLUE, 128)

    fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int {
        val safeR = r.coerceIn(0, 255)
        val safeG = g.coerceIn(0, 255)
        val safeB = b.coerceIn(0, 255)
        val safeA = a.coerceIn(0, 255)
        return (safeA shl 24) or (safeB shl 16) or (safeG shl 8) or safeR
    }

    fun rgba(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        val intR = (r.coerceIn(0f, 1f) * 255).toInt()
        val intG = (g.coerceIn(0f, 1f) * 255).toInt()
        val intB = (b.coerceIn(0f, 1f) * 255).toInt()
        val intA = (a.coerceIn(0f, 1f) * 255).toInt()
        return rgba(intR, intG, intB, intA)
    }

    fun hex(hex: String): Int {
        val cleanHex = hex.removePrefix("#")
        return when (cleanHex.length) {
            3 -> {
                val r = cleanHex[0].toString().repeat(2).toInt(16)
                val g = cleanHex[1].toString().repeat(2).toInt(16)
                val b = cleanHex[2].toString().repeat(2).toInt(16)
                rgba(r, g, b, 255)
            }
            6 -> {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                rgba(r, g, b, 255)
            }
            8 -> {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                val a = cleanHex.substring(6, 8).toInt(16)
                rgba(r, g, b, a)
            }
            else -> WHITE
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        val safeAlpha = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (safeAlpha shl 24)
    }

    fun withAlpha(color: Int, alpha: Float): Int {
        val intAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return withAlpha(color, intAlpha)
    }

    fun success(): Int = ACCENT_SUCCESS

    fun warning(): Int = ACCENT_WARNING

    fun error(): Int = ACCENT_ERROR

    fun primary(): Int = ACCENT_PRIMARY

    fun text(): Int = TEXT_PRIMARY

    fun textSecondary(): Int = TEXT_SECONDARY

    fun textDisabled(): Int = TEXT_DISABLED

    fun background(): Int = BACKGROUND_PRIMARY

    fun surface(): Int = BACKGROUND_TERTIARY

    fun statusColor(isSuccess: Boolean = false, isWarning: Boolean = false, isError: Boolean = false): Int {
        return when {
            isError -> error()
            isWarning -> warning()
            isSuccess -> success()
            else -> primary()
        }
    }

    fun overlay(isDark: Boolean = true): Int {
        return if (isDark) OVERLAY_DARK else OVERLAY_LIGHT
    }
}