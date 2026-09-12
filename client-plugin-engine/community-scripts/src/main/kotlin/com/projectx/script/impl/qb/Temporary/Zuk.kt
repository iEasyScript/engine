package com.projectx.script.impl.qb.Temporary
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import org.projectx.core.game.combat.Ability
import com.projectx.game.interfaces.IFSlot
import org.projectx.core.game.combat.Effect
import com.projectx.game.nxt.entity.SpotAnim
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.util.random
import java.util.regex.Pattern
import kotlin.collections.iterator

@ScriptDescription(
    name = "Zuk Fighter",
    version = "1.0.0",
    author = "Anonymous",
    description = "Automated TzKal-Zuk fighter with prayer switching and combat rotations"
)
class Zuk : StateMachineScript<Zuk>(), ConfigurableScript {
    val hasZukCape = BooleanConfigItem(
        name = "Has Zuk Cape",
        description = "Whether you have the Zuk cape for enhanced mechanics",
        initialValue = false
    )

    val useBook = BooleanConfigItem(
        name = "Use Book",
        description = "Use grimoire or pocket slot book during combat",
        initialValue = false
    )

    val usePoison = BooleanConfigItem(
        name = "Use Poison",
        description = "Apply weapon poison during combat",
        initialValue = false
    )

    val useExcalibur = BooleanConfigItem(
        name = "Use Excalibur",
        description = "Use Excalibur for healing",
        initialValue = false
    )

    val useElvenShard = BooleanConfigItem(
        name = "Use Elven Shard",
        description = "Use Elven ritual shard for enhanced regeneration",
        initialValue = false
    )

    val enableDebugLogs = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable detailed debug logging output",
        initialValue = false
    )

    val overloadName = StringConfigItem(
        name = "Overload Name",
        description = "Name of the overload potion to use",
        initialValue = "Overload"
    )

    val necromancyPrayerName = StringConfigItem(
        name = "Necromancy Prayer",
        description = "Name of the necromancy prayer to use",
        initialValue = "Malevolence"
    )

    val bookName = StringConfigItem(
        name = "Book Name",
        description = "Name of the book/grimoire to use",
        initialValue = "Grimoire"
    )

    val restoreName = StringConfigItem(
        name = "Restore Name",
        description = "Name of the restore potion to use",
        initialValue = "Super restore"
    )

    val foodName = StringConfigItem(
        name = "Food Name",
        description = "Name of the food to use for healing",
        initialValue = "Sailfish"
    )

    val foodPotName = StringConfigItem(
        name = "Food Pot Name",
        description = "Name of the food potion to use",
        initialValue = "Sailfish soup"
    )

    val adrenPotName = StringConfigItem(
        name = "Adrenaline Potion Name",
        description = "Name of the adrenaline potion to use",
        initialValue = "Adrenaline potion"
    )

    val ringSwitchName = StringConfigItem(
        name = "Ring Switch Name",
        description = "Name of the ring to switch to",
        initialValue = "Ring of death"
    )

    val debug: Boolean get() = enableDebugLogs.value

    val overloadNamePattern: Pattern get() = Pattern.compile(".*${overloadName.value}.*")
    val restoreNamePattern: Pattern get() = Pattern.compile(".*${restoreName.value}.*")
    val foodPotNamePattern: Pattern get() = Pattern.compile(".*${foodPotName.value}.*")

    var currentWave: Int = 0
    var currentTarget: NPC? = null
    var isNormalWave: Boolean = false
    var isIgneousWave: Boolean = false
    var isJadWave: Boolean = false
    var isChallengeWave: Boolean = false
    var isPizzaPhase: Boolean = false
    var zukDpsCheckActive: Boolean = false
    var lastClickedTarget: NPC? = null
    var movingToTarget: Boolean = false

    var safespotJad: Tile? = null

    var safespotNormal: Tile? = null
    var arenaMinX: Int = Int.MIN_VALUE
    var arenaMaxX: Int = Int.MAX_VALUE
    var arenaMinY: Int = Int.MIN_VALUE
    var arenaMaxY: Int = Int.MAX_VALUE

    var lastBuffCheck: Long = 0
    var lastExcalUse: Long = 0
    private var lastElvenUse: Long = 0
    private var lastVulnBomb: Long = 0

    companion object {
        const val HUR = 28535
        const val IGNEOUS_HUR = 28537
        const val VOLATILE_HUR = 28546
        const val MEJ = 28542
        const val ZEK = 28543
        const val IGNEOUS_MEJ = 28544
        const val MEJKOT = 28536
        const val XIL = 28538
        const val TOK_XIL = 28539
        const val IGNEOUS_XIL = 28540
        const val KIH = 28545
        const val JAD = 28534
        const val UNBREAKABLE = 28547
        const val FATAL_1 = 28548
        const val FATAL_2 = 28549
        const val FATAL_3 = 28550
        const val HAR_AKEN = 28529
        const val ZUK_DPS = 28526
        const val ZUK_START = 28525
        const val ZUK_FIGHT = 28527
        const val ZUK_END = 28528

        val REGULAR_WAVES = setOf(1, 2, 3, 7, 8, 12, 13)
        val JAD_WAVES = setOf(6, 11, 16)
        val CHALLENGE_WAVES = setOf(5, 10, 15)
        val IGNEOUS_WAVES = setOf(4, 9, 14)

        val WAVE_PRIORITIES = mapOf(
            1 to mapOf(KIH to 3, HUR to 1),
            2 to mapOf(KIH to 4, XIL to 8, HUR to 1, MEJKOT to 2),
            3 to mapOf(KIH to 4, XIL to 8, MEJKOT to 8, HUR to 1),
            4 to mapOf(IGNEOUS_HUR to 100),
            5 to mapOf(VOLATILE_HUR to 100),
            6 to mapOf(JAD to 20, KIH to 6, XIL to 12, MEJKOT to 4),
            7 to mapOf(KIH to 10, XIL to 15, MEJKOT to 10, MEJ to 10, TOK_XIL to 25),
            8 to mapOf(XIL to 15, MEJKOT to 8, MEJ to 5, TOK_XIL to 25),
            9 to mapOf(IGNEOUS_XIL to 150),
            10 to mapOf(UNBREAKABLE to 100),
            11 to mapOf(JAD to 20, KIH to 5, MEJ to 10, MEJKOT to 3),
            12 to mapOf(KIH to 15, MEJ to 5, TOK_XIL to 25, ZEK to 30),
            13 to mapOf(MEJ to 10, TOK_XIL to 20, ZEK to 30),
            14 to mapOf(IGNEOUS_MEJ to 100),
            15 to mapOf(FATAL_1 to 1, FATAL_2 to 1, FATAL_3 to 1),
            16 to mapOf(JAD to 10),
            17 to mapOf(HAR_AKEN to 1000),
            18 to mapOf(IGNEOUS_HUR to 10, IGNEOUS_XIL to 20, IGNEOUS_MEJ to 30)
        )

        fun worthSkullingOrThreading(minTargets: Int): Boolean {
            val currTarget = npcs[localPlayer.interactionSid] ?: return false
            var numTargets = 0

            val allTargetIds = listOf(HUR, IGNEOUS_HUR, VOLATILE_HUR, MEJ, ZEK, IGNEOUS_MEJ,
                MEJKOT, XIL, TOK_XIL, IGNEOUS_XIL, KIH, JAD, UNBREAKABLE, FATAL_1, FATAL_2, FATAL_3, HAR_AKEN)

            for (id in allTargetIds) {
                findClosestNPC(id, 50)?.let { target ->
                    if (target.currentHealth > 0 && target.tile.getDistance(currTarget.tile) <= 6) {
                        numTargets++
                    }
                }
            }

            return numTargets >= minTargets
        }

        fun areTargetsAlive(targetIds: List<Int>): Boolean {
            return targetIds.any { id ->
                (findClosestNPC(id, 40)?.currentHealth ?: 0) > 0
            }
        }

        fun extraActionButtonVisible(): Boolean {
            return varps.getVarBit(10254) == 3
        }

        fun clickExtraActionButton() {
            IFSlot(743, 1, -1).click(1)
        }

        fun targetDeathMarked(): Boolean {
            return varps.getVarBit(11303) and (1 shl 7) != 0
        }

        fun invokeDeathActive(): Boolean {
            return Effect.INVOKE_DEATH.active
        }

        fun inThreadsRotation(): Boolean {
            return Effect.THREADS_OF_FATE.active
        }

        fun specAttackOnCooldown(): Boolean {
            return Effect.WEAPON_SPECIAL_ATTACK.active
        }

        fun necrosisStacks(): Int {
            return varps.getVar(10986)
        }

        fun soulStacks(): Int {
            return varps.getVar(11035)
        }

        fun targetBloated(): Boolean {
            return varps.getVarBit(11303) and (1 shl 5) != 0
        }

        fun targetVulned(): Boolean {
            return varps.getVarBit(896) and (1 shl 29) != 0
        }

        fun targetStunnedOrBound(): Boolean {
            val state = varps.getVarBit(896)
            return (state and 1 != 0) || (state and (1 shl 1) != 0)
        }

        fun deathSkullsActive(): Boolean {
            return getAllSpotAnimsWithinRange(14) { it.id == 7882 }.isNotEmpty()
        }

        fun Zuk.zukStartFightAnimation(): Boolean {
            val zuk = findClosestNPC(ZUK_FIGHT, 50)
            return zuk?.animationId in listOf(34518, 34494) || (zuk?.tile?.y ?: 0) > arenaMaxY
        }

        fun getZukDpsCheckActive(): Boolean {
            val zuk = findClosestNPC(ZUK_DPS, 50)
            return zuk?.animationId == 34516
        }

        fun isPizzaPhaseActive(): Boolean {
            val zuk = findClosestNPC(ZUK_FIGHT, 50)
            return zuk?.animationId in listOf(34495, 34501, 34502, 34505)
        }
    }

    override fun getStartState() = Init

    override fun onStart() {
        addParallelScript(ZukCombatRotation())
        addParallelScript(ZukPrayerSwitcher())
    }
}

