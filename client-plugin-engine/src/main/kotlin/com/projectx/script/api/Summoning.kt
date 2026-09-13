package com.projectx.script.api

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.game.nxt.entity.GroundItem
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import world.gregs.voidps.gameval.Gameval
import kotlin.math.floor

enum class SpecialType {
    NONE,
    ATTACK,
    PASSIVE,
    INTERACT_INVENTORY,
    INTERACT_OBJECT,
    INTERACT_GROUNDITEM
}

enum class Familiar (val pouchId: Int, val pouchName: String = "None", val specialType: SpecialType = SpecialType.NONE){
    NONE(-1),
    SPIRIT_WOLF(12047, "Spirit wolf pouch", SpecialType.PASSIVE),
    DREADFOWL(12043, "Dreadfowl pouch", SpecialType.ATTACK),
    MEERKATS(19622, "Meerkats pouch", SpecialType.PASSIVE),
    SPIRIT_SPIDER(12059, "Spirit spider pouch", SpecialType.PASSIVE),
    THORNY_SNAIL(12019, "Thorny snail pouch", SpecialType.ATTACK),
    GRANITE_CRAB(12009, "Granite crab pouch", SpecialType.PASSIVE),
    SPIRIT_MOSQUITO(12778, "Spirit mosquito pouch", SpecialType.ATTACK),
    DESERT_WYRM(12049, "Desert wyrm pouch", SpecialType.ATTACK),
    SPIRIT_SCORPION(12055, "Spirit scorpion pouch", SpecialType.PASSIVE),
    SPIRIT_TZ_KIH(12808, "Spirit Tz-Kih pouch", SpecialType.ATTACK),
    ALBINO_RAT(12067, "Albino rat pouch", SpecialType.ATTACK),
    SPIRIT_KALPHITE(12063, "Spirit kalphite pouch", SpecialType.ATTACK),
    COMPOST_MOUND(12091, "Compost mound pouch", SpecialType.INTERACT_OBJECT),
    GIANT_CHINCHOMPA(12800, "Giant chinchompa pouch", SpecialType.PASSIVE),
    VAMPYRE_BAT(12053, "Vampyre bat pouch", SpecialType.ATTACK),
    HONEY_BADGER(12065, "Honey badger pouch", SpecialType.PASSIVE),
    BEAVER(12021, "Beaver pouch", SpecialType.INTERACT_OBJECT),
    VOID_RAVAGER(12818, "Void ravager pouch", SpecialType.PASSIVE),
    VOID_SPINNER(12780, "Void spinner pouch", SpecialType.PASSIVE),
    VOID_TORCHER(12798, "Void torcher pouch", SpecialType.PASSIVE),
    VOID_SHIFTER(12814, "Void shifter pouch", SpecialType.PASSIVE),
    BRONZE_MINOTAUR(12073, "Bronze minotaur pouch", SpecialType.ATTACK),
    BULL_ANT(12087, "Bull ant pouch", SpecialType.PASSIVE),
    MACAW(12071, "Macaw pouch", SpecialType.PASSIVE),
    EVIL_TURNIP(12051, "Evil turnip pouch", SpecialType.ATTACK),
    HELLHOUND(49411, "Binding contract (hellhound)", SpecialType.PASSIVE),
    WATERFIEND(49420, "Binding contract (waterfiend)", SpecialType.PASSIVE),
    SPIRIT_COCKATRICE(12095, "Spirit cockatrice pouch", SpecialType.ATTACK),
    SPIRIT_GUTHATRICE(12097, "Spirit guthatrice pouch", SpecialType.ATTACK),
    SPIRIT_SARATRICE(12099, "Spirit saratrice pouch", SpecialType.ATTACK),
    SPIRIT_ZAMATRICE(12101, "Spirit zamatrice pouch", SpecialType.ATTACK),
    SPIRIT_PENGATRICE(12103, "Spirit pengatrice pouch", SpecialType.ATTACK),
    SPIRIT_CORAXATRICE(12105, "Spirit coraxatrice pouch", SpecialType.ATTACK),
    SPIRIT_VULATRICE(12107, "Spirit vulatrice pouch", SpecialType.ATTACK),
    IRON_MINOTAUR(12075, "Iron minotaur pouch", SpecialType.ATTACK),
    PYRELORD(12816, "Pyrelord pouch", SpecialType.INTERACT_INVENTORY),
    MAGPIE(12041, "Magpie pouch", SpecialType.PASSIVE),
    BLOATED_LEECH(12061, "Bloated leech pouch", SpecialType.PASSIVE),
    SPIRIT_TERRORBIRD(12007, "Spirit terrorbird pouch", SpecialType.PASSIVE),
    ABYSSAL_PARASITE(12035, "Abyssal parasite pouch", SpecialType.ATTACK),
    SPIRIT_JELLY(12027, "Spirit jelly pouch", SpecialType.ATTACK),
    STEEL_MINOTAUR(12077, "Steel minotaur pouch", SpecialType.ATTACK),
    IBIS(12531, "Ibis pouch", SpecialType.PASSIVE),
    SPIRIT_KYATT(12812, "Spirit kyatt pouch", SpecialType.ATTACK),
    SPIRIT_LARUPIA(12784, "Spirit larupia pouch", SpecialType.ATTACK),
    SPIRIT_GRAAHK(12810, "Spirit graahk pouch", SpecialType.ATTACK),
    KARAMTHULHU_OVERLORD(12023, "Karamthulhu overlord pouch"),
    SMOKE_DEVIL(12085, "Smoke devil pouch", SpecialType.ATTACK),
    ABYSSAL_LURKER(12037, "Abyssal lurker pouch", SpecialType.PASSIVE),
    SPIRIT_COBRA(12015, "Spirit cobra pouch", SpecialType.INTERACT_INVENTORY),
    STRANGER_PLANT(12045, "Stranger plant pouch", SpecialType.ATTACK),
    MITHRIL_MINOTAUR(12079, "Mithril minotaur pouch", SpecialType.ATTACK),
    BARKER_TOAD(12123, "Barker toad pouch", SpecialType.ATTACK),
    WAR_TORTOISE(12031, "War tortoise pouch", SpecialType.PASSIVE),
    BUNYIP(12029, "Bunyip pouch", SpecialType.INTERACT_INVENTORY),
    FRUIT_BAT(12033, "Fruit bat pouch", SpecialType.PASSIVE),
    RAVENOUS_LOCUST(12820, "Ravenous locust pouch", SpecialType.PASSIVE),
    ARCTIC_BEAR(12057, "Arctic bear pouch", SpecialType.ATTACK),
    PHOENIX(14623, "Phoenix pouch", SpecialType.ATTACK),
    OBSIDIAN_GOLEM(12792, "Obsidian golem pouch", SpecialType.PASSIVE),
    GRANITE_LOBSTER(12069, "Granite lobster pouch", SpecialType.ATTACK),
    PRAYING_MANTIS(12011, "Praying mantis pouch", SpecialType.ATTACK),
    FORGE_REGENT(12782, "Forge regent pouch", SpecialType.ATTACK),
    ADAMANT_MINOTAUR(12081, "Adamant minotaur pouch", SpecialType.ATTACK),
    TALON_BEAST(12794, "Talon beast pouch", SpecialType.ATTACK),
    GIANT_ENT(12013, "Giant ent pouch", SpecialType.ATTACK),
    FIRE_TITAN(12802, "Fire titan pouch", SpecialType.ATTACK),
    MOSS_TITAN(12804, "Moss titan pouch", SpecialType.ATTACK),
    ICE_TITAN(12806, "Ice titan pouch", SpecialType.ATTACK),
    HYDRA(12025, "Hydra pouch", SpecialType.INTERACT_OBJECT),
    SPIRIT_DAGANNOTH(12017, "Spirit dagannoth pouch", SpecialType.ATTACK),
    LAVA_TITAN(12788, "Lava titan pouch", SpecialType.ATTACK),
    ABYSSAL_DEMON(49402, "Binding contract (abyssal demon)", SpecialType.ATTACK),
    SWAMP_TITAN(12776, "Swamp titan pouch", SpecialType.ATTACK),
    RUNE_MINOTAUR(12083, "Rune minotaur pouch", SpecialType.ATTACK),
    UNICORN_STALLION(12039, "Unicorn stallion pouch", SpecialType.PASSIVE),
    GEYSER_TITAN(12786, "Geyser titan pouch", SpecialType.ATTACK),
    WOLPERTINGER(12089, "Wolpertinger pouch", SpecialType.PASSIVE),
    ABYSSAL_TITAN(12796, "Abyssal titan pouch", SpecialType.PASSIVE),
    IRON_TITAN(12822, "Iron titan pouch", SpecialType.ATTACK),
    PACK_YAK(12093, "Pack yak pouch", SpecialType.INTERACT_INVENTORY),
    STEEL_TITAN(12790, "Steel titan pouch", SpecialType.ATTACK),
    BLOOD_REAVER(49405, "Binding contract (blood reaver)", SpecialType.PASSIVE),
    GARGOYLE(49408, "Binding contract (gargoyle)", SpecialType.PASSIVE),
    KALGERION_DEMON(49414, "Binding contract (kal'gerion demon)", SpecialType.PASSIVE),
    RIPPER_DEMON(49419, "Ripper Demon scroll (Death From Above)", SpecialType.ATTACK),
    PAK_MAMMOTH(36060, "Pak mammoth pouch", SpecialType.PASSIVE);

