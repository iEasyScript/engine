package org.projectx.core.game.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SettingType { TOGGLE, DROPDOWN, SLIDER, STEPPER, BUTTON, SPECIAL, SEPARATOR }

enum class VarDomain { VARBIT, VARP }

@Serializable
data class Setting(
    val name: String,
    val struct: Int,
    val caption: String = "",
    val desc: String = "",
    val type: SettingType,
    val category: String,
    val subpage: String,
    @SerialName("var") val backingVar: String? = null,
    val varDomain: String? = null,
    val expr: String? = null,
    val readonly: Boolean = false,
    val optionsEnum: Int? = null,
) {
    val domain: VarDomain? get() = when (varDomain) {
        "varbit" -> VarDomain.VARBIT
        "varp" -> VarDomain.VARP
        else -> null
    }

    val settable: Boolean get() = !readonly && backingVar != null && domain != null && type != SettingType.SEPARATOR
}