private val isInZukInstance get() = inInstancedArea
private val isZukDefeated get() = findClosestNPC(Zuk.ZUK_END, 40) != null

object Init : State<Zuk>() {
    override suspend fun Zuk.checkNext() = when {
        !isInZukInstance -> {
            if(debug) println("[Zuk] Not in Zuk instance, stopping script")
            null
        }
        isZukDefeated -> {
            if(debug) println("[Zuk] Zuk defeated, stopping script")
            null
        }
        else -> {
            if(debug) println("[Zuk] Transitioning to Preparing state")
            Preparing
        }
    }

    override suspend fun Zuk.stateLoop() {
        if (debug) {
            println("=== ZUK STATE: Init ===")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] In instance: $isInZukInstance")
            println("[STATUS] Zuk defeated: $isZukDefeated")
        }
    }
}

object Preparing : State<Zuk>() {
    override suspend fun Zuk.checkNext() = when {
        !isInZukInstance -> {
            if(debug) println("[Zuk] Not in Zuk instance, stopping script")
            null
        }
        isZukDefeated -> {
            if(debug) println("[Zuk] Zuk defeated, stopping script")
            null
        }
        areTargetsAlive() -> {
            if(debug) println("[Zuk] Targets are alive, transitioning to Fighting state")
            Fighting
        }
        else -> {
            if(debug) println("[Zuk] No targets alive, staying in Preparing state")
            null
        }
    }

    override suspend fun Zuk.stateLoop() {
        if (debug) {
            println("=== ZUK STATE: Preparing ===")
            println("[STATUS] Current wave: $currentWave")
            println("[STATUS] Targets alive: ${areTargetsAlive()}")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] Safespot Jad: $safespotJad")
            println("[STATUS] Safespot Normal: $safespotNormal")
            println("[STATUS] Is Normal Wave: $isNormalWave")
            println("[STATUS] Is Jad Wave: $isJadWave")
            println("[STATUS] Is Igneous Wave: $isIgneousWave")
            println("[STATUS] Is Challenge Wave: $isChallengeWave")
        }

        updateFightState()

        if (currentWave > 0) {
            if(debug) println("[Zuk] Determining safe spot for wave $currentWave")

            when {
                isJadWave && safespotJad != null -> {
                    if(debug) println("[Zuk] Moving to Jad safespot for wave $currentWave: $safespotJad")
                    goToSafespot(safespotJad!!)
                }

                isNormalWave && safespotNormal != null -> {
                    if(debug) println("[Zuk] Moving to normal safespot for wave $currentWave: $safespotNormal")
                    goToSafespot(safespotNormal!!)
                }

                isIgneousWave && safespotNormal != null -> {
                    if(debug) println("[Zuk] Moving to normal safespot for igneous wave $currentWave: $safespotNormal")
                    goToSafespot(safespotNormal!!)
                }

                isChallengeWave && safespotNormal != null -> {
                    if(debug) println("[Zuk] Moving to normal safespot for challenge wave $currentWave: $safespotNormal")
                    goToSafespot(safespotNormal!!)
                }

                safespotNormal != null -> {
                    if(debug) println("[Zuk] Moving to default safespot for wave $currentWave: $safespotNormal")
                    goToSafespot(safespotNormal!!)
                }
            }
        }