    companion object {
        @JvmStatic
        fun getName(id: Int): String = entries.firstOrNull { it.pouchId == id }?.pouchName ?: NONE.pouchName
        @JvmStatic
        fun getId(name: String): Int = entries.firstOrNull { it.name == name }?.pouchId ?: NONE.pouchId
        @JvmStatic
        fun getType(name: String): SpecialType = entries.firstOrNull { it.name == name }?.specialType ?: SpecialType.NONE
        @JvmStatic
        fun getType(id: Int): SpecialType = entries.firstOrNull { it.pouchId == id }?.specialType ?: SpecialType.NONE
    }
}

val bobPlayerInventory
    get() = Bootstrap.client.inventoryManager.getWithInterface(93, 671, 32)

val bobStorage
    get() = Bootstrap.client.inventoryManager.getWithInterface(530, 671, 27)

val summoningPoints: Int
    get() = varps.getVarBit(41524) / 10

val summoningPointsPercent: Int
    get() = floor((summoningPoints.toDouble() / (getCurrentLevel(Skill.SUMMONING) * 10)) * 100.0).toInt()

val familiarTimeMins: Int
    get() = varps.getVarBit(6055)

val familiarTimeSeconds: Int
    get() = floor(varps.getVar(1786) / 2.1333333).toInt()

