package org.projectx.tools.util

import java.net.HttpURLConnection
import java.net.URI

const val JAV_CONFIG_URL = "https://www.runescape.com/k=5/l=0/jav_config.ws?binaryType=4"

/**
 * Fetched + parsed jav_config.ws (see docs/binary/jav-config.md).
 *
 * Three line types:
 *  - `key=value`        -> [settings] (e.g. server_version)
 *  - `msg=key=value`    -> [messages]
 *  - `param=N=value`    -> [params] (numbered CLI args for the NXT binary)
 */
class JavConfig(val raw: String) {
    val settings: Map<String, String>
    val params: Map<Int, String>
    val messages: Map<String, String>

    init {
        val settingsMap = mutableMapOf<String, String>()
        val paramsMap = mutableMapOf<Int, String>()
        val messagesMap = mutableMapOf<String, String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("param=") -> {
                    val rest = trimmed.removePrefix("param=")
                    val eq = rest.indexOf('=')
                    if (eq > 0) rest.substring(0, eq).toIntOrNull()?.let { paramsMap[it] = rest.substring(eq + 1) }
                }
                trimmed.startsWith("msg=") -> {
                    val rest = trimmed.removePrefix("msg=")
                    val eq = rest.indexOf('=')
                    if (eq > 0) messagesMap[rest.substring(0, eq)] = rest.substring(eq + 1)
                }
                trimmed.contains('=') -> {
                    val eq = trimmed.indexOf('=')
                    settingsMap[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                }
            }
        }
        settings = settingsMap
        params = paramsMap
        messages = messagesMap
    }

    /** Parses `server_version` ("major.minor") with fallbacks. */
    fun serverVersion(defaultMajor: Int, defaultMinor: Int): Pair<Int, Int> {
        val parts = settings["server_version"]?.split(".") ?: return defaultMajor to defaultMinor
        return (parts.getOrNull(0)?.toIntOrNull() ?: defaultMajor) to (parts.getOrNull(1)?.toIntOrNull() ?: defaultMinor)
    }

    companion object {
        /** Fetch and parse the live jav_config.ws. Throws on network failure. */
        fun fetch(
            url: String = JAV_CONFIG_URL,
            connectTimeoutMs: Int = 10_000,
            readTimeoutMs: Int = 10_000,
        ): JavConfig {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                return JavConfig(conn.inputStream.bufferedReader(Charsets.ISO_8859_1).readText())
            } finally {
                conn.disconnect()
            }
        }
    }
}