        manageBuff()
        delay(100)
    }
}

object Fighting : State<Zuk>() {
    override suspend fun Zuk.checkNext() = when {
        !isInZukInstance -> null
        isZukDefeated -> null
        !areTargetsAlive() -> Preparing
        shouldHandleMechanics() -> MechanicsHandling
        else -> null
    }

    override suspend fun Zuk.stateLoop() {
        findArenaCoordinates()

        updateFightState()
        manageBuff()

        if (needsToMoveToSafespot()) {
            if(debug) println("[Zuk] Fighting: Moving to safespot for wave $currentWave before attacking")
            moveToWaveSafespot()
            delay(200)
            return
        }

        if (needsNewTarget()) {
            val nextTarget = findNextBestTarget()
            if (needToBeNextToTarget(nextTarget)) {
                moveWithinAreaOfTarget(nextTarget)
                return
            } else {
                attackTarget(nextTarget)
            }
        }

        if (inCombat) {
            val target = findNextBestTarget()
            if (target != null && !target.interact("Attack")) {
                delay(600)
            }
        }
        delay(50)
    }
}

object MechanicsHandling : State<Zuk>() {
    override suspend fun Zuk.checkNext() = when {
        !isInZukInstance -> null
        isZukDefeated -> null
        !shouldHandleMechanics() -> Fighting
        else -> null
    }

    override suspend fun Zuk.stateLoop() {
        handleZukMechanics()
        handleHarAkenMechanics()
        delay(100)
    }
}

fun Zuk.findArenaCoordinates() {
    val zuk = findClosestNPC(Zuk.Companion.ZUK_DPS, 50)
    if(debug) println( "[Zuk] Finding arena coordinates based on Zuk DPS NPC: $zuk")
    if (zuk != null) {
        val zukX = zuk.tile.x
        val zukY = zuk.tile.y
        safespotJad = Tile.of((zukX - 8), (zukY - 14), 0)
        safespotNormal = Tile.of((zukX + 9), (zukY - 21), 0)
        arenaMinX = zukX - 15
        arenaMaxX = zukX + 15
        arenaMaxY = zukY - 4
        arenaMinY = zukY - 35

        if(debug) {
            println("[Zuk] Arena coordinates updated:")
            println("[Zuk] Zuk position: ($zukX, $zukY)")
            println("[Zuk] Jad safespot: $safespotJad")
            println("[Zuk] Normal safespot: $safespotNormal")
            println("[Zuk] Arena bounds: X($arenaMinX to $arenaMaxX), Y($arenaMinY to $arenaMaxY)")
        }
    } else {
        if(debug) println("[Zuk] Could not find Zuk DPS NPC, arena coordinates not updated")
    }
}

fun Zuk.updateFightState() {
    val newWave = getCurrentWave()
    if (currentWave != newWave) {
        if(debug) println("[Zuk] Wave changed from $currentWave to $newWave")
        onWaveChange(newWave)
    }
    currentWave = newWave
    currentTarget = npcs[localPlayer.interactionSid]
    isNormalWave = Zuk.Companion.REGULAR_WAVES.contains(newWave)
    isIgneousWave = Zuk.Companion.IGNEOUS_WAVES.contains(newWave)
    isJadWave = Zuk.Companion.JAD_WAVES.contains(newWave)
    isChallengeWave = Zuk.Companion.CHALLENGE_WAVES.contains(newWave)
    zukDpsCheckActive = Zuk.Companion.getZukDpsCheckActive()
    isPizzaPhase = Zuk.Companion.isPizzaPhaseActive()

    if (debug) {
        println("=== FIGHT STATE UPDATE ===")
        println("[WAVE] Current wave: $currentWave")
        println("[WAVE] Type - Normal: $isNormalWave, Igneous: $isIgneousWave, Jad: $isJadWave, Challenge: $isChallengeWave")
        println("[ZUKPHASE] Pizza phase: $isPizzaPhase, DPS check active: $zukDpsCheckActive")
        if (currentTarget != null) {
            println("[TARGET] Current target: ${currentTarget!!.name} (ID: ${currentTarget!!.id}, HP: ${currentTarget!!.currentHealth})")
        } else {
            println("[TARGET] No current target")
        }
    }
}

fun Zuk.onWaveChange(newWave: Int) {
    if(debug) println("[Zuk] Starting wave change to wave $newWave")

    if (newWave != 18) {
        if(debug) println("[Zuk] Wave $newWave is not final wave, checking ring equipment")

        inventory.getItem("Tokkul-Zo")?.let { ring ->
            if(debug) println("[Zuk] Found Tokkul-Zo ring in inventory")
            if (Equipment.Slot.getItem(Equipment.Slot.RING)?.name?.contains("Tokkul-Zo") != true) {
                if(debug) println("[Zuk] Equipping Tokkul-Zo ring")
                ring.click(1)
            } else {
                if(debug) println("[Zuk] Tokkul-Zo ring already equipped")
            }
        } ?: println("[Zuk] No Tokkul-Zo ring found in inventory")
    } else {
        if(debug) println("[Zuk] Wave 18 detected - final wave, no ring switch needed")
    }

    if(debug) println("[Zuk] Wave change completed for wave $newWave")
}

fun getCurrentWave(): Int {
    val wave = varps.getVar(10949) + 1

    return wave
}

fun areTargetsAlive(): Boolean {
    val allTargetIds = listOf(
	    Zuk.Companion.HUR,
	    Zuk.Companion.IGNEOUS_HUR,
	    Zuk.Companion.VOLATILE_HUR,
	    Zuk.Companion.MEJ,
	    Zuk.Companion.ZEK,
	    Zuk.Companion.IGNEOUS_MEJ,
	    Zuk.Companion.MEJKOT,
	    Zuk.Companion.XIL,
	    Zuk.Companion.TOK_XIL,
	    Zuk.Companion.IGNEOUS_XIL,
	    Zuk.Companion.KIH,
	    Zuk.Companion.JAD,
	    Zuk.Companion.UNBREAKABLE,
	    Zuk.Companion.FATAL_1,
	    Zuk.Companion.FATAL_2,
	    Zuk.Companion.FATAL_3,
	    Zuk.Companion.HAR_AKEN
    )

    return allTargetIds.any { id ->
        findClosestNPC(id, 40)?.let { npc ->
            npc.currentHealth > 0
        } ?: false
    }
}

