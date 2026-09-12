package com.projectx.ui.backend.flags

@JvmInline
value class InputTextFlags(val value: Int) {
    companion object {
        val None = InputTextFlags(0)
        val CharsDecimal = InputTextFlags(1 shl 0)
        val CharsHexadecimal = InputTextFlags(1 shl 1)
        val CharsScientific = InputTextFlags(1 shl 2)
        val CharsUppercase = InputTextFlags(1 shl 3)
        val CharsNoBlank = InputTextFlags(1 shl 4)
        val AllowTabInput = InputTextFlags(1 shl 5)
        val EnterReturnsTrue = InputTextFlags(1 shl 6)
        val EscapeClearsAll = InputTextFlags(1 shl 7)
        val CtrlEnterForNewLine = InputTextFlags(1 shl 8)
        val AutoSelectAll = InputTextFlags(1 shl 9)
        val ReadOnly = InputTextFlags(1 shl 10)
        val Password = InputTextFlags(1 shl 11)
    }
    
    operator fun plus(other: InputTextFlags) = InputTextFlags(value or other.value)
}