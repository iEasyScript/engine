package org.projectx.core.worldlist

enum class Country(val id: Int) {
    USA(225),
    UNITED_KINGDOM(77),
    CANADA(38),
    AUSTRALIA(16),
    NETHERLANDS(161),
    SWEDEN(191),
    FINLAND(69),
    IRELAND(101),
    INDIA(97),
    DENMARK(58),
    GERMANY(56),
    BRAZIL(31),
    FRANCE(74),
    MEXICO(152),
    NEW_ZEALAND(166),
    BELGIUM(22),
    NORWAY(162),
    POLAND(176),
    SWITZERLAND(43),
    PORTUGAL(177),
    LUXEMBOURG(134),
    JAPAN(110),
    SOUTH_KOREA(112),
    LITHUANIA(130);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int) = byId[id] ?: USA
    }
}
