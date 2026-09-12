package org.projectx.core.net

import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import world.gregs.voidps.buffer.readRSString
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUMedium
import world.gregs.voidps.buffer.readUShort

enum class LoginMode { LOBBY, WORLD }

@Serializable
data class MachineLoginRecord(
    val at: Long,
    val ip: String,
    val platform: ClientPlatform,
    val machine: MachineInformation,
)

const val MAX_MACHINE_HISTORY = 25

fun MutableList<MachineLoginRecord>.recordMachineLogin(machine: MachineInformation, ip: String, at: Long) {
    add(MachineLoginRecord(at, ip, machine.platform, machine))
    while (size > MAX_MACHINE_HISTORY) removeAt(0)
}

@Serializable
data class MachineInformation(
    val mode: LoginMode,
    val hasUsername: Boolean,
    val username: String,
    val usernameLong: Long,
    val configCharA: Int,
    val configCharB: Int,
    val connectionMode: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val displayMode: Int,
    val machineUid: ByteArray,
    val loginToken: String,
    val capabilities: MachineCapabilities,
    val device: DeviceInfo,
    val anticacheInt0: Int,
    val js5Anticache: List<Int>,
    val clientToken: String,
    val sessionToken: String,
    val binaryType: Int,
    val platformType: Int,
    val worldChangedFlag: Boolean,
    val clientSettings: List<Int>,
    val intField196a4: Int,
    val intField197d4: Int,
    val worldConfigInt1: Int,
    val worldField0: Long,
    val worldField1: Long,
    val reconnectToken: String,
    val worldConst1: Int,
    val worldByte: Int,
    val worldExtraInt: Int,
    val selfIndex: Int,
) {
    val platform: ClientPlatform get() = ClientPlatform.fromClientName(device.clientName)
    val mobile: Boolean get() = platform.mobile

    companion object {
        private const val MACHINE_UID_LEN = 24
        private const val CLIENT_SETTINGS_COUNT = 46

        fun decode(tail: Source, mode: LoginMode): MachineInformation {
            val world = mode == LoginMode.WORLD
            val hasUsername = tail.readUByte() == 1
            var username = ""
            var usernameLong = 0L
            if (hasUsername) username = tail.readRSString() else usernameLong = tail.readLong()

            val configCharA = if (world) -1 else tail.readUByte()
            val configCharB = if (world) -1 else tail.readUByte()
            val connectionMode = tail.readUByte()
            val screenWidth = tail.readUShort()
            val screenHeight = tail.readUShort()
            val displayMode = tail.readUByte()
            val machineUid = tail.readByteArray(MACHINE_UID_LEN)
            val loginToken = tail.readRSString()

            val worldConfigInt1 = if (world) tail.readInt() else 0

            val machineInfoLen = tail.readUByte()
            val capabilities = MachineCapabilities(tail.readByteArray(machineInfoLen))
            val device = DeviceInfo.decode(tail)

            val anticacheInt0 = tail.readInt()
            val worldField0 = if (world) tail.readLong() else 0L
            val worldField1 = if (world) tail.readLong() else 0L
            val js5Anticache = List(tail.readUByte()) { tail.readInt() }
            val clientToken = tail.readRSString()

            var reconnectToken = ""
            var worldConst1 = -1
            var worldByte = -1
            var worldExtraInt = 0
            var intField196a4 = 0
            var intField197d4 = 0
            val sessionToken: String
            val binaryType: Int
            val platformType: Int
            val worldChangedFlag: Boolean
            var selfIndex = -1

            if (world) {
                if (tail.readUByte() != 0) reconnectToken = tail.readRSString()
                worldConst1 = tail.readUByte()
                worldByte = tail.readUByte()
                binaryType = tail.readUByte()
                platformType = tail.readUByte()
                worldExtraInt = tail.readInt()
                sessionToken = tail.readRSString()
                worldChangedFlag = tail.readUByte() != 0
                selfIndex = tail.readUShort()
            } else {
                intField196a4 = tail.readInt()
                intField197d4 = tail.readInt()
                sessionToken = tail.readRSString()
                binaryType = tail.readUByte()
                platformType = tail.readUByte()
                worldChangedFlag = tail.readUByte() != 0
            }

            val clientSettings = List(CLIENT_SETTINGS_COUNT) { tail.readInt() }

            return MachineInformation(
                mode = mode,
                hasUsername = hasUsername,
                username = username,
                usernameLong = usernameLong,
                configCharA = configCharA,
                configCharB = configCharB,
                connectionMode = connectionMode,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                displayMode = displayMode,
                machineUid = machineUid,
                loginToken = loginToken,
                capabilities = capabilities,
                device = device,
                anticacheInt0 = anticacheInt0,
                js5Anticache = js5Anticache,
                clientToken = clientToken,
                sessionToken = sessionToken,
                binaryType = binaryType,
                platformType = platformType,
                worldChangedFlag = worldChangedFlag,
                clientSettings = clientSettings,
                intField196a4 = intField196a4,
                intField197d4 = intField197d4,
                worldConfigInt1 = worldConfigInt1,
                worldField0 = worldField0,
                worldField1 = worldField1,
                reconnectToken = reconnectToken,
                worldConst1 = worldConst1,
                worldByte = worldByte,
                worldExtraInt = worldExtraInt,
                selfIndex = selfIndex,
            )
        }
    }
}

