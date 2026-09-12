package world.gregs.voidps.cache.gameval

object GamevalIndex {
    const val INDEX = 67

    const val COMPONENT = "component"

    const val VARBIT_PREFIX = "_"

    data class VarDomain(val archive: Int, val domain: String) {
        val varType: String get() = "var_$domain"

        val varbitType: String get() = "varbit_$domain"
    }

    val TYPE_BY_ARCHIVE: Map<Int, String> = linkedMapOf(
        0 to "component",
        5 to "bas",
        9 to "category",
        12 to "cursor",
        14 to "dbrow",
        15 to "dbtable",
        16 to "enum",
        20 to "headbar",
        21 to "hitmark",
        24 to "interface",
        25 to "inv",
        28 to "loc",
        29 to "mapelement",
        32 to "material",
        34 to "model",
        35 to "npc",
        36 to "obj",
        37 to "param",
        41 to "quest",
        44 to "seq",
        49 to "graphic",
        50 to "struct",
        55 to "var_clan",
        56 to "var_clan_setting",
        57 to "var_client",
        59 to "var_npc",
        60 to "var_object",
        61 to "var_player",
        64 to "sound",
        69 to "midi",
        80 to "var_player_group",
        89 to "achievement",
        90 to "fontmetrics",
        92 to "stylesheet",
        96 to "ui_anim_curve",
        97 to "ui_anim",
    )

    val VAR_DOMAINS: List<VarDomain> = TYPE_BY_ARCHIVE.entries
        .filter { it.value.startsWith("var_") }
        .map { VarDomain(it.key, it.value.removePrefix("var_")) }

    val VAR_DOMAIN_BY_ARCHIVE: Map<Int, VarDomain> = VAR_DOMAINS.associateBy { it.archive }

    val VAR_DOMAIN_BY_NAME: Map<String, VarDomain> = VAR_DOMAINS.associateBy { it.domain }

    val VAR_DOMAIN_BY_VAR_TYPE: Map<String, VarDomain> = VAR_DOMAINS.associateBy { it.varType }

    val VAR_DOMAIN_BY_VARBIT_TYPE: Map<String, VarDomain> = VAR_DOMAINS.associateBy { it.varbitType }

    val ARCHIVE_BY_TYPE: Map<String, Int> = buildMap {
        for ((id, name) in TYPE_BY_ARCHIVE) put(name, id)
        for (domain in VAR_DOMAINS) put(domain.varbitType, domain.archive)
    }

    fun typeName(archive: Int): String = TYPE_BY_ARCHIVE[archive] ?: "type_$archive"

    fun archiveId(type: String): Int? = ARCHIVE_BY_TYPE[type]
}
