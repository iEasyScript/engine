package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.INTERFACES
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.ComponentCheckbox
import world.gregs.voidps.cache.type.data.ComponentDropdown
import world.gregs.voidps.cache.type.data.ComponentGraphic
import world.gregs.voidps.cache.type.data.ComponentGraphicSet
import world.gregs.voidps.cache.type.data.ComponentGrid
import world.gregs.voidps.cache.type.data.ComponentHook
import world.gregs.voidps.cache.type.data.ComponentInput
import world.gregs.voidps.cache.type.data.ComponentKey
import world.gregs.voidps.cache.type.data.ComponentLayer
import world.gregs.voidps.cache.type.data.ComponentLine
import world.gregs.voidps.cache.type.data.ComponentModel
import world.gregs.voidps.cache.type.data.ComponentPanel
import world.gregs.voidps.cache.type.data.ComponentRectangle
import world.gregs.voidps.cache.type.data.ComponentText
import world.gregs.voidps.cache.type.data.ComponentType
import world.gregs.voidps.cache.type.data.ComponentType.Companion.CHECKBOX
import world.gregs.voidps.cache.type.data.ComponentType.Companion.DROPDOWN
import world.gregs.voidps.cache.type.data.ComponentType.Companion.GRAPHIC
import world.gregs.voidps.cache.type.data.ComponentType.Companion.GRID
import world.gregs.voidps.cache.type.data.ComponentType.Companion.INPUT
import world.gregs.voidps.cache.type.data.ComponentType.Companion.LAYER
import world.gregs.voidps.cache.type.data.ComponentType.Companion.LINE
import world.gregs.voidps.cache.type.data.ComponentType.Companion.MODEL
import world.gregs.voidps.cache.type.data.ComponentType.Companion.PANEL
import world.gregs.voidps.cache.type.data.ComponentType.Companion.RECTANGLE
import world.gregs.voidps.cache.type.data.ComponentType.Companion.TEXT
import world.gregs.voidps.cache.type.data.ComponentType.Companion.TYPE_10
import world.gregs.voidps.cache.type.data.ComponentType10
import world.gregs.voidps.cache.type.data.InterfaceType

class ComponentDecoder : TypeDecoder<ComponentType>(INTERFACES) {

    override fun create(size: Int) = Array(size) { ComponentType(id = it) }

    /** Components are addressed by [InterfaceType.pack], which no flat array of ids can span. */
    override fun size(cache: Cache) = 0

    override fun getArchive(id: Int) = InterfaceType.id(id)

    override fun getFile(id: Int) = InterfaceType.componentId(id)

