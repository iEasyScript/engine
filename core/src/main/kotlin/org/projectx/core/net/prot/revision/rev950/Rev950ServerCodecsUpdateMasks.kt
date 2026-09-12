package org.projectx.core.net.prot.revision.rev950

import org.projectx.core.net.prot.update.NpcUpdateMaskEncoder
import org.projectx.core.net.prot.update.PlayerUpdateMaskEncoder
import org.projectx.core.net.prot.update.UpdateMask
import world.gregs.voidps.buffer.write.BufferWriter

private const val HEADBAR_REMOVE = 0x7FFF
private const val ANIMATION_LAYERS = 4
private const val SMART_MAX = 0x7FFD

internal fun registerRev950ServerCodecsUpdateMasks() {
    registerPlayerMaskEncoders()
    registerNpcMaskEncoders()
}

private fun rawPlayer(vararg keys: Rev950PlayerUpdateMaskKey) {
    for (key in keys) PlayerUpdateMaskEncoder.register(key) { writeBytes((it as UpdateMask.Raw).bytes) }
}

private fun rawNpc(vararg keys: Rev950NpcUpdateMaskKey) {
    for (key in keys) NpcUpdateMaskEncoder.register(key) { writeBytes((it as UpdateMask.Raw).bytes) }
}

private fun registerPlayerMaskEncoders() {
    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.APPEARANCE) { mask ->
        val payload = (mask as UpdateMask.Appearance).data
        writeByteAdd(payload.size)
        for (b in payload) writeByteAdd(b.toInt() and 0xFF)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.FORCED_MOVEMENT) { mask ->
        val m = mask as UpdateMask.ForcedMovement
        writeByteSubtract(m.srcDx)
        writeByte(m.srcDz)
        writeByteInverse(m.dstDx)
        writeByteAdd(m.dstDz)
        writeByteInverse(m.delta3)
        writeByteSubtract(m.delta4)
        writeShortLittle(m.startTime)
        writeShort(m.endTime)
        writeShortLittle(m.animationId)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.OVERHEAD_OPACITY) { mask ->
        writeByteInverse((mask as UpdateMask.OverheadOpacity).opacity)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.OVERHEAD_DISPLAY_BOOL) { mask ->
        writeByteSubtract(if ((mask as UpdateMask.BooleanFlag).value) 1 else 0)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.FACE_DIRECTION) { mask ->
        writeShort((mask as UpdateMask.FaceDirection).angle)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.ANIMATION) { mask ->
        encodeAnimation(mask as UpdateMask.Animation)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.FACE_ENTITY) { mask ->
        val fe = mask as UpdateMask.FaceEntity
        writeShortLittle(fe.index)
        writeByte(fe.kind)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.CHAT_TEXT) { mask ->
        writeString((mask as UpdateMask.ChatText).message)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.OVERHEAD_CHAT) { mask ->
        val chat = mask as UpdateMask.ChatText
        writeString(chat.message)
        writeByte(chat.effects)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.POSITION_COLOR) { mask ->
        val c = mask as UpdateMask.PositionColor
        writeByte(c.r)
        writeByteSubtract(c.g)
        writeByte(c.b)
        writeByteSubtract(c.brightness)
        writeShortAddLittle(c.startCycle)
        writeShort(c.endCycle)
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.HITMARKS) { mask ->
        encodeHitsAndHeadbars(
            mask = mask as UpdateMask.HitMarksAndHeadbars,
            hitCount = { writeByteAdd(it) },
            damage = { writeSmart(it.coerceIn(0, SMART_MAX)) },
            barCount = { writeByteInverse(it) },
            fromFill = { writeByteInverse(it) },
            toFill = { writeByteSubtract(it) },
            secondFromFill = { writeByteAdd(it) },
            secondToFill = { writeByteAdd(it) },
        )
    }

    PlayerUpdateMaskEncoder.register(Rev950PlayerUpdateMaskKey.HITMARKS_2) { mask ->
        encodeHitsAndHeadbars(
            mask = mask as UpdateMask.HitMarksAndHeadbars,
            hitCount = { writeByteInverse(it) },
            damage = { writeIntInverseMiddle(it.coerceAtLeast(0)) },
            barCount = { writeByteAdd(it) },
            fromFill = { writeByteAdd(it) },
            toFill = { writeByteAdd(it) },
            secondFromFill = { writeByte(it) },
            secondToFill = { writeByte(it) },
        )
    }

    rawPlayer(
        Rev950PlayerUpdateMaskKey.MODEL_TRANSFORM_LIST,
        Rev950PlayerUpdateMaskKey.HEAD_ICON,
        Rev950PlayerUpdateMaskKey.CHAT_TEXT_PRIVATE,
        Rev950PlayerUpdateMaskKey.SPOT_ANIM_LIST_BIT26,
        Rev950PlayerUpdateMaskKey.ENTITY_VAR_BIT19,
        Rev950PlayerUpdateMaskKey.ENTITY_VAR_BIT17,
        Rev950PlayerUpdateMaskKey.UNK_BIT2,
        Rev950PlayerUpdateMaskKey.UNK_BIT8,
        Rev950PlayerUpdateMaskKey.UNK_BIT12,
        Rev950PlayerUpdateMaskKey.UNK_BIT13,
        Rev950PlayerUpdateMaskKey.UNK_BIT20,
        Rev950PlayerUpdateMaskKey.UNK_BIT24,
    )
}

