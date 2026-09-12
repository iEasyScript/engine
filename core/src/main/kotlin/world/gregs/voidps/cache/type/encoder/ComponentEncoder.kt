package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.ComponentCheckbox
import world.gregs.voidps.cache.type.data.ComponentDropdown
import world.gregs.voidps.cache.type.data.ComponentGraphic
import world.gregs.voidps.cache.type.data.ComponentGraphicSet
import world.gregs.voidps.cache.type.data.ComponentGrid
import world.gregs.voidps.cache.type.data.ComponentHook
import world.gregs.voidps.cache.type.data.ComponentInput
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
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.ASPECT_MODE
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_ARGUMENT_INT
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_ARGUMENT_STRING
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_SLOTS
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_SLOTS_TAIL
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_SLOTS_V6
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_SLOT_V8
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.HOOK_SLOT_VERSIONED
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.MODEL_OFFSET_2D
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.MODEL_OFFSET_3D
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.NAMED
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.NULL_ID
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.NULL_MODIFIER
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.NULL_VERSION
import world.gregs.voidps.cache.type.decoder.ComponentDecoder.Companion.TARGET_SETTINGS

class ComponentEncoder : TypeEncoder<ComponentType> {

    override fun Writer.encode(definition: ComponentType) {
        val version = definition.version
        writeByte(if (version == -1) NULL_VERSION else version)
        writeByte(definition.type or if (definition.debugName != null) NAMED else 0)
        definition.debugName?.let { writeString(it) }
        writeShort(definition.contentType)
        writeShort(definition.basePositionX)
        writeShort(definition.basePositionY)
        writeShort(definition.baseWidth)
        writeShort(definition.baseHeight)
        writeByte(definition.horizontalSizeMode)
        writeByte(definition.verticalSizeMode)
        writeByte(definition.horizontalPositionMode)
        writeByte(definition.verticalPositionMode)
        if (definition.horizontalSizeMode == ASPECT_MODE || definition.verticalSizeMode == ASPECT_MODE) {
            writeShort(definition.aspectWidth)
            writeShort(definition.aspectHeight)
        }
        writeShort(definition.parent.orNull())
        writeByte(definition.flags)
        writeContent(definition, version)
        if (version <= 5) {
            writeMedium(definition.settings)
        } else {
            writeInt(definition.unknownSettings)
            if (version > 8) {
                writeByte(definition.unknownSettingsByte)
            }
            writeInt(definition.settings)
        }
        writeKeys(definition)
        writeString(definition.name)
        val options = definition.options ?: emptyList()
        val icons = definition.mouseIcons ?: emptyMap()
        writeByte((icons.size shl 4) or options.size)
        for (option in options) {
            writeString(option)
        }
        for ((slot, icon) in icons) {
            writeByte(slot)
            writeShort(icon)
        }
        writeString(definition.optionOverride)
        writeByte(definition.dragDeadZone)
        writeByte(definition.dragDeadTime)
        writeByte(definition.dragRenderBehaviour)
        writeString(definition.targetVerb)
        if (definition.settings and TARGET_SETTINGS != 0) {
            writeShort(definition.targetSlot.orNull())
            writeShort(definition.unknownTargetShort)
            writeShort(definition.unknownTargetShort2)
        }
        if (version >= 0) {
            writeShort(definition.unknownVersionedShort)
            writeParameters(definition)
        }
        writeHooks(definition, version)
        writeTriggers(definition.varTriggers)
        writeTriggers(definition.invTriggers)
        writeTriggers(definition.statTriggers)
        writeTriggers(definition.varcTriggers)
        writeTriggers(definition.varcStringTriggers)
    }

    private fun Writer.writeContent(definition: ComponentType, version: Int) {
        when (definition.type) {
            LAYER -> write(definition.layer!!, version)
            RECTANGLE -> write(definition.rectangle!!)
            TEXT -> write(definition.text!!, version)
            GRAPHIC -> write(definition.graphic!!, version)
            MODEL -> write(definition.model!!, definition)
            LINE -> write(definition.line!!)
            TYPE_10 -> write(definition.type10!!, version)
            PANEL -> write(definition.panel!!)
            CHECKBOX -> write(definition.checkbox!!, version)
            INPUT -> write(definition.input!!, version)
            GRID -> write(definition.grid!!)
            DROPDOWN -> write(definition.dropdown!!, version)
        }
    }

