package com.projectx.util

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Redirects `System.out`/`System.err` into a per-process file under `~/.projectx/logs` while keeping a
 * bounded in-memory tail for the Logs tab to display.
 *
 * The console echo goes to the process's own stdout rather than to whatever `System.out` happened to be
 * at install time: on a reinject that is the *previous* engine load's stream, so chaining onto it would
 * re-stamp and re-write every line once more per reload.
 */
object EngineLog {
    const val MAX_LINES = 100

    private val console = PrintStream(FileOutputStream(FileDescriptor.out), /* autoFlush = */ true)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
    private val lineStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var sink: PrintStream? = null

    val lines = CopyOnWriteArrayList<String>()

    var file: File? = null
        private set

    fun install() {
        try {
            val logDir = File(System.getProperty("user.home"), ".projectx/logs")
            if (!logDir.exists()) logDir.mkdirs()

            val pid = ProcessHandle.current().pid()
            val target = File(logDir, "projectx-${LocalDateTime.now().format(fileStamp)}-$pid.log")
            if (!target.exists()) target.createNewFile()
            file = target

            lines.add("Logging Initialized at ${LocalDateTime.now().format(lineStamp)}")

            val printStream = object : PrintStream(FileOutputStream(target, false), /* autoFlush = */ true) {
                override fun println(x: String?) {
                    val timestamped = "[${LocalDateTime.now().format(lineStamp)}] $x"
                    super.println(timestamped)
                    console.println(timestamped)
                    lines.add(timestamped)
                    if (lines.size > MAX_LINES) lines.removeAt(0)
                }

                override fun println(x: Any?) = println(x?.toString())

                override fun println() = println("")
            }

            sink = printStream
            System.setOut(printStream)
            System.setErr(printStream)
            println("[Engine] ${EngineBuild.describe()} pid $pid")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** Restores the console streams and closes this load's log file so a reload starts clean. */
    fun close() {
        System.setOut(console)
        System.setErr(console)
        runCatching { sink?.close() }
        sink = null
    }
}
