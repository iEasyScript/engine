package org.projectx.core.plugin

import org.projectx.core.Logger.logInfo
import org.projectx.core.getClasses
import java.lang.reflect.Modifier

object PluginLoader {
    fun <T : Any> load(pack: String, type: Class<T>, order: (T) -> Int, run: (T) -> Unit) {
        val instances = getClasses(pack)
            .filter { type.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .map { type.cast(it.getDeclaredConstructor().newInstance()) }
            .sortedBy(order)
        logInfo("Loaded ${instances.size} ${type.simpleName} from $pack")
        instances.forEach(run)
    }
}
