package com.projectx.game.hooks.impl
import com.projectx.game.nxt.ONPC
import com.projectx.game.nxt.OCombinedLocationSection
import com.projectx.game.nxt.OLocation

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OMiniMenuEntry
import com.projectx.game.localizeScene
import com.projectx.game.tileOfSceneLocal
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import world.gregs.voidps.gameval.Gameval
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.entity.EntityTypeContainer
import com.projectx.game.nxt.entity.location.CombinedLocationSection
import com.projectx.game.nxt.entity.location.Location
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.game.nxt.minimenu.MiniMenuEntry
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.localPlayer
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.script.event.impl.ManualGroundItem
import com.projectx.script.event.impl.ManualItemTarget
import com.projectx.util.componentIdFromHash
import com.projectx.util.interfaceIdFromHash
import java.lang.foreign.MemorySegment

object DoAction {
    private val GROUND_ITEM_OPS = setOf(
        DoActionOpcode.GROUND_ITEM_1, DoActionOpcode.GROUND_ITEM_2, DoActionOpcode.GROUND_ITEM_3,
        DoActionOpcode.GROUND_ITEM_4, DoActionOpcode.GROUND_ITEM_5, DoActionOpcode.GROUND_ITEM_6,
        DoActionOpcode.SELECT_GROUND_ITEM,
    )

