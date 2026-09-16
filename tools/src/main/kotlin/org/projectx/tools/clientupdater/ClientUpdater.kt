package org.projectx.tools.clientupdater

import org.projectx.tools.util.JavConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import org.projectx.core.LzmaAlone

/**
 * One per-OS NXT client target. The `binaryType` query param on jav_config.ws selects the platform;
 * each returns its own `codebase` host, `download_name_0`, `download_crc_0` (CRC32 of the
 * *decompressed* binary) and `server_version`.
 *
 * Each OS gets its own folder under the client root (`./data/client/<key>/`) holding the game client,
 * the Jagex launcher (`rs3*`) and our patcher lib — so per-OS files never collide (Linux and macOS
 * clients are both named `rs2client`). This tool downloads/verifies the **game client**; the
 * `rs3*` launcher is acquired/seeded by the cross-platform Rust launcher (it already extracts the
 * Linux one from Jagex's .deb), and this tool only reports whether it is present.
 *
 * The 32-bit Windows build (binaryType 1) is intentionally omitted: it is an ~80 KB i386 stub, not a
 * real client. The working Windows client is 64-bit (binaryType 2).
 *
 * Windows ships twice: binaryType 2 is the OpenGL client (tagged `NXT-Windows-64`) and binaryType 10
 * the Vulkan-only one (`NXT-Windows-64-Vulkan`). Both are named `rs2client.exe` and report the same
 * `server_version`, and they differ in `download_crc_0` and in every code and data address — hence
 * the separate `windows-vulkan` folder, and the per-renderer offset tables in the engine.
 */
enum class OsTarget(
    val key: String,            // folder name under the client root AND the --os CLI key
    val binaryType: Int,
    val label: String,
    val clientName: String,     // game-client filename inside the folder
    val launcherName: String,   // Jagex launcher (rs3*) filename inside the folder
    val magicName: String,
    val magic: ByteArray,
    /** Native executable on its host? ELF/Mach-O get +x; the Windows .exe does not. */
    val executable: Boolean,
) {
    LINUX("linux", 4, "Linux x86-64", "rs2client", "rs3linux", "ELF", byteArrayOf(0x7F, 0x45, 0x4C, 0x46), true),
    WINDOWS("windows", 2, "Windows 64-bit", "rs2client.exe", "rs3windows.exe", "PE/MZ", byteArrayOf(0x4D, 0x5A), false),
    WINDOWS_VULKAN("windows-vulkan", 10, "Windows 64-bit (Vulkan)", "rs2client.exe", "rs3windows.exe", "PE/MZ", byteArrayOf(0x4D, 0x5A), false),
    MACOS("macos", 3, "macOS x86-64", "rs2client", "rs3mac", "Mach-O", byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), true);

    /** Game-client path relative to the client root, e.g. `linux/rs2client`. */
    val clientPath: String get() = "$key/$clientName"

    /** Jagex launcher path relative to the client root, e.g. `linux/rs3linux`. */
    val launcherPath: String get() = "$key/$launcherName"

    companion object {
        fun byKey(key: String): OsTarget? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        val defaultSet: List<OsTarget> = entries.toList()
    }
}

enum class Status { UP_TO_DATE, OUTDATED, MISSING, ERROR }
enum class Action { NONE, INSTALLED, REPLACED, SKIPPED_OUTDATED, FAILED }

/** Result of checking (and optionally updating) one OS target. */
data class TargetResult(
    val target: OsTarget,
    val status: Status,
    var action: Action = Action.NONE,
    val serverVersion: String? = null,
    val latestCrc: Long? = null,
    val localCrc: Long? = null,
    val installedAt: File? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val sourceUrl: String? = null,
    val launcherPresent: Boolean = false,
    val launcherSize: Long? = null,
    val note: String? = null,
)

/** Knobs controlling what [ClientUpdater] does with each target. */
data class UpdateOptions(
    val rootDir: File,
    val checkOnly: Boolean,     // never write anything (pure dry run)
    val update: Boolean,        // replace OUTDATED existing binaries (MISSING are always filled unless checkOnly)
    val force: Boolean,         // re-download even when UP_TO_DATE
)

class ClientUpdater(private val options: UpdateOptions) {

