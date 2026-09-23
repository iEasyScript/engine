package com.projectx.ui

import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.OverlayNavigation
import com.projectx.ui.compose.Page
import com.projectx.ui.compose.library.DetailTab
import com.projectx.ui.compose.library.LibraryModel
import java.lang.reflect.InvocationTargetException

/**
 * What the panel can do to a script. Each runs on the main-logic thread, where the executor ticks scripts and the
 * library is read, rather than on the render thread the click arrived on.
 */
object ScriptActions {
    fun start(meta: ScriptMetadata) = GameThread.post {
        try {
            val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
            if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
            ScriptExecutor.activate(instance)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            println("Failed to start ${meta.name}: ${cause.message ?: cause::class.simpleName}")
            cause.printStackTrace()
        }
    }

    fun stop(meta: ScriptMetadata) = GameThread.post { ScriptExecutor.deactivate(meta.scriptClass) }

    fun stopAll() = GameThread.post { ScriptExecutor.stopAll() }

    fun reload() = GameThread.post { ScriptExecutor.loadScripts() }

    fun removeFromLibrary(meta: ScriptMetadata) = GameThread.post { ScriptLibrary.remove(meta) }

    fun isConfigurable(meta: ScriptMetadata): Boolean =
        ConfigurableScript::class.java.isAssignableFrom(meta.scriptClass)

    fun openSettings(meta: ScriptMetadata) {
        LibraryModel.selectedId = meta.scriptClass.name
        LibraryModel.detailTab = DetailTab.Settings
        OverlayNavigation.open(Page.Library)
        UIState.showMainWindow.value = true
    }

    fun toggleFavorite(meta: ScriptMetadata) = GameThread.post {
        if (!UIState.favoriteScripts.remove(meta.scriptClass)) UIState.favoriteScripts.add(meta.scriptClass)
        UIState.saveFavorites()
    }
}
