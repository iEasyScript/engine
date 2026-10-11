package com.projectx.game.hooks.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyPressesTest {
    private var now = 0L
    private val presses = KeyPresses { now }

    @Test
    fun `a held key counts once`() {
        assertTrue(presses.pressed())
        repeat(50) {
            now += 33_000_000L
            assertFalse(presses.pressed())
        }
    }

    @Test
    fun `a key counts again once it is released`() {
        assertTrue(presses.pressed())
        presses.released()
        now += 1_000_000L
        assertTrue(presses.pressed())
    }

    @Test
    fun `a key whose release was lost counts again after a pause`() {
        assertTrue(presses.pressed())
        now += KeyPresses.LOST_RELEASE_NANOS
        assertTrue(presses.pressed())
    }
}
