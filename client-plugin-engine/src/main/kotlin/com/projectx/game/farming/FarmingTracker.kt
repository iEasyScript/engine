package com.projectx.game.farming

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.PlayerVarDomain
import com.projectx.script.api.timeLoggedInAt
import com.projectx.ui.UIState
import com.projectx.util.showNotification
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/**
 * Reads every farming patch's live status straight from the cache: each patch is a loc whose
 * appearance morphs on a controlling varbit, so the loc's transform array (the child-loc id per
 * varbit value) plus the child loc's gameval name suffix *is* the status - produce-agnostic and
 * self-correcting across cache updates. Nothing here is hard-coded per produce.
 */
object FarmingTracker {

    enum class Status(val label: String) {
        EMPTY("Empty"),
        WEEDS("Weeds"),
        GROWING("Growing"),
        GROWN("Grown"),
        PRODUCE("Produce"),
        DISEASED("Diseased"),
        DEAD("Dead"),
        HARVESTED("Harvested"),
        UNKNOWN("Unknown")
    }

    data class Row(
        val locId: Int,
        val category: PatchCategory,
        val name: String,
        val produce: String,
        val status: Status,
        val statusLabel: String,
        val notifyKey: String
    )

    private const val SCAN_INTERVAL_MS = 2_000L
    private const val LOGIN_COOLDOWN_MS = 15_000L

    private val lastNotified = HashMap<Int, String>()
    private var lastScanMs = 0L

    @Volatile var rows: List<Row> = emptyList()
        private set

    fun reset() {
        lastNotified.clear()
        rows = emptyList()
    }

    fun tick() {
        if (!UIState.farmingTrackerEnabled.value) return
        try {
            val client = runCatching { Bootstrap.client }.getOrNull() ?: return
            if (runCatching { client.mainState }.getOrNull() != MainState.LOGGED_IN) return
            val now = System.currentTimeMillis()
            if (now - lastScanMs < SCAN_INTERVAL_MS) return
            lastScanMs = now

            val domain = client.playerVarDomain
            if (domain.ptr.address() == 0L) return

            val withinLoginCooldown = (now - timeLoggedInAt) < LOGIN_COOLDOWN_MS
            val notify = UIState.farmingNotificationsEnabled.value
            val includeSecondary = UIState.farmingShowSecondary.value

            val nextRows = ArrayList<Row>(FarmingPatches.all.size)
            for (patch in FarmingPatches.all) {
                if (!patch.category.primary && !includeSecondary) continue
                val row = readPatch(patch, domain) ?: continue
                nextRows.add(row)

                val prev = lastNotified.put(patch.locId, row.notifyKey)
                val changed = prev != null && prev != row.notifyKey
                if (changed && row.notifyKey.isNotEmpty() && notify && !withinLoginCooldown) {
                    showNotification("Farming - ${patch.name}", notifyMessage(row))
                }
            }
            rows = nextRows
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun readPatch(patch: PatchDef, domain: PlayerVarDomain): Row? {
        val loc = Cache.loc(patch.locId) ?: return null
        val varbit = loc.varbit
        val transforms = loc.transforms
        if (varbit == -1 || transforms == null || transforms.isEmpty()) return null

        val value = runCatching { domain.getVarBit(varbit) }.getOrDefault(0)
        val childId = childAt(transforms, value)
        val childName = if (childId > 0) Gameval.loc(childId) else null
        val status = classify(childName)
        val full = status == Status.PRODUCE && isFullProduce(transforms, value, childName)

        return Row(
            locId = patch.locId,
            category = patch.category,
            name = patch.name,
            produce = produceOf(childName),
            status = status,
            statusLabel = statusLabel(patch.category, status, full),
            notifyKey = notifyKey(status, full)
        )
    }

    private fun childAt(transforms: IntArray, value: Int): Int =
        if (value in 0..transforms.size - 2) transforms[value] else transforms[transforms.size - 1]

    private fun classify(childName: String?): Status {
        val n = childName?.lowercase() ?: return Status.EMPTY
        return when {
            n.contains("dead") -> Status.DEAD
            n.contains("diseased") -> Status.DISEASED
            n.contains("weeds") -> Status.WEEDS
            n.endsWith("weeded") -> Status.EMPTY
            n.contains("stump") || n.contains("claim") || n.contains("picked") -> Status.HARVESTED
            n.contains("_fruit_") || n.contains("_berry_") || n.contains("_spines_") -> Status.PRODUCE
            n.endsWith("fullygrown") -> Status.GROWN
            n.contains("seedling") || n.endsWith("_seed") -> Status.GROWING
            n.contains("_watered") -> Status.GROWING
            n.matches(STAGE_REGEX) -> Status.GROWING
            else -> Status.UNKNOWN
        }
    }

    private fun isFullProduce(transforms: IntArray, value: Int, childName: String?): Boolean {
        val name = childName ?: return false
        val nextChild = childAt(transforms, value + 1)
        val nextName = if (nextChild > 0) Gameval.loc(nextChild) else null
        val runPrefix = name.dropLastWhile { it.isDigit() }
        val n = name.takeLastWhile { it.isDigit() }.toIntOrNull() ?: return true
        return nextName != "$runPrefix${n + 1}"
    }

    private fun notifyKey(status: Status, full: Boolean): String = when (status) {
        Status.GROWN -> "grown"
        Status.DISEASED -> "diseased"
        Status.PRODUCE -> if (full) "fullproduce" else ""
        else -> ""
    }

    private fun statusLabel(category: PatchCategory, status: Status, full: Boolean): String = when (status) {
        Status.GROWN -> if (category.checkHealth) "Grown - check health" else "Ready to harvest"
        Status.PRODUCE -> if (full) "Full produce - ready" else "Producing"
        else -> status.label
    }

    private fun notifyMessage(row: Row): String {
        val produce = if (row.produce.isNotBlank()) "${row.produce} " else ""
        return when (row.notifyKey) {
            "grown" -> if (row.category.checkHealth) "${produce}has fully grown - check its health." else "${produce}is fully grown and ready to harvest."
            "diseased" -> "${produce}has become diseased."
            "fullproduce" -> "${produce}has full produce ready to pick."
            else -> row.statusLabel
        }
    }

    private fun produceOf(childName: String?): String {
        val n = childName ?: return ""
        if (n.contains("weed") || n.endsWith("weeded")) return ""
        val produce = n.replace(SUFFIX_REGEX, "").trim('_')
        if (produce.isEmpty()) return ""
        return produce.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    private val STAGE_REGEX = Regex(".*_\\d+(_watered)?$")
    private val SUFFIX_REGEX = Regex(
        "(_seedling|_seed|_fullygrown.*|_diseased.*|_dead.*|_fruit_\\d+|_berry_\\d+|_spines_\\d+|_\\d+(_watered)?|_watered|_stump.*|_claim\\w*|_picked.*)$"
    )
}
