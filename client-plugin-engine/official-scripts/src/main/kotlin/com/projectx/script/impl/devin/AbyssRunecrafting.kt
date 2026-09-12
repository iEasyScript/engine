package com.projectx.script.impl.devin

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import org.projectx.core.game.combat.Ability
import com.projectx.game.interfaces.Bank
import org.projectx.core.game.combat.Effect
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.XPDrop
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import com.projectx.util.formatElapsedTime
import com.projectx.util.gaussian
import com.projectx.util.getFormattedXpPerHour
import com.projectx.util.getUnitsPerHour
import com.projectx.util.random

@ScriptDescription(
    name = "Abyssal Runecrafting",
    version = "1.0.0",
    author = "Devin",
    description = "Basic AIO runecrafting through the abyss.",
    visible = true
)
class AbyssRunecrafting : StateMachineScript<AbyssRunecrafting>(), ConfigurableScript {

    private val rune = EnumConfigItem(
        name = "Runes",
        description = "Select the rune you would like to craft.",
        enumValues = Altar.entries.toTypedArray(),
        initialValue = Altar.SPIRIT
    )

    private val useAttuner = BooleanConfigItem(
        name = "Use Attuner",
        description = "Using runic attuner?",
        initialValue = true
    )

    var startTime = 0L
    var startingXp = 0
    private var useFamiliar: Boolean = true

    private var completedRuns = 0
    private var totalRunes = 0
    private var threadsObtained = 0

    private val cachedHasEssence = TickCached { inventory.any { it.name.endsWith(" essence") } }
    val hasEssence get() = cachedHasEssence.get()

    private val cachedNearRift = TickCached { findClosestReachableObject("${rune.value.displayName} rift", 40) != null }
    val nearRift get() = cachedNearRift.get()

    private val cachedNearDarkMage = TickCached { findClosestReachableNPC("Dark mage", 40) != null }
    val nearDarkMage get() = cachedNearDarkMage.get()

    private val wildernessSwords = intArrayOf(37904, 37905, 37906, 37907, 41376, 41377)

    companion object {
        const val MAPSQUARE_ABYSS = 12107
        const val MAPSQUARE_EDGEVILLE = 12342
        const val MAPSQUARE_WILDERNESS = 12343
    }

