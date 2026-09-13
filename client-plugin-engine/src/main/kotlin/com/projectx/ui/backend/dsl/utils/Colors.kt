package com.projectx.ui.backend.dsl.utils

object ImGuiColors {
    const val TRANSPARENT = 0x00000000
    
    // Shared with the launcher's stylesheet so the two read as one product.
    @JvmStatic
    val BACKGROUND_PRIMARY = hex("#16171B")
    @JvmStatic
    val BACKGROUND_SECONDARY = hex("#1E1F24")
    @JvmStatic
    val BACKGROUND_TERTIARY = hex("#2A2C33")
    @JvmStatic
    val BACKGROUND_HOVER = hex("#32353D")
    @JvmStatic
    val BACKGROUND_ACTIVE = hex("#3A3D46")
    @JvmStatic
    val BACKGROUND_SUNK = hex("#23252B")

    @JvmStatic
    val TEXT_PRIMARY = hex("#E2E4E9")
    @JvmStatic
    val TEXT_SECONDARY = hex("#AEB2BB")
    @JvmStatic
    val TEXT_DISABLED = hex("#7D818B")
    @JvmStatic
    val TEXT_ACCENT = hex("#5CC4BA")

    @JvmStatic
    val ACCENT_PRIMARY = hex("#2B9C92")
    @JvmStatic
    val ACCENT_PRIMARY_HOVER = hex("#33B1A6")
    @JvmStatic
    val ACCENT_PRIMARY_ACTIVE = hex("#23847B")
    @JvmStatic
    val ACCENT_SECONDARY = hex("#33B1A6")
    @JvmStatic
    val ACCENT_SUCCESS = hex("#4CBF7A")
    @JvmStatic
    val ACCENT_WARNING = hex("#D9A441")
    @JvmStatic
    val ACCENT_ERROR = hex("#D0584F")

    @JvmStatic
    val BORDER_DEFAULT = hex("#33363E")
    @JvmStatic
    val BORDER_STRONG = hex("#41444D")
    @JvmStatic
    val BORDER_ACTIVE = hex("#2B9C92")
    @JvmStatic
    val BORDER_FOCUS = hex("#33B1A6")
    
    @JvmStatic
    val WHITE = rgba(255, 255, 255, 255)
    @JvmStatic
    val BLACK = rgba(0, 0, 0, 255)
    @JvmStatic
    val RED = ACCENT_ERROR
    @JvmStatic
    val GREEN = ACCENT_SUCCESS
    @JvmStatic
    val BLUE = hex("#0099FF")
    @JvmStatic
    val YELLOW = ACCENT_WARNING
    @JvmStatic
    val CYAN = hex("#00FFFF")
    @JvmStatic
    val MAGENTA = hex("#FF00FF")
    
    @JvmStatic
    val GRAY_LIGHTEST = hex("#5A5E68")
    @JvmStatic
    val GRAY_LIGHTER = hex("#41444D")
    @JvmStatic
    val GRAY_LIGHT = hex("#33363E")
    @JvmStatic
    val GRAY = hex("#2A2C33")
    @JvmStatic
    val GRAY_DARK = hex("#1E1F24")
    @JvmStatic
    val GRAY_DARKER = hex("#16171B")
    @JvmStatic
    val GRAY_DARKEST = hex("#0F1013")

    @JvmStatic
    val BUTTON_DEFAULT = hex("#2A2C33")
    @JvmStatic
    val BUTTON_HOVER = hex("#32353D")
    @JvmStatic
    val BUTTON_ACTIVE = hex("#3A3D46")
    @JvmStatic
    val BUTTON_PRIMARY = hex("#2B9C92")
    @JvmStatic
    val BUTTON_PRIMARY_HOVER = hex("#33B1A6")
    @JvmStatic
    val BUTTON_PRIMARY_ACTIVE = hex("#23847B")

    @JvmStatic
    val TAB_ACTIVE = hex("#E2E4E9")
    @JvmStatic
    val TAB_HOVER = hex("#32353D")
    @JvmStatic
    val TAB_INACTIVE = hex("#2A2C33")

