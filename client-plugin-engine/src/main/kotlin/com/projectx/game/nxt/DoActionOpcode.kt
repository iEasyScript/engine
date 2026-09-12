package com.projectx.game.nxt

import world.gregs.voidps.type.Tile
import com.projectx.diag.CrashForensics
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.DoActionShadow
import com.projectx.game.input.ShadowInputBus
import com.projectx.game.math.WorldToScreen
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.entity.player.Player
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.api.localPlayer
import com.projectx.util.componentIdFromHash
import com.projectx.util.interfaceIdFromHash
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

enum class DoActionOpcode {
    SELECT_OBJECT,
    OBJECT_1,
    OBJECT_2,
    OBJECT_3,
    OBJECT_4,
    SELECT_NPC,
    PLAYER_SELECT,
    COMP_ON_PLAYER,
    SELECT_GROUND_ITEM,
    GROUND_ITEM_1,
    GROUND_ITEM_2,
    GROUND_ITEM_3,
    GROUND_ITEM_4,
    GROUND_ITEM_5,
    WALK,
    SELECT_COMPONENT,
    DIALOGUE,
    PLAYER_1,
    PLAYER_2,
    PLAYER_3,
    PLAYER_4,
    PLAYER_5,
    PLAYER_6,
    PLAYER_7,
    PLAYER_8,
    PLAYER_9,
    PLAYER_10,
    COMPONENT,
    SELECT_COMPONENT_ITEM,
    SELECT_TILE,
    OBJECT_5,
    OBJECT_6,
    GROUND_ITEM_6,
    UNK_1005,
    COMPONENT_SIXPLUS,
    NPC_1,
    NPC_2,
    NPC_3,
    NPC_4,
    NPC_5,
    NPC_6;

    companion object {
        const val UNRESOLVED_ID = -1

        private val OBJSTACK_OPS = setOf(
            SELECT_GROUND_ITEM,
            GROUND_ITEM_1, GROUND_ITEM_2, GROUND_ITEM_3,
            GROUND_ITEM_4, GROUND_ITEM_5, GROUND_ITEM_6,
        )

        private const val ALIAS_SUFFIX = "_ALT"

        /**
         * The client dispatches some actions under an alias id that binds the same handler as the
         * primary, so both bands must map onto the same opcode.
         */
        private val byId: Map<Int, DoActionOpcode> by lazy {
            val byName = entries.associateBy { it.name }
            OffsetTable.doActions()
                .filter { it.id != UNRESOLVED_ID }
                .mapNotNull { row -> byName[row.name.removeSuffix(ALIAS_SUFFIX)]?.let { row.id to it } }
                .toMap()
        }

        fun getActionById(id: Int): DoActionOpcode? {
            if (id == UNRESOLVED_ID) return null
            return byId[id]
        }
    }

    private val entry: DoActionEntry? get() = OffsetTable.doAction(name)

    /** Opcode this action is dispatched under, or [UNRESOLVED_ID] when this build has no entry. */
    val id: Int get() = entry?.id ?: UNRESOLVED_ID

    val actionName: String get() = entry?.method ?: name

    /** Module-relative address of the action's send function, or null when this build has no entry. */
    val callbackRva: Long? get() = entry?.rva?.takeIf { it != 0L }

    private val action: MethodHandle? by lazy {
        val rva = entry?.rva?.takeIf { it != 0L } ?: return@lazy null
        NativeAccess.BASE_ADDR.asSlice(rva, 8).toFunctionHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    }

    // The send callbacks are invoked with the action object as `this` and read the client back out of
    // it, so a block carrying just that one field is enough to stand in for a real action. Which field
    // it is differs per platform (Linux +0x0, Windows +0x8), hence the table lookup rather than a literal.
    private val syntheticAction: MemorySegment =
        NativeAccess.engineArena.allocate(OMiniMenuAction.CLIENT + 0x8)
    private val fakeMiniMenuEntrySharedPtr: MemorySegment = NativeAccess.engineArena.allocate(0x10)
    private val fakeMiniMenuEntry: MemorySegment = NativeAccess.engineArena.allocate(0x128)

    fun fire(param1: Int, param2: Int, param3: Int) {
        val callback = action
        if (callback == null) {
            println("DoAction.$name unavailable on ${OffsetTable.platform} build ${OffsetTable.build}")
            return
        }
        if (Bootstrap.client.mainState != MainState.LOGGED_IN && this != COMPONENT) return
        syntheticAction.set(JAVA_LONG, OMiniMenuAction.CLIENT, Bootstrap.client.ptr.address())
        fakeMiniMenuEntrySharedPtr.set(JAVA_LONG, 0x0L, fakeMiniMenuEntry.address())
        fakeMiniMenuEntrySharedPtr.set(JAVA_LONG, 0x8L, fakeMiniMenuEntry.address())
        fakeMiniMenuEntry.set(JAVA_INT, OMiniMenuEntry.PARAM_1, param1)
        fakeMiniMenuEntry.set(JAVA_INT, OMiniMenuEntry.PARAM_2, param2)
        fakeMiniMenuEntry.set(JAVA_INT, OMiniMenuEntry.PARAM_3, param3)
        fakeMiniMenuEntry.set(JAVA_LONG, OMiniMenuEntry.TARGETED_ENTITY, 0L)
        // highlightType==2 is the sender's cue to queue local predictive pathfinding to the tile;
        // without it the avatar never walks and the server drops the action.
        fakeMiniMenuEntry.set(JAVA_INT, OMiniMenuEntry.HIGHLIGHT_TYPE, 2)
        println("FIRED SYNTHETIC ActionType.$name($param1, $param2, $param3)")
        CrashForensics.trace("DoAction.$name($param1,$param2,$param3)")
        withMenuOpen {
            callback.invoke(syntheticAction, fakeMiniMenuEntrySharedPtr)
        }
        CrashForensics.trace("DoAction.$name returned")
        publishShadowIntent(param1, param2, param3)
    }

