package com.projectx.script.impl.devin.leagues

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.gameval.Gameval

enum class IaiaStation(
    val stationName: String,
    val skill: Skill,
    val skillOption: String,
    val resourceNames: Set<String>,
    private val skillKey: String,
    private val resourceKey: String
) {
    APOTHECARY("Apothecary", Skill.HERBLORE, "Mix tinctures", setOf("leaves"), "herblore", "leaves"),
    FISHERY("Fishery", Skill.FISHING, "Feed fish", setOf("clay"), "fishing", "clay"),
    FLETCHERS_HUT("Fletcher's hut", Skill.FLETCHING, "Fletch logs", setOf("wood"), "fletching", "wood"),
    HUNTING_LODGE("Hunting lodge", Skill.HUNTER, "Build traps", setOf("vines"), "hunter", "vines"),
    STONEMASON("Stonemason", Skill.CONSTRUCTION, "Prepare stone", setOf("stone"), "construction", "stone"),
    TANNERY("Tannery", Skill.CRAFTING, "Dye leather", setOf("hides", "hide", "pelts"), "crafting", "pelts");

    val resourceLabel: String get() = resourceNames.first()

    val locId: Int by lazy { Gameval.id(Gameval.LOC, "quest_egw_finale_reward_station_${skillKey}_multi") ?: -1 }

    val workerNpcId: Int by lazy { Gameval.id(Gameval.NPC, "quest_egw_finale_reward_station_npc_$skillKey") ?: -1 }

    val storedXpVarbit: Int by lazy { Gameval.id(Gameval.VARBIT, "quest_egw_finale_reward_station_xp_$skillKey") ?: -1 }

    val stockedVarbit: Int by lazy { Gameval.id(Gameval.VARBIT, "quest_egw_finale_reward_station_state_$skillKey") ?: -1 }

    val resourceVarp: Int by lazy { Gameval.id(Gameval.VAR_PLAYER, "dino_base_camp_resource_$resourceKey") ?: -1 }

    val assignedWorkerVarbit: Int by lazy { Gameval.id(Gameval.VARBIT, "dino_base_camp_worker_assigned_$resourceKey") ?: -1 }

    val unresolvedGamevals: List<String>
        get() = buildList {
            if (locId < 0) add("loc")
            if (workerNpcId < 0) add("npc")
            if (storedXpVarbit < 0) add("stored xp varbit")
            if (stockedVarbit < 0) add("stocked varbit")
            if (resourceVarp < 0) add("resource varp")
            if (assignedWorkerVarbit < 0) add("worker varbit")
        }

    override fun toString() = stationName
}