fun Zuk.findNextBestTarget(): NPC? {
    if (Zuk.Companion.extraActionButtonVisible()) {
        return if (currentWave == 18) {
            findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)
        } else {
            findClosestNPC(Zuk.Companion.ZUK_DPS, 40)
        }
    }

    if (currentWave == 18) {
        val zuk = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)
        if (isPizzaPhase) {
            return getHighestPriorityTarget()
        }
        return zuk
    }

    if (currentWave == 17) {
        val harAken = findClosestNPC(Zuk.Companion.HAR_AKEN, 50)
        if (harAken != null && harAken.currentHealth > 0) {
            return harAken
        }
        return findClosestNPC(Zuk.Companion.ZUK_DPS, 50)
    }

    return getHighestPriorityTarget()
}

fun Zuk.getHighestPriorityTarget(): NPC? {
    val wavePriorities = Zuk.Companion.WAVE_PRIORITIES[currentWave] ?: return null
    var bestTarget: NPC? = null
    var bestScore = Double.MAX_VALUE

    for ((npcId, priority) in wavePriorities) {
        findClosestNPC(npcId, 50)?.let { npc ->
            if (npc.currentHealth > 0) {
                val animFactor = if (npc.isAnimating) 2.0 else 1.0
                val score = npc.tile.getDistance(localPlayer.tile) / (priority * animFactor)
                if (score < bestScore) {
                    bestScore = score
                    bestTarget = npc
                }
            }
        }
    }

    return bestTarget
}

fun Zuk.needsNewTarget(): Boolean {
    val target = npcs[localPlayer.interactionSid]
    return target == null || target.currentHealth <= 0 || shouldStopTargeting()
}

fun Zuk.shouldStopTargeting(): Boolean {
    val target = npcs[localPlayer.interactionSid] ?: return false

    if (target.id == Zuk.Companion.ZUK_DPS) {
        return (isChallengeWave && areTargetsAlive()) ||
                (currentWave == 17 && findClosestNPC(Zuk.Companion.HAR_AKEN, 40) != null) ||
                listOf(Zuk.Companion.IGNEOUS_HUR, Zuk.Companion.IGNEOUS_MEJ, Zuk.Companion.IGNEOUS_XIL).any {
                    (findClosestNPC(it, 40)?.currentHealth ?: 0) > 0
                }
    }

    if (target.id == Zuk.Companion.HAR_AKEN) {
        return (findClosestNPC(Zuk.Companion.HAR_AKEN, 40)?.currentHealth ?: 0) <= 0
    }

    return false
}

fun Zuk.needToBeNextToTarget(target: NPC?): Boolean {
    return target?.id == Zuk.Companion.IGNEOUS_MEJ ||
            (isPizzaPhase && (target?.id == Zuk.Companion.IGNEOUS_HUR || target?.id == Zuk.Companion.IGNEOUS_XIL))
}

suspend fun Zuk.moveWithinAreaOfTarget(target: NPC?) {
    if (target == null) {
        if(debug) println("[Zuk] moveWithinAreaOfTarget: Target is null, returning")
        return
    }

    if (lastClickedTarget == target) {
        if(debug) println("[Zuk] moveWithinAreaOfTarget: Already clicked target ${target.name}, skipping")
        return
    }

    val targetTile = target.tile
    val currentDistance = target.tile.getDistance(localPlayer.tile)
    val randomTile = Tile.of(
        (targetTile.x + random(-2, 2)),
        (targetTile.y + random(-2, 2)),
        0
    )

    if(debug) println("[Zuk] moveWithinAreaOfTarget: Moving to ${target.name} at $targetTile")
    if(debug) println("[Zuk] moveWithinAreaOfTarget: Current distance: $currentDistance tiles")
    if(debug) println("[Zuk] moveWithinAreaOfTarget: Random tile destination: $randomTile")

    if (currentDistance > 10) {
        if(debug) println("[Zuk] moveWithinAreaOfTarget: Distance > 10, attacking target first")
        target.interact("Attack")
        delay(1000)

        if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
            if(debug) println("[Zuk] moveWithinAreaOfTarget: Used Surge ability")
            delay(300)
            if(debug) println("[Zuk] moveWithinAreaOfTarget: Walking to random tile after surge")
            walkTo(randomTile, false)
        } else {
            if(debug) println("[Zuk] moveWithinAreaOfTarget: Surge not available, walking normally")
            walkTo(randomTile, false)
        }
    } else {
        if(debug) println("[Zuk] moveWithinAreaOfTarget: Distance <= 10, walking directly to random tile")
        walkTo(randomTile, false)
    }

    lastClickedTarget = target
    movingToTarget = true
    if(debug) println("[Zuk] moveWithinAreaOfTarget: Movement completed, set lastClickedTarget and movingToTarget flag")
}

suspend fun Zuk.attackTarget(target: NPC?) {
    if (target == null) {
        if(debug) println("[Zuk] attackTarget: Target is null, cannot attack")
        return
    }

    if (target.currentHealth <= 0) {
        if(debug) println("[Zuk] attackTarget: Target ${target.name} has no health (${target.currentHealth}), skipping attack")
        return
    }

    if(debug) println("[Zuk] attackTarget: Attacking ${target.name} (ID: ${target.id}, HP: ${target.currentHealth}) at ${target.tile}")
    target.interact("Attack")
    delay(600)
    if(debug) println("[Zuk] attackTarget: Attack interaction completed with 600ms delay")
}

suspend fun Zuk.goToSafespot(safespot: Tile) {
    val currentTile = localPlayer.tile
    val distance = safespot.getDistance(currentTile)

    if(debug) println("[Zuk] goToSafespot: Moving to safespot $safespot from current position $currentTile")
    if(debug) println("[Zuk] goToSafespot: Distance to safespot: $distance tiles")

    if (currentTile == safespot) {
        if(debug) println("[Zuk] goToSafespot: Already at safespot, returning")
        return
    }

    if(debug) println("[Zuk] goToSafespot: Starting walk to safespot")
    walkTo(safespot, false)
    delay(600)

    val newDistance = safespot.getDistance(localPlayer.tile)
    if(debug) println("[Zuk] goToSafespot: After initial walk, distance is now $newDistance tiles")

    if (newDistance > 12) {
        if(debug) println("[Zuk] goToSafespot: Distance still > 12, checking if Surge is available")
        if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
            if(debug) println("[Zuk] goToSafespot: Used Surge ability to get closer")
            delay(600)
            if(debug) println("[Zuk] goToSafespot: Walking to safespot again after surge")
            walkTo(safespot, false)
        } else {
            if(debug) println("[Zuk] goToSafespot: Surge not available, distance remains $newDistance")
        }
    }

    if(debug) println("[Zuk] goToSafespot: Waiting for movement to complete (max 4 seconds)")
    delayWhile(4000) { localPlayer.isMoving }

    val finalDistance = safespot.getDistance(localPlayer.tile)
    if(debug) println("[Zuk] goToSafespot: Movement completed, final distance: $finalDistance tiles")
}

