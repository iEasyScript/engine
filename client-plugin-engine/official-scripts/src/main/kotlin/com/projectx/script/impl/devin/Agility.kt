package com.projectx.script.impl.devin

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.script.ConfigurableScript
import com.projectx.script.OptionsConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

@ScriptDescription(
    name = "Agility",
    version = "1.0.0",
    author = "Devin",
    description = "Basic AIO agility courses."
)
class Agility : Script(), ConfigurableScript {
    private var step = 0
    private val chatMessages = listOf(
        "You get caught under the weight of the creature and are left stunned.",
        "... but you lose your footing and fall into the lava.",
        "You slip and fall to the pit below.",
        "You slip and fall onto the spikes below."
    )

    data class Obstacle(
        val name: String? = null,
        val action: String? = null,
        val id: Int? = null,
        val tile: Tile? = null
    )

    private val courses = mapOf(
        "gnome" to listOf(
            Obstacle("Log balance", "Walk-across"),
            Obstacle("Obstacle net", "Climb-over"),
            Obstacle("Tree branch", "Climb"),
            Obstacle("Balancing rope", "Walk-on"),
            Obstacle("Tree branch", "Climb-down"),
            Obstacle("Obstacle net", "Climb-over"),
            Obstacle("Obstacle pipe", "Squeeze-through"),
        ),
        "mort_myre_bridge" to listOf(Obstacle("Bridge", "Jump")),
        "anachronia_level_30_south" to listOf(
            Obstacle("Temple wall", "Traverse"),
            Obstacle("Cliff face", "Traverse", 113688),
            Obstacle("Cliff face", "Traverse", 113689),
            Obstacle("Vines", "Cross"),
            Obstacle(null, "Walk", null, Tile.of(5371, 2303, 0)),
            Obstacle("Root", "Climb over"),
            Obstacle(null, "Walk", null, Tile.of(5359, 2282, 0)),
            Obstacle("Vines", "Cross"),
            Obstacle(null, "Walk", null, Tile.of(5377, 2256, 0)),
            Obstacle("Cliff face", "Traverse"),
            Obstacle("Tree", "Jump across"),

            Obstacle("Tree", "Jump across"),
            Obstacle("Cliff face", "Traverse"),
            Obstacle(null, "Walk", null, Tile.of(5375, 2281, 0)),
            Obstacle("Vines", "Cross"),
            Obstacle(null, "Walk", null, Tile.of(5367, 2303, 0)),
            Obstacle("Root", "Climb over"),
            Obstacle(null, "Walk", null, Tile.of(5392, 2322, 0)),
            Obstacle("Vines", "Cross"),
            Obstacle("Cliff face", "Traverse", 113689),
            Obstacle("Cliff face", "Traverse", 113688),
            Obstacle("Temple wall", "Traverse")
        ),
        "anachronia_level_30_north" to listOf(
            Obstacle("Cliff face", "Traverse", 113738),
            Obstacle("Cliff face", "Traverse", 113737),
            Obstacle("Ruined temple", "Traverse", 113736),
            Obstacle("Ruined temple", "Traverse", 113735),
            Obstacle("Cave entrance", "Enter"),
            Obstacle("Roots", "Cross"),

            Obstacle("Roots", "Cross"),
            Obstacle("Cave entrance", "Enter"),
            Obstacle("Ruined temple", "Traverse", 113735),
            Obstacle("Ruined temple", "Traverse", 113736),
            Obstacle("Cliff face", "Traverse", 113737),
            Obstacle("Cliff face", "Traverse", 113738),
        ),
        "anachronia_level_50_north" to listOf(
            Obstacle("Ruined temple", "Traverse"),
            Obstacle("Ruined temple", "Jump across"),
            Obstacle("Ruined temple", "Traverse"),
            Obstacle(null, "Walk", null, Tile.of(5523, 2492, 0)),
            Obstacle("Ruined temple", "Climb"),
            Obstacle("Ruined temple", "Jump across"),
            Obstacle(null, "Walk", null, Tile.of(5563, 2469, 0)),
            Obstacle("Bones", "Traverse"),
            Obstacle("Spine", "Cross"),
            Obstacle("Bones", "Traverse"),

            Obstacle("Bones", "Traverse"),
            Obstacle("Spine", "Cross"),
            Obstacle("Bones", "Traverse"),
            Obstacle(null, "Walk", null, Tile.of(5546, 2492, 0)),
            Obstacle("Ruined temple", "Jump across"),
            Obstacle("Ruined temple", "Climb"),
            Obstacle(null, "Walk", null, Tile.of(5506, 2481, 0)),
            Obstacle("Ruined temple", "Traverse"),
            Obstacle("Ruined temple", "Jump across"),
            Obstacle("Ruined temple", "Traverse")
        ),
        "wilderness" to listOf(
            //TODO better failure support. IE: Falling into the pits and going back to the correct obstacle
            Obstacle("Obstacle pipe", "Squeeze-through"),
            Obstacle("Ropeswing", "Swing-on"),
            Obstacle("Stepping stone", "cross"),
            Obstacle("Log balance", "Walk-across"),
            Obstacle("Cliffside", "Climb"),
        ),
        "hets_oasis" to listOf(
            Obstacle("Fallen palm tree", "Run across", 122443),
            Obstacle(null, "Walk", null, Tile.of(3364, 3242, 0)),
            Obstacle("Fallen palm tree", "Run across", 122450, Tile.of(3364, 3236, 0)),
            Obstacle("Rope ladder", "Climb up"),
            Obstacle("Gap", "Jump over"),
            Obstacle("Stone pillar", "Climb down"),
            Obstacle("Rock wall", "Walk across"),
            Obstacle("Fallen palm tree", "Run across"),
            Obstacle("Small gap", "Hop over"),
            Obstacle("Medium gap", "Leap over"),
            Obstacle("Fallen palm tree", "Run across"),
            Obstacle("Collapsed walls", "Jump across"),
            Obstacle("Large rocks", "Climb up"),
            Obstacle("Ledge", "Climb up"),
            Obstacle("Gap", "Jump over"),
            Obstacle("Ledge", "Climb down"),
            Obstacle("Large rock", "Climb down"),
        )
    )

