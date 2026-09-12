package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap

/**
 * Reads dialog/conversation component text by walking the open dialog interfaces live. Every caller is
 * on the main-logic thread (quest condition evaluation and the editor HUD), where interfaces are stable
 * — so the EASTL text read can't race a rebuild. Reading the same text off the DrawSlotChildren draw
 * path used to fault here; it doesn't anymore.
 */
object InterfaceTextCapture {
    // 1180-1199 = NPC speech / option dialogs; 720 = legacy option list; the rest are alt overrides.
    val DIALOG_INTERFACES: Set<Int> = setOf(
        1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189,
        1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199,
        1244, 1251, 1370, 1500, 1224, 847, 720,
    )

    /** Concatenated non-blank text of every component in each open interface in [interfaceIds]. */
    fun textFor(interfaceIds: Set<Int>): String {
        val sb = StringBuilder()
        forEachComponentText(interfaceIds) { _, text -> sb.append(text).append(' ') }
        return sb.toString()
    }

    /** Per-interface concatenated text (capped per interface), for the editor's debug HUD / sweep. */
    fun samplesByInterface(interfaceIds: Set<Int>, maxCharsPerIface: Int = 400): Map<Int, String> {
        val out = HashMap<Int, StringBuilder>()
        forEachComponentText(interfaceIds) { iface, text ->
            val sb = out.getOrPut(iface) { StringBuilder() }
            if (sb.length < maxCharsPerIface) sb.append(text).append(' ')
        }
        return out.mapValues { it.value.toString().take(maxCharsPerIface) }
    }

    private fun forEachComponentText(interfaceIds: Set<Int>, action: (Int, String) -> Unit) {
        val list = Bootstrap.client.interfaceList
        for (iface in interfaceIds) {
            val parent = list.getRaw(iface) ?: continue
            for (component in parent) {
                val text = component.text
                if (text.isNotBlank()) action(iface, text)
            }
        }
    }
}
