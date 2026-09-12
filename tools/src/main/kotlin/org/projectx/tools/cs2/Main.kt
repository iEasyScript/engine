package org.projectx.tools.cs2

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """
CS2 clientscript toolchain — disassemble, decompile, recompile.

  --cache <dir>   cache to read, opened READ-ONLY   (default ./data/cache)
  <sub-command> [args]

Run with no sub-command to list them.
"""

fun main(args: Array<String>) {
    var cacheDir = "./data/cache"
    val rest = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--cache" -> cacheDir = args[++i]
            "--help", "-h" -> {
                println(USAGE.trimIndent())
                rest.add("help")
            }
            else -> rest.add(a)
        }
        i++
    }
    val path = Paths.get(cacheDir).toAbsolutePath().normalize()
    if (!path.exists()) {
        System.err.println("No such cache directory: $path")
        exitProcess(1)
    }
    val cache: Cache = SQLiteCache.load(path, readOnly = true)
    // Everything reaching the cache through its static accessors has to land on this same
    // read-only handle; left uninitialised they open the default path for writing.
    Cache.init(cache)
    runCs2(cache, rest)
    cache.close()
}
