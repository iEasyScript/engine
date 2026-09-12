package com.projectx.game.input

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.motor.MotorDriver
import com.projectx.game.input.wire.TrailSample
import com.projectx.game.input.wire.WireInput
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.Client
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.OGlobal
import com.projectx.profiling.PlayerProfile
import com.projectx.profiling.PlayerProfiles
import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ThreadLocalRandom

/**
 * Drives a server-visible cursor trail from the trained motor model.
 *
 * Every pixel and every inter-sample interval comes from the model. There is deliberately no analytic
 * fallback: the engine used to draw a minimum-jerk curve with per-profile speed and tremor constants because
 * the previous model could not produce usable motion, and a hand-tuned curve is precisely the kind of regular,
 * parameterised signal mouse-trail biometrics look for. With no model trained for this player, nothing is
 * generated at all.
 *
 * Critical invariants - DO NOT VIOLATE:
 *  - Server-bound sends go through [WireInput] only, never through `com.projectx.game.input.action`. The real
 *    cursor is never touched and no local interaction is ever produced.
 *  - A trail is only generated toward a target a script actually asked for. Until the behaviour policy exists
 *    there is nothing that legitimately decides where an idle cursor should wander, and inventing one would be
 *    fabricating behaviour rather than reproducing the player's.
 */
object SyntheticInputShadow {
    private const val TRAIL_CAPACITY = 256
    private const val FALLBACK_VIEWPORT_WIDTH = 1920f
    private const val FALLBACK_VIEWPORT_HEIGHT = 1080f

    /** Samples released per tick. The wire batches whatever it gets into one history packet. */
    private const val SAMPLES_PER_TICK = 3

    private val trail = ConcurrentLinkedDeque<TrailPoint>()

    @Volatile private var driver: MotorDriver? = null

    @Volatile var currentModelKey: String = ""
        private set
    @Volatile var samplesGenerated: Long = 0
        private set
    @Volatile var reachesGenerated: Long = 0
        private set
    @Volatile var reachesDiscarded: Long = 0
        private set
    @Volatile var lastRolloutMillis: Double = 0.0
        private set

    @Volatile var lastSynthX: Float = FALLBACK_VIEWPORT_WIDTH / 2f
        private set
    @Volatile var lastSynthY: Float = FALLBACK_VIEWPORT_HEIGHT / 2f
        private set

    @Volatile private var pendingActionTarget: Pair<Float, Float>? = null
    @Volatile private var pendingClick: Boolean = false
    @Volatile private var pauseUntilNanos: Long = 0L

    enum class TrailKind { MOVE, CLICK }
    data class TrailPoint(val x: Float, val y: Float, val timestampNanos: Long, val kind: TrailKind)

    fun trailSnapshot(): List<TrailPoint> = trail.toList()

    fun isModelLoaded(): Boolean = driver != null

    val pendingSamples: Int get() = driver?.pending ?: 0

    fun loadModelForPlayer(playerKey: String) {
        if (playerKey.isBlank()) return
        if (playerKey == currentModelKey && driver != null) return
        val loaded = MotorDriver.forPlayer(playerKey)
        if (loaded == null) {
            println("[SyntheticInputShadow] No motor model for '$playerKey' - no trail will be generated.")
            return
        }
        driver?.close()
        driver = loaded
        currentModelKey = playerKey
        println("[SyntheticInputShadow] Motor model loaded for '$playerKey'.")
    }

    fun unload() {
        driver?.close()
        driver = null
        currentModelKey = ""
        trail.clear()
    }

