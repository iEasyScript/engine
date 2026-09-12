package org.projectx.core

/**
 * Username formatting utilities.
 * Based on ~/projectx/server/core UtilsAndExtensions.kt — logic only.
 */

/** Protocol format: lowercase, spaces → underscores. Used for DB keys and lookups. */
fun String.formatForProtocol(): String = lowercase().replace(" ", "_")

/** Display format: underscores → spaces, title-case each word. Used for UI and social packets. */
fun String.formatForDisplay(): String =
    replace("_", " ").lowercase().split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Username validation: alphanumeric + underscores, 1-12 chars, no leading/trailing/double underscores. */
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{0,10}[a-zA-Z0-9]$")
fun String.isValidUsername(): Boolean =
    length in 1..12 && !contains("__") && formatForProtocol().matches(USERNAME_REGEX)
