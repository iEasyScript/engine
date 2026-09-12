package com.projectx.puzzle.combolock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FremennikRiddleTest {

    @Test
    fun liveFishRiddleGivesFire() {
        // Read live from this account's door: the decoy "but not in the sea" must be ignored.
        assertEquals("FIRE", FremennikRiddle.answerFrom("My first is in fish, but not in the sea."))
    }

    @Test
    fun everyRiddleKeywordMapsToItsWord() {
        assertEquals("MIND", FremennikRiddle.answerFrom("My first is in mage, but not in wizard."))
        assertEquals("TREE", FremennikRiddle.answerFrom("My first is in tar, but not in star."))
        assertEquals("LIFE", FremennikRiddle.answerFrom("My first is in the well, but not in the bucket."))
        assertEquals("FIRE", FremennikRiddle.answerFrom("My first is in fish, but not in the sea."))
        assertEquals("TIME", FremennikRiddle.answerFrom("My first is in water, but not in fire."))
        assertEquals("WIND", FremennikRiddle.answerFrom("My first is in wizard, but not in mage."))
    }

    @Test
    fun onlyTheFirstSentenceDecides() {
        // Later lines mention other keywords; the marker after the first "my first is in" wins.
        val riddle = "My first is in water, but not in the tar. My second is in fish and mage."
        assertEquals("TIME", FremennikRiddle.answerFrom(riddle))
    }

    @Test
    fun unknownTextReturnsNull() {
        assertNull(FremennikRiddle.answerFrom("Peer the Seer stares at you."))
        assertNull(FremennikRiddle.answerFrom(""))
    }

    @Test
    fun matcherFallbackFindsTheAnswer() {
        val recent = "My first is in fish, but not in the sea.".lowercase()
        assertEquals("FIRE", FremennikRiddle.answerMatching { recent.contains(it) })
        assertNull(FremennikRiddle.answerMatching { false })
    }
}
