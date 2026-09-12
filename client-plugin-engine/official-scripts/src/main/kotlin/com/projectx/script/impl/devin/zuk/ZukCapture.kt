package com.projectx.script.impl.devin.zuk

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllSpotAnimsWithinRange
import com.projectx.script.api.healthPercent
import com.projectx.script.api.localPlayer
import com.projectx.script.api.prayerPercent
import com.projectx.script.api.players
import com.projectx.script.api.projectiles
import com.projectx.script.api.varps
import org.projectx.core.game.combat.Effect
import com.projectx.pathfinder.WorldCollision
import com.projectx.pathfinder.hasLineOfSight
import com.projectx.pathfinder.routeToTile
import world.gregs.voidps.path.toTiles

/**
 * Diagnostic capture for a full recorded attempt. Events are logged the moment they change so a
 * transient projectile or hitsplat is never missed between snapshots, and each line is prefixed so
 * it can be grepped out and correlated against a packet capture by timestamp.
 *
 * Every read is individually guarded: a single unreadable field must not cost the rest of the line.
 */
object ZukCapture {

    private const val TAG = "[ZukCap]"
    private const val SNAPSHOT_INTERVAL_MS = 1000L
    private const val NEARBY = 40

    private var lastSnapshot = 0L
    private var lastWave: Int? = null
    private var lastAction: String? = null
    private var lastPrayer: String? = null
    private var lastAttackers: String? = null
    private val seenHits = HashSet<String>()
    private val seenProjectiles = HashMap<String, Long>()

    /** Comfortably longer than one projectile's flight (~1.7s measured) and shorter than the 3.0s gap. */
    private const val PROJECTILE_KEY_TTL_MS = 2_500L
    private val seenSpotAnims = HashMap<String, Long>()
    private val bossAnims = HashMap<Int, Pair<Int, Int>>()
    private val seenMinions = HashSet<Int>()
    private var barLogged = false
    private val immunityRemaining = HashMap<Effect, Long>()

    private val TRACKED_IMMUNITY = listOf(Effect.DEVOTION, Effect.BARRICADE, Effect.RESONANCE)

    fun reset() {
        lastSnapshot = 0
        lastWave = null
        lastAction = null
        lastPrayer = null
        lastAttackers = null
        seenHits.clear()
        seenProjectiles.clear()
        seenSpotAnims.clear()
        bossAnims.clear()
        seenMinions.clear()
        barLogged = false
        immunityRemaining.clear()
        garbageImmunityLogged.clear()
    }

    private fun log(line: String) = println("$TAG $line")

    fun message(matched: ZukMessage, raw: String) = log("MESSAGE ${matched.name} \"$raw\"")

    /** Not debug-gated — presses are rare, and the 20:42 over-consumption was invisible without this. */
    fun sustain(kind: String) {
        val hp = runCatching { healthPercent }.getOrNull()
        val prayer = runCatching { prayerPercent }.getOrNull()
        log("SUSTAIN kind=$kind hp=$hp prayer=$prayer")
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        runCatching(block).onFailure { log("ERR $what: $it") }
    }

    /** Called every script tick so nothing transient is missed. */
    fun tick(action: ZukAction?, prayer: CombatStyle?) {
        guarded("wave") {
            val wave = ZukWaves.currentWaveNumber()
            if (wave != lastWave) {
                lastWave = wave
                log("WAVE now=$wave type=${ZukWaves.current()?.type} idx=${varps.getVarBit(ZukIds.WAVE_INDEX_VARBIT)}")
            }
        }
        guarded("action") {
            val label = action?.label
            if (label != lastAction) {
                lastAction = label
                log("ACTION $label")
            }
        }
        guarded("prayer") {
            val label = prayer?.displayName
            if (label != lastPrayer) {
                lastPrayer = label
                log("PRAYER want=$label stunned=${ZukActions.targetStunned()} vulned=${ZukActions.targetVulnerable()}")
            }
        }
        guarded("bar") { captureActionBar() }
        guarded("immunity") { captureImmunity() }
        guarded("hits") { captureHits() }
        guarded("projectiles") { captureProjectiles() }
        guarded("anims") { captureAnims() }
        guarded("attackers") { captureAttackers() }
        guarded("spotAnims") { captureSpotAnims() }
        guarded("minions") { captureMinions() }

        val now = System.currentTimeMillis()
        if (now - lastSnapshot >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshot = now
            snapshot()
        }
    }

