package com.projectx.util

import java.util.Properties

/** The commit and time this jar was built from, stamped in at build time. */
object EngineBuild {
    private val properties: Properties by lazy {
        Properties().also { props ->
            EngineBuild::class.java.getResourceAsStream("/engine-build.properties")?.use(props::load)
        }
    }

    val commit: String get() = properties.getProperty("commit", "unknown")
    val builtAt: String get() = properties.getProperty("builtAt", "unknown")

    fun describe(): String = "commit $commit built $builtAt"
}
