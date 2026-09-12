package org.projectx.tools.util

/** Lowercase hex dump of a byte array, e.g. "0a ff 00". Pass "" for unseparated output. */
fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02x".format(it.toInt() and 0xFF) }
