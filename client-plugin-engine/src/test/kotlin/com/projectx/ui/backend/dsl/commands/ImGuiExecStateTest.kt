package com.projectx.ui.backend.dsl.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ImGui pairs its scopes two ways: Begin()/BeginChild() must always be ended whatever they return,
 * while tables, tab bars, menus, combos, popups and tree nodes are only ended when they opened. A
 * replay that ends a closed child the second way leaves the child open, and every window after it
 * nests inside it ("Missing EndChild()", "Must call EndChild() and not End()").
 */
class ImGuiExecStateTest {

    @Test
    fun `a child that reports closed is still ended and its contents are skipped`() {
        var ends = 0
        ImGuiExecState.beginAlwaysEnded(open = false)
        assertTrue(ImGuiExecState.isSkipping())
        ImGuiExecState.end { ends++ }
        assertEquals(1, ends)
        assertFalse(ImGuiExecState.isSkipping())
    }

    @Test
    fun `a table that did not open is not ended`() {
        var ends = 0
        ImGuiExecState.begin(open = false)
        ImGuiExecState.end { ends++ }
        assertEquals(0, ends)
        assertFalse(ImGuiExecState.isSkipping())
    }

    @Test
    fun `scopes nested inside a closed child are never begun, so only the child is ended`() {
        val ended = mutableListOf<String>()
        ImGuiExecState.beginAlwaysEnded(open = true)
        ImGuiExecState.beginAlwaysEnded(open = false)
        ImGuiExecState.beginSkipped()
        ImGuiExecState.end { ended += "nested" }
        ImGuiExecState.end { ended += "closed child" }
        assertFalse(ImGuiExecState.isSkipping())
        ImGuiExecState.end { ended += "window" }
        assertEquals(listOf("closed child", "window"), ended)
    }
}
