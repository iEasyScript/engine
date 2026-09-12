package world.gregs.voidps.cache.cs2

/**
 * The placeholders a hook binds where live event data belongs.
 *
 * A bound hook argument is not always a value: the compiler stores a constant at
 * the very bottom of the signed 32-bit range, and the hook-execution path swaps
 * in what the event actually carried before the callback runs. Substitution
 * happens only on the int stack, so a string or long argument never holds one.
 *
 * What each placeholder resolves to is read off the client; the spelling is this
 * toolchain's. The client publishes no name for any of them - the only two names
 * it does carry, [OPTION_NAME] and `event_text`, belong to the string flavour of
 * the same mechanism, and they are what says Jagex calls this family `event_*`.
 * These identifiers are therefore evidence about meaning and a label about
 * spelling, exactly as [Cs2NameOrigin.STRUCTURAL] opcode names are.
 */
enum class Cs2EventArg(val value: Int, val identifier: String, val meaning: String) {
    CURSOR_X(Int.MIN_VALUE + 1, "event_cursor_x", "cursor x, relative to the component's own origin"),
    CURSOR_Y_OR_WHEEL_DELTA(
        Int.MIN_VALUE + 2,
        "event_cursor_y_or_wheel_delta",
        "cursor y on that same basis, except on the scrollwheel path, where it carries the wheel " +
            "delta and cursor x comes through as 0",
    ),
    SOURCE_COMPONENT(
        Int.MIN_VALUE + 3,
        "event_source_component",
        "packed id of the component the event happened on",
    ),
    OP_INDEX(Int.MIN_VALUE + 4, "event_op_index", "which menu op was taken, counting from 1"),
    SOURCE_SLOT(Int.MIN_VALUE + 5, "event_source_slot", "sub index within the source component"),
    TARGET_COMPONENT(
        Int.MIN_VALUE + 6,
        "event_target_component",
        "packed id of the component a drag was dropped onto",
    ),
    TARGET_SLOT(Int.MIN_VALUE + 7, "event_target_slot", "sub index within that drag target"),
    KEY_CODE(Int.MIN_VALUE + 8, "event_key_code", "code of the key that was pressed"),
    KEY_CHAR(Int.MIN_VALUE + 9, "event_key_char", "character that key press typed"),
    GAMEPAD_VALUE(Int.MIN_VALUE + 10, "event_gamepad_value", "gamepad button state or axis magnitude"),
    GAMEPAD_CONTROL(
        Int.MIN_VALUE + 11,
        "event_gamepad_control",
        "gamepad button id, axis index or trigger index",
    ),
    ;

    companion object {
        /** The string-slot placeholder, substituted by comparing the literal itself. */
        const val OPTION_NAME = "event_opbase"

        private val byValue = entries.associateBy { it.value }

        private val byIdentifier = entries.associateBy { it.identifier }

        fun of(value: Int): Cs2EventArg? = byValue[value]

        fun named(identifier: String): Cs2EventArg? = byIdentifier[identifier]
    }
}
