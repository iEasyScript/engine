package com.projectx.ui

import com.projectx.BuildInfo
import com.projectx.game.hooks.Priority
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.*
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowPos
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowSize
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.ui.highlight.CollisionDebugRenderer
import com.projectx.ui.highlight.EntityOverlayRenderer
import com.projectx.ui.tabs.*
import com.projectx.util.EngineLog

object UI {
    enum class Category(val displayName: String) {
        AUTOMATION("AUTOMATION"),
        CHARACTER("CHARACTER"),
        WORLD("WORLD"),
        DEVELOPER("DEVELOPER"),
        SYSTEM("SYSTEM")
    }

    enum class Tab(val displayName: String, val category: Category) {
        SCRIPTS("Scripts", Category.AUTOMATION),
        QUEST_HELPER("Quest Helper", Category.AUTOMATION),
        XP_TRACKER("XP Tracker", Category.CHARACTER),
        INVENTORY("Inventory", Category.CHARACTER),
        BUFFS_DEBUFFS("Buffs & Debuffs", Category.CHARACTER),
        INVENTION("Invention", Category.CHARACTER),
        FARMING("Farming", Category.CHARACTER),
        SCENE("Scene", Category.WORLD),
        TILE_MARKERS("Tile Markers", Category.WORLD),
        LOGS("Logs", Category.DEVELOPER),
        PACKET_LOG("Packet Log", Category.DEVELOPER),
        INPUT_RECORDING("Input Recording", Category.DEVELOPER),
        VAR_DEBUG("Var Debug", Category.DEVELOPER),
        INTERFACE_DEBUG("Interface Debug", Category.DEVELOPER),
        CS2_TRACE("CS2 Trace", Category.DEVELOPER),
        SETTINGS("Settings", Category.SYSTEM)
    }

    private const val SIDEBAR_WIDTH = 168f
    private const val HEADER_HEIGHT = 38f
    private const val BRAND_LOGO_SIZE = 16f
    private val ENGINE_VERSION = Regex("""(\d+\.\d+\.\d+)""")

    init {
        EngineLog.install()
    }

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            if (EntityOverlayRenderer.enabled) {
                backgroundDrawList { EntityOverlayRenderer.draw(this) }
            }

            if (UIState.collisionOverlayEnabled.value) {
                backgroundDrawList {
                    CollisionDebugRenderer.draw(this)
                }
            }

            // Loaded here, outside any window scope, so the shared texture cache is warm before
            // nested scopes resolve it. Deliberately not drawn.
            ImGuiTexture.fromPath("/icons/logo_alpha.png")

