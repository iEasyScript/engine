package com.projectx.script.impl.BugAbuser.Clues

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*
import com.projectx.util.random

@ScriptDescription(
    name = "Sandy Clue Solver",
    version = "1.0.0",
    author = "BugAbuser - Originally (dea.d - Discord) from MemoryError",
    description = "Solves Sandy Clues during the Beach Event"
)
class SandyClues : Script() {
    object IDS {
        const val CLUE = 43349
        const val SCROLL_BOX = 43351
    }

    suspend fun gotoTile(tile: Tile) {
        val randomizedTile = Tile.of(
            tile.x + random(-2, 2),
            tile.y + random(-2, 2),
            0
        )
        walkTo(randomizedTile, true)
        delayUntil(30000) { randomizedTile.getDistance(localPlayer.tile) < 4 }
    }
    override fun onStart() {
        super.onStart()
        println("Sandy Clues script started")
    }

    fun testComponent() {
        println(interfaces.getComponent(345, 4)?.text + "component 4")
    }

    fun isClueDialogOpen(): Boolean = interfaces.isOpen(345)
    fun isNPCDialogOpen(): Boolean = interfaces.isOpen(1184)
    fun isScrollBoxDialogOpen(): Boolean = interfaces.isOpen(1189)
    fun isShopInteractDialogOpen(): Boolean = interfaces.isOpen(1186)

    suspend fun takeScroll() {
        gotoTile(Tile.of(3180, 3241, 0))
        findClosestNPC { it.id == 21146 }?.interact("Request Sandy Clue Scroll")
        delayUntil(20000) { isNPCDialogOpen() }
        delay(random(600, 900))
        if (!inventory.any { it.id == IDS.CLUE }) {
            println("No more clues, exiting")
            stop()
        }
    }

    suspend fun dungHole() {
        gotoTile(Tile.of(3170, 3252, 0))
        findClosestObject { it.id == 114121 }?.interact("Dungeoneer")
        delayUntil(20000) { isScrollBoxDialogOpen() }
        delay(random(400, 700))
    }

    suspend fun sarah() {
        gotoTile(Tile.of(3169, 3220, 0))
        findClosestNPC { it.id == 21153 }?.interact("Talk to")
        delayUntil(10000) { isNPCDialogOpen() }
        IFSlot(1184, 15, -1).dialogueContinue()
        delay(random(600, 900))
    }

    suspend fun lifeguard() {
        gotoTile(Tile.of(3170, 3252, 0))
        findClosestNPC { it.id == 21158 }?.interact("Talk to")
        delayUntil(10000) { isNPCDialogOpen() }
        IFSlot(1184, 15, -1).dialogueContinue()
        delay(random(400, 700))
    }

    suspend fun palmer() {
        gotoTile(Tile.of(3154, 3227, 0))
        findClosestNPC { it.id == 21152 }?.interact("Talk to")
        delayUntil(10000) { isNPCDialogOpen() }
        IFSlot(1184, 15, -1).dialogueContinue()
        delay(random(400, 700))
    }

    suspend fun foreman() {
        gotoTile(Tile.of(3158, 3227, 0))
        findClosestNPC { it.id == 21163 }?.interact("Talk to")
        delayUntil(10000) { isNPCDialogOpen() }
        IFSlot(1184, 15, -1).dialogueContinue()
        delay(random(400, 700))
    }

    suspend fun flo() {
        gotoTile(Tile.of(3166, 3217, 0))
        findClosestNPC { it.id == 21148 }?.interact("Open Store")
        delayUntil(20000) { isShopInteractDialogOpen() }
        IFSlot(1186, 8, -1).dialogueContinue()
        delay(random(600, 900))
    }

    suspend fun sheldon() {
        println("DEBUG: sheldon() called")
        gotoTile(Tile.of(3170, 3252, 0))
        println("DEBUG: sheldon() called 2")
        findClosestNPC { it.id == 21147 }?.interact("Open Store")
        delayUntil(20000) { isShopInteractDialogOpen() }
        IFSlot(1186, 8, -1).dialogueContinue()
        delay(random(600, 900))
    }

