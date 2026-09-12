package world.gregs.voidps.cache.type.data

enum class VarDomain(val id: Int, val configArchive: Int) {
    PLAYER(0, 60),
    NPC(1, 61),
    CLIENT(2, 62),
    WORLD(3, 63),
    MAPSQUARE(4, 64),
    OBJECT(5, 65),
    CLAN(6, 66),
    CLAN_SETTING(7, 67),
    CONTROLLER(8, 68),
    PLAYER_GROUP(9, 80);

    companion object {
        private val map = entries.associateBy(VarDomain::id)

        fun forId(id: Int): VarDomain? = map[id]
    }
}
