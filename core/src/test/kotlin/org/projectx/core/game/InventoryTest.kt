package org.projectx.core.game

import kotlinx.serialization.json.Json
import org.projectx.core.attribute.Attributes
import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `attributes preserve value types across a round trip`() {
        val attrs = Attributes()
        attrs["charges"] = 5000
        attrs["bind"] = 123456789012L
        attrs["accuracy"] = 1.5
        attrs["degraded"] = true
        attrs["owner"] = "trent"

        val decoded = json.decodeFromString(Attributes.serializer(), json.encodeToString(Attributes.serializer(), attrs))

        assertEquals(5000, decoded.getInt("charges"))
        assertEquals(123456789012L, decoded.getLong("bind"))
        assertEquals(1.5, decoded.getDouble("accuracy"))
        assertTrue(decoded.getBoolean("degraded"))
        assertEquals("trent", decoded.getString("owner"))
        assertEquals(attrs, decoded)
    }

    @Test
    fun `obj carries metadata through serialization`() {
        val obj = Obj(4151, 1, Attributes().apply { this["charges"] = 100 })
        val decoded = json.decodeFromString(Obj.serializer(), json.encodeToString(Obj.serializer(), obj))
        assertEquals(4151, decoded.id)
        assertEquals(100, decoded.attributes?.getInt("charges"))
    }

    @Test
    fun `always-stack container stacks, counts and removes`() {
        val bank = Inventory(95, 10, alwaysStack = true)
        assertTrue(bank.add(Obj(995, 1000)))
        assertTrue(bank.add(Obj(995, 500)))
        assertEquals(1500, bank.count(995))
        assertEquals(9, bank.freeSlots())
        assertTrue(bank.remove(995, 500))
        assertEquals(1000, bank.count(995))
        assertFalse(bank.remove(995, 5000))
    }

    @Test
    fun `inventory serializes sparsely and round trips slot positions`() {
        val inv = Inventory(93, 800, alwaysStack = true)
        inv.set(0, Obj(995, 100))
        inv.set(517, Obj(4151, 1))

        val encoded = json.encodeToString(InventorySerializer, inv)
        assertTrue(encoded.length < 300, "800-slot inventory with 2 items should not serialize every empty slot")

        val decoded = json.decodeFromString(InventorySerializer, encoded)
        assertEquals(800, decoded.capacity)
        assertEquals(995, decoded[0]?.id)
        assertEquals(100, decoded[0]?.amount)
        assertEquals(4151, decoded[517]?.id)
        assertNull(decoded[1])
    }

    @Test
    fun `metadata items never merge into a stack`() {
        val inv = Inventory(93, 5, alwaysStack = true)
        inv.add(Obj(1, 1, Attributes().apply { this["charges"] = 10 }))
        inv.add(Obj(1, 1, Attributes().apply { this["charges"] = 20 }))
        assertEquals(3, inv.freeSlots())
    }

    @Test
    fun `attributes map to obj-vars only for var_object gameval names`() {
        // The non-var_object key falls through to Cache.objectVarbit, which needs the loaded cache;
        // skip on machines without it (matches the cache-integration test convention).
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no NXT cache on this machine — objVars() needs Cache.objectVarbit")
        CacheFixture.init(dir!!)

        val obj = Obj(4151, 1, Attributes().apply {
            this["objvar_1"] = 42
            this["server_only"] = 99
        })
        val vars = obj.objVars()
        assertEquals(1, vars.size)
        assertEquals(1, vars[0].varId)
        assertEquals(42, vars[0].value)
    }

    @Test
    fun `dirty-slot snapshot carries obj-vars, empty slots are objId -1`() {
        val inv = Inventory(93, 28)
        inv.set(3, Obj(4151, 1, Attributes().apply { this["objvar_2"] = 7 }))
        val slots = inv.snapshotSlots(listOf(0, 3))
        assertEquals(2, slots.size)
        assertTrue(slots[0].empty)
        assertEquals(4151, slots[1].objId)
        assertEquals(7, slots[1].objVars.single().value)
    }
}
