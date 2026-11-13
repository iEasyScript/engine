package com.projectx.webwalker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebLinkPermissionsTest {

    @Test
    fun `the planner's newer item keys are ones this engine can answer`() {
        val keys = WebLinkPermissions.itemFlags
        for (key in listOf(
            "hasSpiritTreeReRooter", "hasQuestCape", "hasLeelasFavour", "hasLeelasFavourEquiped", "hasPortableObelisk",
            "hasDA4Equiped", "hasJujuTeleportSpiritbag", "hasBigBookOPiracy", "hasNaturesSentinelTeleport",
            "hasRimmingtonTablet", "hasAnachroniaTablet", "hasTaverleyTabletModify", "hasMenaphosTabletModify",
            "hasDramenStaff", "hasMasterFarmerHabitat", "hasScepterOfTheGods", "hasPontifexRing",
        )) {
            assertTrue(key in keys, key)
        }
    }

    @Test
    fun `teleports that open a menu are left out under attack, and direct ones are not`() {
        val menus = WebLinkPermissions.menuFlags
        for (key in listOf("hasSpiritTreeReRooter", "hasQuestCape", "hasLeelasFavour", "hasBigBookOPiracy", "hasYanilleTabletModify")) {
            assertTrue(key in menus, key)
        }
        for (key in listOf("hasLeelasFavourEquiped", "hasDA4Equiped", "hasPortableObelisk", "hasYanilleTablet")) {
            assertFalse(key in menus, key)
        }
    }

    @Test
    fun `quest progress keys are quest flags`() {
        for (key in listOf("questUndergroundPass", "TreeGnomeVillageProgress", "MonksFriendQuestProgress", "questDODCoinRealm")) {
            assertTrue(key in WebLinkPermissions.questFlags, key)
        }
    }
}