    fun process(target: OsTarget): TargetResult {
        val launcherFile = File(options.rootDir, target.launcherPath)
        val launcherPresent = launcherFile.isFile
        val launcherSize = if (launcherPresent) launcherFile.length() else null

        val cfg = try {
            JavConfig.fetch(javConfigUrl(target.binaryType))
        } catch (e: Exception) {
            return TargetResult(
                target, Status.ERROR, Action.FAILED,
                launcherPresent = launcherPresent, launcherSize = launcherSize,
                note = "jav_config fetch failed: ${e.message}",
            )
        }

        val codebase = cfg.settings["codebase"]
        val name = cfg.settings["download_name_0"]
        val latestCrc = cfg.settings["download_crc_0"]?.toLongOrNull()
        val serverVersion = cfg.settings["server_version"]
        if (codebase.isNullOrBlank() || name.isNullOrBlank() || latestCrc == null) {
            return TargetResult(
                target, Status.ERROR, Action.FAILED, serverVersion = serverVersion,
                launcherPresent = launcherPresent, launcherSize = launcherSize,
                note = "jav_config missing codebase/download_name_0/download_crc_0",
            )
        }

        val localFile = File(options.rootDir, target.clientPath)
        val localCrc = if (localFile.isFile) crc32(localFile.readBytes()) else null
        val status = when {
            localCrc == null -> Status.MISSING
            localCrc == latestCrc -> Status.UP_TO_DATE
            else -> Status.OUTDATED
        }

        val base = TargetResult(
            target, status, serverVersion = serverVersion, latestCrc = latestCrc, localCrc = localCrc,
            launcherPresent = launcherPresent, launcherSize = launcherSize,
        )

        val shouldDownload = when {
            options.checkOnly -> false
            options.force -> true
            status == Status.MISSING -> true
            status == Status.OUTDATED -> options.update
            else -> false
        }
        if (!shouldDownload) {
            val action = if (status == Status.OUTDATED && !options.checkOnly) Action.SKIPPED_OUTDATED else Action.NONE
            return base.copy(action = action)
        }

        val url = downloadUrl(codebase, target.binaryType, name, latestCrc)
        return try {
            println("  ↓ ${target.key}: downloading $url")
            val compressed = httpGet(url)
            val binary = LzmaAlone.decompress(compressed)

            val actualCrc = crc32(binary)
            require(actualCrc == latestCrc) {
                "CRC32 mismatch after decompress: expected $latestCrc (download_crc_0), got $actualCrc"
            }
            require(startsWith(binary, target.magic)) {
                "Unexpected magic: not a ${target.magicName} binary (got ${hexPrefix(binary)})"
            }

            val replacing = localFile.isFile
            writeAtomically(localFile, binary, target.executable)
            base.copy(
                action = if (replacing) Action.REPLACED else Action.INSTALLED,
                localCrc = actualCrc,
                installedAt = localFile,
                sizeBytes = binary.size.toLong(),
                sha256 = sha256(binary),
                sourceUrl = url,
            )
        } catch (e: Exception) {
            base.copy(action = Action.FAILED, sourceUrl = url, note = e.message)
        }
    }

    private fun writeAtomically(dest: File, data: ByteArray, executable: Boolean) {
        dest.parentFile?.mkdirs()
        val tmp = File.createTempFile(dest.name + ".", ".part", dest.parentFile)
        try {
            tmp.writeBytes(data)
            if (executable) tmp.setExecutable(true, true)
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private fun httpGet(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val JAV_CONFIG_BASE = "https://www.runescape.com/k=5/l=0/jav_config.ws"

        fun javConfigUrl(binaryType: Int) = "$JAV_CONFIG_BASE?binaryType=$binaryType"

        /** Verified live URL scheme: `{codebase}client?binaryType=N&fileName=NAME&crc=CRC`. */
        fun downloadUrl(codebase: String, binaryType: Int, name: String, crc: Long): String {
            val base = if (codebase.endsWith("/")) codebase else "$codebase/"
            return "${base}client?binaryType=$binaryType&fileName=$name&crc=$crc"
        }

        fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

        fun sha256(data: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }

        private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
            if (data.size < prefix.size) return false
            for (i in prefix.indices) if (data[i] != prefix[i]) return false
            return true
        }

        private fun hexPrefix(data: ByteArray, n: Int = 4): String =
            data.take(n).joinToString(" ") { "%02x".format(it) }
    }
}
