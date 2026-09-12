package world.gregs.voidps.cache.cs2

/** How an opcode's operand is laid out in the file. */
enum class Cs2Operand(val fixedBytes: Int?) {
    BYTE(1),

    /** `u16 varbitId` followed by a byte the dispatcher reads but never uses. */
    TRIBYTE(3),

    /** [TRIBYTE] widened to a 24-bit id, which is what the client reads once the server announces 950 or later. */
    WIDE_VARBIT(4),

    INT(4),

    /** `u8 domain`, `u16 varId`, then a byte the dispatcher reads but never uses. */
    VAR(4),

    LONG(8),

    STRING(null),

    /** `u8 tag` selecting an i32, an i64, a string, or - for any other tag - nothing. */
    TAGGED(null),
}

/** Payload selector of a [Cs2Operand.TAGGED] push. */
object Cs2PushTag {
    const val INT = 0
    const val LONG = 1
    const val STRING = 2
    const val NONE = -1
}