fun Zuk.shouldHandleMechanics(): Boolean {
    return currentWave == 18 || currentWave == 17
}

fun Zuk.needsToMoveToSafespot(): Boolean {
    val currentTile = localPlayer.tile
    val tolerance = 3

    when {
        isJadWave -> {
            if (safespotJad == null) {
                if(debug) println("[Zuk] needsToMoveToSafespot: Jad wave but no Jad safespot defined")
                return false
            }
            val distance = currentTile.getDistance(safespotJad!!)
            if (distance > tolerance) {
                if(debug) println("[Zuk] needsToMoveToSafespot: Jad wave - distance to Jad safespot: $distance (> $tolerance)")
                return true
            }
        }

        else -> {
            if (safespotNormal == null) {
                if(debug) println("[Zuk] needsToMoveToSafespot: No normal safespot defined")
                return false
            }
            val distance = currentTile.getDistance(safespotNormal!!)
            if (distance > tolerance) {
                if(debug) println("[Zuk] needsToMoveToSafespot: Wave $currentWave - distance to normal safespot: $distance (> $tolerance)")
                return true
            }
        }
    }

    return false
}

suspend fun Zuk.moveToWaveSafespot() {
    if(debug) println("[Zuk] moveToWaveSafespot: Moving to safespot for wave $currentWave")

    when {
        isJadWave && safespotJad != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to Jad safespot for wave $currentWave: $safespotJad")
            goToSafespot(safespotJad!!)
        }

        isNormalWave && safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to normal safespot for wave $currentWave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        isIgneousWave && safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to normal safespot for igneous wave $currentWave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        isChallengeWave && safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to normal safespot for challenge wave $currentWave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        currentWave == 17 && safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to normal safespot for Har-Aken wave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        currentWave == 18 && safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to normal safespot for Zuk wave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        safespotNormal != null -> {
            if(debug) println("[Zuk] moveToWaveSafespot: Moving to default normal safespot for wave $currentWave: $safespotNormal")
            goToSafespot(safespotNormal!!)
        }

        else -> {
            if(debug) println("[Zuk] moveToWaveSafespot: No appropriate safespot found for wave $currentWave")
        }
    }
}

fun Zuk.needsToMoveToSafety(): Boolean {
    val quakeSpots = getAllSpotAnimsWithinRange(40) { it.id == 7450 }
    if (quakeSpots.isNotEmpty()) {
        if(debug) println("[Zuk] needsToMoveToSafety: Found ${quakeSpots.size} quake spots nearby")
        return true
    }

    val searDebuff = Effect.SEARING_PAIN.stacks
    if (searDebuff >= 15) {
        if (debug) println("[Zuk] needsToMoveToSafety: Searing Pain stacks >= 15 ($searDebuff)")
        return true
    }

    val dangerousProjectiles = projectiles.filter { projectile ->
        projectile.lockedOnto(localPlayer) && projectile.tile.getDistance(localPlayer.tile) <= 3 &&
        (projectile.id == 2733 || projectile.id == 2734)
    }
    if (dangerousProjectiles.isNotEmpty()) {
        if(debug) println("[Zuk] needsToMoveToSafety: Found ${dangerousProjectiles.size} dangerous projectiles nearby")
        return true
    }

    return false
}

suspend fun Zuk.handleSafetyMovement() {
    if(debug) println("[Zuk] handleSafetyMovement: Starting safety movement handling")

    val quakeSpots = getAllSpotAnimsWithinRange(20) { it.id == 7450 }
    if (quakeSpots.isNotEmpty()) {
        if(debug) println("[Zuk] handleSafetyMovement: Handling ${quakeSpots.size} quake spots")
        val zuk = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)
        val safeTile = if (zuk != null) {
            findSafeTileFromQuakes(quakeSpots, zuk.tile)
        } else {
            findSafeTileFromQuakes(quakeSpots, localPlayer.tile)
        }

        if(debug) println("[Zuk] handleSafetyMovement: Moving to safe tile: $safeTile")

        if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
            if(debug) println("[Zuk] handleSafetyMovement: Used Surge ability to escape searing pain")
            delay(200)
        } else {
            if(debug) println("[Zuk] handleSafetyMovement: Surge not available, walking to safe tile")
            goToSafespot(safeTile)
        }
        return
    }

    val searDebuff = Effect.SEARING_PAIN.stacks
    if (searDebuff >= 15) {
        if(debug) println("[Zuk] handleSafetyMovement: Handling high searing pain stacks: $searDebuff")
        if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
            if(debug) println("[Zuk] handleSafetyMovement: Used Surge ability to escape searing pain")
            delay(600)
        } else {
            val zuk = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)
            if (zuk != null) {
                val moveTile = Tile.of(zuk.tile.x, zuk.tile.y + (searDebuff / 2), 0)
                if (debug) println("[Zuk] handleSafetyMovement: Moving away from searing pain to $moveTile")
                goToSafespot(moveTile)
            }
        }
        return
    }
}

