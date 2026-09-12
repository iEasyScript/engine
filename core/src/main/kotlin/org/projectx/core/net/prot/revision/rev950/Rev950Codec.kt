package org.projectx.core.net.prot.revision.rev950

import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.update.ActiveMaskKeys

/**
 * Registered rather than only constructed, so a capture taken under this revision can still be
 * decoded by a build whose *current* revision has moved on. A prot table is only meaningful
 * alongside the capture it framed.
 */
fun registerRevision950() = Codec.registerRevision(950, ::register950)

fun register950() = Codec.register(950) {
    registerRev950ServerCodecsVariable()
    registerRev950ServerCodecsInventory()
    registerRev950ServerCodecsInterface()
    registerRev950ServerCodecsRebuild()
    registerRev950ServerCodecsSocial()
    registerRev950ServerCodecsMisc()
    registerRev950ServerCodecsPlayerInfo()
    registerRev950ServerCodecsNpcInfo()
    registerRev950ServerCodecsZone()
    registerRev950ServerProtStubs()
    registerRev950ClientProts()
    registerRev950ClientProtStubs()
    registerRev950ServerDecoders()
    registerRev950StructuredDecoders()
    registerRev950ContentDecoders()

    registerRev950ServerCodecsUpdateMasks()

    ActiveMaskKeys.playerAppearance = Rev950PlayerUpdateMaskKey.APPEARANCE
    ActiveMaskKeys.playerChatText = Rev950PlayerUpdateMaskKey.CHAT_TEXT
    ActiveMaskKeys.playerHitmarks2 = Rev950PlayerUpdateMaskKey.HITMARKS_2
    ActiveMaskKeys.playerExpansionBits = Rev950PlayerUpdateMaskKey.EXPANSION_BITS
    ActiveMaskKeys.npcExpansionBits = Rev950NpcUpdateMaskKey.EXPANSION_BITS
    ActiveMaskKeys.playerAnimation = Rev950PlayerUpdateMaskKey.ANIMATION
    ActiveMaskKeys.npcAnimation = Rev950NpcUpdateMaskKey.ANIMATION
    ActiveMaskKeys.playerFaceDirection = Rev950PlayerUpdateMaskKey.FACE_DIRECTION
    ActiveMaskKeys.playerFaceEntity = Rev950PlayerUpdateMaskKey.FACE_ENTITY
    ActiveMaskKeys.npcFaceTile = Rev950NpcUpdateMaskKey.FACE_TILE
    ActiveMaskKeys.npcFaceEntity = Rev950NpcUpdateMaskKey.FACE_ENTITY
    ActiveMaskKeys.npcStats = Rev950NpcUpdateMaskKey.NPC_STATS
    ActiveMaskKeys.npcHitmarksAndHeadbars = Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS
    ActiveMaskKeys.npcHitmarksAndHeadbars2 = Rev950NpcUpdateMaskKey.HITMARKS_AND_HEADBARS_2
}
