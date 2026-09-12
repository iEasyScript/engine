package org.projectx.core.worldlist

data class World(
    val number: Int,
    val hostname: String,
    val port: Int = 43595,
    val activity: String = "",
    val country: Country = Country.USA,
    val members: Boolean = true,
    val quickchat: Boolean = false,
    val pvp: Boolean = false,
    val lootShare: Boolean = false,
    val highlighted: Boolean = false,
) {
    var index: Int = 0
        internal set

    var playersOnline: Int = 0

    var offline: Boolean = false

    fun flags(): Int {
        var f = 0
        if (members) f = f or 0x1
        if (quickchat) f = f or 0x2
        if (pvp) f = f or 0x4
        if (lootShare) f = f or 0x8
        if (highlighted) f = f or 0x10
        if (port != 43595) f = f or 0x40000000
        return f
    }
}
