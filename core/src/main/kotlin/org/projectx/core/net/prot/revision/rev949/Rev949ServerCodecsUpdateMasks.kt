package org.projectx.core.net.prot.revision.rev949

import org.projectx.core.net.prot.update.NpcUpdateMaskEncoder
import org.projectx.core.net.prot.update.PlayerUpdateMaskEncoder
import org.projectx.core.net.prot.update.UpdateMask
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * Rev949 PLAYER_INFO / NPC_INFO extended-info mask encoders.
 *
 * Block field layouts are verified against the 949 PlayerEntity / NPCEntity ProcessExtendedInfo
 * readers; the flag bits live in [Rev949PlayerUpdateMaskKey] / [Rev949NpcUpdateMaskKey]. Scrambled
 * scalars other than APPEARANCE are emitted PLAIN — safe by length for dormant flags.
 *
 * The combat facing/animation blocks apply their RE'd ciphers (docs/949-4/.../face-flags-949-4.md):
 * player FACE_DIRECTION = writeShortAddLittle(angle); NPC FACE_ENTITY = writeShortLittle(index) LE
 * (0xFFFF stops); NPC FACE_TILE = writeShort(x*2+1)+writeShortAdd(y*2+1); ANIMATION = 4× bigSmart +
 * a delay byte (player raw g1, NPC g1_add). TRANSIENT_BOOL's mode-1 byte is emitted plain — a
 * capture would confirm whether that dormant flag needs its cipher.
 */
internal fun registerRev949ServerCodecsUpdateMasks() {
    registerPlayerMaskEncoders()
    registerNpcMaskEncoders()
}

private fun rawPlayer(vararg keys: Rev949PlayerUpdateMaskKey) {
    for (key in keys) PlayerUpdateMaskEncoder.register(key) { writeBytes((it as UpdateMask.Raw).bytes) }
}

private fun rawNpc(vararg keys: Rev949NpcUpdateMaskKey) {
    for (key in keys) NpcUpdateMaskEncoder.register(key) { writeBytes((it as UpdateMask.Raw).bytes) }
}