suspend fun Zuk.handleZukMechanics() {
    if (currentWave != 18) {
        if(debug) println("[Zuk] handleZukMechanics: Not on wave 18 (current: $currentWave), skipping Zuk mechanics")
        return
    }

    if(debug) println("[Zuk] handleZukMechanics: Starting Zuk mechanics handling for wave 18")

    val zuk = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)
    if (zuk == null) {
        if(debug) println("[Zuk] handleZukMechanics: No Zuk found within 40 tiles")
        return
    }

    if(debug) println("[Zuk] handleZukMechanics: Found Zuk at ${zuk.tile}, animation: ${zuk.animationId}")

    val searDebuff = Effect.SEARING_PAIN.stacks
    if (searDebuff > 0) {
        if (debug) println("[Zuk] handleZukMechanics: Searing Pain debuff detected with $searDebuff stacks")

        if (searDebuff >= 15) {
            if (debug) println("[Zuk] handleZukMechanics: Searing Pain stacks >= 15, attempting to use Surge ability")
            if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
                if (debug) println("[Zuk] handleZukMechanics: Successfully used Surge ability to escape searing pain")
                delay(600)
            } else {
                if (debug) println("[Zuk] handleZukMechanics: Surge ability not available or failed to cast")
            }
        } else if (searDebuff < 15 && !localPlayer.isMoving) {
            val moveTile = Tile.of(zuk.tile.x, zuk.tile.y + (searDebuff / 2), 0)
            if (debug) println("[Zuk] handleZukMechanics: Moving slightly to manage searing pain (stacks: $searDebuff) to $moveTile")
            walkTo(moveTile, true)
            delay(100)
        } else {
            if (debug) println("[Zuk] handleZukMechanics: Player is moving, not adjusting position for searing pain")
        }
    } else {
        if (debug) println("[Zuk] handleZukMechanics: No searing pain debuff detected")
    }

    val quakeSpots = getAllSpotAnimsWithinRange(20) { it.id == 7450 }
    if (quakeSpots.isNotEmpty()) {
        if (debug) println("[Zuk] handleZukMechanics: Found ${quakeSpots.size} quake spot animations")
        quakeSpots.forEach { spot ->
            if (debug) println("[Zuk] handleZukMechanics: Quake spot at ${spot.tile} with ID ${spot.id}")
        }

        val safeTile = findSafeTileFromQuakes(quakeSpots, zuk.tile)
        if (debug) println("[Zuk] handleZukMechanics: Calculated safe tile from quakes: $safeTile")

        if (Ability.SURGE.offCdIgnoreGCD && castAbility(Ability.SURGE)) {
            if (debug) println("[Zuk] handleZukMechanics: Used Surge ability to escape quakes")
            delay(200)
        } else {
            if (debug) println("[Zuk] handleZukMechanics: Surge not available, walking to safe tile")
            walkTo(safeTile, true)
        }
        delay(200)
    } else {
        if (debug) println("[Zuk] handleZukMechanics: No quake spots detected")
    }

    if (debug) println("[Zuk] handleZukMechanics: Zuk mechanics handling completed")
}

suspend fun Zuk.handleHarAkenMechanics() {
    if (currentWave != 17) {
        if(debug) println("[Zuk] handleHarAkenMechanics: Not on wave 17 (current: $currentWave), skipping Har-Aken mechanics")
        return
    }

    if(debug) println("[Zuk] handleHarAkenMechanics: Starting Har-Aken mechanics handling for wave 17")

    val lavaBlobs = getAllSpotAnimsWithinRange(5) { it.id == 7585 }
    if (lavaBlobs.isNotEmpty()) {
        if(debug) println("[Zuk] handleHarAkenMechanics: Found ${lavaBlobs.size} lava blob animations")
        lavaBlobs.forEach { blob ->
            if(debug) println("[Zuk] handleHarAkenMechanics: Lava blob at ${blob.tile} with ID ${blob.id}")
        }

        val safeTile = findSafeTileFromLavaBlobs(lavaBlobs)
        if(debug) println("[Zuk] handleHarAkenMechanics: Calculated safe tile from lava blobs: $safeTile")

        if(debug) println("[Zuk] handleHarAkenMechanics: Walking to safe tile to avoid lava blobs")
        walkTo(safeTile, true)
        delay(200)
    } else {
        if(debug) println("[Zuk] handleHarAkenMechanics: No lava blobs detected")
    }

    if(debug) println("[Zuk] handleHarAkenMechanics: Har-Aken mechanics handling completed")
}

fun Zuk.findSafeTileFromQuakes(quakeSpots: List<SpotAnim>, zukTile: Tile): Tile {
    val currentTile = localPlayer.tile
    val randomOffset = random(-3, 3)
    val safeTile = Tile.of(
        (currentTile.x + randomOffset),
        (currentTile.y + randomOffset),
        0
    )

    if(debug) println("[Zuk] findSafeTileFromQuakes: Current tile: $currentTile, Zuk tile: $zukTile")
    if(debug) println("[Zuk] findSafeTileFromQuakes: Generated safe tile: $safeTile (offset: $randomOffset)")
    if(debug) println("[Zuk] findSafeTileFromQuakes: Quake spots to avoid: ${quakeSpots.map { it.tile }}")

    return safeTile
}

fun Zuk.findSafeTileFromLavaBlobs(lavaBlobs: List<SpotAnim>): Tile {
    val currentTile = localPlayer.tile
    val randomOffset = random(-3, 3)
    val safeTile = Tile.of(
        (currentTile.x + randomOffset),
        (currentTile.y + randomOffset),
        0
    )

    if(debug) println("[Zuk] findSafeTileFromLavaBlobs: Current tile: $currentTile")
    if(debug) println("[Zuk] findSafeTileFromLavaBlobs: Generated safe tile: $safeTile (offset: $randomOffset)")
    if(debug) println("[Zuk] findSafeTileFromLavaBlobs: Lava blobs to avoid: ${lavaBlobs.map { it.tile }}")

    return safeTile
}

suspend fun Zuk.manageBuff() {
    if (System.currentTimeMillis() - lastBuffCheck < 1000) return

    if (healthCurrent < random(2500, 5000)) {
        inventory.getItem(foodName.value)?.click(1)
        delay(200)
        inventory.getItem(foodPotNamePattern.pattern())?.click("Drink")
    }

    if (prayerPoints < random(200, 400)) {
        inventory.getItem(restoreNamePattern.pattern())?.click("Drink")
        delay(300)
    }

    if (Effect.OVERLOADED.notActive) {
        inventory.getItem(overloadNamePattern.pattern())?.click("Drink")
        delay(300)
    }

    if (usePoison.value && Effect.POISONOUS.notActive) {
        inventory.getItem("Weapon poison")?.click(1)
        delay(300)
    }

    if (useExcalibur.value && System.currentTimeMillis() - lastExcalUse > 300000) {
        if (healthCurrent < random(5500, 7500)) {
            inventory.getItem("Excalibur")?.click(1)
            lastExcalUse = System.currentTimeMillis()
        }
    }

    if (Effect.DARKNESS.notActive) {
        smartCast(Ability.DARKNESS)
    }

    if (Effect.BONE_SHIELD.notActive) {
        smartCast(Ability.LESSER_BONE_SHIELD)
    }

    lastBuffCheck = System.currentTimeMillis()
}

class ZukCombatRotation : Script() {
    override suspend fun loop() {
        if (!inInstancedArea || !inCombat) return

        findClosestNPC(Zuk.Companion.ZUK_FIGHT, 50)

        val currentWave = varps.getVar(10949) + 1
        npcs[localPlayer.interactionSid]

        when {
            Zuk.Companion.REGULAR_WAVES.contains(currentWave) || Zuk.Companion.JAD_WAVES.contains(currentWave) -> waveClearRotation()
        }
    }

