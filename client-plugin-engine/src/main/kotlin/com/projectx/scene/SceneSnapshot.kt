package com.projectx.scene

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.entity.SpotAnim
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer

/**
 * Collects nearby entities for the debug entity overlay. Called live from the overlay's `render()`,
 * which runs on the main-logic thread where the scene is stable — so there is nothing to cache. The
 * per-entity `runCatching` guards defend against transient half-built entities, not against threading.
 */
object SceneSnapshot {
    data class FineCoord(val x: Float, val y: Float, val z: Float)

    data class NpcView(
        val ptrAddr: Long,
        val serverIndex: Int,
        val id: Int,
        val typeId: Int,
        val name: String,
        val tileX: Int,
        val tileY: Int,
        val plane: Int,
        val fine: FineCoord,
        val currentHealth: Int,
        val maxHealth: Int,
        val animationId: Int,
        val hiddenMenuOpFlags: Int,
    )

    data class ObjectView(
        val ptrAddr: Long,
        val id: Int,
        val typeId: Int,
        val name: String,
        val shape: String,
        val tileX: Int,
        val tileY: Int,
        val plane: Int,
        val fine: FineCoord,
    )

    data class SimpleView(
        val ptrAddr: Long,
        val id: Int,
        val tileX: Int,
        val tileY: Int,
        val plane: Int,
        val fine: FineCoord,
    )

    data class ItemView(
        val id: Int,
        val name: String,
        val amount: Int,
        val tileX: Int,
        val tileY: Int,
        val plane: Int,
        val fine: FineCoord,
    )

    data class Scene(
        val playerTileX: Int,
        val playerTileY: Int,
        val playerPlane: Int,
        val npcs: List<NpcView>,
        val objects: List<ObjectView>,
        val spotAnims: List<SimpleView>,
        val projectiles: List<SimpleView>,
        val items: List<ItemView>,
    ) {
        companion object {
            val EMPTY = Scene(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    fun collect(range: Int): Scene {
        if (runCatching { Bootstrap.client.mainState }.getOrNull() != MainState.LOGGED_IN) return Scene.EMPTY
        val playerTile = runCatching { localPlayer.tile }.getOrNull() ?: return Scene.EMPTY

        val npcs = buildList {
            runCatching {
                val npcManager = Bootstrap.client.npcManager
                for (hashCode in npcManager.indices) {
                    if (hashCode <= 0) continue
                    val npcPtr = npcManager[hashCode] ?: continue
                    if (npcPtr.address() == 0L) continue
                    val npc = NPC(npcPtr)
                    if (!npc.exists()) continue
                    runCatching {
                        val t = npc.tile
                        if (playerTile.getDistance(t) > range) return@runCatching
                        val gnFine = npc.graphNode.tileFine
                        add(NpcView(
                            ptrAddr = npc.ptr.address(),
                            serverIndex = npc.serverIndex,
                            id = npc.id,
                            typeId = npc.typeId,
                            name = npc.name(),
                            tileX = t.x,
                            tileY = t.y,
                            plane = t.plane,
                            fine = FineCoord(gnFine.x, gnFine.y, gnFine.z),
                            currentHealth = npc.currentHealth,
                            maxHealth = npc.maxHealth,
                            animationId = npc.animationId,
                            hiddenMenuOpFlags = npc.hiddenMenuOpFlags,
                        ))
                    }
                }
            }
        }

        val objects = buildList {
            runCatching {
                for (obj in getAllObjectsWithinRange(range)) {
                    runCatching {
                        val name = obj.name()
                        if (name.isBlank()) return@runCatching
                        val gn = obj.graphNode ?: return@runCatching
                        val fine = gn.tileFine
                        val t = obj.tile
                        add(ObjectView(
                            ptrAddr = obj.memPointer.address(),
                            id = obj.id,
                            typeId = obj.visibleTypeId,
                            name = name,
                            shape = obj.shape.name,
                            tileX = t.x,
                            tileY = t.y,
                            plane = t.plane,
                            fine = FineCoord(fine.x, fine.y, fine.z),
                        ))
                    }
                }
            }
        }

        val spotAnims = buildList {
            runCatching {
                for (saPtr in Bootstrap.client.spotAnimManager) {
                    runCatching {
                        val sa = SpotAnim(saPtr)
                        val t = sa.tile
                        if (playerTile.getDistance(t) > range) return@runCatching
                        val f = sa.graphNode.tileFine
                        add(SimpleView(sa.ptr.address(), sa.id, t.x, t.y, t.plane,
                            FineCoord(f.x, f.y, f.z)))
                    }
                }
            }
        }

        val projectiles = buildList {
            runCatching {
                for (p in Bootstrap.client.projectileList) {
                    runCatching {
                        val t = p.tile
                        if (playerTile.getDistance(t) > range) return@runCatching
                        val f = p.graphNode.tileFine
                        add(SimpleView(p.ptr.address(), p.id, t.x, t.y, t.plane,
                            FineCoord(f.x, f.y, f.z)))
                    }
                }
            }
        }

        val items = buildList {
            runCatching {
                for (gi in groundItems) {
                    runCatching {
                        val t = gi.tile
                        if (playerTile.getDistance(t) > range) return@runCatching
                        val tx = t.x * 512f + 256f
                        val ty = t.y * 512f + 256f
                        val tz = HeightMap.fineHeight(t)?.toFloat() ?: 0f
                        add(ItemView(gi.id, gi.name, gi.amount, t.x, t.y, t.plane,
                            FineCoord(tx, ty, tz)))
                    }
                }
            }
        }

        return Scene(
            playerTileX = playerTile.x,
            playerTileY = playerTile.y,
            playerPlane = playerTile.plane,
            npcs = npcs,
            objects = objects,
            spotAnims = spotAnims,
            projectiles = projectiles,
            items = items,
        )
    }
}