    override fun readLoop(definition: ComponentType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun ComponentType.read(opcode: Int, buffer: Reader) = throw UnsupportedOperationException("Components are not opcode encoded.")

    private fun ComponentType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte().let { if (it == NULL_VERSION) -1 else it }
        val header = buffer.readUnsignedByte()
        type = header and TYPE_MASK
        if (header and NAMED != 0) {
            debugName = buffer.readString()
        }
        contentType = buffer.readUnsignedShort()
        basePositionX = buffer.readShort()
        basePositionY = buffer.readShort()
        baseWidth = buffer.readUnsignedShort()
        baseHeight = buffer.readUnsignedShort()
        horizontalSizeMode = buffer.readUnsignedByte()
        verticalSizeMode = buffer.readUnsignedByte()
        horizontalPositionMode = buffer.readUnsignedByte()
        verticalPositionMode = buffer.readUnsignedByte()
        if (horizontalSizeMode == ASPECT_MODE || verticalSizeMode == ASPECT_MODE) {
            aspectWidth = buffer.readUnsignedShort()
            aspectHeight = buffer.readUnsignedShort()
        }
        parent = buffer.readUnsignedShort().orNull()
        flags = buffer.readUnsignedByte()
        readContent(buffer)
        if (version <= 5) {
            settings = buffer.readUnsignedMedium()
        } else {
            unknownSettings = buffer.readInt()
            if (version > 8) {
                unknownSettingsByte = buffer.readUnsignedByte()
            }
            settings = buffer.readInt()
        }
        keys = buffer.readKeys()
        name = buffer.readString()
        val counts = buffer.readUnsignedByte()
        val optionCount = counts and 0xf
        if (optionCount > 0) {
            options = List(optionCount) { buffer.readString() }
        }
        val iconCount = counts shr 4
        if (iconCount != 0) {
            val icons = LinkedHashMap<Int, Int>(2)
            buffer.readIcon(icons)
            if (iconCount != 1) {
                buffer.readIcon(icons)
            }
            mouseIcons = icons
        }
        optionOverride = buffer.readString()
        dragDeadZone = buffer.readUnsignedByte()
        dragDeadTime = buffer.readUnsignedByte()
        dragRenderBehaviour = buffer.readUnsignedByte()
        targetVerb = buffer.readString()
        if (settings and TARGET_SETTINGS != 0) {
            targetSlot = buffer.readUnsignedShort().orNull()
            unknownTargetShort = buffer.readUnsignedShort()
            unknownTargetShort2 = buffer.readUnsignedShort()
        }
        if (version >= 0) {
            unknownVersionedShort = buffer.readUnsignedShort()
            readComponentParameters(buffer)
        }
        hooks = buffer.readHooks(version)
        varTriggers = buffer.readTriggers()
        invTriggers = buffer.readTriggers()
        statTriggers = buffer.readTriggers()
        varcTriggers = buffer.readTriggers()
        varcStringTriggers = buffer.readTriggers()
    }

    private fun ComponentType.readContent(buffer: Reader) {
        when (type) {
            LAYER -> layer = buffer.readLayer(version)
            RECTANGLE -> rectangle = ComponentRectangle(buffer.readInt(), buffer.readUnsignedBoolean(), buffer.readAlpha())
            TEXT -> text = buffer.readText(version)
            GRAPHIC -> graphic = buffer.readGraphic(version)
            MODEL -> model = readModel(buffer)
            LINE -> line = ComponentLine(buffer.readUnsignedByte(), buffer.readInt(), buffer.readUnsignedBoolean())
            TYPE_10 -> type10 = ComponentType10(
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readInt(),
                buffer.readAlpha(),
                buffer.readInt(),
                buffer.readGraphic(version),
                buffer.readText(version),
            )
            PANEL -> panel = ComponentPanel(
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedBoolean(),
                buffer.readUnsignedByte(),
            )
            CHECKBOX -> checkbox = ComponentCheckbox(
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readInt(),
                buffer.readGraphic(version),
                buffer.readText(version),
            )
            INPUT -> input = buffer.readInput(version)
            GRID -> grid = ComponentGrid(
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedBoolean(),
            )
            DROPDOWN -> dropdown = buffer.readDropdown(version)
        }
    }

    private fun ComponentType.readModel(buffer: Reader): ComponentModel {
        val model = ComponentModel()
        model.modelId = buffer.readBigSmart()
        model.flags = buffer.readUnsignedByte()
        if (model.flags and MODEL_OFFSET_2D != 0) {
            model.offsetX = buffer.readShort()
            model.offsetY = buffer.readShort()
            model.pitch = buffer.readUnsignedShort()
            model.roll = buffer.readUnsignedShort()
            model.yaw = buffer.readUnsignedShort()
            model.scale = buffer.readUnsignedShort()
        } else if (model.flags and MODEL_OFFSET_3D != 0) {
            model.offsetX = buffer.readShort()
            model.offsetY = buffer.readShort()
            model.offsetZ = buffer.readShort()
            model.pitch = buffer.readUnsignedShort()
            model.roll = buffer.readUnsignedShort()
            model.yaw = buffer.readUnsignedShort()
            model.scale = buffer.readShort()
        }
        model.animation = buffer.readBigSmart()
        if (horizontalSizeMode != 0) {
            model.viewportWidth = buffer.readUnsignedShort()
        }
        if (verticalSizeMode != 0) {
            model.viewportHeight = buffer.readUnsignedShort()
        }
        return model
    }