    private suspend fun waveClearRotation() {
        val targetHp = npcs[localPlayer.interactionSid]?.currentHealth ?: 0
         println("[Zuk] waveClearRotation: Current target HP: $targetHp")

        if (!targetDeathMarked() && !invokeDeathActive() && targetHp <= 20000) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (Zuk.worthSkullingOrThreading(2) && !deathSkullsActive() && soulStacks() >= 2) {
            if (smartCast(Ability.THREADS_OF_FATE)) return
        }

        if (Zuk.worthSkullingOrThreading(3)) {
            if (smartCast(Ability.DEATH_SKULLS)) return
        }

        if (targetHp >= 20000 && !targetBloated()) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (targetHp >= 10000 && soulStacks() >= 3) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (targetHp >= 10000 && necrosisStacks() >= 6) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (targetHp >= 10000 && necrosisStacks() in 1..5 && !specAttackOnCooldown()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.CONJURE_UNDEAD_ARMY)) return
        if (Effect.CONJURE_VENGEFUL_GHOST.active) {
            (smartCast(Ability.COMMAND_VENGEFUL_GHOST))
            return
        }

        if (Effect.CONJURE_SKELETON_WARRIOR.active)
        {
            (smartCast(Ability.COMMAND_SKELETON_WARRIOR))
            return
        }
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private fun inThreadsRotation(): Boolean = Effect.THREADS_OF_FATE.active
    private fun targetDeathMarked(): Boolean = varps.getVarBit(11303) and (1 shl 7) != 0
    private fun invokeDeathActive(): Boolean = Effect.INVOKE_DEATH.active