    val selectedCourse = OptionsConfigItem(
        name = "Course",
        description = "Select which agility course to run",
        options = courses.keys.toTypedArray(),
        initialValue = "None"
    )

    override suspend fun loop() {
        if (selectedCourse.value == "None") return

        if (localPlayer.isAniMoving || healthPercent < 5.0)
            return delay(600, 1200)

        findClosestObject("Ladder", 25)?.takeIf { selectedCourse.value == "wilderness" }?.interact("Climb-up")?.let { delayUntil(30000) { findClosestObject("Ropeswing", 15) != null } }
        Tile.of(3005, 3590, 0).takeIf { selectedCourse.value == "wilderness" && step == 1 && localPlayer.tile.y > 3952 }?.let {
            walkTo(it, false)
            return delay(1200, 1200)
        }

        val obstacle = courses[selectedCourse.value]!![step]

        (when {
            obstacle.tile != null && obstacle.action == "Walk" -> walkTo(obstacle.tile, localPlayer.tile.getDistance(obstacle.tile) >= 15)
            obstacle.id != null -> interactClosestReachableObject(obstacle.id, obstacle.action!!)
            else -> interactClosestReachableObject(obstacle.name!!, obstacle.action!!)
        }).also { success ->
            if (success) {
                stepForward()
                obstacle.tile?.let { tile ->
                    delayUntil(30000) { localPlayer.tile.getDistance(tile) <= 3 }
                } ?: waitForXPDrop(Skill.AGILITY)
            }
        }
    }

    override fun onEvent(event: Event) {
        if (event is Chat && chatMessages.any { event.message.contains(it, ignoreCase = true) }) {
            stepBackward()
        }
    }

    fun stepForward() = ((step + 1) % (courses[selectedCourse.value]?.size ?: 1))
        .also { println("Step going from $step to $it (course size: ${courses[selectedCourse.value]?.size})") }
        .also { step = it }

    fun stepBackward() = ((step - 1 + (courses[selectedCourse.value]?.size ?: 1)) % (courses[selectedCourse.value]?.size ?: 1))
        .also { println("Step going from $step to $it (course size: ${courses[selectedCourse.value]?.size})") }
        .also { step = it }
}