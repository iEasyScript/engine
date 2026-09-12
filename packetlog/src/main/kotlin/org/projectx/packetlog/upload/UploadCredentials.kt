package org.projectx.packetlog.upload

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The device credential and the pseudonymisation salt.
 *
 * ⛔ Deliberately not in `config.json`: that file gets pasted into bug reports, and it already holds
 * one plaintext secret too many. This one is written owner-only and never uploaded - the salt in
 * particular must stay local, because it is what stops the server inverting a pseudonym back to a
 * name it has a dictionary for.
 */
@Serializable
data class UploadCredentials(
    val deviceId: String = "",
    val deviceSecret: String = "",
    val pseudonymSalt: String = "",
    /**
     * Stable for the life of this install, and sent when registering. It survives a credential being
     * revoked and replaced, so an operator banning a source can ban the machine rather than playing
     * whack-a-mole with the credentials it keeps minting.
     */
    val installId: String = "",
    /**
     * Set when the server has refused this device outright. Registration is open, so the only thing
     * stopping a rejected client from immediately registering again is that it remembers being
     * told no.
     */
    val blocked: Boolean = false,
) {

    val enrolled: Boolean get() = deviceId.isNotBlank() && deviceSecret.isNotBlank()

    companion object {

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun defaultFile(): File =
            File(File(System.getProperty("user.home"), ".projectx/packetlog"), "credentials.json")

        fun load(file: File = defaultFile()): UploadCredentials {
            val loaded = if (!file.isFile) null
            else runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()
            val credentials = loaded ?: UploadCredentials()
            // Fill in anything a older file predates, so an upgrade does not need a migration.
            return credentials.copy(
                pseudonymSalt = credentials.pseudonymSalt.ifBlank { randomToken() },
                installId = credentials.installId.ifBlank { randomToken() },
            )
        }

        fun copyOf(
            base: UploadCredentials,
            deviceId: String = base.deviceId,
            deviceSecret: String = base.deviceSecret,
            blocked: Boolean = base.blocked,
        ): UploadCredentials = UploadCredentials(
            deviceId = deviceId,
            deviceSecret = deviceSecret,
            pseudonymSalt = base.pseudonymSalt,
            installId = base.installId,
            blocked = blocked,
        )

        fun save(credentials: UploadCredentials, file: File = defaultFile()) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), credentials))
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }

        private fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