    private fun Reader.readLayer(version: Int): ComponentLayer {
        val layer = ComponentLayer()
        layer.scrollWidth = readUnsignedShort()
        layer.scrollHeight = readUnsignedShort()
        when {
            version < 0 -> layer.noClickThrough = readUnsignedByte()
            version >= 9 -> layer.scrollBarColour = readInt()
            version >= 6 -> layer.scrollBar = List(4) { readUnsignedShort() }
        }
        return layer
    }

    private fun Reader.readInput(version: Int): ComponentInput {
        val content = ComponentInput()
        content.unknown1 = readUnsignedByte()
        content.unknown2 = readUnsignedByte()
        content.unknown3 = readUnsignedByte()
        content.unknown4 = readUnsignedShort()
        if (version >= 9) {
            content.unknownColour = readInt()
            content.unknown5 = readUnsignedByte()
        } else if (version >= 7) {
            content.unknown5 = readUnsignedByte()
        }
        content.alpha = readAlpha()
        content.colour = readInt()
        content.graphic = readGraphic(version)
        content.text = readText(version)
        content.graphics = readGraphicSet(version)
        return content
    }

    private fun Reader.readDropdown(version: Int): ComponentDropdown {
        val content = ComponentDropdown()
        content.unknown1 = readUnsignedByte()
        content.unknown2 = readUnsignedByte()
        content.unknown3 = readUnsignedByte()
        content.unknown4 = readUnsignedByte()
        if (version > 8) {
            content.unknown5 = readUnsignedByte()
        }
        content.unknown6 = readUnsignedByte()
        content.unknown7 = readUnsignedByte()
        val entries = readUnsignedShort()
        content.entryNames = List(entries) { readString() }
        content.entryValueCount = readUnsignedShort()
        if (content.entryValueCount != entries) {
            return content
        }
        content.entryValues = List(entries) { readInt() }
        content.entryOrder = List(readUnsignedShort()) { readUnsignedShort() }
        if (version > 8) {
            content.unknownColour1 = readInt()
            content.unknownColour2 = readInt()
        }
        content.alpha = readAlpha()
        content.colour = readInt()
        content.graphics = List(3) { readGraphic(version) }
        content.text = readText(version)
        content.stateGraphics = readGraphicSet(version)
        return content
    }

    private fun Reader.readGraphic(version: Int): ComponentGraphic {
        val graphic = ComponentGraphic()
        graphic.graphicId = readInt()
        graphic.angle = readUnsignedShort()
        graphic.tiling = readUnsignedByte()
        graphic.alpha = readAlpha()
        graphic.outline = readUnsignedByte()
        graphic.outlineColour = readInt()
        graphic.flipVertical = readUnsignedBoolean()
        graphic.flipHorizontal = readUnsignedBoolean()
        graphic.colour = readInt()
        if (version > 2) {
            graphic.unknownFlag = readUnsignedBoolean()
        }
        if (version > 5) {
            graphic.unknownColour = readInt()
        }
        return graphic
    }

    private fun Reader.readText(version: Int): ComponentText {
        val text = ComponentText()
        text.fontId = readBigSmart()
        if (version > 1) {
            text.fontStyle = readUnsignedByte()
        }
        text.text = readString()
        text.lineHeight = readUnsignedByte()
        text.horizontalAlignment = readUnsignedByte()
        text.verticalAlignment = readUnsignedByte()
        text.shadowed = readUnsignedBoolean()
        text.colour = readInt()
        text.alpha = readAlpha()
        if (version >= 0) {
            text.unknownStyle = readUnsignedByte()
        }
        return text
    }

    private fun Reader.readGraphicSet(version: Int) = ComponentGraphicSet(
        readUnsignedByte(),
        readUnsignedByte(),
        readUnsignedByte(),
        readUnsignedByte(),
        List(3) { readGraphic(version) },
    )