private fun registerNpcMaskEncoders() {
    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.FORCED_MOVEMENT) { mask ->
        val m = mask as UpdateMask.ForcedMovement
        writeByteInverse(m.srcDx)
        writeByteInverse(m.srcDz)
        writeByte(m.dstDx)
        writeByte(m.dstDz)
        writeByteInverse(m.delta3)
        writeByte(m.delta4)
        writeShortLittle(m.startTime)
        writeShortLittle(m.endTime)
        writeShortLittle(m.animationId)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.VISIBILITY_FLAG) { mask ->
        writeByte(if ((mask as UpdateMask.VisibilityFlag).visible) 1 else 0)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.TRANSIENT_BOOL) { mask ->
        writeByteAdd(if ((mask as UpdateMask.BooleanFlag).value) 1 else 0)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.MODEL_OVERRIDE_ID) { mask ->
        val id = when (mask) {
            is UpdateMask.ModelOverride -> mask.modelId
            is UpdateMask.ModelOverrideId -> mask.id
            else -> error("Unexpected mask payload for MODEL_OVERRIDE_ID: ${mask::class.simpleName}")
        }
        writeShortLittle(id)
    }

    // The kind byte sits BETWEEN the two index bytes, which stay big-endian across them.
    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.FACE_ENTITY) { mask ->
        val fe = mask as UpdateMask.FaceEntity
        writeByte(fe.index shr 8)
        writeByte(fe.kind)
        writeByte(fe.index)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.TRACKED_FACE_LOCK) { mask ->
        writeShortLittle((mask as UpdateMask.FaceEntity).index)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.COMBAT_LEVEL_OVERRIDE_RGB) { mask ->
        val c = mask as UpdateMask.PositionColor
        writeByteAdd(c.r)
        writeByteAdd(c.g)
        writeByte(c.b)
        writeByte(c.brightness)
        writeShort(c.startCycle)
        writeShort(c.endCycle)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.NAME_OVERRIDE) { mask ->
        writeString((mask as UpdateMask.NameOverride).name)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.CLIENT_SCRIPT_OVERRIDE) { mask ->
        writeBytes((mask as UpdateMask.ClientScriptOverride).payload)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.NPC_STATS) { mask ->
        val entries = (mask as UpdateMask.NpcStats).entries
        writeByte(entries.size)
        for (e in entries) {
            writeByteAdd(e.slot)
            writeIntLittle(e.current)
            writeByte(e.max shr 16)
            writeByte(e.max)
            writeByte(e.max shr 8)
        }
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS) { mask ->
        encodeHitsAndHeadbars(
            mask = mask as UpdateMask.HitMarksAndHeadbars,
            hitCount = { writeByteInverse(it) },
            damage = { writeSmart(it.coerceIn(0, SMART_MAX)) },
            barCount = { writeByte(it) },
            fromFill = { writeByteAdd(it) },
            toFill = { writeByteInverse(it) },
            secondFromFill = { writeByte(it) },
            secondToFill = { writeByteSubtract(it) },
        )
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2) { mask ->
        encodeHitsAndHeadbars(
            mask = mask as UpdateMask.HitMarksAndHeadbars,
            hitCount = { writeByteAdd(it) },
            damage = { writeIntLittle(it.coerceAtLeast(0)) },
            barCount = { writeByteSubtract(it) },
            fromFill = { writeByte(it) },
            toFill = { writeByteInverse(it) },
            secondFromFill = { writeByte(it) },
            secondToFill = { writeByteSubtract(it) },
        )
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.ANIMATION) { mask ->
        encodeAnimation(mask as UpdateMask.Animation)
    }

    NpcUpdateMaskEncoder.register(Rev950NpcUpdateMaskKey.FACE_TILE) { mask ->
        val t = mask as UpdateMask.FaceTile
        writeShort(t.x * 2 + 1)
        writeShortLittle(t.y * 2 + 1)
    }

    rawNpc(
        Rev950NpcUpdateMaskKey.CHAT_OVERHEAD,
        Rev950NpcUpdateMaskKey.STRING_OVERRIDE,
        Rev950NpcUpdateMaskKey.SPOT_ANIM_LIST_BIT16,
        Rev950NpcUpdateMaskKey.SPOT_ANIM_LIST_BIT21,
        Rev950NpcUpdateMaskKey.UNK_BIT0,
        Rev950NpcUpdateMaskKey.UNK_BIT8,
        Rev950NpcUpdateMaskKey.UNK_BIT10,
        Rev950NpcUpdateMaskKey.UNK_BIT11,
        Rev950NpcUpdateMaskKey.UNK_BIT15,
        Rev950NpcUpdateMaskKey.UNK_BIT22,
        Rev950NpcUpdateMaskKey.UNK_BIT24,
        Rev950NpcUpdateMaskKey.UNK_BIT29,
        Rev950NpcUpdateMaskKey.UNK_BIT30,
        Rev950NpcUpdateMaskKey.UNK_BIT31,
        Rev950NpcUpdateMaskKey.UNK_BIT32,
        Rev950NpcUpdateMaskKey.UNK_BIT34,
    )
}

