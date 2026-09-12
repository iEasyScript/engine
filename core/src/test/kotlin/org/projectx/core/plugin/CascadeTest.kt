package org.projectx.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CascadeTest {

    private sealed interface Key {
        data class IdTile(val id: Int, val tile: Int) : Key
        data class Id(val id: Int) : Key
        data class Name(val name: String) : Key
        data object Global : Key
    }

    private fun ladder(id: Int, tile: Int, name: String) =
        listOf(Key.IdTile(id, tile), Key.Id(id), Key.Name(name), Key.Global)

    @Test
    fun `most specific tier wins over less specific`() {
        val cascade = Cascade<String>()
        cascade.register(Key.IdTile(4, 100), "id+tile")
        cascade.register(Key.Id(4), "id")
        cascade.register(Key.Name("gate"), "name")
        cascade.register(Key.Global, "global")

        assertEquals(listOf("id+tile"), cascade.resolve(ladder(4, 100, "gate")))
    }

    @Test
    fun `falls through to id then name then global`() {
        val cascade = Cascade<String>()
        cascade.register(Key.Id(4), "id")
        cascade.register(Key.Name("gate"), "name")
        cascade.register(Key.Global, "global")

        assertEquals(listOf("id"), cascade.resolve(ladder(4, 999, "gate")))
        assertEquals(listOf("name"), cascade.resolve(ladder(7, 999, "gate")))
        assertEquals(listOf("global"), cascade.resolve(ladder(7, 999, "unknown")))
    }

    @Test
    fun `no match resolves null`() {
        val cascade = Cascade<String>()
        cascade.register(Key.Id(4), "id")
        assertNull(cascade.resolve(ladder(7, 999, "unknown")))
    }

    @Test
    fun `typed keys never collide across tiers`() {
        val cascade = Cascade<String>()
        cascade.register(Key.Id(4), "id-4")
        cascade.register(Key.Name("4"), "name-4")
        assertEquals(listOf("id-4"), cascade.resolve(listOf(Key.Id(4))))
        assertEquals(listOf("name-4"), cascade.resolve(listOf(Key.Name("4"))))
    }

    @Test
    fun `winning bucket keeps all handlers in registration order`() {
        val cascade = Cascade<String>()
        cascade.register(Key.Id(4), "first")
        cascade.register(Key.Id(4), "second")
        assertEquals(listOf("first", "second"), cascade.resolve(ladder(4, 1, "x")))
        assertEquals("second", cascade.resolveOne(ladder(4, 1, "x")))
    }

    @Test
    fun `query returns first non-null across the ladder`() {
        val cascade = Cascade<Int>()
        cascade.register(Key.Id(4), 0)
        cascade.register(Key.Name("gate"), 5)
        assertEquals(5, cascade.query(ladder(4, 1, "gate")) { if (it == 0) null else it })
    }

    @Test
    fun `empty cascade reports empty`() {
        val cascade = Cascade<String>()
        assertTrue(cascade.isEmpty())
        cascade.register(Key.Global, "x")
        assertEquals(1, cascade.size)
    }
}
