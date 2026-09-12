package com.projectx.game.hooks.impl

import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.Priority
import com.projectx.game.nxt.OFunctions
import java.lang.foreign.MemorySegment

/**
 * Drops every outbound crash/error report. `jag::game::ClientError::ReportError` is the single funnel
 * that the fatal-signal handler, the C++ fatal path, CS2 script errors and connection errors all
 * converge on; it synchronously POSTs the message plus a dladdr-relocated backtrace to Jagex's
 * `nxtclienterror.ws`. Returning without invoking the trampoline drops the report entirely.
 *
 * The message is logged locally first so CS2 script errors (which the suppression would otherwise
 * hide) stay visible for debugging. Reading is gated on `flags & 2 == 0`: the fatal-signal path
 * passes 7 (=4|2|1) and reaches here with the process in an undefined state where touching the args
 * is unsafe, whereas script/connection errors pass without bit 2 and carry a valid `char*`.
 */
object ClientErrorReportSuppress {
    @JvmStatic
    @Hook("CLIENTERROR_REPORTERROR", priority = Priority.FIRST)
    fun reportErrorHook(reporter: MemorySegment, message: MemorySegment, callstack: MemorySegment, flags: Long) {
        if ((flags and 0x2L) != 0L) return
        runCatching {
            val text = message.reinterpret(0x1000).getString(0)
            if (text.isNotEmpty()) println("[ClientError flags=0x${flags.toString(16)}] $text")
        }
    }
}
