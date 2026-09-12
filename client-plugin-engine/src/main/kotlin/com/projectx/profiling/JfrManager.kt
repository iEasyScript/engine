package com.projectx.profiling

import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories

/**
 * Lightweight programmatic JFR controller.
 * - Start with sensible defaults (profile configuration + CPU and allocation events)
 * - Stop and dump to ~/.projectx/jfr/{timestamp}.jfr
 */
object JfrManager {
    @Volatile
    private var recording: Recording? = null

    @Volatile
    private var lastDumpPath: Path? = null

    fun isRunning(): Boolean = recording?.state == RecordingState.RUNNING

    fun getLastDumpPath(): Path? = lastDumpPath

    @Synchronized
    fun start(): Boolean {
        if (isRunning()) return false
        val cfg = try {
            Configuration.getConfiguration("profile")
        } catch (_: Throwable) {
            null
        }

        val rec = if (cfg != null) Recording(cfg) else Recording()

        try {
            rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
            rec.enable("jdk.JavaMonitorWait")
            rec.enable("jdk.JavaMonitorEnter")
            rec.enable("jdk.ThreadPark")
            rec.enable("jdk.ObjectAllocationInNewTLAB")
            rec.enable("jdk.ObjectAllocationOutsideTLAB")
            try { rec.enable("jdk.NativeMethodSample") } catch (_: Throwable) {}

            rec.isToDisk = true
            rec.start()
            recording = rec
            return true
        } catch (t: Throwable) {
            try { rec.close() } catch (_: Throwable) {}
            recording = null
            return false
        }
    }

    @Synchronized
    fun stopAndDump(): Path? {
        val rec = recording ?: return null
        try {
            if (rec.state == RecordingState.RUNNING) {
                rec.stop()
            }

            val outDir = Paths.get(System.getProperty("user.home"), ".projectx", "jfr")
            if (!Files.exists(outDir)) {
                outDir.createDirectories()
            }
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val outPath = outDir.resolve("projectx-$ts.jfr")
            rec.dump(outPath)
            lastDumpPath = outPath
            return outPath
        } finally {
            try { rec.close() } catch (_: Throwable) {}
            recording = null
        }
    }
}