@Serializable
data class MachineCapabilities(val raw: ByteArray) {
    val marker: Int get() = if (raw.isNotEmpty()) raw[0].toInt() and 0xff else -1

    val limitCounts: List<Int> get() = if (raw.size >= 44) (36..42 step 2).map { ((raw[it].toInt() and 0xff) shl 8) or (raw[it + 1].toInt() and 0xff) } else emptyList()

    val maxCaps: List<Int> get() = if (raw.size >= 57) (52..56).map { raw[it].toInt() and 0xff } else emptyList()
}

@Serializable
data class DeviceInfo(
    val deviceFlag0: Int,
    val glContextFlag: Boolean,
    val deviceShort8: Int,
    val mediumA8: Int,
    val gpuVendor: String,
    val glRenderer: String,
    val glExtra: String,
    val glVersion: String,
    val deviceByteD8: Int,
    val deviceShortDC: Int,
    val cpuVendor: String,
    val cpuModel: String,
    val cpuByteA0: Int,
    val cpuByteA4: Int,
    val deviceInts: List<Int>,
    val clientName: String,
    val deviceString: String,
    val framing: List<Int>,
) {
    companion object {
        private const val MARKER = 0x09

        fun decode(tail: Source): DeviceInfo {
            val framing = ArrayList<Int>()
            val marker = tail.readUByte()
            require(marker == MARKER) { "deviceInfo marker ${"0x%02x".format(marker)} != 0x09" }
            framing.add(marker)
            val deviceFlag0 = tail.readUByte()
            val glContextFlag = tail.readUByte() != 0
            val deviceShort8 = tail.readUShort()
            repeat(5) { framing.add(tail.readUByte()) }
            framing.add(tail.readUShort())
            framing.add(tail.readUByte())
            val mediumA8 = tail.readUMedium()
            framing.add(tail.readUShort())

            framing.add(tail.readUByte()); val gpuVendor = tail.readRSString()
            framing.add(tail.readUByte()); val glRenderer = tail.readRSString()
            framing.add(tail.readUByte()); val glExtra = tail.readRSString()
            framing.add(tail.readUByte()); val glVersion = tail.readRSString()
            val deviceByteD8 = tail.readUByte()
            val deviceShortDC = tail.readUShort()
            framing.add(tail.readUByte()); val cpuVendor = tail.readRSString()
            framing.add(tail.readUByte()); val cpuModel = tail.readRSString()
            val cpuByteA0 = tail.readUByte()
            val cpuByteA4 = tail.readUByte()
            val deviceInts = List(4) { tail.readInt() }
            framing.add(tail.readUByte()); val clientName = tail.readRSString()
            framing.add(tail.readUByte()); val deviceString = tail.readRSString()

            return DeviceInfo(
                deviceFlag0, glContextFlag, deviceShort8, mediumA8,
                gpuVendor, glRenderer, glExtra, glVersion,
                deviceByteD8, deviceShortDC, cpuVendor, cpuModel, cpuByteA0, cpuByteA4, deviceInts,
                clientName, deviceString, framing,
            )
        }
    }
}