    private fun deathSkullsActive(): Boolean = getAllSpotAnimsWithinRange(12) { it.id == 7882 }.isNotEmpty()
    private fun soulStacks(): Int = Effect.RESIDUAL_SOUL.stacks
    private fun targetBloated(): Boolean = varps.getVarBit(11303) and (1 shl 5) != 0
    private fun necrosisStacks(): Int = Effect.NECROSIS.stacks
    private fun specAttackOnCooldown(): Boolean = Effect.WEAPON_SPECIAL_ATTACK.active
    private fun areTargetsAlive(targetIds: List<Int>): Boolean = targetIds.any { id ->
        (findClosestNPC(
            id,
            40
        )?.currentHealth ?: 0) > 0
    }
    private fun isPizzaPhaseActive(): Boolean = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 40)?.animationId in listOf(34495, 34501, 34502, 34505)
    private fun extraActionButtonVisible(): Boolean = varps.getVarBit(10254) == 3
    private fun clickExtraActionButton() = IFSlot(743, 1, -1).click(1)
    private fun zukStartFightAnimation(): Boolean = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 30)?.animationId in listOf(34518, 34494)
    private fun getZukDpsCheckActive(): Boolean = findClosestNPC(Zuk.Companion.ZUK_DPS, 30)?.animationId == 34516

    private suspend fun threadsRotation() {
        if (!inThreadsRotation()) {
            if (smartCast(Ability.THREADS_OF_FATE)) return
        }

        if (soulStacks() >= 2) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (necrosisStacks() in 1..5 && !specAttackOnCooldown()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.SOUL_SAP)) return

        if (necrosisStacks() >= 6) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun thresholdRotation() {
        val targetHp = npcs[localPlayer.interactionSid]?.currentHealth ?: 0

        if (!targetDeathMarked() && !invokeDeathActive() && targetHp >= 20000) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (!targetBloated()) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (necrosisStacks() >= 6) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (necrosisStacks() >= 4 && !targetBloated()) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (targetHp > 20000 && !targetBloated()) {
            if (smartCast(Ability.SPECTRAL_SCYTHE)) return
        }

        if (targetHp > 5000 && necrosisStacks() in 1..5 && !specAttackOnCooldown()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (soulStacks() >= 2) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (smartCast(Ability.CONJURE_UNDEAD_ARMY)) return
        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun stunRotation() {
        val targetHp = npcs[localPlayer.interactionSid]?.currentHealth ?: 0

        if (!targetDeathMarked() && !invokeDeathActive() && targetHp >= 20000) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (!specAttackOnCooldown() && !Zuk.Companion.targetStunnedOrBound()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (!Zuk.Companion.targetStunnedOrBound()) {
            if (smartCast(Ability.SOUL_STRIKE)) return
        }

        if (!Zuk.Companion.targetStunnedOrBound()) {
            if (smartCast(Ability.SOUL_SAP)) return
        }

        if (!targetBloated()) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (necrosisStacks() >= 4) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun zukFightRotation() {
        val zuk = findClosestNPC(Zuk.Companion.ZUK_FIGHT, 30)

        if (Effect.GEOTHERMAL_BURN.active || Effect.STUNNED.active) {
            if (smartCast(Ability.FREEDOM)) return
        }

        if (zuk?.animationId == 34499) {
            if (smartCast(Ability.RESONANCE)) return
        }

        if (zuk?.animationId == 34493) {
            if (smartCast(Ability.ANTICIPATION)) return
        }

        if (!Zuk.Companion.targetVulned() && shouldUseVulnBomb()) {
            if (useVulnerabilityBomb()) return
        }

        if (adrenaline < 60 && !Effect.ADRENALINE_RENEWAL.active) {
            if (useAdrenalinePotion()) return
        }

        if (!targetDeathMarked() && !invokeDeathActive()) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (smartCast(Ability.LIVING_DEATH)) return
        if (smartCast(Ability.DEATH_SKULLS)) return

        if (!targetBloated() && Effect.LIVING_DEATH.notActive) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (necrosisStacks() >= 6 && Effect.LIVING_DEATH.notActive) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (soulStacks() >= 3 && Effect.LIVING_DEATH.notActive) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (!specAttackOnCooldown() && Effect.LIVING_DEATH.notActive && necrosisStacks() in 1..5) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.CONJURE_UNDEAD_ARMY)) return
        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun buildAdrenRotationBeforeZuk() {
        if (inventory.hasItem(RING_SWITCH)) {
            if (equipItem(RING_SWITCH)) {
                delay(400)
                return
            }
        }

        if (!targetDeathMarked() && !invokeDeathActive()) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (smartCast(Ability.CONJURE_UNDEAD_ARMY)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun harAkenRotation() {
        val targetHp = npcs[localPlayer.interactionSid]?.currentHealth ?: 0

        if (!Zuk.Companion.targetVulned() && shouldUseVulnBomb()) {
            if (useVulnerabilityBomb()) return
        }

        if (adrenaline < 60 && !Effect.ADRENALINE_RENEWAL.active) {
            if (useAdrenalinePotion()) return
        }

        if (!targetDeathMarked() && !invokeDeathActive()) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (smartCast(Ability.SPLIT_SOUL)) return

        if (targetHp > 60000) {
            if (smartCast(Ability.DEATH_SKULLS)) return
        }

        if (!targetBloated()) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (necrosisStacks() >= 6) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (soulStacks() >= 3) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (!specAttackOnCooldown() && necrosisStacks() in 1..5) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.CONJURE_UNDEAD_ARMY)) return
        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun buildAdrenRotation() {
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun zukDpsCheckRotation() {
        if (inventory.hasItem(RING_SWITCH)) {
            if (equipItem(RING_SWITCH)) {
                delay(500)
                return
            }
        }

        if (!Zuk.Companion.targetVulned() && shouldUseVulnBomb()) {
            if (useVulnerabilityBomb()) return
        }

        if (smartCast(Ability.DEATH_SKULLS)) return

        if (soulStacks() >= 3) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (necrosisStacks() >= 6 && !deathSkullsActive()) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (!targetBloated() && !deathSkullsActive()) {
            if (smartCast(Ability.BLOAT)) return
        }

        if (!specAttackOnCooldown() && !deathSkullsActive()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.COMMAND_VENGEFUL_GHOST)) return
        if (smartCast(Ability.COMMAND_SKELETON_WARRIOR)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun challenge2Rotation() {
        if (!Zuk.Companion.targetVulned() && shouldUseVulnBomb()) {
            if (useVulnerabilityBomb()) return
        }

        if (adrenaline < 60 && !Effect.ADRENALINE_RENEWAL.active) {
            if (useAdrenalinePotion()) return
        }

        if (!targetDeathMarked() && !invokeDeathActive()) {
            if (smartCast(Ability.INVOKE_DEATH)) return
        }

        if (smartCast(Ability.DEATH_SKULLS)) return

        if (necrosisStacks() >= 6) {
            if (smartCast(Ability.FINGER_OF_DEATH)) return
        }

        if (soulStacks() >= 3) {
            if (smartCast(Ability.VOLLEY_OF_SOULS)) return
        }

        if (!specAttackOnCooldown()) {
            if (smartCast(Ability.WEAPON_SPECIAL_ATTACK)) return
        }

        if (smartCast(Ability.FINGER_OF_DEATH)) return
        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private suspend fun challenge3Rotation() {
        if (adrenaline < 100 && !Effect.ADRENALINE_RENEWAL.active) {
            if (useAdrenalinePotion()) return
        }

        if (!Effect.BARRICADE.active && Effect.DEVOTION.notActive) {
            if (smartCast(Ability.BARRICADE)) return
        }

        if (!Effect.RESONANCE.active && !Effect.BARRICADE.active) {
            if (smartCast(Ability.RESONANCE)) return
        }

        if (!Effect.BARRICADE.active && !Effect.RESONANCE.active) {
            if (smartCast(Ability.DEVOTION)) return
        }

        if (!Effect.BARRICADE.active && Effect.DEVOTION.notActive &&
            !Effect.RESONANCE.active && !Effect.POWERBURST_OF_LIFEFORCE.active) {
            if (usePowerburstOfVitality()) return
        }

        if (smartCast(Ability.TOUCH_OF_DEATH)) return
        if (smartCast(Ability.SOUL_SAP)) return
        smartCast(Ability.NECRO_BASIC_ATTACK)
    }

    private fun shouldUseVulnBomb(): Boolean {
        return true
    }

    private fun useVulnerabilityBomb(): Boolean {
        return false
    }

    private fun useAdrenalinePotion(): Boolean {
        return false
    }

    private fun usePowerburstOfVitality(): Boolean {
        return false
    }

    private fun equipItem(itemId: Int): Boolean {
        return false
    }

    companion object {
        private const val RING_SWITCH = 123456
    }
}

class ZukPrayerSwitcher : Script() {
    private var activePrayer: Prayer = if(onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL
    private var lastPrayerSwitch: Long = 0

    override suspend fun loop() {
        if (prayerPoints <= 0 || !canUseProtectionPrayers) return

        val now = System.currentTimeMillis()
        if (now - lastPrayerSwitch < 50) return

        val currentTarget = npcs[localPlayer.interactionSid]
        var targetPrayer = if(onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL

        when {
            currentTarget?.id == Zuk.Companion.JAD -> {
                when (currentTarget.animationId) {
                    16195 -> targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MAGIC else Prayer.DEFLECT_MAGIC
                    16202 -> targetPrayer = if (onStandardPrayers) Prayer.PROTECT_RANGED else Prayer.DEFLECT_RANGE
                    16204 -> targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MELEE else Prayer.DEFLECT_MELEE
                }
            }

            currentTarget?.id == Zuk.Companion.ZUK_FIGHT -> {
                when (currentTarget.animationId) {
                    34496, 34497, 34498 -> targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MELEE else Prayer.DEFLECT_MELEE
                    34501, 34499 -> targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MAGIC else Prayer.DEFLECT_MAGIC
                }
            }

            areTargetsAlive(listOf(
	            Zuk.Companion.HUR,
	            Zuk.Companion.IGNEOUS_HUR,
	            Zuk.Companion.KIH,
	            Zuk.Companion.MEJKOT,
	            Zuk.Companion.UNBREAKABLE
            )) -> {
                targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MELEE else Prayer.DEFLECT_MELEE
            }

            areTargetsAlive(listOf(Zuk.Companion.ZEK)) -> {
                targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MAGIC else Prayer.DEFLECT_MAGIC
            }

            areTargetsAlive(listOf(Zuk.Companion.XIL, Zuk.Companion.TOK_XIL, Zuk.Companion.IGNEOUS_XIL)) -> {
                targetPrayer = if (onStandardPrayers) Prayer.PROTECT_RANGED else Prayer.DEFLECT_RANGE
            }

            areTargetsAlive(listOf(Zuk.Companion.MEJ)) -> {
                targetPrayer = if (onStandardPrayers) Prayer.PROTECT_MAGIC else Prayer.DEFLECT_MAGIC
            }
        }

        if (targetPrayer != activePrayer && !targetPrayer.active) {
            targetPrayer.click()
            activePrayer = targetPrayer
            lastPrayerSwitch = now
        }
    }

    private fun areTargetsAlive(targetIds: List<Int>): Boolean {
        return targetIds.any { id ->
            (findClosestNPC(id, 50)?.currentHealth ?: 0) > 0
        }
    }
}
