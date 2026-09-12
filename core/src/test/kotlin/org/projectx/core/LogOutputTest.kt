package org.projectx.core

import org.projectx.core.Logger.logInfo
import org.projectx.core.Logger.logTrace
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the Logger contract after the StackWalker/level-gating rework:
 *  - logInfo is emitted at [Level.INFO] (so `setLogLevel(INFO)` does NOT silence it)
 *  - the caller class/method is resolved correctly via StackWalker
 *  - suppressed levels emit nothing (and skip the caller walk entirely)
 */
class LogOutputTest {

    private val records = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) { synchronized(records) { records += record } }
        override fun flush() {}
        override fun close() {}
    }
    private val julLogger = java.util.logging.Logger.getLogger("ProjectXRS")

    @BeforeTest
    fun setUp() {
        // Force Logger's one-time init (which resets the handler list) BEFORE attaching
        // the capture handler, otherwise the first test would have its handler removed.
        Logger.setupFormat()
        julLogger.addHandler(handler)
    }

    @AfterTest
    fun tearDown() {
        julLogger.removeHandler(handler)
        julLogger.level = null
    }

    @Test
    fun `logInfo emits at INFO with caller class and method`() {
        Logger.setLogLevel(Level.INFO)
        handler.level = Level.ALL
        this.logInfo("info-probe")

        val record = synchronized(records) { records.find { it.message?.contains("info-probe") == true } }
        assertTrue(record != null, "logInfo message should be published at INFO log level")
        assertTrue(record.level == Level.INFO, "logInfo must log at Level.INFO, was ${record.level}")
        assertTrue(
            record.message.contains("LogOutputTest"),
            "caller class should be resolved, message was: ${record.message}",
        )
    }

    @Test
    fun `suppressed levels emit nothing`() {
        Logger.setLogLevel(Level.WARNING)
        handler.level = Level.ALL
        this.logInfo("suppressed-info")
        this.logTrace("suppressed-trace")

        synchronized(records) {
            assertTrue(records.none { it.message?.contains("suppressed-") == true })
        }
    }

    @Test
    fun `log with tag emits at INFO`() {
        Logger.setLogLevel(Level.INFO)
        handler.level = Level.ALL
        Logger.log("TestTag", "tagged-probe")

        synchronized(records) {
            assertTrue(records.any { it.message?.contains("[TestTag] tagged-probe") == true })
        }
    }
}
