package com.projectx.script.impl.qb.utilities.banking

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.player.Player
import com.projectx.script.api.Area
import com.projectx.script.api.Area.Rectangular
import java.util.*
import java.util.regex.Pattern

class Banking {

    private val random = Random()

    class BankWidthrawClass(
        val itemNamePattern: Pattern?,
        val withdrawOption: BankWithdrawOption?,
        val amount: Int
    )

    enum class BankLocation {
        FORT_FORINTHRY("Fort Forinthry", Rectangular(Tile.of(3277, 3551, 0), 10, 10).getArea()),
        ARCH_CHEST(
            "Archaeology Chest",
            Rectangular(Tile.of(3350, 3390, 0), Tile.of(3365, 3397, 0)).getArea()
        ),
        NABANIK_BANKCHEST("Nabanik Chest", Rectangular(Tile.of(3354, 3196, 0), 6, 6).getArea()),
        BURTHORPE("Burthorpe", Rectangular(Tile.of(2885, 3534, 0), Tile.of(2891, 3538, 0)).getArea()),
        TAVERLY("Taverly", Rectangular(Tile.of(2872, 3414, 0), Tile.of(2878, 3421, 0)).getArea()),
        DEEP_SEA_BOAT(
            "DeepSea Boat",
            Rectangular(Tile.of(2097, 7113, 0), Tile.of(2102, 7116, 0)).getArea()
        ),
        DEEP_SEA_NET(
            "DeepSea Net",
            Rectangular(Tile.of(2092, 7089, 0), Tile.of(2102, 7093, 0)).getArea()
        ),
        MENAPHOS_PORT_DEPOSITBOX(
            "Menaphos Port DepositBox",
            Rectangular(Tile.of(3216, 2620, 0), Tile.of(3218, 2624, 0)).getArea()
        ),
        MENAPHOS_PORT_CHEST(
            "Menaphos Port Chest",
            Rectangular(Tile.of(3216, 2620, 0), Tile.of(3218, 2624, 0)).getArea()
        ),
        BROKEN_HOME_DEPOSITBOX(
            "Broken Home DepositBox",
            Rectangular(Tile.of(3359, 3479, 0), Tile.of(3362, 3482, 0)).getArea()
        ),
        LUMBRIDGE("Lumbridge", Rectangular(Tile.of(3214, 3258, 0), Tile.of(3215, 3256, 0)).getArea()),
        GRAND_EXCHANGE(
            "Grand Exchange",
            Rectangular(Tile.of(3162, 3480, 0), Tile.of(3167, 3484, 0)).getArea()
        ),
        AL_KHARID("AlKharid", Rectangular(Tile.of(3269, 3167, 0), Tile.of(3271, 3169, 0)).getArea()),
        EDGEVILLE("Edgeville", Rectangular(Tile.of(3093, 3497, 0), Tile.of(3096, 3489, 0)).getArea()),
        DRAYNOR("Draynor", Rectangular(Tile.of(3090, 3244, 0), Tile.of(3094, 3246, 0)).getArea()),
        MENAPHOSGE(
            "Menaphos GE",
            Rectangular(Tile.of(3233, 2759, 0), Tile.of(3238, 2763, 0)).getArea()
        ),
        YANILLE("Yanille", Rectangular(Tile.of(2609, 3088, 0), Tile.of(2616, 3097, 0)).getArea()),
        FALADOR("Falador", Rectangular(Tile.of(3009, 3355, 0), Tile.of(3018, 3358, 0)).getArea()),
        UM_SMITHY("Um Smithy", Rectangular(Tile.of(1144, 1802, 1), Tile.of(1149, 1806, 1)).getArea()),
        UM_BANK("Um Bank", Rectangular(Tile.of(1105, 1737, 1), Tile.of(1110, 1742, 1)).getArea()),
        CATHBY("Catherby", Rectangular(Tile.of(2795, 3437, 0), Tile.of(2797, 3442, 0)).getArea()),
        SEERS("Seers", Rectangular(Tile.of(2723, 3491, 0), Tile.of(2727, 3495, 0)).getArea()),
        CANIFIS(
            "Canifis", Rectangular(Tile.of(3508, 3478, 0), Tile.of(3513, 3493, 0)).getArea()
        );

        val locationName: String?
        private val area: Area?
        private val canAccess: Boolean

        constructor(locationName: String?, area: Area?) {
            this.locationName = locationName
            this.area = area
            this.canAccess = true
        }

        constructor(locationName: String?, area: Area?, canAccess: Boolean) {
            this.locationName = locationName
            this.area = area
            this.canAccess = canAccess
        }

        fun getArea(): Area? {
            return area
        }

        fun canAccess(): Boolean {
            return canAccess
        }
    }

    fun loadCustomInventory(player: Player, itemList: List<Triple<String, String, String>>): Boolean {
        return false
    }

    private fun withdrawFromBank(itemNamePattern: Pattern?, bankOption: BankWithdrawOption, amount: Int) {
    }

    enum class BankWithdrawOption(val optionText: String, varbitStateValue: Int) {
        ONE("Withdraw-1", 2),
        FIVE("Withdraw-5", 3),
        TEN("Withdraw-10", 4),
        ALL("Withdraw-All", 7),
        X("Withdraw-X", 6);

        val varbitStateValue: Int

        init {
            this.varbitStateValue = varbitStateValue
        }
    }
}