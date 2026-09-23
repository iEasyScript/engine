package com.projectx.util

import com.google.gson.reflect.TypeToken
import org.projectx.packetlog.upload.PacketUploader
import com.projectx.game.hooks.impl.defaultUiToggleKey
import java.io.File
import java.io.IOException

data class PersistentConfig(
    val discordWebhookUrl: String = "",
    val discordEnabled: Boolean = true,
    val systemTrayEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val discordUsername: String = "Project X",
    val discordAvatarUrl: String = "",
    val uiToggleKey: Int = defaultUiToggleKey,
    val favoriteScripts: Set<String> = emptySet(),
    /**
     * Class names of the scripts added to the library from the Store. Null until the library is first set up,
     * which is how a config written before the Store existed is told apart from an emptied library.
     */
    val libraryScripts: Set<String>? = null,
    /**
     * Packet logging runs by default, as the old text dump did - a captured session costs a few
     * megabytes now rather than a hundred, so there is nothing to opt out of on space grounds.
     *
     * Sharing captures is on by default so the corpus the server is validated against actually
     * accumulates. [packetLogConsentVersion] records which consent text was in force when a session
     * was captured, and export checks the *session's* value rather than the current one - so a
     * session recorded under an older understanding never becomes shareable retroactively.
     *
     * Chat, private messages and friend/clan lists are stripped before anything leaves the machine.
     */
    val packetLogEnabled: Boolean = true,
    val packetLogUploadEnabled: Boolean = true,
    /**
     * Keep a session's local copy once it has uploaded, instead of deleting it. Off by default so an
     * unattended machine cannot silently fill its disk; a user who wants both sharing and a queryable
     * local archive turns this on and reclaims space themselves with `packetlog purge` (or the button
     * in the Packet Log tab) once they no longer need a capture.
     */
    val packetLogKeepLocalAfterUpload: Boolean = false,
    val packetLogConsentVersion: Int = 1,
    /** Where opted-in captures are pushed. Overridable for a self-hosted or a test ingest server. */
    val packetLogEndpoint: String = PacketUploader.DEFAULT_ENDPOINT,
    val packetLogTextDumpEnabled: Boolean = false,
    /** Where the overlay panel was left, as x, y, width, height. Null until it is first moved or resized. */
    val overlayBounds: List<Int>? = null,
)

object Configuration {
    private val configDir = File(System.getProperty("user.home"), ".projectx")
    private val configFile = File(configDir, "config.json")
    private var _config: PersistentConfig? = null

    val config: PersistentConfig
        get() {
            if (_config == null)
                loadConfig()
            return _config!!
        }

    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val type = object : TypeToken<PersistentConfig>() {}.type
                _config = JsonFileManager.loadJsonFile(configFile, type)
            } else {
                _config = PersistentConfig()
                saveConfig()
            }
        } catch (e: IOException) {
            println("Failed to load configuration: ${e.message}")
            _config = PersistentConfig()
        }
    }

    private fun saveConfig() {
        try {
            JsonFileManager.saveJsonFile(_config!!, configFile)
        } catch (e: IOException) {
            println("Failed to save configuration: ${e.message}")
        }
    }

    fun updateConfig(newConfig: PersistentConfig) {
        _config = newConfig
        saveConfig()
    }

    fun saveFavoriteScripts(favoriteScriptNames: Set<String>) {
        _config = config.copy(favoriteScripts = favoriteScriptNames)
        saveConfig()
    }

    fun saveLibraryScripts(libraryScriptNames: Set<String>) {
        _config = config.copy(libraryScripts = libraryScriptNames)
        saveConfig()
    }

    fun reloadConfig() {
        _config = null
        loadConfig()
    }
}
