@file:Suppress("unused")

package world.gregs.voidps.collision

object CollisionStrategies {
    val NORMAL = NormalBlockFlagCollision()
    val WATER = BlockedFlagCollision()
    val FLY = LineOfSightBlockFlagCollision()
    val INDOOR = IndoorsFlagCollision()
    val OUTDOOR = OutdoorsFlagCollision()
    val NOCLIP = NoClipCollision()
}
