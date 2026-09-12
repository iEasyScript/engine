package com.projectx.script.impl.trent.dungeoneering

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.event.Event
import com.projectx.script.impl.trent.dungeoneering.render.MapOverlayRenderer
import com.projectx.script.impl.trent.dungeoneering.render.WorldOverlayOptions
import com.projectx.script.impl.trent.dungeoneering.render.WorldOverlayRenderer
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList

@ScriptDescription(
    name = "Dungeoneering Assist",
    version = "1.0.0",
    author = "Trent",
    description = "Highlights Daemonheim resources, keys and skill doors, and maps the critical path.",
    category = ScriptCategory.DUNGEONEERING
)
class DungeoneeringAssistScript : Script(), ConfigurableScript {

    val highlightResources = BooleanConfigItem(
        "Highlight resources", "Highlight woodcutting/mining/fishing nodes on screen", true
    )
    val showResourceLabels = BooleanConfigItem(
        "Show labels", "Draw name and level labels on highlighted entities", true
    )
    val glowSlayerNpcs = BooleanConfigItem(
        "Glow slayer monsters", "Outline Daemonheim slayer creatures, red when above your level", true
    )
    val autoExamineDoors = BooleanConfigItem(
        "Auto-examine skill doors", "Examine newly seen skill doors once to learn their requirement", true
    )
    val examineSpacingMs = IntConfigItem(
        "Examine spacing (ms)", "Delay between automatic door examines", 1500, 500, 5000
    )
    val boostMargin = IntConfigItem(
        "Skill-door boost margin", "Levels above yours a skill door can still be forced before it counts as off-path", 8, 0, 30
    )
    val debugMapOverlay = BooleanConfigItem(
        "Debug map overlay", "Show pip cell, calibration state and room count on the dungeon map", false
    )

    private var lastRenderError = 0L

    override fun onStart() {
        DungeonDebug.install()
    }

    override suspend fun loop() {
        try {
            DungeonContext.update(this, boostMargin.value, autoExamineDoors.value, examineSpacingMs.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        delay(LOOP_MS)
    }

    override fun onEvent(event: Event) {
        DungeonContext.onEvent(event)
    }

    override fun render() {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        try {
            val active = DungeonContext.session
            backgroundDrawList {
                WorldOverlayRenderer.draw(
                    this, active, DungeonContext.scanner,
                    WorldOverlayOptions(
                        resources = highlightResources.value,
                        labels = showResourceLabels.value,
                        glowSlayer = glowSlayerNpcs.value
                    )
                )
                if (active != null) {
                    MapOverlayRenderer.draw(this, active, debugMapOverlay.value)
                }
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastRenderError > 5000) {
                lastRenderError = now
                e.printStackTrace()
            }
        }
    }

    private companion object {
        const val LOOP_MS = 200
    }
}
