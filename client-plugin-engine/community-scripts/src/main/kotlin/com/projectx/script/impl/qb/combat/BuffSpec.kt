package com.projectx.script.impl.qb.combat

import org.projectx.core.game.combat.Effect
import com.projectx.script.Script
import com.projectx.script.api.inventory
import com.projectx.script.api.drinkOverload

suspend fun Script.clickAny(vararg patterns: Regex): Boolean =
    patterns.any { inventory.clickItem(it, 1) }

enum class BuffSpec(
    val effectsToWatch: List<Effect>,
    val inventoryPatterns: List<Regex>,
    val priority: Int = 100,
    val minReapplyIntervalMs: Long = 1200,
    val use: suspend Script.() -> Boolean,
    val waitCondition: () -> Boolean,
) {
    OVERLOAD(
        effectsToWatch = listOf(Effect.OVERLOADED),
        inventoryPatterns = listOf(Regex("(?i).*overload.*")),
        priority = 100,
        minReapplyIntervalMs = 1500,
        use = { drinkOverload() },
        waitCondition = { Effect.OVERLOADED.active },
    ),

    PRAYER_RENEWAL(
        effectsToWatch = listOf(Effect.PRAYER_RENEW, Effect.SUPER_PRAYER_RENEWAL_POTION),
        inventoryPatterns = listOf(Regex("(?i).*(prayer).*(renewal).*")),
        priority = 99,
        minReapplyIntervalMs = 1500,
        use = { inventory.clickItem(Regex("(?i).*(prayer).*(renewal).*"), 1) },
        waitCondition = { Effect.PRAYER_RENEW.active || Effect.SUPER_PRAYER_RENEWAL_POTION.active },
    ),

    POWDER_OF_PENANCE(
        effectsToWatch = listOf(Effect.POWDER_OF_PENANCE),
        inventoryPatterns = listOf(Regex("(?i).*powder of penance.*")),
        priority = 98,
        minReapplyIntervalMs = 1200,
        use = { inventory.clickItem(Regex("(?i).*powder of penance.*"), 1) },
        waitCondition = { Effect.POWDER_OF_PENANCE.active },
    ),

    AGGRESSION_POTION(
        effectsToWatch = listOf(Effect.AGGRESSION_POTION, Effect.AGGRESSION),
        inventoryPatterns = listOf(Regex("(?i).*aggression.*")),
        priority = 97,
        minReapplyIntervalMs = 1500,
        use = { inventory.clickItem(Regex("(?i).*aggression.*"), 1) },
        waitCondition = { Effect.AGGRESSION_POTION.active || Effect.AGGRESSION.active },
    ),

    CHARMING_POTION(
        effectsToWatch = listOf(Effect.CHARMING_POTION),
        inventoryPatterns = listOf(Regex("(?i).*(charming).*(potion).*")),
        priority = 96,
        minReapplyIntervalMs = 1200,
        use = { inventory.clickItem(Regex("(?i).*(charming).*(potion).*"), 1) },
        waitCondition = { Effect.CHARMING_POTION.active },
    ),

    ATTACK_BOOST(
        effectsToWatch = listOf(Effect.ATTACK_STAT_BOOSTED, Effect.OVERLOADED),
        inventoryPatterns = listOf(
            Regex("(?i).*(extreme).*(attack).*"),
            Regex("(?i).*(super).*(attack).*"),
            Regex("(?i).*(attack).*(potion|flask).*"),
        ),
        priority = 95,
        minReapplyIntervalMs = 1500,
        use = { clickAny(
            Regex("(?i).*(extreme).*(attack).*"),
            Regex("(?i).*(super).*(attack).*"),
            Regex("(?i).*(attack).*(potion|flask).*"),
        ) },
        waitCondition = { Effect.ATTACK_STAT_BOOSTED.active || Effect.OVERLOADED.active },
    ),

    STRENGTH_BOOST(
        effectsToWatch = listOf(Effect.STRENGTH_STAT_BOOSTED, Effect.OVERLOADED),
        inventoryPatterns = listOf(
            Regex("(?i).*(extreme).*(strength).*"),
            Regex("(?i).*(super).*(strength).*"),
            Regex("(?i).*(strength).*(potion|flask).*"),
        ),
        priority = 95,
        minReapplyIntervalMs = 1500,
        use = { clickAny(
            Regex("(?i).*(extreme).*(strength).*"),
            Regex("(?i).*(super).*(strength).*"),
            Regex("(?i).*(strength).*(potion|flask).*"),
        ) },
        waitCondition = { Effect.STRENGTH_STAT_BOOSTED.active || Effect.OVERLOADED.active },
    ),

    DEFENCE_BOOST(
        effectsToWatch = listOf(Effect.DEFENCE_STAT_BOOSTED, Effect.OVERLOADED),
        inventoryPatterns = listOf(
            Regex("(?i).*(extreme).*(defen[cs]e).*"),
            Regex("(?i).*(super).*(defen[cs]e).*"),
            Regex("(?i).*(defen[cs]e).*(potion|flask).*"),
        ),
        priority = 95,
        minReapplyIntervalMs = 1500,
        use = { clickAny(
            Regex("(?i).*(extreme).*(defen[cs]e).*"),
            Regex("(?i).*(super).*(defen[cs]e).*"),
            Regex("(?i).*(defen[cs]e).*(potion|flask).*"),
        ) },
        waitCondition = { Effect.DEFENCE_STAT_BOOSTED.active || Effect.OVERLOADED.active },
    ),

    RANGING_BOOST(
        effectsToWatch = listOf(Effect.RANGED_STAT_BOOSTED, Effect.OVERLOADED),
        inventoryPatterns = listOf(
            Regex("(?i).*(extreme).*(rang).*"),
            Regex("(?i).*(super).*(rang).*"),
            Regex("(?i).*(rang(ing)?).*(potion|flask).*"),
        ),
        priority = 95,
        minReapplyIntervalMs = 1500,
        use = { clickAny(
            Regex("(?i).*(extreme).*(rang).*"),
            Regex("(?i).*(super).*(rang).*"),
            Regex("(?i).*(rang(ing)?).*(potion|flask).*"),
        ) },
        waitCondition = { Effect.RANGED_STAT_BOOSTED.active || Effect.OVERLOADED.active },
    ),

    MAGIC_BOOST(
        effectsToWatch = listOf(Effect.MAGIC_STAT_BOOSTED, Effect.OVERLOADED),
        inventoryPatterns = listOf(
            Regex("(?i).*(extreme).*(magic).*"),
            Regex("(?i).*(super).*(magic).*"),
            Regex("(?i).*(magic).*(potion|flask).*"),
        ),
        priority = 95,
        minReapplyIntervalMs = 1500,
        use = { clickAny(
            Regex("(?i).*(extreme).*(magic).*"),
            Regex("(?i).*(super).*(magic).*"),
            Regex("(?i).*(magic).*(potion|flask).*"),
        ) },
        waitCondition = { Effect.MAGIC_STAT_BOOSTED.active || Effect.OVERLOADED.active },
    ),

    ;

    fun isAvailable(): Boolean {
        return inventory.any { item ->
            val name = item.name
            inventoryPatterns.any { it.containsMatchIn(name) }
        }
    }

    fun shouldApply(): Boolean = effectsToWatch.all { it.notActive }
}
