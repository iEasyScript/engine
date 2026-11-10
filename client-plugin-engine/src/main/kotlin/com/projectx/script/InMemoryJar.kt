package com.projectx.script

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** A jar's entries, read out of bytes that never touch the disk. */
internal class InMemoryJar private constructor(val entries: Map<String, ByteArray>) {

    val classNames: List<String>
        get() = entries.keys
            .filter { it.endsWith(CLASS_SUFFIX) && !it.startsWith("META-INF/") && it != "module-info.class" }
            .map { it.removeSuffix(CLASS_SUFFIX).replace('/', '.') }

    companion object {
        private const val CLASS_SUFFIX = ".class"

        fun read(jar: ByteArray): InMemoryJar {
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(jar)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name.removePrefix("/")] = zip.readAllBytes()
                }
            }
            require(entries.isNotEmpty()) { "the jar holds no entries" }
            return InMemoryJar(entries)
        }
    }
}

/**
 * Loads classes and resources straight from an [InMemoryJar]. Resources get `projectx-script:` URLs whose handler
 * serves the entry's bytes, so `getResource(...).openStream()` and `getResourceAsStream` work as they do for a jar.
 */
internal class InMemoryJarClassLoader(
    private val jar: InMemoryJar,
    parent: ClassLoader,
    codeSource: CodeSource?,
) : ClassLoader("projectx-encrypted-script", parent) {

    private val domain = ProtectionDomain(codeSource, null, this, null)
    private val host = "jar${serial.incrementAndGet()}"
    private val handler = EntryHandler(jar)

    override fun findClass(name: String): Class<*> {
        val bytes = jar.entries[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size, domain)
    }

    override fun findResource(name: String): URL? {
        val path = name.removePrefix("/")
        if (path !in jar.entries) return null
        return URL.of(URI(PROTOCOL, host, "/$path", null), handler)
    }

    override fun findResources(name: String): Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))

    private class EntryHandler(private val jar: InMemoryJar) : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection {
            val path = url.toURI().path.removePrefix("/")
            val bytes = jar.entries[path] ?: throw FileNotFoundException(path)
            return EntryConnection(url, bytes)
        }

        // The default resolves the host over DNS to compare or hash a URL.
        override fun getHostAddress(url: URL): InetAddress? = null
    }

    private class EntryConnection(url: URL, private val bytes: ByteArray) : URLConnection(url) {
        override fun connect() {
            connected = true
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

        override fun getContentLengthLong(): Long = bytes.size.toLong()
    }

    companion object {
        const val PROTOCOL = "projectx-script"
        private val serial = AtomicLong()
    }
}