    /**
     * Logged once, because the wave-15 chain is only a fix if the abilities it prescribes are actually
     * barred — and no capture so far proves Devotion is.
     */
    private fun captureActionBar() {
        if (barLogged) return
        val bar = parseAllActionBarAbilities()
        if (bar.isEmpty()) return
        barLogged = true
        log("BAR n=${bar.size} [${bar.keys.joinToString { "${it.name}#${it.structId}" }}]")
        for (effect in TRACKED_IMMUNITY) {
            log("BAR immunity=${effect.name} barred=${bar.keys.any { it.structId == effect.structId }}")
        }
    }

    /**
     * Duration is the one number the wave-15 chain still guesses at. Sampling it live turns "which two
     * abilities cover 9.0s" into a measurement instead of an inference. Garbage reads (never-cast
     * effects report days of remaining time) are logged once per streak, not per tick — the 17:45
     * run wrote 15,961 lines of a single bogus Devotion countdown.
     */
    private fun captureImmunity() {
        for (effect in TRACKED_IMMUNITY) {
            val remaining = effect.timeRemaining
            val was = immunityRemaining.put(effect, remaining)
            if (was == null && remaining <= 0L) continue
            if (was == remaining) continue
            if (remaining !in 1..30_000) {
                if (garbageImmunityLogged.add(effect)) {
                    log("IMMUNITY ${effect.name} remainingMs=$remaining active=${effect.active} sane=false")
                }
                continue
            }
            garbageImmunityLogged.remove(effect)
            log("IMMUNITY ${effect.name} remainingMs=$remaining active=${effect.active}")
        }
    }

    private val garbageImmunityLogged = HashSet<Effect>()

    /** Damage actually taken, which is the ground truth for mapping a projectile or animation to a style. */
    private fun captureHits() {
        val player = localPlayer
        for (hit in player.hits) {
            val key = "${hit.createdClientcycle}:${hit.typeId}:${hit.damage}"
            if (!seenHits.add(key)) continue
            log("HIT type=${hit.type} typeId=${hit.typeId} dmg=${hit.damage} cycle=${hit.createdClientcycle}")
        }
    }

    /**
     * Keys expire, because a later projectile of the same id retracing the same path is a *different*
     * attack. Keeping them forever silently swallowed the wave-15 third and fourth attacks, which is
     * how their existence went unnoticed.
     */
    private fun captureProjectiles() {
        val now = System.currentTimeMillis()
        seenProjectiles.values.removeIf { now - it > PROJECTILE_KEY_TTL_MS }
        for (p in projectiles) {
            runCatching {
                val locked = p.lockedOnto(localPlayer)
                val key = "${p.id}:${p.tile}"
                if (seenProjectiles.put(key, now) != null) return@runCatching
                log("PROJECTILE id=${p.id} locked=$locked tile=${p.tile} style=${ZukIds.PROJECTILE_STYLE[p.id]}")
            }
        }
    }

    /**
     * Every npc's animation, plus whether it is targeting the player.
     *
     * Deriving which animation means which attack style needs a window where exactly one attacker is
     * acting; restricting this to Zuk and Jad made that impossible, because Jad is never alone in any
     * capture — Mejkot, Kih and Xil are always up alongside it, and their damage swamps the
     * correlation. `attacking=` is the discriminator: an animation from the only npc targeting the
     * player attributes cleanly.
     */
    private fun captureAnims() {
        val player = localPlayer
        for (npc in allNpcsWithinRange(NEARBY) { it.exists() }) {
            runCatching {
                val animation = npc.animation ?: return@runCatching
                val anim = animation.id
                val frame = animation.currentFrame
                val previous = bossAnims[npc.serverIndex]
                val replayed = previous != null && previous.first == anim && frame < previous.second
                val unchanged = previous != null && previous.first == anim && !replayed
                bossAnims[npc.serverIndex] = anim to frame
                if (unchanged) return@runCatching
                log(
                    "ANIM npc=${npc.name()} id=${npc.typeId} idx=${npc.serverIndex} anim=$anim frame=$frame " +
                        "replay=$replayed attacking=${npc.interactingWith(player)} " +
                        "dist=${npc.tile.getDistance(player.tile)} tell=${ZukIds.ZUK_ANIM_STYLE[anim]}"
                )
            }
        }
    }

    /**
     * Who is actually targeting the player, which is the only honest basis for weighting threat.
     *
     * The change key includes the assessment, not just the attacker set: engagement can lapse (anim
     * recency expiring) and flip the dominant style while the same npcs stay locked on, and scoring
     * the model against hitsplats needs exactly those transitions in the log.
     */
    private fun captureAttackers() {
        val player = localPlayer
        val attacking = allNpcsWithinRange(NEARBY) { it.exists() && it.interactingWith(player) }
            .mapNotNull { npc ->
                runCatching {
                    "${npc.name()}#${npc.typeId}@${npc.tile.getDistance(player.tile)}:${npc.combatStyle()}"
                }.getOrNull()
            }
        val a = runCatching { ZukThreatModel.assess() }.getOrNull()
        val key = "${attacking.sorted()}|${a?.byStyle}|${a?.nothingCanHitUs}"
        if (key == lastAttackers) return
        lastAttackers = key
        log(
            "ATTACKERS n=${attacking.size} [${attacking.joinToString()}] " +
                "| engaged=${a?.engagements?.size} byStyle=${a?.byStyle} dominant=${a?.dominant()} " +
                "jad=${a?.jadEngaged} safe=${a?.nothingCanHitUs} [${a?.describe()}]"
        )
    }

    /** Keyed id:tile with a TTL — the per-id-forever dedupe hid every recurrence, exactly the bug
     *  class that swallowed the wave-15 projectiles and Jad's anims before it. */
    private fun captureSpotAnims() {
        val now = System.currentTimeMillis()
        seenSpotAnims.values.removeIf { now - it > PROJECTILE_KEY_TTL_MS }
        for (anim in getAllSpotAnimsWithinRange(NEARBY) { true }) {
            runCatching {
                val key = "${anim.id}:${anim.tile}"
                if (seenSpotAnims.put(key, now) != null) return@runCatching
                log("SPOTANIM id=${anim.id} tile=${anim.tile}")
            }
        }
    }

    /**
     * Deliberately unfiltered: recognising an npc requires it to already be in [ZukMinion], so
     * filtering here would hide exactly the ids that are missing from that table.
     */
    private fun captureMinions() {
        for (npc in allNpcsWithinRange(NEARBY) { it.exists() }) {
            runCatching {
                if (!seenMinions.add(npc.serverIndex)) return@runCatching
                log(
                    "SPAWN ${npc.name()} id=${npc.id} typeId=${npc.typeId} idx=${npc.serverIndex} " +
                        "tile=${npc.tile} size=${npc.size} known=${npc.zukMinion() != null} " +
                        "style=${npc.combatStyle()} hp=${npc.currentHealth}/${npc.maxHealth}"
                )
            }
        }
    }

    /**
     * The overlay no longer colours NPCs by reachability because it was wrong in play and the run
     * that proved it captured nothing to diagnose from. This logs the raw inputs per NPC so a later
     * attempt can be checked against which NPCs actually landed hits.
     */
    private fun logExposure() {
        val player = localPlayer.tile
        for (npc in allNpcsWithinRange(NEARBY) { it.isOverlayTarget() }) {
            runCatching {
                val tile = npc.tile
                val size = npc.size
                val route = routeToTile(tile, player, size, moveNear = true)
                val end = route.toTiles(player.plane).lastOrNull()
                log(
                    "EXPOSURE ${npc.name()} id=${npc.typeId} idx=${npc.serverIndex} style=${npc.combatStyle()} " +
                        "tile=$tile size=$size dist=${tile.getDistance(player)} " +
                        "los=${hasLineOfSight(tile, size, player, 1)} " +
                        "routeOk=${route.success} routeEnd=$end"
                )
            }
        }
    }

    private fun snapshot() {
        guarded("snapshot.player") {
            val lip = Bootstrap.client.loggedInPlayer
            val self = lip.self
            val fine = self.graphNode.tileFine
            log(
                "SELF idx=${lip.serverIndex} tile=${runCatching { self.tile }.getOrNull()} size=${self.size} " +
                    "plane=${self.plane} fine=(${fine.x},${fine.y})"
            )
            players.firstOrNull()?.let {
                log("PLAYERS0 tile=${runCatching { it.tile }.getOrNull()} fine=(${it.graphNode.tileFine.x},${it.graphNode.tileFine.y})")
            }
        }
        guarded("snapshot.arena") {
            log(
                "ARENA anchor=${ZukArena.layout?.zuk} inDynamic=${WorldCollision.inDynamic} " +
                    "sceneBase=${WorldCollision.sceneBase}"
            )
        }
        guarded("snapshot.vars") {
            log(
                "VARS waveIdx=${varps.getVarBit(ZukIds.WAVE_INDEX_VARBIT)} mode=${varps.getVarBit(ZukIds.ENCOUNTER_MODE_VARBIT)} " +
                    "practice=${varps.getVarBit(ZukIds.PRACTICE_MODE_VARBIT)} " +
                    "stunned=${varps.getVarBit(ZukIds.TARGET_STUNNED_VARBIT)} " +
                    "bound=${varps.getVarBit(ZukIds.TARGET_BOUND_VARBIT)} " +
                    "vuln=${varps.getVarBit(ZukIds.TARGET_VULNERABLE_VARBIT)}"
            )
        }
        guarded("snapshot.exposure") { logExposure() }
        guarded("snapshot.roster") {
            val roster = allNpcsWithinRange(NEARBY) { it.isOverlayTarget() }
                .mapNotNull { npc -> runCatching { "${npc.name()}@${npc.tile.x},${npc.tile.y}" }.getOrNull() }
            log("ROSTER n=${roster.size} $roster")
        }
    }
}
