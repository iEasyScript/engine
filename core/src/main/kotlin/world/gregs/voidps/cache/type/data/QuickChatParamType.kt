package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.type.data.QuickChatValue.DECIMAL
import world.gregs.voidps.cache.type.data.QuickChatValue.ENUM_LOOKUP
import world.gregs.voidps.cache.type.data.QuickChatValue.OBJ_NAME
import world.gregs.voidps.cache.type.data.QuickChatValue.UNUSED

enum class QuickChatValue { ENUM_LOOKUP, OBJ_NAME, DECIMAL, UNUSED }

data class QuickChatParamType(val id: Int, val idCount: Int, val valueBytes: Int, val value: QuickChatValue) {

    companion object {
        private val types = arrayOf(
            QuickChatParamType(0, 1, 2, ENUM_LOOKUP),
            QuickChatParamType(1, 0, 2, OBJ_NAME),
            QuickChatParamType(2, 0, 4, DECIMAL),
            QuickChatParamType(3, 0, 0, UNUSED),
            QuickChatParamType(4, 1, 1, DECIMAL),
            QuickChatParamType(5, 0, 0, UNUSED),
            QuickChatParamType(6, 2, 4, ENUM_LOOKUP),
            QuickChatParamType(7, 1, 1, ENUM_LOOKUP),
            QuickChatParamType(8, 1, 4, DECIMAL),
            QuickChatParamType(9, 1, 4, DECIMAL),
            QuickChatParamType(10, 0, 2, OBJ_NAME),
            QuickChatParamType(11, 2, 1, ENUM_LOOKUP),
            QuickChatParamType(12, 0, 1, DECIMAL),
            QuickChatParamType(13, 0, 1, DECIMAL),
            QuickChatParamType(14, 1, 4, DECIMAL),
            QuickChatParamType(15, 0, 1, DECIMAL),
            QuickChatParamType(16, 2, 4, ENUM_LOOKUP),
        )

        fun getType(id: Int): QuickChatParamType? = types.getOrNull(id)
    }
}
