package com.projectx.game.input

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.impl.keyCodeName
import com.projectx.game.input.record.RecordingSchema
import com.projectx.game.input.record.RecordingStore
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.OInputGlobals
import com.projectx.game.nxt.OffsetTable
import com.projectx.game.platform.Platform
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.native.NativeBridge
import java.io.File
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Records input events and per-tick game context into the player's recording database.
 *
 * Hooks call [record] from whichever thread the client's input dispatch runs on; everything that touches the
 * database happens in [tickFlush] on the game thread. Start and stop are requests rather than actions for the
 * same reason - the UI buttons run on the render thread, and the previous version opened and closed the output
 * stream from there while the game thread was mid-write.
 */
object InputRecorder {
    private const val FEED_CAPACITY = 128

    /** Beyond this the client has stopped ticking (loading, alt-tab, a missing hook) and the queue is a leak. */
    private const val PENDING_LIMIT = 100_000

    private val eventBuffer = ConcurrentLinkedDeque<Queued>()
    private val pendingCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    private var store: RecordingStore? = null
    private var lastFlushTick: Int = -1
    private val lastButtonState = HashMap<Long, Int>()

    @Volatile private var startRequested = false
    @Volatile private var stopRequested = false

    private val feed = ArrayDeque<FeedEntry>()
    private val counts = LongArray(EventKind.entries.size)

    private enum class EventKind { MOTION, BUTTON, SCROLL, KEY }

    private class FeedEntry(val kind: EventKind, val line: String)

    private class Queued(
        val event: InputEvent,
        val source: RecordingSchema.Source,
        /**
         * Set only for click-ring rows, where the stored value is the client's own flag rather than a
         * [MouseButton] id. Its meaning is unsettled, so it is passed through untouched.
         */
        val rawButtonFlag: Int? = null,
        val clientTimestampMs: Long? = null,
    )

    @Volatile
    var isRecording: Boolean = false
        private set

    var playerName: String = ""
        private set

    var eventCount: Long = 0
        private set

    var databaseFile: File? = null
        private set

    var sessionStartMs: Long = 0
        private set

    val droppedEvents: Long get() = droppedCount.get()

    val sessionDurationMs: Long
        get() = if (isRecording) System.currentTimeMillis() - sessionStartMs else 0

    /**
     * Newest first, so the UI reads top-down without reversing. Motion is optional because it
     * outnumbers every other kind by roughly two orders of magnitude and would bury them.
     */
    fun feedSnapshot(includeMotion: Boolean): List<String> = synchronized(feed) {
        feed.filter { includeMotion || it.kind != EventKind.MOTION }.map { it.line }
    }

    val motionEvents: Long get() = counts[EventKind.MOTION.ordinal]
    val buttonEvents: Long get() = counts[EventKind.BUTTON.ordinal]
    val scrollEvents: Long get() = counts[EventKind.SCROLL.ordinal]
    val keyEvents: Long get() = counts[EventKind.KEY.ordinal]

    /** Serviced on the next tick; the database is only ever touched from the game thread. */
    fun requestStart() {
        if (isRecording) return
        stopRequested = false
        startRequested = true
    }

    fun requestStop() {
        if (!isRecording) return
        startRequested = false
        stopRequested = true
    }

    fun record(event: InputEvent) = enqueue(Queued(event, RecordingSchema.Source.RAW_HOOK))

    /**
     * An entry drained from the client's **click** ring. Kept as its own source because it arrives once per
     * tick while [record] arrives at OS event rate - merged into one untagged stream, as the old format did,
     * the model is trained on a mixture of two sampling rates it cannot tell apart.
     *
     * Every row is recorded as a button row carrying the ring's flag **verbatim, including zero**, and nothing
     * is inferred from its value. That value's meaning is the open question: read as a button id, zero is the
     * left button; read as a presence flag, zero would mean "no click". Branching on it here would erase the
     * evidence needed to settle it - an earlier version turned zero into a motion row with a null button and
     * threw the answer away.
     */
    fun recordServerRing(buttonFlag: Int, x: Int, y: Int, clientTimestampMs: Long, gameTick: Int) {
        enqueue(
            Queued(
                event = MouseButtonEvent(
                    timestampNanos = System.nanoTime(),
                    gameTick = gameTick,
                    x = x,
                    y = y,
                    button = MouseButton.LEFT,
                    pressed = true,
                ),
                source = RecordingSchema.Source.SERVER_RING,
                rawButtonFlag = buttonFlag,
                clientTimestampMs = clientTimestampMs,
            )
        )
    }

    private fun enqueue(queued: Queued) {
        if (!isRecording) return
        if (pendingCount.get() >= PENDING_LIMIT) {
            eventBuffer.poll()?.let { pendingCount.decrementAndGet() }
            droppedCount.incrementAndGet()
        }
        eventBuffer.add(queued)
        pendingCount.incrementAndGet()
    }

