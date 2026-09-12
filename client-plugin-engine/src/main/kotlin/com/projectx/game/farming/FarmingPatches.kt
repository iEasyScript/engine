package com.projectx.game.farming

import world.gregs.voidps.gameval.Gameval

enum class PatchCategory(
    val displayName: String,
    val primary: Boolean,
    val checkHealth: Boolean,
    val bearsProduce: Boolean
) {
    HERB("Herb", true, false, false),
    FLOWER("Flower", true, false, false),
    CACTUS("Cactus", true, true, true),
    BUSH("Bush", true, true, true),
    FRUIT_TREE("Fruit Tree", true, true, true),
    ALLOTMENT("Allotment", false, false, false),
    HOPS("Hops", false, false, false),
    TREE("Tree", false, true, false),
    CALQUAT("Calquat", false, true, true),
    SPIRIT_TREE("Spirit Tree", false, true, false);

    val plural: String get() = "$displayName Patches"
}

data class PatchDef(val locId: Int, val category: PatchCategory) {
    val name: String get() = FarmingPatches.displayName(locId)
}

object FarmingPatches {
    private val LOC_IDS: Map<PatchCategory, IntArray> = mapOf(
        PatchCategory.HERB to intArrayOf(8150, 8151, 8152, 8153, 93287, 136765, 12230, 18816, 104327),
        PatchCategory.FLOWER to intArrayOf(7847, 7848, 7849, 7850, 134120, 136763),
        PatchCategory.CACTUS to intArrayOf(7771, 109546, 114470, 122609),
        PatchCategory.BUSH to intArrayOf(7577, 7578, 7579, 7580, 93285),
        PatchCategory.FRUIT_TREE to intArrayOf(7962, 7963, 7964, 7965, 28919, 94323, 136764),
        PatchCategory.ALLOTMENT to intArrayOf(8550, 8551, 8552, 8553, 8554, 8555, 8556, 8557, 21950, 136786, 136787),
        PatchCategory.HOPS to intArrayOf(8173, 8174, 8175, 8176),
        PatchCategory.TREE to intArrayOf(8388, 8389, 8390, 8391, 19147, 93288, 125581, 136781),
        PatchCategory.CALQUAT to intArrayOf(7807),
        PatchCategory.SPIRIT_TREE to intArrayOf(8338, 8382, 8383, 114572)
    )

    val byCategory: Map<PatchCategory, List<PatchDef>> = PatchCategory.entries.associateWith { cat ->
        LOC_IDS.getValue(cat).map { PatchDef(it, cat) }
    }

    val all: List<PatchDef> = byCategory.values.flatten()

    private val NICE_TOKENS = mapOf(
        "wildy" to "Wilderness", "druid" to "Druid", "myarm" to "My Arm's",
        "veg" to "Allotment", "herbpatch" to "Herb"
    )

    /**
     * loc-id → in-world city. Only patches whose loc id is identical in the 727 reference (stable
     * classic patches) are named here; new-loc patches fall back to the gameval-derived label.
     */
    private val CITY_NAMES = mapOf(
        8150 to "Falador", 8151 to "Catherby", 8152 to "Ardougne", 8153 to "Canifis", 18816 to "Trollheim",
        93287 to "Prifddinas", 136765 to "Havenhythe", 12230 to "Druid", 104327 to "Wilderness",
        7847 to "Falador", 7848 to "Catherby", 7849 to "Ardougne", 7850 to "Canifis",
        134120 to "Prifddinas", 136763 to "Havenhythe",
        7771 to "Al Kharid", 109546 to "Menaphos", 114470 to "Anachronia", 122609 to "Het's Oasis",
        7577 to "Champions' Guild", 7578 to "Rimmington", 7579 to "Etceteria", 7580 to "Ardougne", 93285 to "Prifddinas",
        7962 to "Gnome Stronghold", 7963 to "Tree Gnome Village", 7964 to "Brimhaven", 7965 to "Catherby", 28919 to "Lletya",
        94323 to "Prifddinas", 136764 to "Havenhythe",
        8550 to "Falador North", 8551 to "Falador South", 8552 to "Catherby North", 8553 to "Catherby South",
        8554 to "Ardougne North", 8555 to "Ardougne South", 8556 to "Canifis North", 8557 to "Canifis South",
        21950 to "Harmony", 136786 to "Havenhythe North", 136787 to "Havenhythe South",
        8173 to "Yanille", 8174 to "Entrana", 8175 to "Lumbridge", 8176 to "Seers' Village",
        8388 to "Taverley", 8389 to "Falador", 8390 to "Varrock", 8391 to "Lumbridge", 19147 to "Gnome Stronghold",
        93288 to "Prifddinas", 125581 to "Woodcutter's Grove", 136781 to "Havenhythe",
        7807 to "Karamja",
        8338 to "Port Sarim", 8382 to "Etceteria", 8383 to "Brimhaven", 114572 to "Manor Farm"
    )

    fun displayName(locId: Int): String {
        CITY_NAMES[locId]?.let { return it }
        val raw = Gameval.loc(locId) ?: return "Patch $locId"
        return raw.removePrefix("farming_")
            .split('_')
            .filter { it.isNotEmpty() && it != "patch" }
            .joinToString(" ") { NICE_TOKENS[it] ?: it.replaceFirstChar(Char::uppercase) }
    }
}