    private val cachedHasDamagedPouches = TickCached { false }
    val hasDamagedPouches get() = cachedHasDamagedPouches.get()

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.RUNECRAFTING)
    }

    object TeleportToBank : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? {
            return if (localPlayer.tile.mapSquareId == MAPSQUARE_EDGEVILLE) {
                if (hasEssence) JumpTheWALL()
                else if (useFamiliar && !Effect.FAMILIAR_SUMMONED.active) BankForFamiliar
                else BankForEssence
            } else null
        }

        override suspend fun AbyssRunecrafting.stateLoop() {
            if (inventory.hasItem(*wildernessSwords) && inventory.getItem(*wildernessSwords)?.click(1) == true) {
                delayUntil(3000) { equipment.hasItem(*wildernessSwords) }
                return
            }
            if (equipment.hasItem(*wildernessSwords) && equipment.getItem(*wildernessSwords)?.click(2) != true)
                if (!castAbility(Ability.EDGEVILLE_LODESTONE)) {
                    println("Unable to teleport back to Edgeville.")
                }
            delayUntil(2_400) { localPlayer.tile.mapSquareId == MAPSQUARE_EDGEVILLE }
            delay(800, 100)
        }
    }

    object BankForFamiliar : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? {
            return when {
                !useFamiliar || Effect.FAMILIAR_SUMMONED.active -> BankForEssence
                else -> null
            }
        }
        override suspend fun AbyssRunecrafting.stateLoop() {
            if (localPlayer.tile.mapSquareId == MAPSQUARE_EDGEVILLE) {
                val bank = findClosestObjectToTile(Tile.of(3097, 3497, 0), 30) { it.name() == "Counter" && it.hasOption("Load Last Preset from") }

                if (inventory.count("Abyssal titan pouch") <= 0) {
                    if (!localPlayer.isMoving && !bankOpen && bank != null && bank.interact("Bank"))
                        delayUntil(3_000) { interfaces.isOpen(Bank.BANK_INTERFACE_ID) }
                    if (bankOpen) {
                        if (withdrawBankItem("Abyssal titan pouch", 1))
                            delay(600)
                        else
                            useFamiliar = false
                    }
                } else {
                    if (!localPlayer.isMoving) {
                        val nearObelisk = Tile.of(3125, 3517, 0).randomize(2)
                        walkTo(nearObelisk, true)
                        delayUntil(5_000) { localPlayer.tile.withinDistance(nearObelisk, 3) }
                        return
                    }
                    if (findClosestReachableObject("Small obelisk", 10)?.interact("Renew points") == true) {
                        delayUntil { !localPlayer.isMoving }
                        delay(1_800, 534)
                        inventory.getItem("Abyssal titan pouch")?.click("Summon")
                        delay(1_200, 446)
                        equipment.getItem(*wildernessSwords)?.click(2)
                        return
                    }
                }
            }
        }
    }

    object BankForEssence : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (hasEssence) JumpTheWALL() else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            if (localPlayer.tile.mapSquareId == MAPSQUARE_EDGEVILLE) {
                val bank = findClosestObjectToTile(Tile.of(3097, 3497, 0), 30) { it.name() == "Counter" && it.hasOption("Load Last Preset from") }
                if (bank != null && bank.interact("Load Last Preset from")) {
                    delayUntil(random(1200, 1500).toLong()) { localPlayer.isMoving }
                    delayUntil(10000) { hasEssence }
                }
            }
        }
    }

    private val wallSurgeTile1 = Tile.of(3101, 3511, 0)
    private val wallTile = Tile.of(3101, 3520, 0)

    class JumpTheWALL : State<AbyssRunecrafting>() {
        var inWildy = false
        var atAltar = false
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (atAltar) CraftRunes else if (inWildy) GoToZammyMage else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            if (findClosestObject("${rune.value.displayName} altar") != null)
                atAltar = true
            if (useAttuner.value)
                attunedAltar?.let { rune.value = if (it == Altar.ALL_ALTARS) if (runicAttunerStacks >= 49) Altar.SOUL else Altar.BLOOD else it }
            if (useAttuner.value && attunerTeleportAvailable) {
                if (equipment.clickItem("Runic attuner", "Teleport"))
                    delayUntil(gaussian(7229L, 1502L)) { findClosestObject("${rune.value.displayName} altar") != null }
                return
            }
            if (localPlayer.tile.y > 3521) {
                inWildy = true
                return delay(110, 85)
            }

            if (localPlayer.tile.getDistance(wallTile) <= 12 && interactClosestObject(65082, "Cross")) {
                delayUntil(gaussian(15692L, 1105L)) { localPlayer.tile.y > 3521 || interfaces.isOpen(382) }
                return
            }

            if (localPlayer.tile.x < wallSurgeTile1.x && walkTo(wallSurgeTile1.randomizeY(1), true)) {
                delayUntil(gaussian(6220L, 1105L)) { localPlayer.tile.getDistance(wallSurgeTile1) <= 2 }
                if (surge())
                    delay(450, 200)
                return
            }
            delay(110, 85)
        }
    }

    private val mageTile = Tile.of(3105, 3558, 0)

    object GoToZammyMage : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (localPlayer.tile.y <= 3521) JumpTheWALL() else if (localPlayer.tile.mapSquareId == MAPSQUARE_ABYSS) EnterAbyssCore else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            val closeToTarget = localPlayer.tile.withinDistance(mageTile, 10)

            if (localPlayer.tile.getDistance(wallTile) <= 4 && surge())
                delay(450, 200)

            if (!closeToTarget && !localPlayer.isMoving) {
                println("Walking to target tile $mageTile")
                walkTo(mageTile.randomize(2), minimap = true)
                delayUntil(3_000) { localPlayer.isMoving }
                return
            }

            if (localPlayer.isMoving) {
                if (findClosestNPC("Mage of Zamorak", 12)?.tile?.randomize(1)?.let { dive(it) } == true) {
                    delayUntil(1200) { localPlayer.isAnimating }
                    delay(400, 100)
                    println("Diving toward Mage")
                }
                return
            }

            if (findClosestNPC("Mage of Zamorak")?.interact("Teleport") == true) {
                println("Interacting with Mage of Zamorak")
                delayUntil(5_000) { localPlayer.tile.mapSquareId == MAPSQUARE_ABYSS }
                return
            }
            delay(100, 50)
        }
    }

    object EnterAbyssCore : State<AbyssRunecrafting>() {
        private val obstacles = listOf(
            Obstacle("Tendrils", "Chop", Skill.WOODCUTTING),
            Obstacle("Rock", "Mine", Skill.MINING),
            Obstacle("Eyes", "Distract", Skill.THIEVING),
            Obstacle("Gap", "Squeeze-through", Skill.AGILITY),
            Obstacle("Boil", "Burn-down", Skill.FIREMAKING),
            Obstacle("Passage", "Walk-through")
        )

        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? {
            return when {
                hasDamagedPouches && nearDarkMage -> RepairPouches
                !hasDamagedPouches && (nearDarkMage || nearRift) -> TraverseToAltar
                else -> null
            }
        }

        override suspend fun AbyssRunecrafting.stateLoop() {
            val candidateObjects = obstacles
                .filter { it.skill == null || getCurrentLevel(it.skill) >= it.level }
                .mapNotNull { obs ->
                    findClosestObject(20) { it.name() == obs.name && it.hasOption(obs.option) }
                        ?.let { obj -> Triple(obj, obs, obj.tile.getDistance(localPlayer.tile)) }
                }

            val closest = candidateObjects.minByOrNull { it.third }

            if (closest != null) {
                val (obj, obs, _) = closest
                println("Interacting with ${obj.name()} via ${obs.option}")
                if (obj.interact(obs.option)) {
                    if (obs.skill != null)
                        waitForXPDrop(obs.skill)
                    else
                        delayUntil(gaussian(5000L, 2250L)) { nearRift || !localPlayer.isAnimating }
                }
            } else {
                println("No valid obstacle found")
                delay(1_000)
            }
        }

        data class Obstacle(val name: String, val option: String, val skill: Skill? = null, val level: Int = 30)
    }

    object RepairPouches : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (!hasDamagedPouches) TraverseToAltar else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            // TODO: Implement actual NPC dialogue repair logic
        }
    }

    object TraverseToAltar : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (localPlayer.tile.mapSquareId != MAPSQUARE_ABYSS) CraftRunes else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            val rift = findClosestReachableObject(40) { it.name() == "${rune.value.displayName} rift" }
            if (rift == null) {
                println("Cannot find the ${rune.value} rift")
                delay(300, 100)
                return
            }

            if (!interactClosestReachableObject("${rune.value.displayName} rift", "Exit-through")) {
                walkTo(rift.tile.randomize(3), true)
                delay(3592, 1000)
                return
            } else
                delayUntil(15000) { localPlayer.tile.mapSquareId != MAPSQUARE_ABYSS }
        }
    }

    class CraftSoulAltar : State<AbyssRunecrafting>() {
        var done = false

        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (done) TeleportToBank else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            if (soulAltarCharges < 100) {
                if (!hasEssence && soulAltarEss < 4) {
                    done = true
                    return
                }
                if (hasEssence && soulAltarEss < 4) {
                    if (interactClosestObject("Charger", "Deposit"))
                        delayUntil(gaussian(8000L, 1000L)) { !hasEssence }
                    return
                }
                if (soulAltarEss >= 4) {
                    if (interactClosestObject("Charger", "Charge altar"))
                        delayUntil(gaussian(200000L, 1000L)) { soulAltarEss < 4 || soulAltarCharges >= 100 }
                    return
                }
                return
            }

            if (localPlayer.tile.mapSquareId != MAPSQUARE_EDGEVILLE && localPlayer.tile.mapSquareId != MAPSQUARE_WILDERNESS &&
                !Effect.POWERBURST_POTION_IS_ON_COOLDOWN.active && !Effect.POWERBURST_OF_SORCERY.active) {
                val powerburst = inventory.find { it.name.contains("Powerburst of sorcery", ignoreCase = true) }
                if (powerburst != null && powerburst.click("Drink")) {
                    println("Drinking Powerburst of Sorcery: ${powerburst.name}")
                    delayUntil(2_000) { Effect.POWERBURST_OF_SORCERY.active }
                    return
                }
            }

            if (inventory.clickItem("Crystal binding rod", 2)) {
                delayUntil(2000) { equipment.hasItem("Crystal binding rod") }
                return
            }

            if (!localPlayer.isAniMoving && soulAltarCharges >= 100 && interactClosestReachableObject("${rune.value.displayName} altar", "Use"))
                waitForXPDrop(Skill.RUNECRAFTING, 5_000)
        }

        override fun AbyssRunecrafting.onStateEvent(event: Event) {
            if (event is XPDrop && event.skill == Skill.RUNECRAFTING) {
                completedRuns++
                totalRunes += inventory.count("${rune.value.displayName} rune")
                threadsObtained += inventory.count("Magical thread")
            }
        }
    }

    object CraftRunes : State<AbyssRunecrafting>() {
        override suspend fun AbyssRunecrafting.checkNext(): State<AbyssRunecrafting>? = if (rune.value == Altar.SOUL) CraftSoulAltar() else if (!hasEssence) TeleportToBank else null
        override suspend fun AbyssRunecrafting.stateLoop() {
            if (rune.value == Altar.TIME && !localPlayer.isAniMoving && Ability.SURGE.offCdIgnoreGCD) {
                surge()
                delayUntil(2000) { localPlayer.isAnimating }
            }

            if (localPlayer.tile.mapSquareId != MAPSQUARE_EDGEVILLE && localPlayer.tile.mapSquareId != MAPSQUARE_WILDERNESS &&
                !Effect.POWERBURST_POTION_IS_ON_COOLDOWN.active && !Effect.POWERBURST_OF_SORCERY.active) {
                val powerburst = inventory.find { it.name.contains("Powerburst of sorcery", ignoreCase = true) }
                if (powerburst != null && powerburst.click("Drink")) {
                    println("Drinking Powerburst of Sorcery: ${powerburst.name}")
                    delayUntil(2_000) { Effect.POWERBURST_OF_SORCERY.active }
                    return
                }
            }

            if (inventory.clickItem("Crystal binding rod", 2)) {
                delayUntil(2000) { equipment.hasItem("Crystal binding rod") }
                return
            }

            if (!localPlayer.isAniMoving && hasEssence && interactClosestReachableObject("${rune.value.displayName} altar", "Use"))
                waitForXPDrop(Skill.RUNECRAFTING, 5_000)
        }

        override fun AbyssRunecrafting.onStateEvent(event: Event) {
            if (event is XPDrop && event.skill == Skill.RUNECRAFTING) {
                completedRuns++
                totalRunes += inventory.count("${rune.value.displayName} rune")
                threadsObtained += inventory.count("Magical thread")
            }
        }
    }

    override fun getStartState(): State<AbyssRunecrafting> = BankForEssence

    override fun render() {
        ImGuiDsl.window("Abyss Runecrafting") {
            image(graphicTexture(GraphicIds.RUNECRAFTING), 32f, 32f)
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("Location: ${if (rune.value != Altar.SPIRIT) rune.value.displayName else "None yet. Select one."}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.RUNECRAFTING), startTime)}")
            text("Runs/h: ${getUnitsPerHour(completedRuns, startTime)}")
            text("Runes/h: ${getUnitsPerHour(totalRunes, startTime)}")
            text("Threads/h: ${getUnitsPerHour(threadsObtained, startTime)}")
            xpProgressBar(Skill.RUNECRAFTING)
        }
    }

    class TickCached<T>(private val supplier: () -> T) {
        private var lastCheck = 0L
        private var cachedValue: T? = null

        fun get(): T {
            val now = System.currentTimeMillis()
            if (now - lastCheck >= 600 || cachedValue == null) {
                cachedValue = supplier()
                lastCheck = now
            }
            return cachedValue!!
        }

        fun invalidate() {
            lastCheck = 0L
        }
    }

    fun onConfigUpdated() {
        this::class.java.declaredFields
            .filter { ConfigItem::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                val configItem = field.get(this) as? ConfigItem<*>
                val name = configItem?.name
                val value = configItem?.value
                println("   - $name = $value")
            }
    }
}