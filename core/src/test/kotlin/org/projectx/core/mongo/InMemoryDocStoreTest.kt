package org.projectx.core.mongo

import kotlinx.coroutines.runBlocking
import org.projectx.core.model.Account
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class InMemoryDocStoreTest {
    private fun accounts() = InMemoryDocStore().collection<Account>("accounts")

    @Test
    fun `insert then findOne by field`() = runBlocking {
        val c = accounts()
        c.insert(Account(username = "trent", email = "t@x.com", displayName = "Trent"))
        assertEquals("Trent", c.findOne("username", "trent")?.displayName)
        assertEquals("trent", c.findOne("email", "t@x.com")?.username)
        assertNull(c.findOne("username", "nobody"))
    }

    @Test
    fun `upsert replaces matching document`() = runBlocking {
        val c = accounts()
        c.upsert("username", "trent", Account(username = "trent", displayName = "Old"))
        c.upsert("username", "trent", Account(username = "trent", displayName = "New"))
        assertEquals("New", c.findOne("username", "trent")?.displayName)
        assertEquals(1L, c.count("username", "trent"))
    }

    @Test
    fun `findOne returns an isolated fresh copy so live mutations never leak (relog reset)`() = runBlocking {
        val c = accounts()
        val stored = Account(username = "trent", displayName = "Trent")
        c.upsert("username", "trent", stored)

        val a = c.findOne("username", "trent")!!
        val b = c.findOne("username", "trent")!!
        assertNotSame(stored, a)
        assertNotSame(a, b)

        a.displayName = "MutatedRead"
        assertEquals("Trent", c.findOne("username", "trent")?.displayName)

        stored.displayName = "MutatedAfterWrite"
        assertEquals("Trent", c.findOne("username", "trent")?.displayName)
    }

    @Test
    fun `findIn and findAnyOf and delete`() = runBlocking {
        val c = accounts()
        c.insert(Account(username = "a", displayName = "Alpha"))
        c.insert(Account(username = "b", displayName = "Bravo"))
        c.insert(Account(username = "c", displayName = "Charlie"))

        assertEquals(setOf("a", "b"), c.findIn("username", listOf("a", "b")).map { it.username }.toSet())

        val anyOf = c.findAnyOf("username" to "a", "displayName" to "Bravo")
        assertEquals(setOf("a", "b"), anyOf.map { it.username }.toSet())

        c.delete("username", "a")
        assertNull(c.findOne("username", "a"))
        assertEquals(2, c.findIn("username", listOf("a", "b", "c")).size)
    }
}