private fun registerPlayerMaskEncoders() {
    // 949 APPEARANCE cipher = {len mode0 plain, payload mode2 add}; 948 was {mode3 subtract, mode2}.
    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.APPEARANCE) { mask ->
        val payload = (mask as UpdateMask.Appearance).data
        writeByte(payload.size)
        for (b in payload) writeByteAdd(b.toInt() and 0xFF)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.FORCED_MOVEMENT) { mask ->
        val m = mask as UpdateMask.ForcedMovement
        writeByte(m.srcDx)
        writeByte(m.srcDz)
        writeByte(m.dstDx)
        writeByte(m.dstDz)
        writeByte(m.delta3)
        writeByte(m.delta4)
        writeShort(m.startTime)
        writeShort(m.endTime)
        writeShort(m.animationId)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.OVERHEAD_OPACITY) { mask ->
        writeByte((mask as UpdateMask.OverheadOpacity).opacity)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.OVERHEAD_DISPLAY_BOOL) { mask ->
        writeByte(if ((mask as UpdateMask.BooleanFlag).value) 1 else 0)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.FACE_DIRECTION) { mask ->
        writeShortAddLittle((mask as UpdateMask.FaceDirection).angle)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.ANIMATION) { mask ->
        encodeAnimation(mask as UpdateMask.Animation, addByteDelay = false)
    }

    // bit 0: stores the target to PlayerEntity+0x1B4 (interactionSid) via FUN_004ac200. gScrambledMedium
    // mode 1 (little-endian): value = kind<<16 | index, so wire = [idxLo, idxHi, kind]. kind 1=npc list,
    // 2=player list, 0xFF=stop; index is the RAW list index.
    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.FACE_ENTITY) { mask ->
        val fe = mask as UpdateMask.FaceEntity
        writeShortLittle(fe.index)
        writeByte(fe.kind)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.CHAT_TEXT) { mask ->
        writeString((mask as UpdateMask.ChatText).message)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.OVERHEAD_CHAT) { mask ->
        val chat = mask as UpdateMask.ChatText
        writePrefixedString(chat.message)
        writeByte(chat.effects)
    }

    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.POSITION_COLOR) { mask ->
        val c = mask as UpdateMask.PositionColor
        writeByte(c.r)
        writeByte(c.g)
        writeByte(c.b)
        writeByte(c.brightness)
        writeShort(c.startCycle)
        writeShort(c.endCycle)
    }

    // Hitsplats + health bars share the AddHitmark/AddHeadbar client sinks, but each entity's bit-N
    // decoder reads the counts/fills with its OWN scrambled modes — player and NPC are NOT identical.
    // HITMARKS_2 (bit 25) is the player's live wide channel; HITMARKS (bit 4) is the narrow legacy one.
    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.HITMARKS) { mask ->
        encodeHitMarksAndHeadbars(mask as UpdateMask.HitMarksAndHeadbars)
    }
    PlayerUpdateMaskEncoder.register(Rev949PlayerUpdateMaskKey.HITMARKS_2) { mask ->
        encodePlayerWideHitsAndHeadbars(mask as UpdateMask.HitMarksAndHeadbars)
    }

    rawPlayer(
        Rev949PlayerUpdateMaskKey.SPOT_ANIM_REMOVAL,
        Rev949PlayerUpdateMaskKey.UNK_BIT8,
        Rev949PlayerUpdateMaskKey.HEAD_ICON,
        Rev949PlayerUpdateMaskKey.CHAT_TEXT_PRIVATE,
        Rev949PlayerUpdateMaskKey.SPOT_ANIM_LIST_BIT24,
        Rev949PlayerUpdateMaskKey.SPOT_ANIM_LIST_BIT19,
        Rev949PlayerUpdateMaskKey.SPOT_ANIM_LIST_BIT22,
        Rev949PlayerUpdateMaskKey.UNK_BIT13,
        Rev949PlayerUpdateMaskKey.UNK_BIT9,
        Rev949PlayerUpdateMaskKey.UNK_BIT3,
        Rev949PlayerUpdateMaskKey.UNK_BIT27,
        Rev949PlayerUpdateMaskKey.UNK_BIT16,
    )
}

