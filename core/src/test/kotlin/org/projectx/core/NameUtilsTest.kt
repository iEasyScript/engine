package org.projectx.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NameUtilsTest {

    @Test
    fun `formatForProtocol lowercases and underscores spaces`() {
        val cases = mapOf(
            "Foo Bar" to "foo_bar",
            "ALLCAPS" to "allcaps",
            "already_proto" to "already_proto",
            "Mixed Case Name" to "mixed_case_name",
            "" to "",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, input.formatForProtocol(), "formatForProtocol('$input')")
        }
    }

    @Test
    fun `formatForDisplay spaces underscores and title-cases words`() {
        val cases = mapOf(
            "foo_bar" to "Foo Bar",
            "FOO_BAR" to "Foo Bar",
            "single" to "Single",
            "a_b_c" to "A B C",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, input.formatForDisplay(), "formatForDisplay('$input')")
        }
    }

    @Test
    fun `protocol and display formats are inverse for well-formed names`() {
        for (display in listOf("Foo Bar", "Player 123", "Solo")) {
            assertEquals(display, display.formatForProtocol().formatForDisplay())
        }
    }

    @Test
    fun `isValidUsername accepts normal names`() {
        val valid = listOf(
            "ab",
            "player1",
            "Player_One",
            "with space",      // spaces become single underscores in protocol form
            "a23456789012",    // exactly 12 chars
            "1numeric",
        )
        for (name in valid) {
            assertEquals(true, name.isValidUsername(), "expected valid: '$name'")
        }
    }

    @Test
    fun `isValidUsername rejects malformed names`() {
        val invalid = listOf(
            "",
            "a",                 // regex requires at least 2 chars
            "_leading",
            "trailing_",
            "dou__ble",
            "a234567890123",     // 13 chars
            "bad-char",
            "semi;colon",
            "tab\tname",
        )
        for (name in invalid) {
            assertEquals(false, name.isValidUsername(), "expected invalid: '$name'")
        }
    }
}