    private fun Writer.write(layer: ComponentLayer, version: Int) {
        writeShort(layer.scrollWidth)
        writeShort(layer.scrollHeight)
        when {
            version < 0 -> writeByte(layer.noClickThrough)
            version >= 9 -> writeInt(layer.scrollBarColour)
            version >= 6 -> for (value in layer.scrollBar) writeShort(value)
        }
    }

    private fun Writer.write(rectangle: ComponentRectangle) {
        writeInt(rectangle.colour)
        writeByte(rectangle.filled)
        writeAlpha(rectangle.alpha)
    }

    private fun Writer.write(line: ComponentLine) {
        writeByte(line.width)
        writeInt(line.colour)
        writeByte(line.mirrored)
    }

    private fun Writer.write(panel: ComponentPanel) {
        writeShort(panel.unknown1)
        writeShort(panel.unknown2)
        writeByte(panel.unknown3)
        writeByte(panel.unknown4)
    }

    private fun Writer.write(grid: ComponentGrid) {
        writeShort(grid.unknown1)
        writeShort(grid.unknown2)
        writeByte(grid.unknown3)
        writeShort(grid.unknown4)
        writeShort(grid.unknown5)
        writeByte(grid.unknown6)
    }

    private fun Writer.write(model: ComponentModel, definition: ComponentType) {
        writeBigSmart(model.modelId)
        writeByte(model.flags)
        if (model.flags and MODEL_OFFSET_2D != 0) {
            writeShort(model.offsetX)
            writeShort(model.offsetY)
            writeShort(model.pitch)
            writeShort(model.roll)
            writeShort(model.yaw)
            writeShort(model.scale)
        } else if (model.flags and MODEL_OFFSET_3D != 0) {
            writeShort(model.offsetX)
            writeShort(model.offsetY)
            writeShort(model.offsetZ)
            writeShort(model.pitch)
            writeShort(model.roll)
            writeShort(model.yaw)
            writeShort(model.scale)
        }
        writeBigSmart(model.animation)
        if (definition.horizontalSizeMode != 0) {
            writeShort(model.viewportWidth)
        }
        if (definition.verticalSizeMode != 0) {
            writeShort(model.viewportHeight)
        }
    }

    private fun Writer.write(content: ComponentType10, version: Int) {
        writeByte(content.unknown1)
        writeByte(content.unknown2)
        writeByte(content.unknown3)
        writeByte(content.unknown4)
        writeByte(content.unknown5)
        writeInt(content.unknownColour)
        writeAlpha(content.alpha)
        writeInt(content.colour)
        write(content.graphic, version)
        write(content.text, version)
    }

    private fun Writer.write(checkbox: ComponentCheckbox, version: Int) {
        writeByte(checkbox.unknown1)
        writeByte(checkbox.unknown2)
        writeByte(checkbox.unknown3)
        writeByte(checkbox.unknown4)
        writeByte(checkbox.unknown5)
        writeInt(checkbox.colour)
        write(checkbox.graphic, version)
        write(checkbox.text, version)
    }

    private fun Writer.write(input: ComponentInput, version: Int) {
        writeByte(input.unknown1)
        writeByte(input.unknown2)
        writeByte(input.unknown3)
        writeShort(input.unknown4)
        if (version >= 9) {
            writeInt(input.unknownColour)
            writeByte(input.unknown5)
        } else if (version >= 7) {
            writeByte(input.unknown5)
        }
        writeAlpha(input.alpha)
        writeInt(input.colour)
        write(input.graphic, version)
        write(input.text, version)
        write(input.graphics, version)
    }

    private fun Writer.write(dropdown: ComponentDropdown, version: Int) {
        writeByte(dropdown.unknown1)
        writeByte(dropdown.unknown2)
        writeByte(dropdown.unknown3)
        writeByte(dropdown.unknown4)
        if (version > 8) {
            writeByte(dropdown.unknown5)
        }
        writeByte(dropdown.unknown6)
        writeByte(dropdown.unknown7)
        writeShort(dropdown.entryNames.size)
        for (name in dropdown.entryNames) {
            writeString(name)
        }
        writeShort(dropdown.entryValueCount)
        val values = dropdown.entryValues ?: return
        for (value in values) {
            writeInt(value)
        }
        writeShort(dropdown.entryOrder.size)
        for (entry in dropdown.entryOrder) {
            writeShort(entry)
        }
        if (version > 8) {
            writeInt(dropdown.unknownColour1)
            writeInt(dropdown.unknownColour2)
        }
        writeAlpha(dropdown.alpha)
        writeInt(dropdown.colour)
        for (graphic in dropdown.graphics) {
            write(graphic, version)
        }
        write(dropdown.text, version)
        write(dropdown.stateGraphics, version)
    }