private fun registerNpcMaskEncoders() {
    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.FORCED_MOVEMENT) { mask ->
        val m = mask as UpdateMask.ForcedMovement
        writeByte(m.srcDx)
        writeByte(m.srcDz)
        writeByte(m.dstDx)
        writeByte(m.dstDz)
        writeByte(m.delta3)
        writeByte(m.delta4)
        writeShort(m.startTime)
        writeShort(m.endTime)
        writeShort(m.animationId)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.VISIBILITY_FLAG) { mask ->
        writeByte(if ((mask as UpdateMask.VisibilityFlag).visible) 1 else 0)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.TRANSIENT_BOOL) { mask ->
        writeByte(if ((mask as UpdateMask.BooleanFlag).value) 1 else 0)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.MODEL_OVERRIDE_ID) { mask ->
        val id = when (mask) {
            is UpdateMask.ModelOverride -> mask.modelId
            is UpdateMask.ModelOverrideId -> mask.id
            else -> error("Unexpected mask payload for MODEL_OVERRIDE_ID: ${mask::class.simpleName}")
        }
        writeShort(id)
    }

    // bit 7: sets NPCEntity+0x1B4 (interactionSid) via FUN_004ac200. gScrambledMedium mode 0 (table
    // @0xd66cdc) = BIG-endian: value = kind<<16 | index, so wire = [kind, idxHi, idxLo]. (The player's
    // bit-0 face is mode 1 / little-endian — different, do NOT share.) kind 1=npc, 2=player, 0xFF=stop.
    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.FACE_ENTITY) { mask ->
        val fe = mask as UpdateMask.FaceEntity
        writeByte(fe.kind)
        writeShort(fe.index)
    }

    // bit 10: the hard render-face lock (+0x10d0), plain writeShortLittle. Slides a moving NPC — not
    // wired to facing; kept for stationary stare cases.
    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.TRACKED_FACE_LOCK) { mask ->
        writeShortLittle((mask as UpdateMask.FaceEntity).index)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.COMBAT_LEVEL_OVERRIDE_RGB) { mask ->
        val c = mask as UpdateMask.PositionColor
        writeByte(c.r)
        writeByte(c.g)
        writeByte(c.b)
        writeByte(c.brightness)
        writeShort(c.startCycle)
        writeShort(c.endCycle)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.NAME_OVERRIDE) { mask ->
        writePrefixedString((mask as UpdateMask.NameOverride).name)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.CLIENT_SCRIPT_OVERRIDE) { mask ->
        writeBytes((mask as UpdateMask.ClientScriptOverride).payload)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.NPC_STATS) { mask ->
        val entries = (mask as UpdateMask.NpcStats).entries
        writeByte(entries.size)
        for (e in entries) {
            writeByte(e.slot)                 // gScrambledByte mode 0 = plain
            writeIntInverseMiddle(e.current)  // gScrambledUint mode 3
            // gScrambledMedium mode 3: wire [ (v>>8)&0xff, (v>>16)&0xff, v&0xff ]
            writeByte(e.max ushr 8)
            writeByte(e.max ushr 16)
            writeByte(e.max)
        }
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.HITMARKS_AND_HEADBARS) { mask ->
        encodeHitMarksAndHeadbars(mask as UpdateMask.HitMarksAndHeadbars)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2) { mask ->
        encodeNpcWideHitsAndHeadbars(mask as UpdateMask.HitMarksAndHeadbars)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.ANIMATION) { mask ->
        encodeAnimation(mask as UpdateMask.Animation, addByteDelay = true)
    }

    NpcUpdateMaskEncoder.register(Rev949NpcUpdateMaskKey.FACE_TILE) { mask ->
        val t = mask as UpdateMask.FaceTile
        writeShort(t.x * 2 + 1)
        writeShortAdd(t.y * 2 + 1)
    }

    rawNpc(
        Rev949NpcUpdateMaskKey.CHAT_OVERHEAD,
        Rev949NpcUpdateMaskKey.STRING_OVERRIDE,
        Rev949NpcUpdateMaskKey.SPOT_ANIM_LIST_BIT19,
        Rev949NpcUpdateMaskKey.SPOT_ANIM_LIST_BIT23,
        Rev949NpcUpdateMaskKey.UNK_BIT4,
        Rev949NpcUpdateMaskKey.UNK_BIT8,
        Rev949NpcUpdateMaskKey.UNK_BIT9,
        Rev949NpcUpdateMaskKey.UNK_BIT12,
        Rev949NpcUpdateMaskKey.UNK_BIT13,
        Rev949NpcUpdateMaskKey.UNK_BIT21,
        Rev949NpcUpdateMaskKey.UNK_BIT24,
        Rev949NpcUpdateMaskKey.UNK_BIT27,
        Rev949NpcUpdateMaskKey.UNK_BIT29,
        Rev949NpcUpdateMaskKey.UNK_BIT30,
        Rev949NpcUpdateMaskKey.UNK_BIT33,
        Rev949NpcUpdateMaskKey.UNK_BIT34,
    )
}

/**
 * HITMARKS_AND_HEADBARS block: a hitsplat sublist then a headbar sublist, byte-exact to the 949
 * client decode in `jag::NPCEntity::ProcessExtendedInfo`. Each scrambled field uses the fixed
 * transform the client reads it with (from the block's `.rodata` mode table `03 02 00 01 01 02 00`):
 * hitmark count = mode 3 (subtract), headbar count = plain, fills = mode 1 (add), second bar's
 * fills = mode 2 (inverse) / plain. Hitmarks use the short form (`v0` = type < 0x7FFE).
 */