val familiarSummoned: Boolean
    get() = varps.getVar(1831) != -1

val familiarId: Int
    get() = varps.getVar(1831)

val familiarName: String
    get() = Familiar.getName(familiarId)

val familiarHealth: Int
    get() = varps.getVarBit(19034)

val familiarScrolls: Int
    get() = varps.getVarBit(25412)

val familiarSpecialPoints: Int
    get() = varps.getVar(1787)

suspend fun Script.familiarRenewFromBank(pouch: Familiar): Boolean {
    if (!bankOpen && openClosestBank(false)) {
        delayUntil(1800, 600) { bankOpen }
    }

    if (bankOpen && bank.hasItem(pouch.pouchName)) {
        if (!withdrawBankItem(pouch.pouchName, 1).also {
                delayUntil(1800, 600) { inventory.hasItem(pouch.pouchName) }
                closeBank()
            }) return false
    }

    return inventory.getItem(pouch.pouchName)?.click("Summon") == true
}

suspend fun Script.familiarSummonFamiliar(pouch: Familiar): Boolean {
    return inventory.getItem(pouch.pouchName)?.click("Summon")?.also {
        delayUntil { familiarSummoned }
    } == true
}

suspend fun Script.familiarRenewFromInterface(pouch: Familiar): Boolean {
    if (!inventory.hasItem(pouch.pouchId)) return false
    val pouchCount = inventory.count(pouch.pouchId)

    return interfaces.isOpen(662) && IFSlot(662, 53, -1).click(1).also {
        delayUntil { inventory.count(pouch.pouchId) < pouchCount }
    }
}