    suspend fun wellington() {
        gotoTile(Tile.of(3180, 3241, 0))
        findClosestNPC { it.id == 21150 }?.interact("Talk to")
        delayUntil(10000) { isNPCDialogOpen() }
        IFSlot(1184, 15, -1).dialogueContinue()
        delay(random(600, 900))
    }

    suspend fun coconuts() {
        gotoTile(Tile.of(3169, 3220, 0))
        findClosestObject { it.id == 97332 }?.interact("Deposit coconuts")
        delayUntil(20000) { isScrollBoxDialogOpen() }
        delay(random(600, 900))
    }

    suspend fun fishTable() {
        gotoTile(Tile.of(3180, 3241, 0))
        findClosestObject { it.id == 97277 }?.interact("Deposit fish")
        delayUntil(20000) { isScrollBoxDialogOpen() }
        delay(random(600, 900))
    }

    suspend fun palmTree() {
        gotoTile(Tile.of(3180, 3241, 0))
        findClosestObject { it.id == 117512 }?.interact("Pick coconut")
        delayUntil(20000) { isScrollBoxDialogOpen() }
        delay(random(600, 900))
    }

    val KEYWORDS = mapOf(
        "SARAH" to "she isn't shy",
        "LIFEGUARD" to "sits around watching beach",
        "PALMER" to "palm of his hand",
        "FOREMAN" to "endless stream",
        "FLO" to "common ground",
        "SHELDON" to "one hat, two hat, three hat",
        "WELLINGTON" to "named after a boot",
        "HOLE" to "large hole",
        "COCONUTS" to "dwarf looks after a pile",
        "FISH_TABLE" to "smells fishy behind a dwarf",
        "PALM_TREE" to "pick some coconuts"
    )

    fun getClueText(): String? {
        return interfaces.getComponent(345, 4)?.text
    }

    suspend fun solveClue() {
        val message = getClueText()
        println("DEBUG: solveClue() message = '$message'")
        val normalizedMessage = message?.trim()?.lowercase() ?: ""
        println("DEBUG: normalizedMessage='$normalizedMessage'")
        println("DEBUG: SHELDON keyword='${KEYWORDS["SHELDON"]}'")
        println("DEBUG: contains? " + normalizedMessage.contains(KEYWORDS["SHELDON"]!!))
        when {
            normalizedMessage.contains(KEYWORDS["FLO"]!!) -> flo()
            normalizedMessage.contains(KEYWORDS["FOREMAN"]!!) -> foreman()
            normalizedMessage.contains(KEYWORDS["LIFEGUARD"]!!) -> lifeguard()
            normalizedMessage.contains(KEYWORDS["PALMER"]!!) -> palmer()
            normalizedMessage.contains(KEYWORDS["SARAH"]!!) -> sarah()
            normalizedMessage.contains(KEYWORDS["SHELDON"]!!) -> sheldon()
            normalizedMessage.contains(KEYWORDS["WELLINGTON"]!!) -> wellington()
            normalizedMessage.contains(KEYWORDS["HOLE"]!!) -> dungHole()
            normalizedMessage.contains(KEYWORDS["COCONUTS"]!!) -> coconuts()
            normalizedMessage.contains(KEYWORDS["FISH_TABLE"]!!) -> fishTable()
            normalizedMessage.contains(KEYWORDS["PALM_TREE"]!!) -> palmTree()
            else -> {
                println("unknown message: $message")
                stop()
            }
        }
    }

    override suspend fun loop() {
        when {
            inventory.any { it.id == IDS.SCROLL_BOX } -> {
                println("scroll")
                inventory.firstOrNull { it.id == IDS.SCROLL_BOX }?.click("Open")
                delay(random(600, 900))
            }
            inventory.any { it.id == IDS.CLUE } -> {
                inventory.firstOrNull { it.id == IDS.CLUE }?.click("Read")
                delayUntil(10000) { isClueDialogOpen() }
                delay(random(1200, 1500))
                solveClue()
            }
            else -> takeScroll()
        }
        delay(random(600, 900))
    }
}
