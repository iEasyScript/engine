package com.projectx.ui.backend.dsl.utils

object ImGuiColors {
    const val TRANSPARENT = 0x00000000
    
    // Shared with the launcher's stylesheet so the two read as one product.
    val BACKGROUND_PRIMARY = hex("#16171B")
    val BACKGROUND_SECONDARY = hex("#1E1F24")
    val BACKGROUND_TERTIARY = hex("#2A2C33")
    val BACKGROUND_HOVER = hex("#32353D")
    val BACKGROUND_ACTIVE = hex("#3A3D46")
    val BACKGROUND_SUNK = hex("#23252B")

    val TEXT_PRIMARY = hex("#E2E4E9")
    val TEXT_SECONDARY = hex("#AEB2BB")
    val TEXT_DISABLED = hex("#7D818B")
    val TEXT_ACCENT = hex("#5CC4BA")

    val ACCENT_PRIMARY = hex("#2B9C92")
    val ACCENT_PRIMARY_HOVER = hex("#33B1A6")
    val ACCENT_PRIMARY_ACTIVE = hex("#23847B")
    val ACCENT_SECONDARY = hex("#33B1A6")
    val ACCENT_SUCCESS = hex("#4CBF7A")
    val ACCENT_WARNING = hex("#D9A441")
    val ACCENT_ERROR = hex("#D0584F")

    val BORDER_DEFAULT = hex("#33363E")
    val BORDER_STRONG = hex("#41444D")
    val BORDER_ACTIVE = hex("#2B9C92")
    val BORDER_FOCUS = hex("#33B1A6")
    
    val WHITE = rgba(255, 255, 255, 255)
    val BLACK = rgba(0, 0, 0, 255)
    val RED = ACCENT_ERROR
    val GREEN = ACCENT_SUCCESS
    val BLUE = hex("#0099FF")
    val YELLOW = ACCENT_WARNING
    val CYAN = hex("#00FFFF")
    val MAGENTA = hex("#FF00FF")
    
    val GRAY_LIGHTEST = hex("#5A5E68")
    val GRAY_LIGHTER = hex("#41444D")
    val GRAY_LIGHT = hex("#33363E")
    val GRAY = hex("#2A2C33")
    val GRAY_DARK = hex("#1E1F24")
    val GRAY_DARKER = hex("#16171B")
    val GRAY_DARKEST = hex("#0F1013")

    val BUTTON_DEFAULT = hex("#2A2C33")
    val BUTTON_HOVER = hex("#32353D")
    val BUTTON_ACTIVE = hex("#3A3D46")
    val BUTTON_PRIMARY = hex("#2B9C92")
    val BUTTON_PRIMARY_HOVER = hex("#33B1A6")
    val BUTTON_PRIMARY_ACTIVE = hex("#23847B")

    val TAB_ACTIVE = hex("#E2E4E9")
    val TAB_HOVER = hex("#32353D")
    val TAB_INACTIVE = hex("#2A2C33")

    val SCROLLBAR_BG = hex("#1E1F24")
    val SCROLLBAR_GRAB = hex("#3A3D45")
    val SCROLLBAR_GRAB_HOVER = hex("#4A4E57")
    val SCROLLBAR_GRAB_ACTIVE = hex("#5A5E68")

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