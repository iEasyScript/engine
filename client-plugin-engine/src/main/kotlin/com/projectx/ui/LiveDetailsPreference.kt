package com.projectx.ui

import java.io.File

/** Whether the instance heartbeat tells the website's Control Panel what this client is running. On unless turned off. */
internal object LiveDetailsPreference {
    private val off = File(System.getProperty("user.home"), ".projectx/website-live-details-off")

    val enabled: Setting<Boolean> = setting(!off.exists())

    fun save(on: Boolean) {
        if (on) off.delete() else off.apply { parentFile.mkdirs() }.createNewFile()
    }
}