    private fun Reader.readKeys(): Map<Int, ComponentKey>? {
        var header = readUnsignedByte()
        if (header == 0) {
            return null
        }
        val keys = LinkedHashMap<Int, ComponentKey>(4)
        while (header != 0) {
            val slot = (header shr 4) - 1
            val modifier = readUnsignedByte() or ((header and 0xf) shl 8)
            keys[slot] = ComponentKey(if (modifier == NULL_MODIFIER) -1 else modifier, readByte(), readByte())
            header = readUnsignedByte()
        }
        return keys
    }

    private fun Reader.readIcon(icons: MutableMap<Int, Int>) {
        val slot = readUnsignedByte()
        icons[slot] = readUnsignedShort()
    }

    private fun ComponentType.readComponentParameters(buffer: Reader) {
        var values: MutableMap<Int, Any>? = null
        repeat(buffer.readUnsignedByte()) {
            val key = buffer.readUnsignedMedium()
            val value = buffer.readInt()
            (values ?: LinkedHashMap<Int, Any>().also { values = it })[key] = value
        }
        var versions: MutableMap<Int, Int>? = null
        repeat(buffer.readUnsignedByte()) {
            val key = buffer.readUnsignedMedium()
            val version = buffer.readUnsignedByte()
            (values ?: LinkedHashMap<Int, Any>().also { values = it })[key] = if (version == 0) buffer.readString() else ""
            if (version != 0) {
                (versions ?: LinkedHashMap<Int, Int>().also { versions = it })[key] = version
            }
        }
        params = values
        paramStringVersions = versions
    }

    private fun Reader.readHooks(version: Int): Map<Int, ComponentHook>? {
        var hooks: MutableMap<Int, ComponentHook>? = null
        fun read(slot: Int) {
            val hook = readHook() ?: return
            (hooks ?: LinkedHashMap<Int, ComponentHook>().also { hooks = it })[slot] = hook
        }
        for (slot in HOOK_SLOTS) {
            read(slot)
        }
        if (version >= 0) {
            read(HOOK_SLOT_VERSIONED)
        }
        for (slot in HOOK_SLOTS_TAIL) {
            read(slot)
        }
        if (version > 5) {
            for (slot in HOOK_SLOTS_V6) {
                read(slot)
            }
        }
        if (version > 7) {
            read(HOOK_SLOT_V8)
        }
        return hooks
    }

    private fun Reader.readHook(): ComponentHook? {
        val length = readUnsignedByte()
        if (length == 0) {
            return null
        }
        var scriptId = -1
        val arguments = ArrayList<Any>(length - 1)
        for (i in 0 until length) {
            val tag = readUnsignedByte()
            when {
                i == 0 -> scriptId = readInt()
                tag == HOOK_ARGUMENT_INT -> arguments.add(readInt())
                tag == HOOK_ARGUMENT_STRING -> arguments.add(readString())
            }
        }
        return ComponentHook(scriptId, arguments)
    }

    private fun Reader.readTriggers(): List<Int>? {
        val length = readUnsignedByte()
        if (length == 0) {
            return null
        }
        return List(length) { readInt() }
    }

    private fun Reader.readAlpha() = readUnsignedByte().inv() and 0xff

    private fun Int.orNull() = if (this == NULL_ID) -1 else this

    internal companion object {
        const val NULL_VERSION = 0xff
        const val NULL_ID = 0xffff
        const val NULL_MODIFIER = 0xfff
        const val NAMED = 0x80
        const val TYPE_MASK = 0x7f
        const val ASPECT_MODE = 4
        const val TARGET_SETTINGS = 0x3f800
        const val MODEL_OFFSET_2D = 0x1
        const val MODEL_OFFSET_3D = 0x2
        const val HOOK_ARGUMENT_INT = 0
        const val HOOK_ARGUMENT_STRING = 1
        const val HOOK_SLOT_VERSIONED = 40
        const val HOOK_SLOT_V8 = 54

        val HOOK_SLOTS = intArrayOf(37, 4, 6, 16, 15, 18, 19, 20, 38, 39)
        val HOOK_SLOTS_TAIL = intArrayOf(5, 0, 1, 2, 3, 7, 8, 9, 21, 22)
        val HOOK_SLOTS_V6 = intArrayOf(51, 52, 53)
    }
}
