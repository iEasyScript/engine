package com.projectx.game.nxt

import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readShort
import com.projectx.game.platform.Platform
import com.projectx.game.platform.Renderer
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * Identifies which renderer build of the client is running, from the platform tag the build system
 * compiles into it. The Vulkan Windows binary is tagged `NXT-Windows-64-Vulkan`; the OpenGL one only
 * `NXT-Windows-64`. The tag lives in `.rdata`, so only that section is scanned.
 */
object ClientRenderer {
    private val VULKAN_TAG = "NXT-Windows-64-Vulkan".toByteArray(Charsets.US_ASCII)
    private const val RDATA = ".rdata"

    val current: Renderer by lazy {
        check(NativeAccess.isAttached) { "The client renderer can only be read from inside an injected client" }
        detect(NativeAccess.BASE_ADDR)
    }

    private fun detect(image: MemorySegment): Renderer {
        if (Platform.current != Platform.WINDOWS) return Renderer.OPENGL
        val rdata = section(image, RDATA) ?: error("Client image has no $RDATA section")
        val bytes = rdata.toArray(JAVA_BYTE)
        return if (indexOf(bytes, VULKAN_TAG) >= 0) Renderer.VULKAN else Renderer.OPENGL
    }

    private fun section(image: MemorySegment, name: String): MemorySegment? {
        val peHeader = image.readInt(PE_HEADER_POINTER).toLong()
        val sectionCount = image.readShort(peHeader + SECTION_COUNT).toInt() and 0xffff
        val optionalHeaderSize = image.readShort(peHeader + OPTIONAL_HEADER_SIZE).toInt() and 0xffff
        val table = peHeader + OPTIONAL_HEADER + optionalHeaderSize
        for (i in 0 until sectionCount) {
            val header = table + i * SECTION_HEADER_SIZE
            val sectionName = ByteArray(SECTION_NAME_LENGTH) { image.get(JAVA_BYTE, header + it) }
                .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            if (sectionName == name) {
                val size = image.readInt(header + SECTION_VIRTUAL_SIZE).toLong()
                val address = image.readInt(header + SECTION_VIRTUAL_ADDRESS).toLong()
                return image.asSlice(address, size)
            }
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private const val PE_HEADER_POINTER = 0x3cL
    private const val SECTION_COUNT = 0x6L
    private const val OPTIONAL_HEADER_SIZE = 0x14L
    private const val OPTIONAL_HEADER = 0x18L
    private const val SECTION_HEADER_SIZE = 0x28L
    private const val SECTION_NAME_LENGTH = 8
    private const val SECTION_VIRTUAL_SIZE = 0x8L
    private const val SECTION_VIRTUAL_ADDRESS = 0xcL
}
