package com.projectx.ui

import com.projectx.ui.backend.dsl.ImGuiState
import org.projectx.core.game.skill.Skill
import com.projectx.game.hooks.impl.defaultUiToggleKey
import com.projectx.game.hooks.impl.isForeignKeycode
import com.projectx.script.Script
import com.projectx.util.Configuration

object UIState {
    val showMainWindow: ImGuiState<Boolean> = setting(true)
    val uiToggleKey: ImGuiState<Int> = setting(
        Configuration.config.uiToggleKey
            .takeIf { it != 0 && !isForeignKeycode(it) }
            ?: defaultUiToggleKey
    )


    val storeSearchText: ImGuiState<String> = setting("")
    val logFilterText: ImGuiState<String> = setting("")
    val logErrorsOnly: ImGuiState<Boolean> = setting(false)
    @Suppress("UNCHECKED_CAST")
    val favoriteScripts = mutableSetOf<Class<out Script>>().apply {
        val savedFavorites = Configuration.config.favoriteScripts
        savedFavorites.forEach { className ->
            try {
                val scriptClass = Class.forName(className) as? Class<out Script>
                scriptClass?.let { add(it) }
            } catch (e: ClassNotFoundException) {
            }
        }
    }
    val inventorySearchText: ImGuiState<String> = setting("")
    val inventoryNameSearch: ImGuiState<String> = setting("")
    val buffsDebuffsSearchText: ImGuiState<String> = setting("")
    val entitySearchText: ImGuiState<String> = setting("")
    val questHelperFilter: ImGuiState<String> = setting("")

    val interfaceDebugInterfaceId: ImGuiState<Int> = setting(0)
    val interfaceDebugComponentId: ImGuiState<Int> = setting(0)
    val interfaceDebugTextFilter: ImGuiState<String> = setting("")
    val interfaceDebugShowHidden: ImGuiState<Boolean> = setting(true)
    val interfaceDebugExpanded = mutableSetOf<Int>()

    /** Expanded component layers, keyed as `(interfaceId shl 32) or componentId`. */
    val interfaceDebugExpandedLayers = mutableSetOf<Long>()

    /**
     * Which slot child of the selected component is being inspected, or -1 for the component itself.
     *
     * Slot children share their template's component id, so an index is the only thing that distinguishes
     * one from its siblings.
     */
    val interfaceDebugSlotIndex: ImGuiState<Int> = setting(-1)

    val varDebugDomainIndex: ImGuiState<Int> = setting(0)
    val varDebugReadModeIndex: ImGuiState<Int> = setting(0)
    val varDebugId: ImGuiState<Int> = setting(0)
    val varDebugLive: ImGuiState<Boolean> = setting(true)
    val varDebugCachedValue: ImGuiState<Int> = setting(0)

    val cs2TraceEnabled: ImGuiState<Boolean> = setting(false)
    val cs2TraceFilterId: ImGuiState<Int> = setting(-1)
    val cs2TraceSearch: ImGuiState<String> = setting("")
    val cs2TraceShowArgs: ImGuiState<Boolean> = setting(true)

    data class VarDebugWatch(
        var domainIndex: Int,
        var readModeIndex: Int,
        var id: Int,
        var live: Boolean = true,
        var cachedValue: Int = 0
    )

    val varDebugWatches = mutableListOf<VarDebugWatch>()

    val varChangeSearchText: ImGuiState<String> = setting("")
    val varChangeTrackVarp: ImGuiState<Boolean> = setting(true)
    val varChangeTrackVarpbit: ImGuiState<Boolean> = setting(true)
    val varChangeTrackVarc: ImGuiState<Boolean> = setting(true)
    val varChangeTrackVarcbit: ImGuiState<Boolean> = setting(true)

    val mcpEnabled: ImGuiState<Boolean> = setting(false)
    val varpDebugEnabled: ImGuiState<Boolean> = setting(false)
    val varcDebugEnabled: ImGuiState<Boolean> = setting(false)
    val discordEnabled: ImGuiState<Boolean> = setting(Configuration.config.discordEnabled)
    val rawLoginDumpEnabled: ImGuiState<Boolean> = setting(false)
    val packetLogEnabled: ImGuiState<Boolean> = setting(Configuration.config.packetLogEnabled)
    val packetLogUploadEnabled: ImGuiState<Boolean> = setting(Configuration.config.packetLogUploadEnabled)
    val packetLogKeepLocalAfterUpload: ImGuiState<Boolean> = setting(Configuration.config.packetLogKeepLocalAfterUpload)
    val packetLogTextDumpEnabled: ImGuiState<Boolean> = setting(Configuration.config.packetLogTextDumpEnabled)
    val showTrainingEventFeed: ImGuiState<Boolean> = setting(true)
    val trainingEventFeedMotion: ImGuiState<Boolean> = setting(false)
    val showSceneObjects: ImGuiState<Boolean> = setting(false)
    val showNpcs: ImGuiState<Boolean> = setting(false)
    val showPlayers: ImGuiState<Boolean> = setting(false)
    val showSpotAnims: ImGuiState<Boolean> = setting(false)
    val showProjectiles: ImGuiState<Boolean> = setting(false)
    val showGroundItems: ImGuiState<Boolean> = setting(false)
    val showClickboxes: ImGuiState<Boolean> = setting(false)
    val questHelperEnabled: ImGuiState<Boolean> = setting(true)
    val questHelperShowOverlay: ImGuiState<Boolean> = setting(true)
    val questHelperAutoAdvance: ImGuiState<Boolean> = setting(true)
    val questHelperShowLocked: ImGuiState<Boolean> = setting(false)
    val questHelperShowCompleted: ImGuiState<Boolean> = setting(false)
    val inventionDiscoverySolver: ImGuiState<Boolean> = setting(true)
    val inventionXpTrackerEnabled: ImGuiState<Boolean> = setting(false)
    val inventionComponentTrackerEnabled: ImGuiState<Boolean> = setting(false)
    val farmingTrackerEnabled: ImGuiState<Boolean> = setting(true)
    val farmingNotificationsEnabled: ImGuiState<Boolean> = setting(true)
    val farmingShowSecondary: ImGuiState<Boolean> = setting(false)

