package com.projectx.pathfinder.hops

import com.projectx.pathfinder.StepRule
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The hop-knowledge overlay: a shared baseline everyone reads, plus a machine-local store only a learning client
 * writes. The overlay only ever adds hop-passable edges and hop-stoppers to the hop rule; walking never reads it.
 * This machine's own exported baseline lines and its local store hold the same hops, so they merge by the larger
 * count rather than the sum.
 */
internal class HopKnowledge(
    baselineLines: List<String>,
    private val store: HopStore?,
    private val contributor: String?,
) {
    private class View(val pass: Set<Long>, val stop: Set<Long>, val all: Map<String, HopEvidence>)

    private val lock = Any()
    private val baseline = sortedMapOf<String, HopEvidence>()
    private val baselineOwn = sortedMapOf<String, HopEvidence>()
    private var local: MutableMap<String, HopEvidence> = sortedMapOf()
    private val pending = sortedMapOf<String, HopEvidence>()

    @Volatile
    private var view = View(emptySet(), emptySet(), emptyMap())

    val learning: Boolean get() = store != null

    init {
        split(baselineLines)
        store?.let { learned ->
            runCatching { learned.rotate() }
            local = runCatching { learned.load() }.getOrDefault(sortedMapOf())
        }
        rebuild()
    }

    fun passable(x: Int, y: Int, plane: Int, direction: Direction): Boolean =
        view.pass.isNotEmpty() && HopEdges.pack(x, y, plane, direction) in view.pass

    fun stops(x: Int, y: Int, plane: Int, direction: Direction): Boolean =
        view.stop.isNotEmpty() && HopEdges.pack(x, y, plane, direction) in view.stop

    /** [walk], plus usable hop-passable edges whose far tile is [known], minus usable hop-stoppers. */
    fun rule(walk: StepRule, known: (Tile) -> Boolean): StepRule = StepRule { x, y, plane, d ->
        when {
            stops(x, y, plane, d) -> false
            walk.canStep(x, y, plane, d) -> true
            else -> passable(x, y, plane, d) && known(Tile.of(x + d.delta.x, y + d.delta.y, plane))
        }
    }

    fun learnedIn(pass: Boolean, minX: Int, minY: Int, maxX: Int, maxY: Int, plane: Int): List<Long> =
        (if (pass) view.pass else view.stop).filter {
            HopEdges.plane(it) == plane && HopEdges.x(it) in minX..maxX && HopEdges.y(it) in minY..maxY
        }

    fun evidence(key: String): HopEvidence? = view.all[key]?.copy()

    val size: Int get() = view.all.size

    /** Applies [change] to [key]'s evidence and queues it for the store; nothing on a client that does not learn. */
    fun record(key: String, change: (HopEvidence) -> Unit) {
        if (store == null) return
        synchronized(lock) {
            val delta = HopEvidence()
            change(delta)
            contributor?.let(delta.contributors::add)
            pending.merge(key, delta) { a, b -> a.also { it.add(b) } }
            local.merge(key, delta.copy()) { a, b -> a.also { it.add(b) } }
            rebuild()
        }
    }

    fun recordObservation(json: String) {
        store?.append(json)
    }

    fun flush() {
        val store = store ?: return
        val deltas = synchronized(lock) {
            if (pending.isEmpty()) return
            val copy = pending.toSortedMap()
            pending.clear()
            copy
        }
        val stored = store.flush(deltas)
        synchronized(lock) {
            if (stored == null) {
                for ((key, delta) in deltas) pending.merge(key, delta) { a, b -> a.also { it.add(b) } }
                return
            }
            for ((key, delta) in pending) stored.merge(key, delta.copy()) { a, b -> a.also { it.add(b) } }
            local = stored
            rebuild()
        }
    }

    private fun split(lines: List<String>) {
        for (line in lines) {
            if (line.isBlank()) continue
            val one = sortedMapOf<String, HopEvidence>()
            HopStore.readLines(listOf(line), one)
            for ((key, e) in one) {
                val mine = contributor != null && e.contributors.size == 1 && contributor in e.contributors
                (if (mine) baselineOwn else baseline).merge(key, e) { a, b -> a.also { it.add(b) } }
            }
        }
    }

    private fun rebuild() {
        val all = HashMap<String, HopEvidence>()
        for ((key, e) in baseline) all[key] = e.copy()
        val mine = HashMap<String, HopEvidence>()
        for ((key, e) in baselineOwn) mine[key] = e.copy()
        for ((key, e) in local) mine.merge(key, e.copy(), ::atLeast)
        for ((key, e) in mine) all.merge(key, e) { a, b -> a.also { it.add(b) } }
        val pass = HashSet<Long>()
        val stop = HashSet<Long>()
        for ((key, e) in all) {
            if (!e.usableEdge()) continue
            val packed = HopEdges.parse(key)
            if (packed < 0) continue
            if (key.startsWith(HopEdges.PASS + ":")) pass += packed else stop += packed
        }
        view = View(pass, stop, all)
    }

    companion object {
        /** The larger of two views of the same evidence, field by field. */
        fun atLeast(a: HopEvidence, b: HopEvidence): HopEvidence {
            val e = a.copy()
            e.s = maxOf(a.s, b.s)
            e.w = maxOf(a.w, b.w)
            e.f = maxOf(a.f, b.f)
            if (b.failedDay > a.failedDay) {
                e.sa = b.sa
                e.wa = b.wa
                e.failedDay = b.failedDay
                e.failedBuild = b.failedBuild
            } else if (b.failedDay == a.failedDay) {
                e.sa = maxOf(a.sa, b.sa)
                e.wa = maxOf(a.wa, b.wa)
            }
            if (b.firstDay != 0L && (e.firstDay == 0L || b.firstDay < e.firstDay)) e.firstDay = b.firstDay
            if (b.lastDay > e.lastDay) {
                e.lastDay = b.lastDay
                e.build = b.build
            }
            if (b.probedDay > e.probedDay) e.probedDay = b.probedDay
            e.contributors += b.contributors
            return e
        }
    }
}

/**
 * The live knowledge. Every client reads the shipped baseline (`/webwalker/hop_knowledge.jsonl`, empty until hops are
 * contributed); only one started with `-Dprojectx.hopLearning=true` records hops into `~/.projectx/hops`.
 */
internal object Hops {
    private const val BASELINE = "/webwalker/hop_knowledge.jsonl"

    val learning: Boolean = System.getProperty("projectx.hopLearning") == "true"

    private val contributor: String? = System.getProperty("projectx.hopContributor")
        ?: runCatching { Integer.toHexString(System.getProperty("user.name").hashCode()) }.getOrNull()

    val knowledge: HopKnowledge by lazy {
        val lines = Hops::class.java.getResourceAsStream(BASELINE)?.use { it.reader().readLines() }.orEmpty()
        val store = if (learning) HopStore(storeDir()) else null
        HopKnowledge(lines, store, contributor).also {
            println("[hops] knowledge: ${it.size} keys" + if (learning) " (learning into ${store?.dir})" else " (read-only)")
        }
    }

    val recorder: HopRecorder by lazy { HopRecorder(knowledge, contributor) }

    private fun storeDir(): Path = Paths.get(System.getProperty("user.home"), ".projectx", "hops").also {
        runCatching { Files.createDirectories(it) }
    }
}