suspend fun Script.familiarRecall(): Boolean =
    interfaces.isOpen(662) && IFSlot(662, 54, -1).click(1).also { delay(1000) }

suspend fun Script.familiarDismiss(): Boolean {
    if (!interfaces.isOpen(662)) return false
    if (!IFSlot(662, 56, -1).click(1)) return false

    delayUntil { interfaces.isOpen(1188) }

    return interfaces.isOpen(1188) && IFSlot(1188, 8, -1).dialogueContinue().also {
        delayUntil { !familiarSummoned }
    }
}

suspend fun Script.bobGiveAllItems(): Boolean {
    if (!interfaces.isOpen(662)) return false

    val freeSlots = inventory.freeSlots
    val clicked = IFSlot(662, 15, -1).click(1) || IFSlot(662, 105, -1).click(1)

    return clicked.also {
        if (it) delayUntil { inventory.freeSlots >= freeSlots }
    }
}

suspend fun Script.bobTakeAllItems(): Boolean {
    if (!interfaces.isOpen(662)) return false

    val freeSlots = inventory.freeSlots
    val clicked = IFSlot(662, 16, -1).click(1) || IFSlot(662, 106, -1).click(1)

    return clicked.also {
        if (it) delayUntil { inventory.freeSlots <= freeSlots }
    }
}

private suspend fun Script.bobGiveItem(item: Item?): Boolean {
    val familiar = findNPC { it.hasOption("Store") } ?: return false

    return item?.useOn(familiar)?.also { delay(1200) } == true
}

suspend fun Script.bobGiveItem(name: String): Boolean =
    bobGiveItem(inventory.getItem(name))

suspend fun Script.bobGiveItem(id: Int): Boolean =
    bobGiveItem(inventory.getItem(id))

private fun bobTake(item: Item?): Boolean {
    if (!interfaces.isOpen(662)) return false

    if (item != null){
        return beastOfBurden.clickItem(item.id, 1)
    }
    return false
}

suspend fun Script.bobTake(name: String): Boolean = bobTake(beastOfBurden.getItem(name))

suspend fun Script.bobTake(id: Int): Boolean = bobTake(beastOfBurden.getItem(id))

private suspend fun Script.bobStorage(items: List<Item?>, amounts: List<Any>, withdraw: Boolean = false): Boolean? {
    if (items.size != amounts.size) return null
    val familiar = findNPC { it.hasOption("Store") } ?: return false

    if (familiar.interact("Store")) delayUntil { interfaces.isOpen(671) }

    for ((item, amount) in items.zip(amounts)) {
        if (item == null) continue
        println("Item: $item Amount: $amount (${Gameval.obj(item.id) ?: ""})")

        val bobItem = if (withdraw) bobStorage.getItem(item.id) else bobPlayerInventory.getItem(item.id)

        val clicked = when (amount) {
            1 -> bobItem?.click(1)
            5 -> bobItem?.click(2)
            10 -> bobItem?.click(3)
            else -> bobItem?.click(4)
        }
        if (clicked == null || clicked == false) return false
        delay(1200, 200)
    }
    return IFSlot(671, 23, -1).click(1)
}

@JvmName("bobStoreByID")
suspend fun Script.bobStore(vararg idsAndAmounts: Pair<Int, Int>): Boolean? {
    val items = idsAndAmounts.map { inventory.getItem(it.first)}
    val amounts = idsAndAmounts.map { it.second }
    return bobStorage(items, amounts)
}

@JvmName("bobStoreByName")
suspend fun Script.bobStore(vararg namesAndAmounts: Pair<String, Int>): Boolean? {
    val items = namesAndAmounts.map { inventory.getItem(it.first) }
    val amounts = namesAndAmounts.map { it.second }
    return bobStorage(items, amounts)
}

@JvmName("bobWithdrawById")
suspend fun Script.bobWithdraw(vararg namesAndAmounts: Pair<String, Int>): Boolean? {
    val items = namesAndAmounts.map { beastOfBurden.getItem(it.first) }
    val amounts = namesAndAmounts.map { it.second }
    return bobStorage(items, amounts, true)
}