    // Ground-item senders fold jag::MiniMenu.menuOpen into the OPOBJ flags byte: without it the
    // server treats the take as a direct left-click and opens area-loot instead of picking up.
    private fun withMenuOpen(block: () -> Unit) {
        val miniMenu = if (this in OBJSTACK_OPS)
            Bootstrap.client.ptr.deref(OClient.MINIMENU, OMiniMenu.extent).getOrNull else null
        if (miniMenu == null) {
            block()
            return
        }
        val saved = miniMenu.get(JAVA_BYTE, OMiniMenu.MENU_OPEN)
        miniMenu.set(JAVA_BYTE, OMiniMenu.MENU_OPEN, 1)
        try {
            block()
        } finally {
            miniMenu.set(JAVA_BYTE, OMiniMenu.MENU_OPEN, saved)
        }
    }

    private fun publishShadowIntent(param1: Int, param2: Int, param3: Int) {
        try {
            val profile = PlayerProfiles.get()
            if (!profile.synthInputEnabled || !profile.synthShadowDoActions) return
            val (tx, ty) = resolveTarget(param1, param2, param3)
            ShadowInputBus.publish(
                DoActionShadow(
                    opcode = this,
                    param1 = param1,
                    param2 = param2,
                    param3 = param3,
                    gameTick = Bootstrap.client.clientCycle,
                    timestampNanos = System.nanoTime(),
                    resolvedTargetX = tx,
                    resolvedTargetY = ty,
                )
            )
        } catch (_: Throwable) {
            // Never let shadow plumbing affect the action call.
        }
    }

    /**
     * Resolve the on-screen target for this action while we're still on the
     * game thread under the action call's stack frame. The synth consumer will
     * trust whatever we put in the intent — by the time it runs, the entity
     * pointer may be freed memory (NPC despawned, loc deleted), so we can't
     * defer this. Returns null if resolution isn't supported or fails.
     */
    private fun resolveTarget(param1: Int, param2: Int, param3: Int): Pair<Float?, Float?> {
        return try {
            when (this) {
                WALK, SELECT_TILE -> tileScreen(param2, param3)

                SELECT_NPC, NPC_1, NPC_2, NPC_3, NPC_4, NPC_5, NPC_6 -> npcScreen(param1)

                SELECT_OBJECT,
                OBJECT_1, OBJECT_2, OBJECT_3, OBJECT_4, OBJECT_5, OBJECT_6 ->
                    tileScreen(param2, param3)  // loc actions carry tile coords in param2/3

                SELECT_GROUND_ITEM,
                GROUND_ITEM_1, GROUND_ITEM_2, GROUND_ITEM_3,
                GROUND_ITEM_4, GROUND_ITEM_5, GROUND_ITEM_6 ->
                    tileScreen(param2, param3)

                PLAYER_SELECT, COMP_ON_PLAYER,
                PLAYER_1, PLAYER_2, PLAYER_3, PLAYER_4, PLAYER_5,
                PLAYER_6, PLAYER_7, PLAYER_8, PLAYER_9, PLAYER_10 ->
                    playerScreen(param1)

                COMPONENT, COMPONENT_SIXPLUS, SELECT_COMPONENT, SELECT_COMPONENT_ITEM, DIALOGUE ->
                    componentScreen(param3)

                else -> null to null
            }
        } catch (_: Throwable) {
            null to null
        }
    }

    private fun tileScreen(tileX: Int, tileY: Int): Pair<Float?, Float?> {
        val plane = localPlayer.tile.plane
        val v = WorldToScreen.getEstimatedTileCenter(Tile.of(tileX, tileY, plane))
            ?: return null to null
        return v.x to v.y
    }

    private fun npcScreen(serverIndex: Int): Pair<Float?, Float?> {
        val ptr = Bootstrap.client.npcManager[serverIndex] ?: return null to null
        if (ptr.address() == 0L) return null to null
        val npc = NPC(ptr)
        if (!npc.exists()) return null to null
        val cx = npc.screenCenterX
        val cy = npc.screenCenterY
        if (cx <= 0 || cy <= 0) return null to null
        return cx.toFloat() to cy.toFloat()
    }

    private fun playerScreen(serverIndex: Int): Pair<Float?, Float?> {
        val ptr = Bootstrap.client.playerManager[serverIndex]
        if (ptr.address() == 0L) return null to null
        val player = Player(ptr)
        val cx = player.screenCenterX
        val cy = player.screenCenterY
        if (cx <= 0 || cy <= 0) return null to null
        return cx.toFloat() to cy.toFloat()
    }

    private fun componentScreen(componentHash: Int): Pair<Float?, Float?> {
        val list = Bootstrap.client.interfaceList
        val ifaceId = interfaceIdFromHash(componentHash)
        val compId = componentIdFromHash(componentHash)
        val comp = list.getComponent(ifaceId, compId) ?: return null to null
        val rect = comp.screenRect ?: return null to null
        val cx = rect.x + rect.width / 2f
        val cy = rect.y + rect.height / 2f
        if (cx <= 0f || cy <= 0f) return null to null
        return cx to cy
    }
}