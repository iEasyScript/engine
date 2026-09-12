package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One square of the map the cutscene builds. [plane], [x] and [z] are the file's own packed source
 * position; [level], [chunkX] and [chunkZ] are the destination the chunk is copied to, in chunks.
 */
data class CutsceneArea(
    val plane: Int,
    val x: Int,
    val z: Int,
    val chunkWidth: Int,
    val chunkHeight: Int,
    val level: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val unknown9: Int
)

/** One leg of a camera path: where the camera sits, where it looks, and how long the leg lasts. */
data class CutsceneCameraStep(
    val eyeX: Int,
    val eyeZ: Int,
    val eyeHeight: Int,
    val lookX: Int,
    val lookZ: Int,
    val lookHeight: Int,
    val duration: Int
)

/** A camera path, one entry per leg. */
data class CutsceneCameraPath(val steps: List<CutsceneCameraStep>)

/**
 * Someone the cutscene can drive. [kind] 0 names an npc by [npc]; kind 1 stands the local player
 * in and carries no id. [name] is the cutscene's own label for the actor and nothing else reads it.
 */
data class CutsceneActor(val kind: Int, val npc: Int, val name: String)

/** A location the cutscene places, by its own id. */
data class CutsceneObject(val loc: Int, val unknown: Int)

/** One step of a walked path, in the cutscene's own local tile coordinates. */
data class CutsceneWaypoint(val level: Int, val x: Int, val z: Int)

/** A walked path, one entry per step. */
data class CutscenePath(val steps: List<CutsceneWaypoint>)

/**
 * One timed instruction. [type] decides which of the remaining fields the file carries, so an
 * absent field is left null rather than given a value the file never held.
 */
data class CutsceneAction(
    val type: Int,
    val time: Int,
    val actor: Int? = null,
    val x: Int? = null,
    val z: Int? = null,
    val level: Int? = null,
    val angle: Int? = null,
    val sequence: Int? = null,
    val text: String? = null,
    val duration: Int? = null,
    val shorts: IntArray? = null,
    val bytes: IntArray? = null,
    val ints: IntArray? = null
)

data class CutsceneType(
    override var id: Int = -1,
    var version: Int = 0,
    var aspectWidth: Int = 0,
    var aspectHeight: Int = 0,
    var unknown5: Int = 255,
    var areas: Array<CutsceneArea>? = null,
    var cameraPaths: Array<CutsceneCameraPath>? = null,
    var actors: Array<CutsceneActor>? = null,
    var objects: Array<CutsceneObject>? = null,
    var paths: Array<CutscenePath>? = null,
    var actions: Array<CutsceneAction>? = null,
) : CacheType {
    companion object {
        val EMPTY = CutsceneType()
    }
}
