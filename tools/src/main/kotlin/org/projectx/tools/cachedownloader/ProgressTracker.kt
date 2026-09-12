package org.projectx.tools.cachedownloader

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProgressTracker {
    val totalFiles = AtomicInteger(0)
    val completedFiles = AtomicInteger(0)
    val totalBytes = AtomicLong(0)
    val failedFiles = AtomicInteger(0)
    val startTime = System.currentTimeMillis()

    fun complete(bytes: Int) {
        completedFiles.incrementAndGet()
        totalBytes.addAndGet(bytes.toLong())
    }

    fun fail() {
        failedFiles.incrementAndGet()
    }

    fun report() {
        val completed = completedFiles.get()
        val total = totalFiles.get()
        val bytes = totalBytes.get()
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if (elapsed > 0) bytes / elapsed else 0.0
        val failed = failedFiles.get()

        val mbDownloaded = bytes / (1024.0 * 1024.0)
        val mbPerSec = rate / (1024.0 * 1024.0)

        val remaining = total - completed
        val eta = if (rate > 0 && completed > 0) {
            val avgBytesPerFile = bytes.toDouble() / completed
            val remainingBytes = remaining * avgBytesPerFile
            (remainingBytes / rate).toLong()
        } else 0L

        val etaStr = if (eta > 0) {
            val mins = eta / 60
            val secs = eta % 60
            "${mins}m ${secs}s"
        } else "calculating..."

        val failStr = if (failed > 0) " | $failed failed" else ""
        print("\r[$completed/$total files] %.1f MB downloaded | %.1f MB/s | ETA: $etaStr$failStr    ".format(mbDownloaded, mbPerSec))
    }

    fun summary() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val mbDownloaded = totalBytes.get() / (1024.0 * 1024.0)
        println()
        println("Download complete!")
        println("  Files: ${completedFiles.get()}/${totalFiles.get()}")
        println("  Size: %.1f MB".format(mbDownloaded))
        println("  Time: %.1f seconds".format(elapsed))
        println("  Failed: ${failedFiles.get()}")
    }
}
