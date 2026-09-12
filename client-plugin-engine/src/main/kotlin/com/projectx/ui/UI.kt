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
import com.projectx.ui.backend.dsl.utils.Corner
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.ImageHelper.getNoiseTexture
import com.projectx.ui.backend.native.GraphicRotation
import com.projectx.ui.backend.native.graphicTexture
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

    private const val CATEGORY_WIDTH = 104f
    private val DARK_ON_ACCENT = ImGuiColors.hex("#2A0F04")
    private const val BRAND_LOGO_SIZE = 46f
    private const val CONTENT_HEIGHT = -26f

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
                    runCatching {
                        watermark(graphicTexture(18026, GraphicRotation.R0), Corner.BottomLeft, scale = 1.5f)
                        watermark(graphicTexture(18026, GraphicRotation.R270), Corner.BottomRight, scale = 1.5f)
                        applyBackgroundOverlay(getNoiseTexture(), 0.25f)
                    }

                    navBar()
                    child("tab-content", width = 0f, height = CONTENT_HEIGHT) {
                        renderTabContent()
                    }
                    statusBar()
                }
            }
            refreshDataForVisibleTabs()
            renderConfigurationWindows()
        } catch (t: Throwable) {
            println("Error in ImGui render: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun WindowScope.navBar() {
        brandBlock()
        sameLine()
        group {
            categoryRow()
            tabRow()
        }
        separator()
    }

    private fun LayoutScope.brandBlock() {
        val logo = UiChrome.logo ?: ImGuiTexture.fromPath(UiChrome.LOGO_PATH)
        if (logo != null) image(logo, BRAND_LOGO_SIZE, BRAND_LOGO_SIZE)
    }

    private fun LayoutScope.categoryRow() {
        val active = UIState.selectedTab.category
        Category.entries.forEachIndexed { index, category ->
            if (index > 0) sameLine()
            navButton(category.displayName, isActive = category == active, width = CATEGORY_WIDTH) {
                Tab.entries.first { it.category == category }.let { UIState.selectedTab = it }
            }
        }
    }

    private fun LayoutScope.tabRow() {
        val active = UIState.selectedTab
        Tab.entries.filter { it.category == active.category }.forEachIndexed { index, tab ->
            if (index > 0) sameLine()
            navButton(tab.displayName, isActive = tab == active, width = 0f) {
                UIState.selectedTab = tab
            }
        }
    }

    private fun LayoutScope.navButton(label: String, isActive: Boolean, width: Float, onClick: () -> Unit) {
        val fill: Int
        val hovered: Int
        val pressed: Int
        val labelColor: Int
        if (isActive) {
            fill = ImGuiColors.ACCENT_PRIMARY
            hovered = ImGuiColors.ACCENT_PRIMARY_HOVER
            pressed = ImGuiColors.ACCENT_PRIMARY_ACTIVE
            labelColor = DARK_ON_ACCENT
        } else {
            fill = ImGuiColors.BACKGROUND_TERTIARY
            hovered = ImGuiColors.BACKGROUND_HOVER
            pressed = ImGuiColors.BACKGROUND_ACTIVE
            labelColor = ImGuiColors.TEXT_SECONDARY
        }
        pushStyleColor(ImGuiCol.Button, fill)
        pushStyleColor(ImGuiCol.ButtonHovered, hovered)
        pushStyleColor(ImGuiCol.ButtonActive, pressed)
        button(label, width = width, textColor = labelColor, onClick = onClick)
        popStyleColor(3)
    }

    private fun WindowScope.statusBar() {
        val running = ScriptExecutor.scripts.values.count { ScriptExecutor.isScriptRunning(it.scriptClass) }

        pushStyleColor(ImGuiCol.Text, if (running > 0) ImGuiColors.ACCENT_SUCCESS else ImGuiColors.TEXT_DISABLED)
        text(if (running > 0) "$running running" else "idle")
        popStyleColor(1)
        sameLine()
        pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
        text("· ${ScriptExecutor.scripts.size} scripts · ${UIState.selectedTab.displayName}")
        popStyleColor(1)
        sameLine()
        pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
        text("· build ${shortBuild()}")
        popStyleColor(1)
        if (isItemHovered()) setTooltip(fullBuild())
    }

    private fun fullBuild(): String = runCatching { BuildInfo.VERSION }.getOrDefault("unknown")

    /** The timestamp alone; the jar name makes the bar unreadable and rarely differs. */
    private fun shortBuild(): String = fullBuild().substringAfterLast('@').takeLast(12)


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
                        runCatching {
                            watermark(graphicTexture(18026, GraphicRotation.R0), Corner.BottomLeft, scale = 1.2f)
                            watermark(graphicTexture(18026, GraphicRotation.R270), Corner.BottomRight, scale = 1.2f)
                            applyBackgroundOverlay(getNoiseTexture(), 0.25f)
                        }
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
