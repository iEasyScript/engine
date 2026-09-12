package org.projectx.tools.cachedownloader

import kotlinx.coroutines.runBlocking
import org.projectx.tools.util.JavConfig

fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "content.runescape.com"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 43594
    val major = args.getOrNull(2)?.toIntOrNull() ?: 948
    val minor = args.getOrNull(3)?.toIntOrNull() ?: 2
    val connections = args.getOrNull(4)?.toIntOrNull() ?: 8
    val outputPath = args.getOrNull(5) ?: "./data/cache"

    println("Cache Downloader")
    println("  Fetching config from jav_config.ws...")
    val params = JavConfig.fetch().params
    val token = params[29] ?: throw IllegalStateException("Could not find JS5 token (param=29) in jav_config.ws")
    // Content URL from param 49 or 37 (HTTP JS5 base URL)
    val contentUrl = params[49]?.let { "http://$it" }
        ?: params[37]?.let { "http://$it" }
        ?: "http://content.runescape.com"
    println("  Token: $token")
    println("  Content URL: $contentUrl")
    println("  Host: $host:$port")
    println("  Version: $major.$minor")
    println("  Connections: $connections")
    println("  Output: $outputPath")
    println()

    runBlocking {
        CacheDownloader(host, port, major, minor, connections, outputPath, token, contentUrl).download()
    }
}