    fun tick() {
        val profile = try { PlayerProfiles.get() } catch (_: Throwable) { return }
        if (!profile.synthInputEnabled) return
        val client = Bootstrap.client
        if (client.mainState != MainState.LOGGED_IN) return

        ensureModelLoaded(profile, client)
        val active = driver ?: return

        consumeIntents()
        val now = System.nanoTime()

        // Release what is already generated before starting anything new, so a reach reaches the wire at the
        // pace it was generated at rather than all in one tick.
        if (!active.isIdle) {
            emit(active, profile.synthSendToServer)
            return
        }

        if (pendingClick) {
            pendingClick = false
            trail.add(TrailPoint(lastSynthX, lastSynthY, now, TrailKind.CLICK))
            trimTrail()
            if (profile.synthSendToServer) {
                WireInput.sendClick(lastSynthX.toInt(), lastSynthY.toInt(), MouseButton.LEFT, clientClockMs())
            }
            pauseUntilNanos = now + idlePauseNanos()
            return
        }

        val target = pendingActionTarget ?: return
        if (now < pauseUntilNanos) return
        pendingActionTarget = null
        startReach(active, target)
    }

    private fun startReach(active: MotorDriver, target: Pair<Float, Float>) {
        val (width, height) = viewportSize()
        val reached = active.beginReach(
            startX = lastSynthX,
            startY = lastSynthY,
            targetX = target.first.coerceIn(0f, width - 1f),
            targetY = target.second.coerceIn(0f, height - 1f),
            viewportWidth = width,
            viewportHeight = height,
            clockMs = clientClockMs(),
        )
        lastRolloutMillis = active.lastRolloutMillis
        reachesGenerated++

        // An undertrained model wanders instead of arriving. Reporting that trail is worse than reporting
        // nothing, so it is discarded along with the click that would have followed it.
        if (!reached) {
            active.clear()
            reachesDiscarded++
            return
        }
        pendingClick = true
    }

    private fun emit(active: MotorDriver, sendToServer: Boolean) {
        val samples: List<TrailSample> = active.drain(SAMPLES_PER_TICK)
        if (samples.isEmpty()) return

        val now = System.nanoTime()
        for (sample in samples) {
            lastSynthX = sample.x.toFloat()
            lastSynthY = sample.y.toFloat()
            trail.add(TrailPoint(lastSynthX, lastSynthY, now, TrailKind.MOVE))
            samplesGenerated++
        }
        trimTrail()
        if (sendToServer) WireInput.sendTrail(samples) else WireInput.drainTrail()
    }

    private fun trimTrail() {
        while (trail.size > TRAIL_CAPACITY) trail.poll()
    }

    private fun consumeIntents() {
        var intent: ShadowIntent? = ShadowInputBus.poll()
        while (intent != null) {
            if (intent is DoActionShadow) {
                val x = intent.resolvedTargetX
                val y = intent.resolvedTargetY
                if (x != null && y != null) {
                    pendingActionTarget = x to y
                    pauseUntilNanos = 0L
                }
            }
            intent = ShadowInputBus.poll()
        }
    }

    private fun idlePauseNanos(): Long {
        val profile = PlayerProfiles.get()
        val low = profile.synthIdlePauseMinMs.coerceAtLeast(50L)
        val high = profile.synthIdlePauseMaxMs.coerceAtLeast(low + 1L)
        return ThreadLocalRandom.current().nextLong(low, high) * 1_000_000L
    }

    private fun viewportSize(): Pair<Float, Float> = try {
        val (w, h) = NativeBridge.getDisplaySize()
        w.coerceAtLeast(640f) to h.coerceAtLeast(480f)
    } catch (_: Throwable) {
        FALLBACK_VIEWPORT_WIDTH to FALLBACK_VIEWPORT_HEIGHT
    }

    /** The clock the client stamps its own input samples with; a trail must share it or its deltas are wrong. */
    private fun clientClockMs(): Long = try {
        NativeAccess.BASE_ADDR.get(JAVA_LONG, OGlobal.MONOTONIC_CLOCK_MS)
    } catch (_: Throwable) {
        0L
    }

    private fun ensureModelLoaded(profile: PlayerProfile, client: Client) {
        val desired = profile.synthModelPlayer.ifBlank {
            try { client.loggedInPlayer.getPlayerName() ?: "" } catch (_: Throwable) { "" }
        }
        if (desired.isBlank()) return
        if (desired != currentModelKey || driver == null) loadModelForPlayer(desired)
    }
}
