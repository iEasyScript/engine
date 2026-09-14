package com.projectx.mcp.tools

import com.projectx.script.api.GrandExchange
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object GrandExchangeTools {

    fun register(server: Server): Int {
        registerGetGrandExchange(server)
        return 1
    }

    private fun registerGetGrandExchange(server: Server) {
        server.addTool(
            name = "get_grand_exchange",
            description = """
                Purpose: Snapshot the Grand Exchange through the script API: every offer slot (status, type, item, price, quantity, completed quantity and gold), what each slot has waiting to collect, and the buy/sell setup screen's state.
                || Returns: JSON envelope with: supported, open, setup {slot, item_id, quantity, price, market_price}, search_results[] {slot, name}, offers[], collectable[].
                || Inputs: none.
                || Use cases: "Did my offer fill?", "Which slots are free?", "What did the setup screen pick up?".
                || Pitfalls: Offers are live with the window closed; setup fields are only meaningful while `setup.slot` >= 0.
            """.trimIndent().replace("\n", " "),
            inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
        ) { _ ->
            safeJsonCall("get_grand_exchange") { _ ->
                requireLoggedIn()
                put("supported", GrandExchange.isSupported)
                put("open", GrandExchange.isOpen)
                putJsonObject("setup") {
                    put("slot", GrandExchange.setupSlot)
                    put("item_id", GrandExchange.setupItemId)
                    put("quantity", GrandExchange.setupQuantity)
                    put("price", GrandExchange.setupPrice)
                    put("market_price", GrandExchange.setupMarketPrice)
                }
                putJsonArray("search_results") {
                    for ((slot, name) in GrandExchange.searchResults()) {
                        add(buildJsonObject { put("slot", slot); put("name", name) })
                    }
                }
                putJsonArray("offers") {
                    for (offer in GrandExchange.offers()) {
                        add(buildJsonObject {
                            put("slot", offer.slot)
                            put("status", offer.status.name)
                            put("type", offer.type.name)
                            put("item_id", offer.itemId)
                            put("item_name", offer.itemName)
                            put("price", offer.price)
                            put("quantity", offer.quantity)
                            put("completed_quantity", offer.completedQuantity)
                            put("completed_gold", offer.completedGold)
                        })
                    }
                }
                putJsonArray("collectable") {
                    for (slot in 0 until GrandExchange.SLOT_COUNT) {
                        for (item in GrandExchange.collectable(slot)) {
                            add(buildJsonObject {
                                put("slot", slot)
                                put("item_id", item.id)
                                put("name", item.name)
                                put("amount", item.amount)
                            })
                        }
                    }
                }
            }
        }
    }
}
