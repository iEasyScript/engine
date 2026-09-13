package com.projectx.ui

import org.projectx.core.game.skill.Skill
import com.projectx.game.hooks.impl.defaultUiToggleKey
import com.projectx.game.hooks.impl.isForeignKeycode
import com.projectx.script.Script
import com.projectx.script.ScriptMetadata
import com.projectx.ui.backend.dsl.*
import com.projectx.util.Configuration

object UIState {
    var selectedTab = UI.Tab.SCRIPTS
    val showMainWindow = boolState(true)
    val uiToggleKey = intState(
        Configuration.config.uiToggleKey
            .takeIf { it != 0 && !isForeignKeycode(it) }
            ?: defaultUiToggleKey
    )

    val openConfigWindows = mutableMapOf<ScriptMetadata, ImGuiState<Boolean>>()
    val configSaveConfirmations = mutableMapOf<ScriptMetadata, Long>()

    val scriptSearchText = stringState("", 128)
    val statusFilterIndex = intState(0)
    val storeSearchText = stringState("", 128)
    val storeSourceIndex = intState(0)
    val logFilterText = stringState("", 128)
    val logErrorsOnly = boolState(false)
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
    val varcSearchText = stringState("", 128)
    val inventorySearchText = stringState("", 128)
    val inventoryNameSearch = stringState("", 128)
    val buffsDebuffsSearchText = stringState("", 128)
    val entitySearchText = stringState("", 128)
    val questHelperFilter = stringState("", 128)

    val interfaceDebugInterfaceId = intState(0)
    val interfaceDebugComponentId = intState(0)
    val interfaceDebugTextFilter = stringState("", 128)
    val interfaceDebugShowHidden = boolState(true)
    val interfaceDebugExpanded = mutableSetOf<Int>()

    /** Expanded component layers, keyed as `(interfaceId shl 32) or componentId`. */
    val interfaceDebugExpandedLayers = mutableSetOf<Long>()

    /**
     * Which slot child of the selected component is being inspected, or -1 for the component itself.
     *
     * Slot children share their template's component id, so an index is the only thing that distinguishes
     * one from its siblings.
     */
    val interfaceDebugSlotIndex = intState(-1)

    val varDebugDomainIndex = intState(0)
    val varDebugReadModeIndex = intState(0)
    val varDebugId = intState(0)
    val varDebugLive = boolState(true)
    val varDebugCachedValue = intState(0)

    val cs2TraceEnabled = boolState(false)
    val cs2TraceFilterId = intState(-1)
    val cs2TraceSearch = stringState("", 64)
    val cs2TraceShowArgs = boolState(true)

    data class VarDebugWatch(
        var domainIndex: Int,
        var readModeIndex: Int,
        var id: Int,
        var live: Boolean = true,
        var cachedValue: Int = 0
    )

    val varDebugWatches = mutableListOf<VarDebugWatch>()

    val varChangeTrackingEnabled = boolState(false)
    val varChangeSearchText = stringState("", 128)
    val varChangeTrackVarp = boolState(true)
    val varChangeTrackVarpbit = boolState(true)
    val varChangeTrackVarc = boolState(true)
    val varChangeTrackVarcbit = boolState(true)

    val mcpEnabled = boolState(false)
    val varpDebugEnabled = boolState(false)
    val varcDebugEnabled = boolState(false)
    val discordEnabled = boolState(Configuration.config.discordEnabled)
    val inventoryEnabled = boolState(false)
    val buffsDebuffsEnabled = boolState(false)
    val rawLoginDumpEnabled = boolState(false)
    val packetLogEnabled = boolState(Configuration.config.packetLogEnabled)
    val packetLogUploadEnabled = boolState(Configuration.config.packetLogUploadEnabled)
    val packetLogKeepLocalAfterUpload = boolState(Configuration.config.packetLogKeepLocalAfterUpload)
    val packetLogTextDumpEnabled = boolState(Configuration.config.packetLogTextDumpEnabled)
    val showTrainingEventFeed = boolState(true)
    val trainingEventFeedMotion = boolState(false)
    val showSceneObjects = boolState(false)
    val showNpcs = boolState(false)
    val showPlayers = boolState(false)
    val showSpotAnims = boolState(false)
    val showProjectiles = boolState(false)
    val showGroundItems = boolState(false)
    val showClickboxes = boolState(false)
    val questHelperEnabled = boolState(true)
    val questHelperShowOverlay = boolState(true)
    val questHelperAutoAdvance = boolState(true)
    val questHelperShowLocked = boolState(false)
    val questHelperShowCompleted = boolState(false)
    val inventionDiscoverySolver = boolState(true)
    val inventionXpTrackerEnabled = boolState(false)
    val inventionComponentTrackerEnabled = boolState(false)
    val farmingTrackerEnabled = boolState(true)
    val farmingNotificationsEnabled = boolState(true)
    val farmingShowSecondary = boolState(false)

    val collisionOverlayEnabled = boolState(false)
    val collisionOverlayRadius = intState(20)
    val collisionShowWalls = boolState(true)
    val collisionShowBlockWalk = boolState(true)
    val collisionShowFloorDecoration = boolState(false)
    val collisionShowProjectile = boolState(false)
    val collisionShowRouteBlocker = boolState(false)
    val collisionShowBlockNpc = boolState(false)
    val collisionShowBlockPlayer = boolState(false)
    val collisionShowRoof = boolState(false)
    val collisionShowWater = boolState(false)
    val renderShowClipped = boolState(false)
    val renderShowLowerObjects = boolState(false)
    val renderShowUnderRoof = boolState(false)
    val renderShowForceBottom = boolState(false)
    val renderShowRoof = boolState(false)
    val renderShowFlag20 = boolState(false)
    val renderShowFlag40 = boolState(false)
    val renderShowFlag80 = boolState(false)

    val discordWebhookUrl = stringState(Configuration.config.discordWebhookUrl, 256)
    val discordUsername = stringState(Configuration.config.discordUsername, 64)
    val inventoryId = intState(InventoryType.BACKPACK.id)

    val entityRange = intState(20)
    val maxVarcEntries = 100

    val entityTextColorR = floatState(1.0f)
    val entityTextColorG = floatState(1.0f)
    val entityTextColorB = floatState(1.0f)
    val entityTextColorA = floatState(0.8f)

    val clickboxColorR = floatState(0.0f)
    val clickboxColorG = floatState(1.0f)
    val clickboxColorB = floatState(1.0f)
    val clickboxOutlineMode = intState(2)
    val clickboxIntensity = intState(8)

    var lastRefreshTime = 0L
    val refreshInterval = 100L

    val xpData = mutableMapOf<Skill, Pair<Long, Int>>()
    val varTableData = mutableListOf<VarcEntry>()
    val inventoryData = mutableListOf<InventoryEntry>()
    val buffsDebuffsData = mutableListOf<BuffDebuffEntry>()

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