    val collisionOverlayEnabled: ImGuiState<Boolean> = setting(false)
    val collisionOverlayRadius: ImGuiState<Int> = setting(20)
    val collisionShowWalls: ImGuiState<Boolean> = setting(true)
    val collisionShowBlockWalk: ImGuiState<Boolean> = setting(true)
    val collisionShowFloorDecoration: ImGuiState<Boolean> = setting(false)
    val collisionShowProjectile: ImGuiState<Boolean> = setting(false)
    val collisionShowRouteBlocker: ImGuiState<Boolean> = setting(false)
    val collisionShowBlockNpc: ImGuiState<Boolean> = setting(false)
    val collisionShowBlockPlayer: ImGuiState<Boolean> = setting(false)
    val collisionShowRoof: ImGuiState<Boolean> = setting(false)
    val collisionShowWater: ImGuiState<Boolean> = setting(false)
    val renderShowClipped: ImGuiState<Boolean> = setting(false)
    val renderShowLowerObjects: ImGuiState<Boolean> = setting(false)
    val renderShowUnderRoof: ImGuiState<Boolean> = setting(false)
    val renderShowForceBottom: ImGuiState<Boolean> = setting(false)
    val renderShowRoof: ImGuiState<Boolean> = setting(false)
    val renderShowFlag20: ImGuiState<Boolean> = setting(false)
    val renderShowFlag40: ImGuiState<Boolean> = setting(false)
    val renderShowFlag80: ImGuiState<Boolean> = setting(false)

    val discordWebhookUrl: ImGuiState<String> = setting(Configuration.config.discordWebhookUrl)
    val discordUsername: ImGuiState<String> = setting(Configuration.config.discordUsername)
    val inventoryId: ImGuiState<Int> = setting(InventoryType.BACKPACK.id)

    val entityRange: ImGuiState<Int> = setting(20)
    val maxVarcEntries = 100

    val entityTextColorR: ImGuiState<Float> = setting(1.0f)
    val entityTextColorG: ImGuiState<Float> = setting(1.0f)
    val entityTextColorB: ImGuiState<Float> = setting(1.0f)
    val entityTextColorA: ImGuiState<Float> = setting(0.8f)

    val clickboxColorR: ImGuiState<Float> = setting(0.0f)
    val clickboxColorG: ImGuiState<Float> = setting(1.0f)
    val clickboxColorB: ImGuiState<Float> = setting(1.0f)
    val clickboxOutlineMode: ImGuiState<Int> = setting(2)
    val clickboxIntensity: ImGuiState<Int> = setting(8)


    val xpData = mutableMapOf<Skill, Pair<Long, Int>>()
    val varTableData = mutableListOf<VarcEntry>()

    fun saveFavorites() {
        val favoriteClassNames = favoriteScripts.map { it.name }.toSet()
        Configuration.saveFavoriteScripts(favoriteClassNames)
    }

    /** Newest change per (type, id), most-recent first, bounded by [maxVarcEntries]. */
    fun addVarTableEntry(type: String, id: Int, prevValue: Int, newValue: Int) {
        if (prevValue == newValue) return

        val entry = VarcEntry(type, id, prevValue, newValue)
        val existingIndex = varTableData.indexOfFirst { it.type == type && it.id == id }
        if (existingIndex != -1) {
            varTableData[existingIndex] = entry
            return
        }

        varTableData.add(0, entry)
        if (varTableData.size > maxVarcEntries) {
            varTableData.removeAt(varTableData.size - 1)
        }
    }

    fun updateXpTable(skill: Skill, xpGained: Int) {
        val totalXp = xpData.getOrDefault(skill, System.currentTimeMillis() to 0)
        xpData[skill] = totalXp.first to totalXp.second + xpGained
    }
}
