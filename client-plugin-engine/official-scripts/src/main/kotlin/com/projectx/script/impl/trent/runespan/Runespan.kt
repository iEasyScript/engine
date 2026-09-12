package com.projectx.script.impl.trent.runespan

import org.projectx.core.game.skill.Skill
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*

@ScriptDescription(
    name = "Runespan",
    version = "1.0.0",
    author = "Trent",
    description = "AIO Runespan"
)
class Runespan: Script() {
    override suspend fun loop() {
        if (inventory.count(ESSENCE) < 8) {
            if (interactClosestReachableNPC("Collect")) {
                println("Ran out of essence... Grabbing more...")
                waitThenDelayUntil(2500, 20000) { inventory.count(ESSENCE) >= 8 }
            }
            return
        }

        val node = getBestNearbyNode()

        if (node == null) {
            delay(2000)
            return
        }

        if (when(node) {
            is NPC -> node.interact("Siphon")
            is SceneObject -> node.interact("Siphon")
            else -> false
        })
            waitThenDelayWhile(2500) { localPlayer.isAniMoving }
    }

    private fun getBestNearbyNode(): Any? {
        val sorted = NodeInfo.bestNodesForLevel(true, getCurrentLevel(Skill.RUNECRAFTING))

        for (node in sorted) {
            if (node.npc)
                findClosestReachableNPC(5) { it.id == node.id && !it.isAnimating }?.let { return it }
            else
                findClosestReachableObject(5) { it.id == node.id }?.let { return it }
        }
        return null
    }
}

enum class NodeInfo(
    val members: Boolean,
    val npc: Boolean,
    var id: Int,
    var levelRequired: Int,
    val avgXpDrop: Double,
    val runeId: IntArray
) {
    CYCLONE(false, false, 70455, 1, 19.0, intArrayOf(AIR)),
    MIND_STORM(false, false, 70456, 1, 20.0, intArrayOf(MIND)),
    WATER_POOL(false, false, 70457, 5, 25.3, intArrayOf(WATER)),
    ROCK_FRAGMENT(false, false, 70458, 9, 28.6, intArrayOf(EARTH)),
    FIRE_BALL(false, false, 70459, 14, 34.8, intArrayOf(FIRE)),
    VINE(false, false, 70460, 17, 32.3, intArrayOf(WATER, EARTH)),
    FLESHLY_GROWTH(false, false, 70461, 20, 46.2, intArrayOf(BODY)),
    FIRE_STORM(false, false, 70462, 27, 32.25, intArrayOf(AIR, FIRE)),
    CHAOTIC_CLOUD(true, false, 70463, 35, 61.6, intArrayOf(CHAOS)),
    NEBULA(true, false, 70464, 40, 74.7, intArrayOf(COSMIC, ASTRAL)),
    SHIFTER(true, false, 70465, 44, 86.8, intArrayOf(NATURE)),
    JUMPER(true, false, 70466, 54, 107.8, intArrayOf(LAW)),
    SKULLS(true, false, 70467, 65, 120.0, intArrayOf(DEATH)),
    BLOOD_POOL(true, false, 70468, 77, 146.3, intArrayOf(BLOOD)),
    BLOODY_SKULLS(true, false, 70469, 83, 159.75, intArrayOf(DEATH, BLOOD)),
    LIVING_SOUL(true, false, 70470, 90, 213.0, intArrayOf(SOUL)),
    UNDEAD_SOUL(true, false, 70471, 95, 199.8, intArrayOf(DEATH, SOUL)),

    RUNESPHERE_AIR(true, true, 15449, 1, 1000.8, intArrayOf(AIR)),
    RUNESPHERE_MIND(true, true, 15448, 8, 1000.8, intArrayOf(MIND)),
    RUNESPHERE_WATER(true, true, 15447, 15, 1000.8, intArrayOf(WATER)),
    RUNESPHERE_EARTH(true, true, 15446, 22, 1000.8, intArrayOf(EARTH)),
    RUNESPHERE_FIRE(true, true, 15445, 29, 1000.8, intArrayOf(FIRE)),
    RUNESPHERE_BODY(true, true, 15444, 36, 1000.8, intArrayOf(BODY)),
    RUNESPHERE_COSMIC(true, true, 15443, 42, 1000.8, intArrayOf(COSMIC)),
    RUNESPHERE_CHAOS(true, true, 15442, 50, 1000.8, intArrayOf(CHAOS)),
    RUNESPHERE_ASTRAL(true, true, 15441, 57, 1000.8, intArrayOf(ASTRAL)),
    RUNESPHERE_NATURE(true, true, 15440, 64, 1000.8, intArrayOf(NATURE)),
    RUNESPHERE_LAW(true, true, 15439, 71, 1000.8, intArrayOf(LAW)),
    RUNESPHERE_DEATH(true, true, 15438, 78, 1000.8, intArrayOf(DEATH)),
    RUNESPHERE_BLOOD(true, true, 15437, 85, 1000.8, intArrayOf(BLOOD)),
    RUNESPHERE_SOUL(true, true, 15436, 92, 1000.8, intArrayOf(SOUL)),

    AIR_ESSLING(false, true, 15403, 1, 9.5, intArrayOf(AIR)),
    MIND_ESSLING(false, true, 15404, 1, 10.0, intArrayOf(MIND)),
    WATER_ESSLING(false, true, 15405, 5, 12.6, intArrayOf(WATER)),
    EARTH_ESSLING(false, true, 15406, 9, 14.3, intArrayOf(EARTH)),
    FIRE_ESSLING(false, true, 15407, 14, 17.4, intArrayOf(FIRE)),
    BODY_ESSHOUND(false, true, 15408, 20, 23.1, intArrayOf(BODY)),
    COSMIC_ESSHOUND(true, true, 15409, 27, 26.6, intArrayOf(COSMIC)),
    CHOAS_ESSHOUND(true, true, 15410, 35, 30.8, intArrayOf(CHAOS)),
    ASTRAL_ESSHOUND(true, true, 15411, 40, 35.7, intArrayOf(ASTRAL)),
    NATURE_ESSHOUND(true, true, 15412, 44, 43.4, intArrayOf(NATURE)),
    LAW_ESSHOUND(true, true, 15413, 54, 53.9, intArrayOf(LAW)),
    DEATH_ESSWRAITH(true, true, 15414, 65, 60.0, intArrayOf(DEATH)),
    BLOOD_ESSWRAITH(true, true, 15415, 77, 73.1, intArrayOf(BLOOD)),
    SOUL_ESSWRAITH(true, true, 15416, 90, 106.5, intArrayOf(SOUL));

    companion object {
        private val MAP: Map<Int, NodeInfo> = entries.associateBy { it.id }

        fun forId(objectId: Int): NodeInfo? = MAP[objectId]

        fun bestNodesForLevel(members: Boolean, level: Int): List<NodeInfo> {
            return entries
                .filter { !(!members && it.members) && it.levelRequired <= level }
                .sortedByDescending { it.avgXpDrop }
        }
    }
}

private const val ESSENCE = 24227
private const val AIR = 24215
private const val EARTH = 24216
private const val WATER = 24214
private const val FIRE = 24213
private const val MIND = 24217
private const val BODY = 24218
private const val CHAOS = 24221
private const val NATURE = 24220
private const val COSMIC = 24223
private const val ASTRAL = 24224
private const val LAW = 24222
private const val BLOOD = 24225
private const val DEATH = 24219
private const val SOUL = 24226