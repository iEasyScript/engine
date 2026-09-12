package org.projectx.tools.util

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.TypeDecoder
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Every decoder the cache library ships, discovered from the classpath rather than listed by hand,
 * so a newly added decoder is unpacked the day it lands instead of silently going missing.
 *
 * A class the registry cannot drive itself is reported rather than dropped — the caller records it
 * as known-but-unpacked, which is the difference between "no data" and "we cannot read this".
 */
object DecoderRegistry {

    private const val PACKAGE = "world.gregs.voidps.cache.type.decoder"
    private val PATH = PACKAGE.replace('.', '/')

    class Unavailable(val className: String, val reason: String)

    class Scan(val decoders: List<TypeDecoder<out CacheType>>, val unavailable: List<Unavailable>)

    fun scan(): Scan {
        val decoders = ArrayList<TypeDecoder<out CacheType>>()
        val unavailable = ArrayList<Unavailable>()
        for (name in classNames().sorted()) {
            val type = try {
                Class.forName("$PACKAGE.$name")
            } catch (e: Throwable) {
                unavailable.add(Unavailable(name, "not loadable: ${e::class.simpleName}"))
                continue
            }
            if (!TypeDecoder::class.java.isAssignableFrom(type)) {
                if (name.endsWith("Decoder")) {
                    unavailable.add(Unavailable(name, "not a TypeDecoder — needs bespoke iteration"))
                }
                continue
            }
            if (Modifier.isAbstract(type.modifiers)) continue
            val constructor = try {
                type.getDeclaredConstructor()
            } catch (e: NoSuchMethodException) {
                unavailable.add(Unavailable(name, "no no-argument constructor"))
                continue
            }
            try {
                constructor.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                decoders.add(constructor.newInstance() as TypeDecoder<out CacheType>)
            } catch (e: Throwable) {
                unavailable.add(Unavailable(name, "construction failed: ${e::class.simpleName}"))
            }
        }
        decoders.sortWith(compareBy({ it.index }, { it::class.simpleName }))
        unavailable.sortBy { it.className }
        return Scan(decoders, unavailable)
    }

    /** Type name a decoder writes under: `QuickChatPhraseDecoder` -> `quick-chat-phrase`. */
    fun typeName(decoder: TypeDecoder<*>): String = typeName(decoder::class.simpleName ?: "unknown")

    fun typeName(decoderClassName: String): String = decoderClassName
        .removeSuffix("Decoder")
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "-")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "-")
        .lowercase()

    private fun classNames(): Set<String> {
        val names = LinkedHashSet<String>()
        val loader = Thread.currentThread().contextClassLoader ?: DecoderRegistry::class.java.classLoader
        for (url in loader.getResources(PATH)) {
            when (url.protocol) {
                "file" -> {
                    val dir = Paths.get(URLDecoder.decode(url.path, Charsets.UTF_8))
                    if (!Files.isDirectory(dir)) continue
                    Files.list(dir).use { stream ->
                        for (entry in stream) {
                            if (entry.extension == "class") names.add(entry.nameWithoutExtension)
                        }
                    }
                }
                "jar" -> {
                    val jarPath = URLDecoder.decode(url.path.substringAfter("file:").substringBefore("!"), Charsets.UTF_8)
                    JarFile(jarPath).use { jar ->
                        for (entry in jar.entries()) {
                            val entryName = entry.name
                            if (!entryName.startsWith("$PATH/") || !entryName.endsWith(".class")) continue
                            names.add(entryName.removePrefix("$PATH/").removeSuffix(".class"))
                        }
                    }
                }
            }
        }
        return names.filterTo(LinkedHashSet()) { !it.contains('$') }
    }
}
