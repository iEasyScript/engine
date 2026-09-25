package com.projectx.game.input

import com.projectx.game.platform.Platform

/**
 * A key in the engine's own vocabulary, mapped to whatever code the host client actually delivers.
 *
 * The client's input layer is platform-neutral in shape but not in values: the SDL-backed Linux build
 * passes SDL keycodes, while the Windows build is fed Win32 virtual-key codes straight from its
 * WndProc. Naming keys in one namespace and translating only at the boundary keeps every caller —
 * scripts, MCP, the UI toggle — free of that split.
 *
 * Keys with no counterpart on one of the hosts are deliberately absent rather than mapped to a
 * near-miss: a wrong code presses a real but different key, which is worse than not binding at all.
 */
enum class Key(private val sdl: Int, private val virtualKey: Int) {
    BACKSPACE(0x8, 0x8),
    TAB(0x9, 0x9),
    RETURN(0xD, 0xD),
    ESCAPE(0x1B, 0x1B),
    SPACE(0x20, 0x20),
    DELETE(0x7F, 0x2E),
    A(0x61, 0x41),
    B(0x62, 0x42),
    C(0x63, 0x43),
    D(0x64, 0x44),
    E(0x65, 0x45),
    F(0x66, 0x46),
    G(0x67, 0x47),
    H(0x68, 0x48),
    I(0x69, 0x49),
    J(0x6A, 0x4A),
    K(0x6B, 0x4B),
    L(0x6C, 0x4C),
    M(0x6D, 0x4D),
    N(0x6E, 0x4E),
    O(0x6F, 0x4F),
    P(0x70, 0x50),
    Q(0x71, 0x51),
    R(0x72, 0x52),
    S(0x73, 0x53),
    T(0x74, 0x54),
    U(0x75, 0x55),
    V(0x76, 0x56),
    W(0x77, 0x57),
    X(0x78, 0x58),
    Y(0x79, 0x59),
    Z(0x7A, 0x5A),
    NUM0(0x30, 0x30),
    NUM1(0x31, 0x31),
    NUM2(0x32, 0x32),
    NUM3(0x33, 0x33),
    NUM4(0x34, 0x34),
    NUM5(0x35, 0x35),
    NUM6(0x36, 0x36),
    NUM7(0x37, 0x37),
    NUM8(0x38, 0x38),
    NUM9(0x39, 0x39),
    F1(0x4000003A, 0x70),
    F2(0x4000003B, 0x71),
    F3(0x4000003C, 0x72),
    F4(0x4000003D, 0x73),
    F5(0x4000003E, 0x74),
    F6(0x4000003F, 0x75),
    F7(0x40000040, 0x76),
    F8(0x40000041, 0x77),
    F9(0x40000042, 0x78),
    F10(0x40000043, 0x79),
    F11(0x40000044, 0x7A),
    F12(0x40000045, 0x7B),
    F13(0x40000068, 0x7C),
    F14(0x40000069, 0x7D),
    F15(0x4000006A, 0x7E),
    F16(0x4000006B, 0x7F),
    F17(0x4000006C, 0x80),
    F18(0x4000006D, 0x81),
    F19(0x4000006E, 0x82),
    F20(0x4000006F, 0x83),
    F21(0x40000070, 0x84),
    F22(0x40000071, 0x85),
    F23(0x40000072, 0x86),
    F24(0x40000073, 0x87),
    CAPSLOCK(0x40000039, 0x14),
    PRINTSCREEN(0x40000046, 0x2C),
    SCROLLLOCK(0x40000047, 0x91),
    PAUSE(0x40000048, 0x13),
    INSERT(0x40000049, 0x2D),
    HOME(0x4000004A, 0x24),
    PAGEUP(0x4000004B, 0x21),
    END(0x4000004D, 0x23),
    PAGEDOWN(0x4000004E, 0x22),
    RIGHT(0x4000004F, 0x27),
    LEFT(0x40000050, 0x25),
    DOWN(0x40000051, 0x28),
    UP(0x40000052, 0x26),
    NUMLOCK(0x40000053, 0x90),
    KP_DIVIDE(0x40000054, 0x6F),
    KP_MULTIPLY(0x40000055, 0x6A),
    KP_MINUS(0x40000056, 0x6D),
    KP_PLUS(0x40000057, 0x6B),
    KP_ENTER(0x40000058, 0xD),
    KP_1(0x40000059, 0x61),
    KP_2(0x4000005A, 0x62),
    KP_3(0x4000005B, 0x63),
    KP_4(0x4000005C, 0x64),
    KP_5(0x4000005D, 0x65),
    KP_6(0x4000005E, 0x66),
    KP_7(0x4000005F, 0x67),
    KP_8(0x40000060, 0x68),
    KP_9(0x40000061, 0x69),
    KP_0(0x40000062, 0x60),
    KP_PERIOD(0x40000063, 0x6E),
    APPLICATION(0x40000065, 0x5D),
    HELP(0x40000075, 0x2F),
    SELECT(0x40000077, 0x29),
    CLEAR(0x4000009C, 0xC),
    CANCEL(0x4000009B, 0x3),
    // The WndProc forwards wParam untouched, and Windows only ever puts the side-neutral VK_CONTROL,
    // VK_SHIFT or VK_MENU there: the side-specific codes never reach the client, which drops them.
    LCTRL(0x400000E0, 0x11),
    LSHIFT(0x400000E1, 0x10),
    LALT(0x400000E2, 0x12),
    LGUI(0x400000E3, 0x5B),
    RCTRL(0x400000E4, 0x11),
    RSHIFT(0x400000E5, 0x10),
    RALT(0x400000E6, 0x12),
    RGUI(0x400000E7, 0x5C),
    MUTE(0x4000007F, 0xAD),
    VOLUMEUP(0x40000080, 0xAF),
    VOLUMEDOWN(0x40000081, 0xAE),
    SLEEP(0x4000011A, 0x5F);

    /** The code this host's client will deliver for, and accept for, this key. */
    val native: Int get() = if (Platform.current == Platform.WINDOWS) virtualKey else sdl

    companion object {
        /** Left and right modifiers share one Windows code; the left key, declared first, names it. */
        private val byNative: Map<Int, Key> by lazy { entries.distinctBy { it.native }.associateBy { it.native } }

        @JvmStatic
        fun fromNative(code: Int): Key? = byNative[code]

        /**
         * The key that types [char]. Letters are the trap: SDL names them by lowercase ASCII and
         * Win32 by uppercase, so passing a raw char code through would land on a numpad key on
         * Windows.
         */
        @JvmStatic
        fun forChar(char: Char): Key? = when (char) {
            in 'a'..'z' -> valueOf(char.uppercaseChar().toString())
            in 'A'..'Z' -> valueOf(char.toString())
            in '0'..'9' -> valueOf("NUM$char")
            ' ' -> SPACE
            '\n', '\r' -> RETURN
            '\t' -> TAB
            '\b' -> BACKSPACE
            else -> null
        }
    }
}
