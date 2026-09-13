package com.projectx.game.interfaces

import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.api.interfaces
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

class Bank {
    companion object {
        const val BANK_INTERFACE_ID = 517

        private fun component(name: String) = Gameval.requireComponentHash("bank:$name") and 0xFFFF

        @JvmStatic
        val BANK_ITEMS_COMPONENT_ID = component("bank_inv")
        @JvmStatic
        val BANK_INV_COMPONENT_ID = component("inventory_click")
        @JvmStatic
        val SHARE_QUICK_COMPONENT_ID = component("share_quick_click")
        @JvmStatic
        val CLOSE_COMPONENT_ID = component("close_button_layer")
        @JvmStatic
        val CERT_COMPONENT_ID = component("bank_cert_button")
        @JvmStatic
        val TABS_ALL_COMPONENT_ID = component("tabs_all_button")
        @JvmStatic
        val DEPOSIT_INVENTORY_COMPONENT_ID = component("bank_inv_button")
        @JvmStatic
        val DEPOSIT_WORN_COMPONENT_ID = component("bank_worn_button")

        @JvmStatic
        fun fetchBankArray(componentId: Int): ArrayList<InterfaceComponent> {
            val bankItems = interfaces.getComponent(BANK_INTERFACE_ID, componentId)
            val bankChildren = bankItems?.slotChildren
            val res = ArrayList<InterfaceComponent>()
            if (bankChildren?.size!! > 0) {
                for (c in bankChildren) {
                    if (c.itemId > 0 && c.stackSize > 0)
                        res.add(c)
                }
            }
            return res
        }

        @JvmStatic
        fun fetchBankItemsArray() = fetchBankArray(BANK_ITEMS_COMPONENT_ID)
        @JvmStatic
        fun fetchBankInventoryArray() = fetchBankArray(BANK_INV_COMPONENT_ID)

        @JvmOverloads
        @JvmStatic
        fun doBankAction(componentId: Int, slotId: Int = -1, optionNum: Int = 1) =
            IFSlot(BANK_INTERFACE_ID, componentId, slotId).click(optionNum)

        internal fun doBankItemsAction(name: String, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (Cache.obj(item.itemId)?.name == name)
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankItemsAction(regex: Regex, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (regex.matches(Cache.obj(item.itemId)?.name ?: ""))
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankItemsAction(itemId: Int, optionNum: Int): Boolean {
            for (item in fetchBankItemsArray()) {
                if (item.itemId == itemId)
                    return doBankAction(BANK_ITEMS_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankInventoryAction(name: String, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (Cache.obj(item.itemId)?.name == name)
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankInventoryAction(regex: Regex, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (regex.matches(Cache.obj(item.itemId)?.name ?: ""))
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }

        internal fun doBankInventoryAction(itemId: Int, optionNum: Int): Boolean {
            for (item in fetchBankInventoryArray()) {
                if (item.itemId == itemId)
                    return doBankAction(BANK_INV_COMPONENT_ID, item.slotId, optionNum)
            }
            return false
        }
    }
}