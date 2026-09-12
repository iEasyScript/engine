package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapModel
import com.projectx.script.impl.trent.dungeoneering.map.RoomClass
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A banned cell is painted LIKELY_BONUS so the frontier picker routes around it. The boss cell used to be
 * added as an objective before that filter ran, so banning it — which is what repeated deaths in it do —
 * left the bot walking back into the same fight forever.
 */
class FrontierCandidateTest {

    private fun modelWithBoss(bossClass: RoomClass = RoomClass.UNKNOWN) = DungeonMapModel().apply {
        cell(0, 0).apply { start = true; occupied = true }
        cell(1, 0).apply { boss = true; occupied = true; roomClass = bossClass }
    }

    @Test
    fun `the boss room is an objective while it has not been given up on`() {
        assertTrue(CriticalityAnalyzer.candidateReasons(modelWithBoss()).contains("boss"))
    }

    @Test
    fun `a boss room banned after repeated deaths stops being an objective`() {
        val reasons = CriticalityAnalyzer.candidateReasons(modelWithBoss(RoomClass.LIKELY_BONUS))
        assertFalse(reasons.contains("boss"))
    }

    @Test
    fun `an ordinary banned room stops being an explore objective`() {
        val model = DungeonMapModel().apply {
            cell(0, 0).apply { start = true; occupied = true }
            cell(1, 0).apply { unknownRoom = true; occupied = true }
        }
        assertTrue(CriticalityAnalyzer.candidateReasons(model).contains("explore"))

        model.cell(1, 0).roomClass = RoomClass.LIKELY_BONUS
        assertFalse(CriticalityAnalyzer.candidateReasons(model).contains("explore"))
    }
}