@JvmName("bobWithdrawByName")
suspend fun Script.bobWithdraw(vararg idsAndAmount: Pair<Int, Int>): Boolean? {
    val items = idsAndAmount.map { beastOfBurden.getItem(it.first) }
    val amounts = idsAndAmount.map { it.second }
    return bobStorage(items, amounts, true)
}

fun Script.familiarStoreScrolls(): Boolean =
    interfaces.isOpen(662) && IFSlot(662, 78, -1).click(1)

suspend fun Script.familiarTakeScrolls(): Boolean =
    interfaces.isOpen(662) && IFSlot(662, 78, -1).click(2).also {
        delayUntil { familiarScrolls == 0 }
    }

private suspend fun Script.castSpecialNpc(target: NPC?): Boolean {
    if (!interfaces.isOpen(662) || familiarScrolls == 0 || !inCombat) return false
    val scrollsRemaining = familiarScrolls

    if (IFSlot(662, 117, -1).select()) {
        target?.target()
        waitThenDelayUntil(600, 3000) { familiarScrolls != scrollsRemaining }
        return true
    }
    return false
}

private suspend fun Script.castSpecialInv(item: Item?): Boolean {
    if (!interfaces.isOpen(662) || familiarScrolls == 0) return false
    val scrollsRemaining = familiarScrolls

    if (item != null && IFSlot(662, 117, -1).select()) {
        delay(800, 200)
        val clicked = inventory.getItem(item.id)?.slot?.target()
        waitThenDelayUntil(600, 3000) { familiarScrolls != scrollsRemaining }
        return clicked == true
    }
    return false
}

private suspend fun Script.castSpecialGround(item: GroundItem?): Boolean {
    if (!interfaces.isOpen(662) || familiarScrolls == 0) return false
    val scrollsRemaining = familiarScrolls

    if (IFSlot(662, 117, -1).select()) {
        item?.target()
        waitThenDelayUntil(600, 3000) { familiarScrolls != scrollsRemaining  }
        return true
    }
    return false
}

private suspend fun Script.castSpecialObj(obj: SceneObject?): Boolean {
    if (!interfaces.isOpen(662) || familiarScrolls == 0) return false
    val scrollsRemaining = familiarScrolls

    val buttons = IFSlot(662, 117, -1).select() || IFSlot(662, 38, -1).select()
    if (buttons) {
        val clicked = obj?.target()
        waitThenDelayUntil(600, 3000) { familiarScrolls != scrollsRemaining  }
        return clicked == true
    }
    return false
}

fun Script.familiarCastSpecial(): Boolean {
    if (!interfaces.isOpen(662) || familiarScrolls == 0) return false
    val scrollsRemaining = familiarScrolls

    if (IFSlot(662, 117, -1).click(1)) {
        return true
    }
    return false
}

suspend fun Script.familiarCastSpecial(id: Int): Boolean {
    return when (Familiar.getType(familiarId)){
        SpecialType.ATTACK -> castSpecialNpc(findClosestNPC(id))
        SpecialType.INTERACT_INVENTORY -> castSpecialInv(inventory.getItem(id))
        SpecialType.INTERACT_OBJECT -> castSpecialObj(findClosestReachableObject(id))
        SpecialType.INTERACT_GROUNDITEM -> castSpecialGround(groundItems.firstOrNull{it.id == id})
        else -> false
    }
}

suspend fun Script.familiarCastSpecial(name: String): Boolean {
    return when (Familiar.getType(familiarId)) {
        SpecialType.ATTACK -> castSpecialNpc(findClosestNPC(name))
        SpecialType.INTERACT_INVENTORY -> castSpecialInv(inventory.getItem(name))
        SpecialType.INTERACT_OBJECT -> castSpecialObj(findClosestReachableObject(name))
        SpecialType.INTERACT_GROUNDITEM -> castSpecialGround(groundItems.firstOrNull{it.name == name})
        else -> false
    }
}

fun familiarAttack(target: String): Boolean? {
    if (!interfaces.isOpen(662) || !inCombat || !familiarSummoned) return false

    val npc = findClosestNPC(target)

    if (IFSlot(662, 104, -1).click(0)) {
        return npc?.target()
    }
    return false
}