            val showMain = UIState.showMainWindow.value
            if (showMain) {
                setNextWindowPos(10f, 10f, ImGuiCond.FirstUseEver)
                setNextWindowSize(980f, 640f, ImGuiCond.FirstUseEver)

                window("Project X", WindowFlags.NoTitleBar) {
                    header()
                    sidebar()
                    sameLine()
                    child("tab-content", childFlags = ImGuiChildFlags.Borders or ImGuiChildFlags.AlwaysUseWindowPadding) {
                        renderTabContent()
                    }
                }
            }
            refreshDataForVisibleTabs()
            renderConfigurationWindows()
        } catch (t: Throwable) {
            println("Error in ImGui render: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun WindowScope.header() {
        child(
            "header",
            height = HEADER_HEIGHT,
            childFlags = ImGuiChildFlags.Borders,
            windowFlags = (WindowFlags.NoScrollbar + WindowFlags.NoScrollWithMouse).value,
        ) {
            val logo = UiChrome.logo ?: ImGuiTexture.fromPath(UiChrome.LOGO_PATH)
            if (logo != null) {
                image(logo, BRAND_LOGO_SIZE, BRAND_LOGO_SIZE)
                sameLine()
            }
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_ACCENT) { text("Project X") }
            sameLine()
            mutedText(engineLabel())
            itemTooltip(fullBuild())

            val running = ScriptExecutor.scripts.values.count { ScriptExecutor.isScriptRunning(it.scriptClass) }
            sameLine()
            mutedText("|")
            sameLine()
            styleColor(ImGuiCol.Text, if (running > 0) ImGuiColors.ACCENT_SUCCESS else ImGuiColors.TEXT_DISABLED) {
                text(if (running > 0) "$running running" else "idle")
            }
            sameLine()
            mutedText("|")
            sameLine()
            mutedText("${ScriptExecutor.scripts.size} scripts")
        }
    }

    private fun WindowScope.sidebar() {
        child("sidebar", width = SIDEBAR_WIDTH, childFlags = ImGuiChildFlags.Borders or ImGuiChildFlags.AlwaysUseWindowPadding) {
            styleVar(ImGuiStyleVar.ButtonTextAlign, 0f, 0.5f) {
                styleVar(ImGuiStyleVar.ItemSpacing, 6f, 5f) {
                    Category.entries.forEachIndexed { index, category ->
                        if (index > 0) spacing()
                        mutedText(category.displayName)
                        Tab.entries.filter { it.category == category }.forEach { tab ->
                            navButton(tab.displayName, isActive = tab == UIState.selectedTab) {
                                UIState.selectedTab = tab
                            }
                        }
                    }
                }
            }
        }
    }

    private fun LayoutScope.navButton(label: String, isActive: Boolean, onClick: () -> Unit) {
        if (isActive) {
            pushStyleColor(ImGuiCol.Button, ImGuiColors.ACCENT_PRIMARY)
            pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.ACCENT_PRIMARY_HOVER)
            pushStyleColor(ImGuiCol.ButtonActive, ImGuiColors.ACCENT_PRIMARY_ACTIVE)
            pushStyleColor(ImGuiCol.Border, ImGuiColors.ACCENT_PRIMARY_HOVER)
        } else {
            pushStyleColor(ImGuiCol.Button, ImGuiColors.BACKGROUND_TERTIARY)
            pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.BACKGROUND_HOVER)
            pushStyleColor(ImGuiCol.ButtonActive, ImGuiColors.BACKGROUND_ACTIVE)
            pushStyleColor(ImGuiCol.Border, ImGuiColors.BORDER_STRONG)
        }
        button(label, width = -1f, textColor = if (isActive) ImGuiColors.WHITE else ImGuiColors.TEXT_PRIMARY, onClick = onClick)
        popStyleColor(4)
    }

    private fun LayoutScope.mutedText(value: String) {
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) { text(value) }
    }

    private fun fullBuild(): String = runCatching { BuildInfo.VERSION }.getOrDefault("unknown")

    /** The engine version from the jar name, or the build timestamp for a jar built without one. */
    private fun engineLabel(): String {
        val build = fullBuild()
        val version = ENGINE_VERSION.find(build.substringBefore('@'))?.value
        return if (version != null) "Engine v$version" else "Build ${build.substringAfterLast('@').takeLast(12)}"
    }


    private fun ChildScope.renderTabContent() {
        try {
            when (UIState.selectedTab) {
                Tab.SCRIPTS -> with(ScriptsTab) { render() }
                Tab.QUEST_HELPER -> with(QuestHelperTab) { render() }
                Tab.XP_TRACKER -> with(XpTrackerTab) { render() }
                Tab.INVENTORY -> with(InventoryTab) { render() }
                Tab.BUFFS_DEBUFFS -> with(BuffsDebuffsTab) { render() }
                Tab.INVENTION -> with(InventionTab) { render() }
                Tab.FARMING -> with(FarmingTab) { render() }
                Tab.SCENE -> with(SceneTab) { render() }
                Tab.TILE_MARKERS -> with(TileMarkersTab) { render() }
                Tab.LOGS -> with(LogsTab) { render() }
                Tab.PACKET_LOG -> with(PacketLogTab) { render() }
                Tab.INPUT_RECORDING -> with(InputRecordingTab) { render() }
                Tab.VAR_DEBUG -> with(VarDebugTab) { render() }
                Tab.INTERFACE_DEBUG -> with(InterfaceDebugTab) { render() }
                Tab.CS2_TRACE -> with(CS2TraceTab) { render() }
                Tab.SETTINGS -> with(SettingsTab) { render() }
            }
        } catch (t: Throwable) {
            text("Tab '${UIState.selectedTab}' failed to render:")
            text(t.javaClass.simpleName + ": " + (t.message ?: ""))
            text("Pick another tab. (See logs for stack trace.)")
            println("[UI] Tab render error for ${UIState.selectedTab}: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun refreshDataForVisibleTabs() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - UIState.lastRefreshTime > UIState.refreshInterval) {
            UIState.lastRefreshTime = currentTime

            if (UIState.inventoryEnabled.value) {
                InventoryTab.loadInventory(UIState.inventoryId.value)
            }
            if (UIState.buffsDebuffsEnabled.value) {
                BuffsDebuffsTab.loadBuffsDebuffs()
            }
            if (UIState.selectedTab == Tab.LOGS) {
                LogsTab.updateLogLines()
            }
        }
    }

    private fun WindowScope.renderScriptConfig(script: ConfigurableScript) {
        with(ScriptConfigRenderer) { renderScriptConfig(script) }
    }

    private fun renderConfigurationWindows() {
        UIState.openConfigWindows.entries.removeAll { (metadata, windowState) ->
            val isOpen = try {
                windowState.value
            } catch (_: IllegalStateException) {
                false
            }

            if (!isOpen) {
                try {
                    windowState.close()
                } catch (_: Throwable) {
                }

                return@removeAll true
            }

            try {
                val instance = ScriptExecutor.getScriptInstance(metadata.scriptClass)
                    ?: if (ConfigurableScript::class.java.isAssignableFrom(metadata.scriptClass)) {
                        try {
                            metadata.scriptClass.getDeclaredConstructor().newInstance().also {
                                if (it is ConfigurableScript) ScriptConfigStore.applyTo(it)
                            }
                        } catch (e: Exception) {
                            println("Failed to create script instance for configuration: ${e.message}")
                            null
                        }
                    } else null

                if (instance is ConfigurableScript) {
                    val windowIndex = UIState.openConfigWindows.keys.indexOf(metadata)
                    val offsetX = 100f + (windowIndex * 30f)
                    val offsetY = 100f + (windowIndex * 30f)
                    setNextWindowPos(offsetX, offsetY, cond = ImGuiCond.FirstUseEver)
                    setNextWindowSize(400f, 500f, cond = ImGuiCond.FirstUseEver)

                    window(
                        title = "${metadata.name} ${metadata.version} Settings",
                        flags = WindowFlags.None,
                        open = windowState
                    ) {
                        renderScriptConfig(instance)

                        separator()
                        button("Save & Close") {
                            ScriptConfigStore.save(instance)
                            windowState.value = false
                        }
                        sameLine()
                        button("Save") {
                            ScriptConfigStore.save(instance)
                            UIState.configSaveConfirmations[metadata] = System.currentTimeMillis()
                        }

                        val lastSaveTime = UIState.configSaveConfirmations[metadata] ?: 0L
                        if (System.currentTimeMillis() - lastSaveTime < 2000) {
                            spacing()
                            text("Settings saved!")
                        }
                    }
                } else {
                    windowState.value = false
                }
            } catch (e: Exception) {
                println("Error rendering config window for ${metadata.name}: ${e.message}")
                e.printStackTrace()
                windowState.value = false
            }

            false
        }
    }
}