    @JvmStatic
    @Hook("MINIMENU_DOACTION")
    fun doActionHook(miniMenu: MemorySegment, miniMenuEntrySharedPtr: MemorySegment, vec3f: MemorySegment): MemorySegment {
        synchronized(Bootstrap.lock) {
            try {
                val entry = MiniMenuEntry(miniMenuEntrySharedPtr.toShared().value(OMiniMenuEntry.extent))
                val knownAction = DoActionOpcode.getActionById(entry.action.id)
                println("[DoAction] Opcode: ${knownAction?.actionName ?: "${entry.action.id}"} Params: ${entry.param1}, ${entry.param2}, ${entry.param3} Targeted entity: ${entry.target.address().toString(16)}")
                when(knownAction) {
                    DoActionOpcode.WALK -> {
                        val tile = Tile.of(entry.param2, entry.param3, localPlayer.plane)
                        println("\tMinimap: ${entry.param1} Tile.of(${tile.x}, ${tile.y}, ${tile.plane}) local: tileOfLocal(${tile.xInMapSquare}, ${tile.yInMapSquare}, ${tile.plane})")
                        val sceneLocal = tile.localizeScene()
                        if (sceneLocal != null)
                            println("\tsceneLocal: tileOfSceneLocal(${sceneLocal.x}, ${sceneLocal.y}, ${tile.plane})")
                        ScriptExecutor.pushEvent(ManualDoAction(knownAction, Tile.of(entry.param2, entry.param3, localPlayer.plane)))
                    }
                    DoActionOpcode.COMPONENT, DoActionOpcode.COMPONENT_SIXPLUS -> {
                        println("\tOpNum: ${entry.param1} IFSlot(${Gameval.interfaceLabel(interfaceIdFromHash(entry.param3))}, ${Gameval.componentLabel(interfaceIdFromHash(entry.param3), componentIdFromHash(entry.param3))}, ${entry.param2})")
                        ScriptExecutor.pushEvent(ManualDoAction(knownAction, IFSlot(interfaceIdFromHash(entry.param3), componentIdFromHash(entry.param3), entry.param2)))
                    }
                    DoActionOpcode.SELECT_COMPONENT -> {
                        println("\tOpNum: ${entry.param1} IFSlot(${Gameval.interfaceLabel(interfaceIdFromHash(entry.param3))}, ${Gameval.componentLabel(interfaceIdFromHash(entry.param3), componentIdFromHash(entry.param3))}, ${entry.param2})")
                        ScriptExecutor.pushEvent(ManualDoAction(knownAction, IFSlot(interfaceIdFromHash(entry.param3), componentIdFromHash(entry.param3), entry.param2)))
                    }
                    DoActionOpcode.DIALOGUE -> {
                        println("\tOpNum: ${entry.param1} IFSlot(${Gameval.interfaceLabel(interfaceIdFromHash(entry.param3))}, ${Gameval.componentLabel(interfaceIdFromHash(entry.param3), componentIdFromHash(entry.param3))}, ${entry.param2})")
                    }
                    DoActionOpcode.OBJECT_1, DoActionOpcode.OBJECT_2, DoActionOpcode.OBJECT_3, DoActionOpcode.OBJECT_4, DoActionOpcode.OBJECT_5, DoActionOpcode.OBJECT_6 -> {
                        val loc: EntityTypeContainer? = if (entry.target.address() != 0L) EntityTypeContainer(entry.target.toShared().value(0x18L)) else null
                        if (loc != null) {
                            val obj: SceneObject? = when (loc.type) {
                                EntityType.LOCATION -> Location(entry.target.toShared().value(OLocation.extent))
                                EntityType.COMBINED_LOCATION_SECTION -> CombinedLocationSection(entry.target.toShared().value(OCombinedLocationSection.extent))
                                else -> null
                            }
                            if (obj != null) {
                                ScriptExecutor.pushEvent(ManualDoAction(knownAction, obj))
                                println("\t${obj.javaClass.simpleName}: realId: ${Gameval.locLabel(obj.id)} visibleId: ${Gameval.locLabel(obj.visibleTypeId)} name: ${obj.name()} tile: ${obj.tile}")
                            }
                        }
                    }
                    DoActionOpcode.GROUND_ITEM_1, DoActionOpcode.GROUND_ITEM_2, DoActionOpcode.GROUND_ITEM_3,
                    DoActionOpcode.GROUND_ITEM_4, DoActionOpcode.GROUND_ITEM_5, DoActionOpcode.GROUND_ITEM_6,
                    DoActionOpcode.SELECT_GROUND_ITEM -> {
                        val name = entry.targetString.toString().replace(Regex("<[^>]*>"), "").trim()
                        val tile = Tile.of(entry.param2, entry.param3, localPlayer.plane)
                        println("\tGroundItem: id: ${entry.param1} (${Gameval.obj(entry.param1) ?: ""}) name: $name tile: $tile")
                        ScriptExecutor.pushEvent(ManualDoAction(knownAction, ManualGroundItem(entry.param1, name, tile)))
                    }
                    DoActionOpcode.NPC_1, DoActionOpcode.NPC_2, DoActionOpcode.NPC_3, DoActionOpcode.NPC_4, DoActionOpcode.NPC_5, DoActionOpcode.NPC_6 -> {
                        val npc = if (entry.target.address() != 0L) NPC(entry.target.toShared().value(ONPC.extent)) else null
                        if (npc != null) {
                            println("\tNPC: addr: ${Bootstrap.client.npcManager[npc.serverIndex]?.address()?.toString(16)} sid: ${npc.serverIndex} realId: ${Gameval.npcLabel(npc.id)} visibleId: ${Gameval.npcLabel(npc.typeId)} name: ${npc.name}")
                            ScriptExecutor.pushEvent(ManualDoAction(knownAction, npc))
                        }
                    }
                    else -> Unit
                }
                // Any entry carrying an item id (inventory click, use-item) is also surfaced as an
                // item target so the quest editor's picker can capture it. targetString is the
                // visible menu label, markup stripped. Ground-item ops are skipped here — they're
                // surfaced above as ManualGroundItem (with the world tile).
                if (knownAction != null && knownAction !in GROUND_ITEM_OPS && entry.itemId > 0) {
                    val itemName = entry.targetString.toString().replace(Regex("<[^>]*>"), "").trim()
                    ScriptExecutor.pushEvent(ManualDoAction(knownAction, ManualItemTarget(entry.itemId, itemName)))
                }
                val sendFuncRva = entry.action.actionSendFunc.address() - NativeAccess.BASE_ADDR.address()
                if (knownAction == null) {
                    println("\tUnknown action: ${entry.action.id}")
                    println("\tActionFuncPtr: 0x${entry.action.ptr.address().toString(16)}")
                    println("\tActionFuncPtrOffset: 0x${sendFuncRva.toString(16)}")
                } else if (sendFuncRva != knownAction.callbackRva) {
                    println("\t INVALID ACTION SEND FUNCTION: 0x${sendFuncRva.toString(16)}")
                    println(
                        "\t offset table says ${knownAction.callbackRva?.let { "0x${it.toString(16)}" } ?: "<missing>"}" +
                            " for ${knownAction.name} (id ${knownAction.id}, ${knownAction.actionName})"
                    )
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return HookManager.trampoline(::doActionHook.name).invokeExact(miniMenu, miniMenuEntrySharedPtr, vec3f) as MemorySegment
        }
    }
}