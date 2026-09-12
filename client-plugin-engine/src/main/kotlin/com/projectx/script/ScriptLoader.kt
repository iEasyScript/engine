package com.projectx.script

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Paths

enum class ScannerType {
    JAR,
    CLASS,
    BOTH
}

interface ClassScanner {
    fun scan(): List<Class<*>>
}

sealed class AbstractClassScanner : ClassScanner {
    abstract class PathClassScanner(
        private val directoryPath: String,
    ) : AbstractClassScanner() {
        protected fun getAbsolutePaths(relativePaths: Array<String>?): List<String> =
            relativePaths?.map { path ->
                Paths.get(directoryPath, path).toString()
            } ?: emptyList()
    }

    abstract class DefaultClassScanner : AbstractClassScanner()
}

object FileExtensions {
    const val JAR = ".jar"
    const val CLASS = ".class"
}

class JarDirectoryScanner(
    private val directoryPath: String
) : AbstractClassScanner.PathClassScanner(directoryPath) {

    override fun scan(): List<Class<*>> = runCatching {
        val jarFiles = findJarFiles()
        val absoluteJarPaths = getAbsolutePaths(jarFiles)
        scanJarFiles(absoluteJarPaths)
    }.fold(
        onFailure = { emptyList() },
        onSuccess = { it }
    )

    private fun findJarFiles(): Array<String>? =
        File(directoryPath).list { _, fileName ->
            fileName.lowercase().endsWith(FileExtensions.JAR)
        }

    // Enumerate class NAMES from the jars (bytecode only, no loading), then load each through a
    // classloader parented to the engine's loader so a script's engine/core superclasses & deps
    // resolve. Detecting subclasses by reflection (not ClassGraph's jar-only graph) catches
    // transitive Script subclasses (e.g. StateMachineScript-based). Per-class failures are isolated
    // so one bad script can't drop the whole jar. initialize=false avoids running <clinit> at scan.
    private fun scanJarFiles(jarPaths: List<String>): List<Class<*>> {
        if (jarPaths.isEmpty()) return emptyList()
        val urls = jarPaths.map { File(it).toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, Script::class.java.classLoader)
        val classNames = ClassGraph()
            .enableClassInfo()
            .overrideClasspath(jarPaths)
            .scan()
            .use { it.allClasses.names }
        return classNames.mapNotNull { name ->
            runCatching { Class.forName(name, false, loader) }.getOrNull()
                ?.takeIf {
                    Script::class.java.isAssignableFrom(it) &&
                        it != Script::class.java &&
                        !Modifier.isAbstract(it.modifiers)
                }
        }
    }
}

class ClassFileScanner(
    private val directoryPath: String
) : AbstractClassScanner.PathClassScanner(directoryPath) {

    override fun scan(): List<Class<*>> = runCatching { scanClassFiles() }
        .fold(
            onSuccess = { it },
            onFailure = { emptyList() }
        )

    private fun scanClassFiles(): List<Class<*>> =
        ClassGraph()
            .enableAllInfo()
            .overrideClasspath(directoryPath)
            .scan()
            .getSubclasses(Script::class.java.name)
            .filter { it.isClassFile() }
            .loadClasses()

    private fun ClassInfo.isClassFile(): Boolean =
        resource.path.endsWith(FileExtensions.CLASS)
}

class CurrentProjectScriptsScanner : AbstractClassScanner.DefaultClassScanner() {

    override fun scan(): List<Class<*>> = runCatching { scanClassFiles() }
        .fold(
            onSuccess = { it },
            onFailure = { emptyList() }
        )

    private fun scanClassFiles(): List<Class<*>> =
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .scan()
            .getSubclasses(Script::class.java.name)
            .loadClasses()
}

class CombinedScanner(
    directoryPath: String
) : AbstractClassScanner() {

    private val jarScanner = JarDirectoryScanner(directoryPath)
    private val classFileScanner = ClassFileScanner(directoryPath)

    override fun scan(): List<Class<*>> = runCatching {
        val jarClasses = jarScanner.scan()
        val classFileClasses = classFileScanner.scan()

        jarClasses + classFileClasses
    }.fold(
        onSuccess = { it },
        onFailure = { emptyList() }
    )
}

object ClassScannerFactory {
    fun create(path: String, type: ScannerType): ClassScanner =
        when (type) {
            ScannerType.JAR -> JarDirectoryScanner(path)
            ScannerType.CLASS -> ClassFileScanner(path)
            ScannerType.BOTH -> CombinedScanner(path)
        }
}