package com.projectx.profiling

import com.projectx.game.input.Key
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.util.JsonFileManager
import com.projectx.util.Logger
import com.projectx.util.random
import java.io.File
import kotlin.system.exitProcess

class PlayerProfiles {
    companion object {
        private val loadedProfiles = mutableMapOf<String, PlayerProfile>()
        val DEFAULTS = PlayerProfile()

        fun get(): PlayerProfile {
            val name = Bootstrap.client.loggedInPlayer.getPlayerName()
            return if (name == null) DEFAULTS else getByName(name)
        }

        fun getByName(name: String): PlayerProfile {
            loadedProfiles[name]?.let { return it }
            val profile = loadProfile(name)
            profile.applyDefaults()
            loadedProfiles[name] = profile
            saveProfile(name, profile)

            return profile
        }

        private fun saveProfile(username: String, profile: PlayerProfile) {
            try {
                val sanitizedUsername = sanitizeFilename(username)
                val profilesDir = File("${System.getProperty("user.home")}/.projectx/profiles")
                if (!profilesDir.exists())
                    profilesDir.mkdirs()
                val configFile = File(profilesDir, "$sanitizedUsername.json")
                JsonFileManager.saveJsonFile(profile, configFile)
            } catch (e: Exception) {
                Logger.handle(e)
                exitProcess(5)
            }
        }

        private fun loadProfile(username: String): PlayerProfile {
            try {
                val sanitizedUsername = sanitizeFilename(username)
                val profilesDir = File("${System.getProperty("user.home")}/.projectx/profiles")
                val configFile = File(profilesDir, "$sanitizedUsername.json")

                return if (configFile.exists() && configFile.length() > 0) {
                    JsonFileManager.loadJsonFile(configFile, PlayerProfile::class.java)
                } else {
                    PlayerProfile()
                }
            } catch (e: Exception) {
                Logger.handle(e)
                return PlayerProfile()
            }
        }

        private fun sanitizeFilename(filename: String): String {
            return filename
                .replace(Regex("[<>:\"/\\\\|?*]"), "_")
                .replace(Regex("\\s+"), "_")
                .take(100)
                .lowercase()
        }
    }
}

class PlayerProfile {
    var afkLogoutRefreshSeconds = random(210, 250)
    var afkLogoutRefreshVariance = random(15, 23)
    var afkLogoutRefreshKey = setOf(Key.LALT, Key.LCTRL, Key.PAGEDOWN, Key.PAGEUP).random()
    var gaussVariance = 0.4
    var walkPathClickTime = random(2200, 4100)
    var futurePathStepMin = random(10, 13)
    var futurePathStepMax = random(futurePathStepMin+3, 20)
    var walkPathDeviation = random(1, 3)
    var minimapWalkPerc = random(10, 60)
    var interactDistanceRange = random(16, 20)

    var synthInputEnabled: Boolean = false
    var synthShadowDoActions: Boolean = true
    var synthModelPlayer: String = ""
    var synthVisualizerEnabled: Boolean = true
    var synthSendToServer: Boolean = true

    // The trajectory shape, speed and tremor constants that used to live here are gone: the motor model
    // learns all of it from the player, and a per-profile randomization of hand-written curve parameters was
    // only ever an approximation of a fingerprint. What remains is the pause between reaches, which no
    // trained model covers yet.
    var synthIdlePauseMinMs: Long = random(700, 2200).toLong()
    var synthIdlePauseMaxMs: Long = (synthIdlePauseMinMs + random(1800, 6000)).coerceAtMost(12_000L)

    fun applyDefaults() {
        for (field in this::class.java.declaredFields) {
            field.isAccessible = true
            if (field.get(this) == null) {
                field.set(this, field.get(PlayerProfiles.DEFAULTS))
            }
        }
    }

    val afkLogoutMinMillis get() = refreshWindowMillis().first
    val afkLogoutMaxMillis get() = refreshWindowMillis().second

    /**
     * When to next remind the client someone is here, as a window to pick from.
     *
     * Clamped rather than trusted, because the interval is persisted per player and the value this used to
     * generate - 400 to 425 seconds - is longer than the game's own five minute idle logout. A keepalive
     * slower than the thing it is meant to prevent never prevents it: it arrives a minute or two after the
     * client has already gone back to the lobby. Profiles written before that was noticed still hold the
     * old number, so the ceiling is applied on the way out and no migration is needed.
     */
    private fun refreshWindowMillis(): Pair<Long, Long> {
        val profile = PlayerProfiles.get()
        val seconds = profile.afkLogoutRefreshSeconds.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
        val variance = profile.afkLogoutRefreshVariance.coerceIn(0, MAX_REFRESH_VARIANCE_SECONDS)
        return (seconds - variance) * 1000L to (seconds + variance) * 1000L
    }

    private companion object {
        /** The game returns a client to the lobby after five minutes without input. */
        const val IDLE_LOGOUT_SECONDS = 300

        const val MAX_REFRESH_VARIANCE_SECONDS = 25

        /** Far enough inside the logout that a slow tick or a long action cannot overrun it. */
        const val MAX_REFRESH_SECONDS = IDLE_LOGOUT_SECONDS - MAX_REFRESH_VARIANCE_SECONDS - 25

        /** Below this it is pressing keys for no reason. */
        const val MIN_REFRESH_SECONDS = 120
    }
}