    @JvmStatic
    val SCROLLBAR_BG = hex("#1E1F24")
    @JvmStatic
    val SCROLLBAR_GRAB = hex("#3A3D45")
    @JvmStatic
    val SCROLLBAR_GRAB_HOVER = hex("#4A4E57")
    @JvmStatic
    val SCROLLBAR_GRAB_ACTIVE = hex("#5A5E68")

    @JvmStatic
    val ORANGE = hex("#FF8A3D")
    @JvmStatic
    val PURPLE = hex("#B070B0")
    @JvmStatic
    val BROWN = hex("#C08A6E")
    @JvmStatic
    val PINK = hex("#D080A0")
    
    @JvmStatic
    val OVERLAY_LIGHT = withAlpha(WHITE, 20)
    @JvmStatic
    val OVERLAY_DARK = withAlpha(BLACK, 180)
    @JvmStatic
    val ACCENT_PRIMARY_SEMI = withAlpha(ACCENT_PRIMARY, 128)
    @JvmStatic
    val ACCENT_SUCCESS_SEMI = withAlpha(ACCENT_SUCCESS, 128)
    @JvmStatic
    val ACCENT_WARNING_SEMI = withAlpha(ACCENT_WARNING, 128)
    @JvmStatic
    val ACCENT_ERROR_SEMI = withAlpha(ACCENT_ERROR, 128)
    
    @JvmStatic
    val ORANGE_SEMI = withAlpha(ORANGE, 128)
    @JvmStatic
    val WHITE_SEMI = withAlpha(WHITE, 128)
    @JvmStatic
    val BLACK_SEMI = withAlpha(BLACK, 128)
    @JvmStatic
    val RED_SEMI = withAlpha(RED, 128)
    @JvmStatic
    val GREEN_SEMI = withAlpha(GREEN, 128)
    @JvmStatic
    val BLUE_SEMI = withAlpha(BLUE, 128)

    @JvmOverloads
    @JvmStatic
    fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int {
        val safeR = r.coerceIn(0, 255)
        val safeG = g.coerceIn(0, 255)
        val safeB = b.coerceIn(0, 255)
        val safeA = a.coerceIn(0, 255)
        return (safeA shl 24) or (safeB shl 16) or (safeG shl 8) or safeR
    }

    @JvmOverloads
    @JvmStatic
    fun rgba(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        val intR = (r.coerceIn(0f, 1f) * 255).toInt()
        val intG = (g.coerceIn(0f, 1f) * 255).toInt()
        val intB = (b.coerceIn(0f, 1f) * 255).toInt()
        val intA = (a.coerceIn(0f, 1f) * 255).toInt()
        return rgba(intR, intG, intB, intA)
    }

    @JvmStatic
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

    @JvmStatic
    fun withAlpha(color: Int, alpha: Int): Int {
        val safeAlpha = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (safeAlpha shl 24)
    }

    @JvmStatic
    fun withAlpha(color: Int, alpha: Float): Int {
        val intAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return withAlpha(color, intAlpha)
    }

    @JvmStatic
    fun success(): Int = ACCENT_SUCCESS

    @JvmStatic
    fun warning(): Int = ACCENT_WARNING

    @JvmStatic
    fun error(): Int = ACCENT_ERROR

    @JvmStatic
    fun primary(): Int = ACCENT_PRIMARY

    @JvmStatic
    fun text(): Int = TEXT_PRIMARY

    @JvmStatic
    fun textSecondary(): Int = TEXT_SECONDARY

    @JvmStatic
    fun textDisabled(): Int = TEXT_DISABLED

    @JvmStatic
    fun background(): Int = BACKGROUND_PRIMARY

    @JvmStatic
    fun surface(): Int = BACKGROUND_TERTIARY

    @JvmOverloads
    @JvmStatic
    fun statusColor(isSuccess: Boolean = false, isWarning: Boolean = false, isError: Boolean = false): Int {
        return when {
            isError -> error()
            isWarning -> warning()
            isSuccess -> success()
            else -> primary()
        }
    }

    @JvmOverloads
    @JvmStatic
    fun overlay(isDark: Boolean = true): Int {
        return if (isDark) OVERLAY_DARK else OVERLAY_LIGHT
    }
}