    fun tickFlush() {
        if (startRequested) {
            startRequested = false
            openSession()
        }
        if (!isRecording) return
        if (stopRequested) {
            stopRequested = false
            closeSession()
            return
        }

        val active = store ?: return
        try {
            val client = Bootstrap.client
            val gameTick = client.clientCycle
            if (gameTick == lastFlushTick) return
            lastFlushTick = gameTick

            active.beginFlush()
            try {
                writeTickContext(active, gameTick)
                pollButtons(active, gameTick)
                drainEvents(active)
                active.commitFlush()
            } catch (e: Throwable) {
                active.rollbackFlush()
                throw e
            }
            eventCount = active.eventsWritten
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** Called from engine teardown so a reload while recording does not lose the queue or leak the session. */
    fun shutdown() {
        if (!isRecording) return
        closeSession()
    }

    private fun openSession() {
        val resolved = resolvePlayerName()
        if (resolved.isBlank()) {
            println("[InputRecorder] Cannot start recording - not logged in.")
            return
        }

        val (viewportWidth, viewportHeight) = viewportSize()
        try {
            val opened = RecordingStore.open(
                player = resolved,
                startedEpochMs = System.currentTimeMillis(),
                monoBaseNanos = System.nanoTime(),
                platform = Platform.current.id,
                arch = Platform.arch,
                clientBuild = OffsetTable.build,
                keycodeNamespace = keycodeNamespace(),
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                provenance = "live_v${RecordingSchema.VERSION}",
            )
            store = opened
            databaseFile = opened.file
            playerName = resolved
            sessionStartMs = System.currentTimeMillis()
            eventCount = 0
            lastFlushTick = -1
            lastButtonState.clear()
            eventBuffer.clear()
            pendingCount.set(0)
            droppedCount.set(0)
            counts.fill(0)
            synchronized(feed) { feed.clear() }
            isRecording = true
            println("[InputRecorder] Recording session ${opened.sessionId} into ${opened.file.absolutePath}")
        } catch (e: Throwable) {
            println("[InputRecorder] Failed to open recording database: ${e.message}")
            e.printStackTrace()
            store = null
        }
    }

    private fun closeSession() {
        isRecording = false
        val active = store ?: return
        try {
            active.beginFlush()
            drainEvents(active)
            active.commitFlush()
            active.finishSession(System.currentTimeMillis(), droppedCount.get())
            eventCount = active.eventsWritten
        } catch (e: Throwable) {
            active.rollbackFlush()
            e.printStackTrace()
        } finally {
            active.close()
            store = null
        }
        eventBuffer.clear()
        pendingCount.set(0)
        println("[InputRecorder] Stopped recording. Events: $eventCount, dropped: ${droppedCount.get()}")
    }

    private fun writeTickContext(active: RecordingStore, gameTick: Int) {
        val client = Bootstrap.client
        val mainState = client.mainState
        var playerX = 0
        var playerY = 0
        var plane = 0
        if (mainState == MainState.LOGGED_IN) {
            try {
                val tile = localPlayer.tile
                playerX = tile.x
                playerY = tile.y
                plane = tile.plane
            } catch (_: Throwable) {
            }
        }
        active.addTickContext(
            monoNanos = System.nanoTime(),
            gameTick = gameTick,
            mainState = mainState,
            playerX = playerX,
            playerY = playerY,
            plane = plane,
            overlayCapture = overlayCapturing(),
        )
    }

    private fun drainEvents(active: RecordingStore) {
        var queued = eventBuffer.poll()
        while (queued != null) {
            pendingCount.decrementAndGet()
            writeEvent(active, queued)
            queued = eventBuffer.poll()
        }
    }

    private fun writeEvent(active: RecordingStore, queued: Queued) {
        val event = queued.event
        val source = queued.source
        trackForFeed(event)
        when (event) {
            is MouseMotionEvent -> active.addEvent(
                monoNanos = event.timestampNanos,
                kind = RecordingSchema.Kind.MOUSE_MOTION,
                source = source,
                gameTick = event.gameTick,
                x = event.x,
                y = event.y,
                clientTimestampMs = queued.clientTimestampMs,
            )
            is MouseButtonEvent -> active.addEvent(
                monoNanos = event.timestampNanos,
                kind = RecordingSchema.Kind.MOUSE_BUTTON,
                source = source,
                gameTick = event.gameTick,
                x = event.x,
                y = event.y,
                button = queued.rawButtonFlag ?: event.button.id,
                pressed = event.pressed.takeIf { queued.rawButtonFlag == null },
                clientTimestampMs = queued.clientTimestampMs,
            )
            is MouseScrollEvent -> active.addEvent(
                monoNanos = event.timestampNanos,
                kind = RecordingSchema.Kind.MOUSE_SCROLL,
                source = source,
                gameTick = event.gameTick,
                x = event.x,
                y = event.y,
                scrollDelta = event.scrollDelta,
            )
            is KeyboardEvent -> active.addEvent(
                monoNanos = event.timestampNanos,
                kind = RecordingSchema.Kind.KEYBOARD,
                source = source,
                gameTick = event.gameTick,
                pressed = event.pressed,
                keyCode = event.keyCode,
            )
            is KeyCharEvent -> active.addEvent(
                monoNanos = event.timestampNanos,
                kind = RecordingSchema.Kind.KEY_CHAR,
                source = source,
                gameTick = event.gameTick,
                keyCode = event.charCode,
            )
        }
    }

    /**
     * Right and middle have no client entry point to hook, so their state byte is sampled once per tick. That
     * makes their timing tick-granular and loses a press and release inside one tick entirely - the reason
     * these rows are tagged as polled rather than passed off as hook-rate input.
     */
    private fun pollButtons(active: RecordingStore, gameTick: Int) {
        pollButton(active, gameTick, OInputGlobals.RIGHT_BUTTON_STATE, MouseButton.RIGHT)
        pollButton(active, gameTick, OInputGlobals.MIDDLE_BUTTON_STATE, MouseButton.MIDDLE)
    }

    private fun pollButton(active: RecordingStore, gameTick: Int, stateOffset: Long, button: MouseButton) {
        try {
            val base = NativeAccess.BASE_ADDR
            val currentState = base.get(ValueLayout.JAVA_BYTE, stateOffset).toInt() and 0xFF
            val previous = lastButtonState.put(stateOffset, currentState)

            // A synthetic dispatch wrote this same byte, so the transition is ours - the baseline above is
            // already resynced, and recording it would teach the model our own clicks.
            if (SyntheticButtonState.consumeWritten(stateOffset)) return
            if (previous == null || currentState == previous) return

            writeEvent(
                active,
                Queued(
                    event = MouseButtonEvent(
                        timestampNanos = System.nanoTime(),
                        gameTick = gameTick,
                        x = base.get(ValueLayout.JAVA_FLOAT, OInputGlobals.MOUSE_X).toInt(),
                        y = base.get(ValueLayout.JAVA_FLOAT, OInputGlobals.MOUSE_Y).toInt(),
                        button = button,
                        pressed = currentState != 0,
                    ),
                    source = RecordingSchema.Source.POLLED,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    private fun trackForFeed(event: InputEvent) {
        val kind = when (event) {
            is MouseMotionEvent -> EventKind.MOTION
            is MouseButtonEvent -> EventKind.BUTTON
            is MouseScrollEvent -> EventKind.SCROLL
            is KeyboardEvent -> EventKind.KEY
            is KeyCharEvent -> EventKind.KEY
        }
        counts[kind.ordinal]++

        val line = when (event) {
            is MouseMotionEvent -> "t${event.gameTick}  move    (${event.x}, ${event.y})"
            is MouseButtonEvent -> "t${event.gameTick}  ${event.button.name.lowercase()} ${if (event.pressed) "down" else "up  "}  (${event.x}, ${event.y})"
            is MouseScrollEvent -> "t${event.gameTick}  scroll  ${event.scrollDelta} @ (${event.x}, ${event.y})"
            is KeyboardEvent -> "t${event.gameTick}  key     ${keyCodeName(event.keyCode)} ${if (event.pressed) "down" else "up"}"
            is KeyCharEvent -> "t${event.gameTick}  char    ${describeChar(event.charCode)}"
        }
        synchronized(feed) {
            feed.addFirst(FeedEntry(kind, line))
            while (feed.size > FEED_CAPACITY) feed.removeLast()
        }
    }

    private fun describeChar(charCode: Int): String =
        if (charCode in 32..126) "'${charCode.toChar()}'" else "0x${charCode.toString(16).uppercase()}"

    private fun resolvePlayerName(): String = try {
        Bootstrap.client.loggedInPlayer.getPlayerName() ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** Linux delivers SDL keycodes, Windows delivers virtual-key codes; the two are not interchangeable. */
    private fun keycodeNamespace(): String =
        if (Platform.current == Platform.WINDOWS) "win32_vk" else "sdl"

    private fun viewportSize(): Pair<Int, Int> = try {
        val (width, height) = NativeBridge.getDisplaySize()
        width.toInt() to height.toInt()
    } catch (_: Throwable) {
        0 to 0
    }

    /** The overlay swallows input before the client sees it, leaving a hole the reader has to know about. */
    private fun overlayCapturing(): Boolean = try {
        NativeBridge.wantCaptureMouse() || NativeBridge.wantCaptureKeyboard()
    } catch (_: Throwable) {
        false
    }
}
