package org.projectx.tools.cachedownloader

import world.gregs.voidps.cache.sqlite.IndexFile
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

class CacheStorage(private val basePath: Path) : Closeable {

    private val indexFiles = HashMap<Int, IndexFile>()

    init {
        Files.createDirectories(basePath)
    }

    @Synchronized
    private fun getOrCreate(index: Int): IndexFile {
        return indexFiles.getOrPut(index) {
            IndexFile(basePath.resolve("js5-$index.jcache"))
        }
    }

    @Synchronized
    fun exists(index: Int, archive: Int): Boolean {
        return getOrCreate(index).exists(archive)
    }

    @Synchronized
    fun allKeys(index: Int): Set<Int> {
        return getOrCreate(index).allKeys()
    }

    @Synchronized
    fun allVersions(index: Int): Map<Int, Pair<Int, Int>> {
        return getOrCreate(index).allVersions()
    }

    @Synchronized
    fun updateVersion(index: Int, archive: Int, version: Int) {
        getOrCreate(index).updateVersion(archive, version)
    }

    @Synchronized
    fun batchUpdateVersions(index: Int, updates: Map<Int, Int>) {
        getOrCreate(index).batchUpdateVersions(updates)
    }

    fun store(index: Int, archive: Int, data: ByteArray, version: Int, crc: Int) {
        val file = getOrCreate(index)
        synchronized(file) {
            file.putRaw(archive, data, version, crc)
        }
    }

    fun storeRefTable(index: Int, data: ByteArray, version: Int, crc: Int) {
        val file = getOrCreate(index)
        synchronized(file) {
            file.putRefTable(data, version, crc)
        }
    }

    @Synchronized
    override fun close() {
        for (file in indexFiles.values) {
            file.close()
        }
    }
}
