package com.projectx.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.store.StartRefusal.Reason
import com.projectx.ui.compose.GameThread
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The client slot this engine holds on the site. An account runs as many clients at once as it has instances - one
 * free, more rented, bought or with a membership, shared with the OSRS client - and the site keeps the count so it
 * holds across machines. No script starts without a slot. A running script carries on through a failed heartbeat;
 * only a Stop pressed on the website stops it.
 */
internal object Instances {
    private const val HEARTBEAT_SECONDS = 30L
    private const val STOPPED = "stopped"

    enum class State { CHECKING, HELD, SIGNED_OUT, SIGN_IN_EXPIRED, FULL, UNREACHABLE, STOPPED }

    @Volatile
    var state = State.CHECKING
        private set

    @Volatile
    var used = 0
        private set

    @Volatile
    var total = 0
        private set

    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "projectx-instances").apply { isDaemon = true } }

    @Volatile
    private var started = false

    fun instancesUrl() = "${Entitlements.storeBase()}/instances"

    @Synchronized
    fun start() {
        if (started) return
        started = true
        worker.scheduleWithFixedDelay({ runCatching { tick() } }, 0, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    }

    fun mayStart(script: Script): Boolean {
        if (state == State.HELD) return true
        val title = script.javaClass.getAnnotation(ScriptDescription::class.java)?.name ?: script.javaClass.simpleName
        StoreNotices.refused(title, null, refusalFor(state))
        worker.submit {
            runCatching { tick() }
            StoreNotices.refused(title, null, if (state == State.HELD) Reason.INSTANCE_READY else refusalFor(state))
        }
        return false
    }

    fun release() {
        if (state != State.HELD) return
        val token = Entitlements.token() ?: return
        runCatching { post(token, "release", Duration.ofSeconds(3)) }
        state = State.CHECKING
    }

    @Synchronized
    private fun tick() {
        if (state == State.STOPPED) return
        val token = Entitlements.token()
        if (token == null) {
            state = State.SIGNED_OUT
            return
        }
        val beat = state == State.HELD
        val response = runCatching { post(token, if (beat) "heartbeat" else "claim", Duration.ofSeconds(15)) }.getOrNull()
        val answer = response?.let { runCatching { JsonParser.parseString(it.body()).asJsonObject }.getOrNull() }
        answer?.getAsJsonObject("allowance")?.let {
            used = it.get("used")?.asInt ?: used
            total = it.get("total")?.asInt ?: total
        }
        val next = next(state, response?.statusCode(), answer?.get("error")?.asString, local())
        if (next != state) println("[instances] ${describe(next)}")
        if (next == State.STOPPED) GameThread.post { ScriptExecutor.stopAll() }
        state = next
    }

    /**
     * What a claim or heartbeat answer means. [status] is null when the site could not be reached: a heartbeat keeps
     * its slot through that, while a claim fails closed, except against a store running on this machine.
     */
    internal fun next(current: State, status: Int?, error: String?, localStore: Boolean): State {
        val beat = current == State.HELD
        return when {
            status == null -> if (beat || localStore) State.HELD else State.UNREACHABLE
            status in 200..299 -> State.HELD
            status == 401 -> State.SIGN_IN_EXPIRED
            status == 409 && error == STOPPED -> State.STOPPED
            status == 409 -> State.FULL
            beat -> State.HELD
            else -> State.UNREACHABLE
        }
    }

    private fun post(token: String, action: String, timeout: Duration): HttpResponse<String> {
        val body = JsonObject().apply {
            addProperty("action", action)
            addProperty("sessionId", sessionId)
            addProperty("label", label())
        }
        val request = HttpRequest.newBuilder(URI.create("${Entitlements.storeBase()}/api/v1/instances"))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun label(): String {
        val machine = System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this computer")
        return "RS3 on $machine".take(60)
    }

    private fun local(): Boolean = runCatching { URI.create(Entitlements.storeBase()).host in setOf("localhost", "127.0.0.1") }.getOrDefault(false)

    private fun refusalFor(state: State) = when (state) {
        State.SIGNED_OUT -> Reason.INSTANCE_SIGNED_OUT
        State.SIGN_IN_EXPIRED -> Reason.SIGN_IN_EXPIRED
        State.FULL -> Reason.NO_INSTANCE
        State.STOPPED -> Reason.INSTANCE_STOPPED
        State.CHECKING, State.UNREACHABLE, State.HELD -> Reason.INSTANCE_UNREACHABLE
    }

    private fun describe(state: State) = when (state) {
        State.HELD -> "this client has an instance ($used of $total in use)."
        State.SIGNED_OUT -> "sign in with Discord in the launcher's Store tab to run scripts."
        State.SIGN_IN_EXPIRED -> "your store sign-in is no longer valid; sign in again on the launcher's Store tab."
        State.FULL -> "no instance free: $used of $total in use. Close a client or add an instance at ${instancesUrl()}"
        State.UNREACHABLE -> "couldn't reach the store to check your instances; trying again."
        State.STOPPED -> "this client was stopped from the website; its scripts have been stopped."
        State.CHECKING -> "checking your instances."
    }
}
