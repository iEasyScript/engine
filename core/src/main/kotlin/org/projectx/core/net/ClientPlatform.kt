package org.projectx.core.net

import kotlinx.serialization.Serializable

@Serializable
enum class ClientPlatform(val mobile: Boolean) {
    WINDOWS(false),
    LINUX(false),
    MAC(false),
    ANDROID(true),
    IOS(true),
    UNKNOWN(false);

    companion object {
        fun fromClientName(clientName: String): ClientPlatform = when {
            clientName.endsWith("Windows", ignoreCase = true) -> WINDOWS
            clientName.endsWith("Linux", ignoreCase = true) -> LINUX
            clientName.endsWith("Mac", ignoreCase = true) -> MAC
            clientName.endsWith("Android", ignoreCase = true) -> ANDROID
            clientName.endsWith("iOS", ignoreCase = true) -> IOS
            else -> UNKNOWN
        }
    }
}
