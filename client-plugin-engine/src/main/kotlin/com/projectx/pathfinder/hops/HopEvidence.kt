package com.projectx.pathfinder.hops

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.TreeSet

/**
 * What has been seen about one key, as counts that add up across clients: strong and weak successes [s] and [w],
 * failures [f], and the successes since the latest failure ([sa], [wa]) by which a demoted key earns its way back.
 * Days are UTC epoch days. Not thread-safe; [HopKnowledge] guards every instance.
 */
internal class HopEvidence {
    var s = 0
    var w = 0
    var f = 0
    var sa = 0
    var wa = 0
    var firstDay = 0L
    var lastDay = 0L
    var failedDay = 0L
    var probedDay = 0L
    var build = ""
    var failedBuild = ""
    val contributors = TreeSet<String>()

    fun success(strong: Boolean, day: Long, build: String) {
        if (strong) s++ else w++
        if (f > 0) if (strong) sa++ else wa++
        touch(day, build)
    }

    fun failure(day: Long, build: String) {
        f++
        failedDay = day
        failedBuild = build
        sa = 0
        wa = 0
        touch(day, build)
    }

    private fun touch(day: Long, build: String) {
        if (firstDay == 0L || day < firstDay) firstDay = day
        if (day > lastDay) lastDay = day
        this.build = build
    }

    /** Folds [other] in: counts add; "since the latest failure" follows the newer failure. */
    fun add(other: HopEvidence) {
        s += other.s
        w += other.w
        f += other.f
        if (other.failedDay > failedDay) {
            sa = other.sa
            wa = other.wa
            failedDay = other.failedDay
            failedBuild = other.failedBuild
        } else if (other.failedDay == failedDay) {
            sa += other.sa
            wa += other.wa
        }
        if (other.firstDay != 0L && (firstDay == 0L || other.firstDay < firstDay)) firstDay = other.firstDay
        if (other.lastDay > lastDay) {
            lastDay = other.lastDay
            build = other.build
        }
        if (other.probedDay > probedDay) probedDay = other.probedDay
        contributors += other.contributors
    }

    fun copy(): HopEvidence = HopEvidence().also { it.add(this) }

    fun usableEdge(): Boolean = if (f == 0) 2 * s + w >= USABLE_SCORE else 2 * sa + wa >= RECOVER_SCORE

    fun toJson(key: String, contributor: String?): String {
        val o = JsonObject()
        o.addProperty("k", key)
        if (contributor != null) o.addProperty("c", contributor)
        o.addProperty("s", s)
        o.addProperty("w", w)
        o.addProperty("f", f)
        if (sa != 0) o.addProperty("sa", sa)
        if (wa != 0) o.addProperty("wa", wa)
        o.addProperty("fd", firstDay)
        o.addProperty("ld", lastDay)
        if (failedDay != 0L) {
            o.addProperty("xf", failedDay)
            o.addProperty("xb", failedBuild)
        }
        if (probedDay != 0L) o.addProperty("pd", probedDay)
        o.addProperty("b", build)
        if (contributor == null && contributors.isNotEmpty()) {
            o.add("cs", JsonArray().apply { contributors.forEach(::add) })
        }
        return o.toString()
    }

    override fun toString(): String = "s=$s w=$w f=$f"

    companion object {
        /** Score (2 per strong success, 1 per weak) an edge needs with no failure... */
        const val USABLE_SCORE = 4

        /** ...and after a failure, counting only the successes since. */
        const val RECOVER_SCORE = 6

        /** The evidence of one store line and its key, or null when the line is not one. */
        fun fromJson(o: JsonObject): Pair<String, HopEvidence>? {
            val key = o.get("k")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val e = HopEvidence()
            fun int(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            fun long(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            fun text(name: String) = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            e.s = int("s")
            e.w = int("w")
            e.f = int("f")
            e.sa = int("sa")
            e.wa = int("wa")
            e.firstDay = long("fd")
            e.lastDay = long("ld")
            e.failedDay = long("xf")
            e.probedDay = long("pd")
            e.build = text("b")
            e.failedBuild = text("xb")
            text("c").takeIf { it.isNotEmpty() }?.let(e.contributors::add)
            o.getAsJsonArray("cs")?.forEach { if (it.isJsonPrimitive) e.contributors += it.asString }
            return key to e
        }
    }
}
