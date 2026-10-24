package com.projectx.pathfinder.hops

import com.projectx.pathfinder.WalkMap
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the hops of a learning client and turns them into evidence: [begin] at the cast captures the window,
 * [Pending.observe] while it arms, [Pending.finish] once it landed, was refused or never happened. Explaining and
 * writing run on a virtual thread, so the walk never waits for them. A client that does not learn gets null.
 */
internal class HopRecorder(private val knowledge: HopKnowledge, private val contributor: String?) {
    private val sequence = AtomicInteger()

    inner class Pending(val observation: HopObservation) {
        private var done = false

        /** A tile seen before the jump: the hop may have fired from it. */
        @Synchronized
        fun observe(tile: Tile) {
            if (done || tile.plane != observation.plane) return
            val last = observation.starts.lastOrNull()
            if (last != null && (tile == last || tile.getLongestDelta(last) > 2)) return
            if (observation.starts.size < MAX_STARTS) observation.starts += tile
        }

        @Synchronized
        fun noteWalkClick() {
            if (!done) observation.held = false
        }

        @Synchronized
        fun finish(landed: Tile?, outcome: String) {
            if (done) return
            done = true
            observation.landed = landed
            observation.outcome = outcome
            Thread.ofVirtual().name("hops-explain").start { settle(observation) }
        }
    }

    fun begin(ability: String, from: Tile, direction: Direction?, aim: Tile?, map: WalkMap, occupied: List<Tile>): Pending? {
        if (!knowledge.learning) return null
        val o = HopObservation()
        o.id = "${contributor ?: "local"}-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        o.time = System.currentTimeMillis()
        o.ability = ability
        o.from = from
        o.starts += from
        o.direction = direction
        o.aim = aim
        o.capture(from, map, knowledge)
        val spec = HopExplainer.Spec(ability, direction, aim)
        o.predicted = HopExplainer.run(spec, map.rule(), from)
        o.predictedLearned = HopExplainer.run(spec, map.hopRule(), from)
        for (tile in occupied) if (tile.plane == o.plane) o.markOccupied(tile.x, tile.y)
        return Pending(o)
    }

    private fun settle(o: HopObservation) {
        try {
            val result = HopExplainer.explain(o)
            apply(o, result) { key, change -> knowledge.record(key, change) }
            knowledge.recordObservation(o.toJson())
            knowledge.flush()
            if (result.kind != HopExplainer.Kind.MATCH) {
                println("[hops] ${o.ability} from ${o.from}" + (o.aim?.let { " at $it" } ?: " facing ${o.direction}") +
                    ": predicted ${o.predicted}, ${o.outcome} ${o.landed} -> $result")
            }
        } catch (e: RuntimeException) {
            println("[hops] could not settle hop ${o.id}: $e")
        }
    }

    companion object {
        const val MAX_STARTS = 8

        /** Folds an explanation into evidence through [put]. */
        fun apply(o: HopObservation, result: HopExplainer.Result, put: (String, (HopEvidence) -> Unit) -> Unit) {
            val day = HopStore.today(o.time)
            val build = o.build
            when (result.kind) {
                HopExplainer.Kind.PASS -> result.pass.forEach { put(HopEdges.key(HopEdges.PASS, it)) { e -> e.success(result.strong, day, build) } }
                HopExplainer.Kind.LEARNED_OK -> result.confirmed.forEach { put(HopEdges.key(HopEdges.PASS, it)) { e -> e.success(result.strong, day, build) } }
                HopExplainer.Kind.LEARNED_FAILED -> result.failed.forEach { edge ->
                    val kind = if (edge in o.learnedPass) HopEdges.PASS else HopEdges.STOP
                    put(HopEdges.key(kind, edge)) { e -> e.failure(day, build) }
                }
                HopExplainer.Kind.STOP -> result.stop.forEach { put(HopEdges.key(HopEdges.STOP, it)) { e -> e.success(result.strong, day, build) } }
                HopExplainer.Kind.REFUSED -> result.stop.forEach { put(HopEdges.key(HopEdges.STOP, it)) { e -> e.success(false, day, build) } }
                else -> {}
            }
        }
    }
}