/**
 * A hitsplat sublist then a headbar sublist. Both entity kinds and both channels share this
 * structure; only the counts, the damage width and the four fill transforms move between them,
 * so those are the parameters. A headbar whose second smart is [HEADBAR_REMOVE] ends its entry.
 */
private inline fun BufferWriter.encodeHitsAndHeadbars(
    mask: UpdateMask.HitMarksAndHeadbars,
    hitCount: (Int) -> Unit,
    damage: (Int) -> Unit,
    barCount: (Int) -> Unit,
    fromFill: (Int) -> Unit,
    toFill: (Int) -> Unit,
    secondFromFill: (Int) -> Unit,
    secondToFill: (Int) -> Unit,
) {
    hitCount(mask.hits.size)
    for (hit in mask.hits) {
        // 0x7FFE and 0x7FFF select alternate field sets in the client, so a type may not reach them.
        writeSmart(hit.type.coerceIn(0, SMART_MAX))
        damage(hit.damage)
        writeSmart(hit.delay.coerceAtLeast(0))
    }
    barCount(mask.headbars.size)
    for (bar in mask.headbars) {
        writeSmart(bar.type)
        if (bar.remove) {
            writeSmart(HEADBAR_REMOVE)
            continue
        }
        writeSmart(bar.transitionCycles)
        writeSmart(bar.delay)
        fromFill(bar.fromFill)
        if (bar.transitionCycles != 0) toFill(bar.toFill)
        writeSmart((bar.secondType + 1).coerceIn(0, SMART_MAX))
        if (bar.secondType >= 0) {
            secondFromFill(bar.secondFromFill)
            if (bar.transitionCycles != 0) secondToFill(bar.secondToFill)
        }
    }
}

/** Four animation-layer ids then a raw delay byte; unused layers are the big-smart null. */
private fun BufferWriter.encodeAnimation(mask: UpdateMask.Animation) {
    writeBigSmart(mask.animId)
    repeat(ANIMATION_LAYERS - 1) { writeBigSmart(-1) }
    writeByte(mask.speed)
}
