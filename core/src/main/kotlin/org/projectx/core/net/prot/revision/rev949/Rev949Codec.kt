package org.projectx.core.net.prot.revision.rev949

import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.update.ActiveMaskKeys

/**
 * 949 prot: opcode+size framing from the RS3ProtFinder dump. Names are being rebuilt by
 * correlating a live 949 capture against applied-DB handler hints; the var/stat/inventory encoders
 * are re-derived byte-for-byte from the 949 decoders. Opcodes still lacking a confirmed name stay
 * `UNKNOWN_<op>` and lack an encoder.
 */
/**
 * Registered rather than only constructed, so a capture taken under this revision can still be
 * decoded by a build whose *current* revision has moved on. A prot table is only meaningful
 * alongside the capture it framed.
 */
fun registerRevision949() = Codec.registerRevision(949, ::register949)

fun register949() = Codec.register(949) {
    registerRev949ServerCodecsVariable()
    registerRev949ServerCodecsInventory()
    registerRev949ServerCodecsInterface()
    registerRev949ServerCodecsRebuild()
    registerRev949ServerCodecsSocial()
    registerRev949ServerCodecsMisc()
    registerRev949ServerCodecsPlayerInfo()
    registerRev949ServerCodecsNpcInfo()
    registerRev949ServerCodecsZone()
    registerRev949ServerProtStubs()
    registerRev949ClientProts()
    registerRev949ClientProtStubs()
    registerRev949ServerDecoders()
    registerRev949StructuredDecoders()
    registerRev949ContentDecoders()

    registerRev949ServerCodecsUpdateMasks()

    ActiveMaskKeys.playerAppearance = Rev949PlayerUpdateMaskKey.APPEARANCE
    ActiveMaskKeys.playerChatText = Rev949PlayerUpdateMaskKey.CHAT_TEXT
    ActiveMaskKeys.playerHitmarks2 = Rev949PlayerUpdateMaskKey.HITMARKS_2
    ActiveMaskKeys.playerExpansionBits = Rev949PlayerUpdateMaskKey.EXPANSION_BITS
    ActiveMaskKeys.npcExpansionBits = Rev949NpcUpdateMaskKey.EXPANSION_BITS
    ActiveMaskKeys.playerAnimation = Rev949PlayerUpdateMaskKey.ANIMATION
    ActiveMaskKeys.npcAnimation = Rev949NpcUpdateMaskKey.ANIMATION
    ActiveMaskKeys.playerFaceDirection = Rev949PlayerUpdateMaskKey.FACE_DIRECTION
    ActiveMaskKeys.playerFaceEntity = Rev949PlayerUpdateMaskKey.FACE_ENTITY
    ActiveMaskKeys.npcFaceTile = Rev949NpcUpdateMaskKey.FACE_TILE
    ActiveMaskKeys.npcFaceEntity = Rev949NpcUpdateMaskKey.FACE_ENTITY
    ActiveMaskKeys.npcStats = Rev949NpcUpdateMaskKey.NPC_STATS
    ActiveMaskKeys.npcHitmarksAndHeadbars = Rev949NpcUpdateMaskKey.HITMARKS_AND_HEADBARS
    ActiveMaskKeys.npcHitmarksAndHeadbars2 = Rev949NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2
}
