package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.nxt.entity.HintIcon
import com.projectx.game.nxt.entity.HintTrail
import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.interfaces.InterfaceList
import com.projectx.game.nxt.inventories.Inventories
import com.projectx.game.nxt.mainlogicmanager.ClientVarDomain
import com.projectx.game.nxt.mainlogicmanager.MainLogicManager
import com.projectx.game.nxt.mainlogicmanager.StatTable
import com.projectx.game.nxt.stockmarket.StockMarket
import java.lang.foreign.MemorySegment

object MainState {
    const val INITIALIZING = 0
    const val LOGIN_SCREEN = 10
    const val LOBBY_SCREEN = 20
    const val ACCOUNT_CREATION = 23
    const val LOGGED_IN = 30
    const val ATTEMPTING_TO_REESTABLISH_NOTIFICATION = 35
    const val RECONNECTING_TO_SERVER = 37
    const val LOADING_NOTIFICATION = 40
}

class Client(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OClient.extent)

    val mainState
        get() = ptr.getInt(OClient.MAIN_STATE)
    val clientCycle
        get() = ptr.getInt(OClient.CLIENT_CYCLE)

    /** jag::input::Input, embedded in the Client — available without waiting for an input event. */
    val input: MemorySegment
        get() = ptr.asSlice(OClient.INPUT, OInput.SIZE)

    /**
     * This is a getter because logged in player can be nullptr at game state 10.
     */
    val loggedInPlayer: LoggedInPlayer
        get() = LoggedInPlayer(ptr.deref(OClient.LOGGED_IN_PLAYER, 0x20000L), this)

    val playerVarDomain: PlayerVarDomain
        get() = PlayerVarDomain(ptr.pointerAtOffset(OClient.PLAYER_VAR_DOMAIN, 0x20000L))

    val inventoryManager: Inventories
        get() = Inventories(ptr.deref(OClient.INVENTORY_MANAGER, 0x20000L))

    val playerManager: PlayerManager
        get() = PlayerManager(ptr.deref(OClient.PLAYER_MANAGER, 0x20000L))

    val npcManager: NPCManager
        get() = NPCManager(ptr.deref(OClient.NPC_MANAGER, 0x20000L))

    val sceneManager: SceneManager
        get() = SceneManager(ptr.deref(OClient.SCENE_MANAGER, 0x20000L))

    val interfaceList: InterfaceList
        get() = InterfaceList(ptr.deref(OClient.INTERFACE_MANAGER, OInterfaceManager.extent).pointerAtOffset(OInterfaceManager.INTERFACE_LIST, OInterfaceList.extent))

    val spotAnimManager: SpotAnimManager
        get() = SpotAnimManager(ptr.deref(OClient.SPOTANIM_MANAGER, 0x20000L))

    val projectileList
        get() = ProjectileList(ptr.deref(OClient.PROJECTILE_LIST, 0x20000L))

    val itemStackList
        get() = ItemStackList(ptr.deref(OClient.ITEMSTACK_LIST, 0x20000L))

    val mainLogicManager: MainLogicManager
        get() = MainLogicManager(ptr.deref(OClient.MAINLOGIC_MANAGER, 0x20000L))

    val stockMarket: StockMarket
        get() = StockMarket(ptr.deref(OClient.STOCKMARKET, OStockMarket.extent))

    val sdlManager: SDLManager
        get() = SDLManager(ptr.deref(OClient.SDL_MANAGER, OSDLManager.extent))

    val clientVarDomain: ClientVarDomain
        get() = mainLogicManager.clientVarDomain

    val skills: StatTable
        get() = mainLogicManager.statTable

    /** Every live hint icon the server has placed, read from the fixed hint-slot arrays. */
    val hintIcons: List<HintIcon>
        get() = HintIcon.all()

    /** The tiles the live hint icons mark. */
    val hintTiles: List<Tile>
        get() = HintIcon.tiles()

    /** Hint trails (the walked-path variant) from the separate HINT_TRAIL slot array. */
    val hintTrails: List<HintTrail>
        get() = HintTrail.all()

    companion object {
        fun getClient(base: MemorySegment): Client {
            return Client(base.deref(OGlobal.CLIENT, 0x20000L))
        }
    }
}