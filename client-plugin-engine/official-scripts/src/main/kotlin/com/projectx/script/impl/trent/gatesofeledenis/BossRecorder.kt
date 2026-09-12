package com.projectx.script.impl.trent.gatesofeledenis

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.*
import world.gregs.voidps.type.Tile

private const val EVENT_LIMIT = 250_000
private const val SAMPLE_INTERVAL_MILLIS = 40L

class BossEvent(val cycle: Int, val millis: Long, val kind: String, val detail: String)

object BossRecorder {
    var paused = false
    var startedAt = System.currentTimeMillis()
        private set

    var dropped = 0
        private set

    private val events = ArrayDeque<BossEvent>()
    private val spotAnimAge = HashMap<String, Long>()
    private val projectileAge = HashMap<String, Long>()
    private val varpValues = HashMap<Int, Int>()
    private val nodeNames = HashMap<String, String>()
    private var lastAnimation = Int.MIN_VALUE
    private var lastTile: Tile? = null
    private var lastBars = ""
    private var lastNpcs = ""

    val size get() = events.size

    fun recent(count: Int) = events.takeLast(count)

    fun all() = events.toList()

    fun clear() {
        events.clear()
        spotAnimAge.clear()
        projectileAge.clear()
        varpValues.clear()
        nodeNames.clear()
        lastAnimation = Int.MIN_VALUE
        lastTile = null
        lastBars = ""
        lastNpcs = ""
        dropped = 0
        startedAt = System.currentTimeMillis()
    }

    var mirrorToLog = false

    fun add(kind: String, detail: String) {
        if (paused) return
        val event = BossEvent(Bootstrap.client.clientCycle, System.currentTimeMillis() - startedAt, kind, detail)
        if (mirrorToLog) println("[GoE] ${event.millis} ${event.cycle} $kind $detail")
        events.addLast(event)
        while (events.size > EVENT_LIMIT) {
            events.removeFirst()
            dropped++
        }
    }

    private var lastSampleAt = 0L

    fun sample() {
        if (paused || Bootstrap.client.mainState != MainState.LOGGED_IN) return
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MILLIS) return
        lastSampleAt = now
        runCatching {
            val here = localPlayer.tile
            sampleSpotAnims(here)
            sampleProjectiles(here)
            sampleVarps()
            samplePlayer(here)
            sampleGateBars()
            sampleNpcs()
            sampleNodes()
        }
    }

    private fun offset(tile: Tile, here: Tile) = "${tile.x - here.x},${tile.y - here.y}"

    private fun sampleSpotAnims(here: Tile) {
        val live = HashMap<String, Long>()
        for (anim in spotAnims) {
            val key = "${anim.id}@${anim.tile.x},${anim.tile.y}"
            live[key] = anim.timeAliveMillis
            if (!spotAnimAge.containsKey(key))
                add("SPOTANIM+", "id=${anim.id} at=${offset(anim.tile, here)} ageAtSight=${anim.timeAliveMillis}")
        }
        for ((key, age) in spotAnimAge) if (key !in live) add("SPOTANIM-", "$key lived=$age")
        spotAnimAge.clear()
        spotAnimAge.putAll(live)
    }

    private fun sampleProjectiles(here: Tile) {
        val live = HashMap<String, Long>()
        for (bolt in projectiles) {
            val key = "${bolt.id}@${bolt.ptr.address()}"
            live[key] = 0L
            if (!projectileAge.containsKey(key))
                add(
                    "BOLT+",
                    "id=${bolt.id} at=${offset(bolt.tile, here)} " +
                            "locked=${bolt.lockedOnto(localPlayer)} target=${bolt.lockedToServerIndex}"
                )
        }
        for (key in projectileAge.keys) if (key !in live) add("BOLT-", key.substringBefore('@'))
        projectileAge.clear()
        projectileAge.putAll(live)
    }

    private fun sampleVarps() {
        for ((name, id) in WATCHED_VARPS) {
            val value = varps.getVar(id)
            if (varpValues.put(id, value) != value) add("VAR", "$name=$value")
        }
    }

    private fun samplePlayer(here: Tile) {
        val animation = localPlayer.animationId
        if (animation != lastAnimation) {
            add("ANIM", "$animation")
            lastAnimation = animation
        }
        if (here != lastTile) {
            add("MOVE", "${here.x},${here.y}")
            lastTile = here
        }
    }

    private fun sampleGateBars() {
        val bars = npcs.values.filter { it.id in GATE_NPC_IDS }.flatMap { gate ->
            gate.headbars.map { "t${it.type}/d${it.durationMillis}" }
        }.sorted().joinToString(" ")
        if (bars != lastBars) {
            add("GATEBAR", if (bars.isEmpty()) "none" else bars)
            lastBars = bars
        }
    }

    private fun sampleNpcs() {
        val names = npcs.values.filter { it.name == "Feline akh" && it.currentHealth > 0 }
            .joinToString(" ") { "${it.name}#${it.serverIndex}" }
        if (names != lastNpcs) {
            add("NPC", if (names.isEmpty()) "none" else names)
            lastNpcs = names
        }
    }

    private fun sampleNodes() {
        for (node in getAllObjectsWithinRange(50)) {
            val name = node.name()
            if (name !in SHARD_NAMES && name != MOONSTONE) continue
            val key = "${node.tile.x},${node.tile.y}"
            val previous = nodeNames.put(key, name)
            if (previous != null && previous != name) add("NODE", "$key $previous -> $name")
        }
    }
}