    private fun Writer.write(graphic: ComponentGraphic, version: Int) {
        writeInt(graphic.graphicId)
        writeShort(graphic.angle)
        writeByte(graphic.tiling)
        writeAlpha(graphic.alpha)
        writeByte(graphic.outline)
        writeInt(graphic.outlineColour)
        writeByte(graphic.flipVertical)
        writeByte(graphic.flipHorizontal)
        writeInt(graphic.colour)
        if (version > 2) {
            writeByte(graphic.unknownFlag)
        }
        if (version > 5) {
            writeInt(graphic.unknownColour)
        }
    }

    private fun Writer.write(text: ComponentText, version: Int) {
        writeBigSmart(text.fontId)
        if (version > 1) {
            writeByte(text.fontStyle)
        }
        writeString(text.text)
        writeByte(text.lineHeight)
        writeByte(text.horizontalAlignment)
        writeByte(text.verticalAlignment)
        writeByte(text.shadowed)
        writeInt(text.colour)
        writeAlpha(text.alpha)
        if (version >= 0) {
            writeByte(text.unknownStyle)
        }
    }

    private fun Writer.write(set: ComponentGraphicSet, version: Int) {
        writeByte(set.unknown1)
        writeByte(set.unknown2)
        writeByte(set.unknown3)
        writeByte(set.unknown4)
        for (graphic in set.graphics) {
            write(graphic, version)
        }
    }

    private fun Writer.writeKeys(definition: ComponentType) {
        for ((slot, key) in definition.keys ?: emptyMap()) {
            val modifier = if (key.modifier == -1) NULL_MODIFIER else key.modifier
            writeByte(((slot + 1) shl 4) or (modifier shr 8))
            writeByte(modifier and 0xff)
            writeByte(key.code)
            writeByte(key.unknown)
        }
        writeByte(0)
    }

    private fun Writer.writeParameters(definition: ComponentType) {
        val params = definition.params ?: emptyMap()
        val integers = params.filterValues { it is Int }
        val strings = params.filterValues { it is String }
        writeByte(integers.size)
        for ((key, value) in integers) {
            writeMedium(key)
            writeInt(value as Int)
        }
        writeByte(strings.size)
        for ((key, value) in strings) {
            val version = definition.paramStringVersions?.get(key) ?: 0
            writeByte(version)
            if (version == 0) {
                writeString(value as String)
            }
        }
    }

    private fun Writer.writeHooks(definition: ComponentType, version: Int) {
        val hooks = definition.hooks
        for (slot in HOOK_SLOTS) {
            writeHook(hooks?.get(slot))
        }
        if (version >= 0) {
            writeHook(hooks?.get(HOOK_SLOT_VERSIONED))
        }
        for (slot in HOOK_SLOTS_TAIL) {
            writeHook(hooks?.get(slot))
        }
        if (version > 5) {
            for (slot in HOOK_SLOTS_V6) {
                writeHook(hooks?.get(slot))
            }
        }
        if (version > 7) {
            writeHook(hooks?.get(HOOK_SLOT_V8))
        }
    }

    private fun Writer.writeHook(hook: ComponentHook?) {
        if (hook == null) {
            writeByte(0)
            return
        }
        writeByte(hook.arguments.size + 1)
        writeByte(HOOK_ARGUMENT_INT)
        writeInt(hook.scriptId)
        for (argument in hook.arguments) {
            if (argument is String) {
                writeByte(HOOK_ARGUMENT_STRING)
                writeString(argument)
            } else {
                writeByte(HOOK_ARGUMENT_INT)
                writeInt(argument as Int)
            }
        }
    }

    private fun Writer.writeTriggers(triggers: List<Int>?) {
        if (triggers == null) {
            writeByte(0)
            return
        }
        writeByte(triggers.size)
        for (trigger in triggers) {
            writeInt(trigger)
        }
    }

    private fun Writer.writeAlpha(alpha: Int) = writeByte(alpha.inv() and 0xff)

    private fun Int.orNull() = if (this == -1) NULL_ID else this
}
