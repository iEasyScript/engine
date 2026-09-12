package org.projectx.core.game.combat.npc

import java.io.File

/** Every `*.json` under [root] (recursively), skipping files whose name starts with `_`. */
internal fun jsonFiles(root: File): List<File> {
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "json" && !it.name.startsWith("_") }
        .toList()
}