private fun BufferWriter.encodeHitMarksAndHeadbars(mask: UpdateMask.HitMarksAndHeadbars) {
    writeByteSubtract(mask.hits.size)
    for (hit in mask.hits) {
        writeSmart(hit.type)
        writeSmart(hit.damage.coerceIn(0, 0x7FFD))
        writeSmart(hit.delay.coerceAtLeast(0))
    }
    writeByte(mask.headbars.size)
    for (bar in mask.headbars) {
        writeSmart(bar.type)
        if (bar.remove) {
            writeSmart(HEADBAR_REMOVE)
            continue
        }
        writeSmart(bar.transitionCycles)
        writeSmart(bar.delay)
        writeByteAdd(bar.fromFill)
        if (bar.transitionCycles != 0) writeByteAdd(bar.toFill)
        writeSecondHeadbarType(bar.secondType)
        if (bar.secondType >= 0) {
            writeByteInverse(bar.secondFromFill)
            if (bar.transitionCycles != 0) writeByte(bar.secondToFill)
        }
    }
}

/** 2nd-headbar-id smart (`FUN_0011dcf0`): 1-byte form, client returns `wire - 1`; -1 => 0x00. */
private fun BufferWriter.writeSecondHeadbarType(id: Int) {
    writeByte((id + 1).coerceIn(0, 0x7F))
}

/**
 * Wide (32-bit-damage) HITMARKS_AND_HEADBARS block, the live-combat channel: player bit 25 and NPC
 * bit 32. Both share the structure (hitmarks then headbars, `writeIntInverseMiddle` damage,
 * `writeByteSubtract` from-fill), but the two decoders read the counts and the remaining fills with
 * DIFFERENT scrambled modes, so the varying transforms are injected. Getting the player hitmark count
 * wrong (it reads `writeByteAdd`) makes the client interpret count 1 as 129 → giant garbage splats.
 */
private inline fun BufferWriter.encodeWideHitsAndHeadbars(
    mask: UpdateMask.HitMarksAndHeadbars,
    hitCount: (Int) -> Unit,
    barCount: (Int) -> Unit,
    toFill: (Int) -> Unit,
    secondFromFill: (Int) -> Unit,
    secondToFill: (Int) -> Unit,
) {
    hitCount(mask.hits.size)
    for (hit in mask.hits) {
        writeSmart(hit.type)
        writeIntInverseMiddle(hit.damage.coerceAtLeast(0))
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
        writeByteSubtract(bar.fromFill)
        if (bar.transitionCycles != 0) toFill(bar.toFill)
        writeSecondHeadbarType(bar.secondType)
        if (bar.secondType >= 0) {
            secondFromFill(bar.secondFromFill)
            if (bar.transitionCycles != 0) secondToFill(bar.secondToFill)
        }
    }
}

/** NPC bit 32 (mode table `00 03 02 03 00 00 02`): count plain, toFill/secondFromFill plain, secondToFill inverse. */
private fun BufferWriter.encodeNpcWideHitsAndHeadbars(mask: UpdateMask.HitMarksAndHeadbars) =
    encodeWideHitsAndHeadbars(mask, { writeByte(it) }, { writeByteInverse(it) }, { writeByte(it) }, { writeByte(it) }, { writeByteInverse(it) })

/** Player bit 25 (mode table `01 03 01 03 01 01 03`): counts + toFill + secondFromFill ADD, secondToFill SUBTRACT. */
private fun BufferWriter.encodePlayerWideHitsAndHeadbars(mask: UpdateMask.HitMarksAndHeadbars) =
    encodeWideHitsAndHeadbars(mask, { writeByteAdd(it) }, { writeByteAdd(it) }, { writeByteAdd(it) }, { writeByteAdd(it) }, { writeByteSubtract(it) })

private const val HEADBAR_REMOVE = 0x7FFF


/**
 * ANIMATION block (RequestAnimation): 4 big-smart anim-layer ids then a delay byte. Empty layers are
 * `-1` (emitted as big-smart `0x7fff`). The delay byte cipher differs by table — player raw g1
 * ([addByteDelay] false), NPC g1_add ([addByteDelay] true). We drive layer 0 and leave 1..3 empty.
 */
private fun BufferWriter.encodeAnimation(mask: UpdateMask.Animation, addByteDelay: Boolean) {
    writeBigSmart(mask.animId)
    writeBigSmart(-1)
    writeBigSmart(-1)
    writeBigSmart(-1)
    if (addByteDelay) writeByteAdd(mask.speed) else writeByte(mask.speed)
}
