package com.projectx.script.impl.trent.clue

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.math.WorldToScreen
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.quest.overlay.stripHtml
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import kotlin.math.abs

private const val SCAN_INTERFACE = 1752
private const val DIG_ICON = 0
private const val SCAN_DATA = 3
private const val FOUND_TEXT = 4
private const val SCAN_ENUM_PREFIX = "trail_scan_"

/** Only claim a region when one of its spots is genuinely close; otherwise the player is still travelling. */
private const val REGION_RANGE = 80

/**
 * How long a pinned spot outlives its hint icon. Long enough to ride out a scene load that drops the
 * icon for a moment, short enough that finishing one clue cannot leave its spot marked over the next -
 * two scan clues in the same region keep the panel open throughout, so nothing else clears the pin.
 */
private const val HINT_MEMORY_MS = 5_000L

/**
 * Scan clues.
 *
 * The dig spot comes from one place: the game's own hint icon, read straight from the client's
 * hint-slot descriptors. It is exact, and the moment it exists nothing else is worth drawing.
 *
 * Before it appears the overlay shows the region's known dig coordinates as plain candidates, plus the
 * orb's own readout. Nothing here tries to infer the answer from the orb's pace count - that distance
 * is measured in a metric the client cannot see, and guessing at it produced confident wrong answers.
 */
class ScanModule : ClueModule {

    override val name = "Scan"

    private val regions: Map<String, List<Tile>> by lazy {
        Gameval.entries(Gameval.ENUM)
            .filterValues { it.startsWith(SCAN_ENUM_PREFIX) }
            .entries
            .associate { (id, enumName) ->
                enumName.removePrefix(SCAN_ENUM_PREFIX) to
                        Cache.enum(id)?.values?.values?.mapNotNull { packedTile(it as? Int ?: 0) }.orEmpty()
            }
            .filterValues { it.isNotEmpty() }
    }

    /** Every region's spots as one set - used only to tell our hint from someone else's. */
    private val digSpots: Set<Tile> by lazy { regions.values.flatten().toSet() }

    private var pinned: Tile? = null
    private var lastHintAt = 0L
    private var readout = ""
    private var region = ""
    private var candidates: List<Tile> = emptyList()

    override fun active() = isOnScreen(SCAN_INTERFACE)

    override fun reset() {
        pinned = null
        lastHintAt = 0L
        readout = ""
        region = ""
        candidates = emptyList()
    }

    override fun update() {
        readout = stripHtml(textOf(SCAN_INTERFACE, SCAN_DATA).ifEmpty { textOf(SCAN_INTERFACE, FOUND_TEXT) })
            .replace("\n", " ")
            .trim()
        val now = System.currentTimeMillis()
        val hint = findHint()
        if (hint != null) {
            lastHintAt = now
            if (hint != pinned) {
                pinned = hint
                println("[ClueHelper] scan hint icon at ${hint.x}, ${hint.y} lvl ${hint.level}")
            }
        } else if (pinned != null && now - lastHintAt > HINT_MEMORY_MS) {
            println("[ClueHelper] scan hint icon gone - dropping the pinned spot")
            pinned = null
        }
        if (pinned != null) {
            candidates = emptyList()
            return
        }
        val here = runCatching { localPlayer.tile }.getOrNull() ?: return
        // Nearest region, not the first one in range: Darkmeyer sits inside Morytania, so "any spot
        // within N tiles" happily picks the haunted woods list while standing in Darkmeyer.
        val nearest = regions.entries
            .mapNotNull { (name, spots) -> spots.minByOrNull { paces(here, it) }?.let { Triple(name, spots, paces(here, it)) } }
            .minByOrNull { it.third }
        if (nearest == null || nearest.third > REGION_RANGE) {
            region = ""
            candidates = emptyList()
            return
        }
        region = nearest.first
        candidates = nearest.second.sortedBy { paces(here, it) }
    }

    /**
     * A hint whose tile is a known scan dig coordinate is ours; anything else the server has placed
     * (a quest marker, a D&D) is not. Membership is checked against every region at once, so no region
     * has to be identified first.
     */
    private fun findHint(): Tile? {
        val hints = runCatching { Bootstrap.client.hintIcons }.getOrDefault(emptyList())
        val coordinates = hints.filter { it.isCoordinate }
        return (coordinates.filter { it.tile in digSpots }.ifEmpty { coordinates }).map { it.tile }.singleOrNull()
    }

    override fun nextAction(): ClueAction? {
        if (rectOf(SCAN_INTERFACE, DIG_ICON) == null) return null
        return ClueAction("dig scan spot") { IFSlot(SCAN_INTERFACE, DIG_ICON).click() }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val here = runCatching { localPlayer.tile }.getOrNull() ?: return
        with(scope) {
            val answer = pinned
            if (answer != null) {
                markAnswer(answer, here)
            } else {
                candidates.forEach { spot ->
                    if (spot.level != here.level) return@forEach
                    val color = ImGuiColors.CYAN or (120 shl 24)
                    val height = groundHeight(spot)
                    tile(spot, color, height)
                    textOnTile(spot, color, "${paces(here, spot)}", heightFine = height)
                }
            }
            anchor()?.let { panel(it, lines(here, answer), accent(answer)) }
        }
    }

    /** Deliberately loud: this is the tile the game itself is pointing at. */
    private fun BackgroundDrawListScope.markAnswer(answer: Tile, here: Tile) {
        val color = pulseAlpha(ImGuiColors.GREEN, minAlpha = 210)
        if (answer.level != here.level) {
            edgeBearing(here, answer, color, "DIG lvl ${answer.level}")
            return
        }
        val height = groundHeight(answer)
        tile(answer, color, height)
        val centre = runCatching { WorldToScreen.getEstimatedTileCenter(answer, height) }.getOrNull()
        if (centre == null) {
            edgeBearing(here, answer, color, "DIG - ${paces(here, answer)} tiles")
            return
        }
        for (ring in 1..3) circle(centre, 16f * ring, color, segments = 0, thickness = 3f)
        label(Vector2f(centre.x, centre.y - 52f), "DIG HERE", color)
        textOnTile(answer, color, "${paces(here, answer)} tiles", heightFine = height)
    }

    private fun anchor() = rectOf(SCAN_INTERFACE, SCAN_DATA) ?: rectOf(SCAN_INTERFACE, FOUND_TEXT)

    private fun lines(here: Tile, answer: Tile?) = buildList {
        add(name)
        if (readout.isNotEmpty()) add(readout)
        when {
            answer != null -> {
                add("Dig at ${answer.x}, ${answer.y}${if (answer.level == 0) "" else " lvl ${answer.level}"}")
                add("${paces(here, answer)} tiles away")
            }
            candidates.isEmpty() -> add("Waiting for the hint icon")
            else -> {
                add("${candidates.size} spots in ${region.replace('_', ' ')} - no hint icon yet")
                candidates.take(3).forEach { add("${it.x}, ${it.y} - ${paces(here, it)}") }
            }
        }
    }

    private fun accent(answer: Tile?) =
        (if (answer != null) ImGuiColors.GREEN else ImGuiColors.CYAN) or (255 shl 24)

    private fun paces(from: Tile, to: Tile) = maxOf(abs(from.x - to.x), abs(from.y - to.y))
}
