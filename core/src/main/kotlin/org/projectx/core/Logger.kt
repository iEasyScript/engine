package org.projectx.core

import java.util.logging.ConsoleHandler
import java.util.logging.Level

object Logger {
    private const val ROOT_KEY = "ProjectXRS"
    private val PROJECTX_ROOT_LOGGER = java.util.logging.Logger.getLogger(ROOT_KEY);

    init {
        setupFormat()
    }

    @JvmStatic
    fun setupFormat() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1\$tF %1\$tT %1\$tL] [%4$-7s] %5\$s %n")
        PROJECTX_ROOT_LOGGER.handlers.forEach { PROJECTX_ROOT_LOGGER.removeHandler(it) }
        PROJECTX_ROOT_LOGGER.addHandler(ConsoleHandler())
        PROJECTX_ROOT_LOGGER.useParentHandlers = false
    }

    @JvmStatic
    fun setLogLevel(level: Level) {
        PROJECTX_ROOT_LOGGER.level = level
        PROJECTX_ROOT_LOGGER.handlers.forEach { it.level = level }
    }

    /**
     * Level probes for callers that must build an expensive message before logging it — the log
     * functions take an already-evaluated argument, so a per-packet firehose has to check first.
     */
    val traceEnabled: Boolean get() = PROJECTX_ROOT_LOGGER.isLoggable(Level.FINER)
    val finestEnabled: Boolean get() = PROJECTX_ROOT_LOGGER.isLoggable(Level.FINEST)

    private val stackWalker = StackWalker.getInstance()

    /**
     * Resolves the calling class/method by walking only as many frames as needed.
     * Only call this after an [java.util.logging.Logger.isLoggable] check — walking
     * frames on every suppressed log call is wasted work.
     */
    private fun getCallerInfo(): Pair<String, String> = stackWalker.walk { frames ->
        frames
            .filter { !it.className.contains("Logger") && !it.className.contains("Thread") }
            .findFirst()
            .map { Pair(it.className.substringAfterLast('.'), it.methodName) }
            .orElse(Pair("Unknown", "unknown"))
    }

    private fun formatMessage(className: String, methodName: String, msg: Any): String = "[$className.$methodName] $msg"

    fun logError(message: String, throwable: Throwable? = null) {
        if (PROJECTX_ROOT_LOGGER.isLoggable(Level.SEVERE)) {
            val (className, methodName) = getCallerInfo()
            val full = throwable?.let { "$message\n${it.stackTraceToString()}" } ?: message
            PROJECTX_ROOT_LOGGER.log(Level.SEVERE, formatMessage(className, methodName, full))
        }
    }

    fun Any.logWarn(msg: Any, throwable: Throwable? = null) {
        if (PROJECTX_ROOT_LOGGER.isLoggable(Level.WARNING)) {
            val (className, methodName) = getCallerInfo()
            val full = throwable?.let { "$msg\n${it.stackTraceToString()}" } ?: msg.toString()
            PROJECTX_ROOT_LOGGER.log(Level.WARNING, formatMessage(className, methodName, full))
        }
    }

    fun Any.logInfo(msg: Any) {
        if (!PROJECTX_ROOT_LOGGER.isLoggable(Level.INFO)) return
        val (className, methodName) = getCallerInfo()
        PROJECTX_ROOT_LOGGER.log(Level.INFO, formatMessage(className, methodName, msg))
    }

    fun Any.logTrace(msg: Any) {
        if (!PROJECTX_ROOT_LOGGER.isLoggable(Level.FINER)) return
        val (className, methodName) = getCallerInfo()
        PROJECTX_ROOT_LOGGER.log(Level.FINER, formatMessage(className, methodName, msg))
    }

    fun Any.logFinest(msg: Any) {
        if (!PROJECTX_ROOT_LOGGER.isLoggable(Level.FINEST)) return
        val (className, methodName) = getCallerInfo()
        PROJECTX_ROOT_LOGGER.log(Level.FINEST, formatMessage(className, methodName, msg))
    }

    @JvmStatic
    fun log(tag: String, message: Any) {
        PROJECTX_ROOT_LOGGER.log(Level.INFO, "[$tag] $message")
    }
}
