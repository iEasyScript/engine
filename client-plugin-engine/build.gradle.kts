import java.time.Instant
plugins {
    application
    // kotlin.jvm + java-library are applied to every module by the root `subprojects {}` block,
    // and the Kotlin version comes from the shared version catalog (2.3.20). Only the
    // engine-specific plugins are declared here.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    id("com.gradleup.shadow") version "9.3.1"
}

group = "com.projectx"
version = "1.0.0"

// Preserve the standalone artifact name (com.projectx-1.0.0-all.jar) referenced by the inject
// script and the unified launcher, even though this is now the :client-plugin-engine subproject of projectx.
base { archivesName.set("com.projectx") }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

application {
    mainClass.set("com.projectx.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

// The engine ships as the shadow jar, never as an application archive. Compose also has two artifacts that share
// the file name runtime-saveable-desktop-1.12.1.jar (androidx and org.jetbrains), and those can't both go in one lib/.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

// repositories are declared centrally by the root `allprojects {}` block (mavenLocal + mavenCentral).
// Compose's androidx dependencies are published only to Google's repository.
repositories { google() }

dependencies {
    // Shared networking protocol (packet opcodes/sizes/names + structured codecs + Isaac) lives in
    // :core — the engine consumes it instead of duplicating it. The server-only transitive deps
    // (MongoDB driver, argon2) are excluded: the engine never touches the DB/auth layer, so they
    // would only bloat the dlopen-injected shadow JAR.
    implementation(project(":core")) {
        exclude(group = "org.mongodb")
        exclude(group = "de.mkammerer")
    }

    // EngineHandle (the hot-reload boundary type) is provided at runtime by the supervisor jar on
    // the system classpath — compileOnly so it is NOT bundled into the shadow jar, otherwise the
    // child URLClassLoader would define its own copy and the (EngineHandle) cast would fail.
    implementation(project(":packetlog"))
    compileOnly(project(":client-plugin-engine-supervisor"))

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.github.classgraph:classgraph:4.8.177")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("com.github.weisj:darklaf-core:3.0.2")
    implementation("com.formdev:flatlaf:3.4")
    implementation("com.formdev:flatlaf-intellij-themes:3.2")
    // MCP server for live memory diagnostics (accessed by Claude Code)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.4")
    implementation("io.ktor:ktor-server-cio:3.1.2")
    implementation("io.ktor:ktor-server-sse:3.1.2")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    // The input recorder owns its own database. :core declares the same driver as `implementation`, so it is
    // only on the runtime classpath here.
    implementation(libs.sqlite.jdbc)

    // The overlay's panels are Compose, rendered offscreen by Skia and drawn through the ImGui hook as a texture.
    implementation(libs.compose.desktop)
    runtimeOnly(libs.skiko.runtime.windows.x64)
    runtimeOnly(libs.skiko.runtime.linux.x64)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    // Opt-in inputs for tests that otherwise skip: fixture regeneration, and a cache copy for route tests. Gradle
    // does not forward -D to the test JVM, so the few properties that drive them are passed through by name.
    for (name in listOf("projectx.regenerateSchemaMirror", "projectx.testCache")) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

val javaHomeProvider = providers.environmentVariable("JAVA_HOME")
    .orElse(providers.systemProperty("java.home"))

val nativeBootstrapSourceDir = layout.projectDirectory.dir("native-bootstrap")
val nativeBootstrapBuildDir = nativeBootstrapSourceDir.dir("build")
val hostOsName = providers.systemProperty("os.name").map { it.lowercase() }.getOrElse("")
val hostIsWindows = hostOsName.contains("win")

// Injected into rs2client as a shared library: a .so on Linux via GDB-dlopen, a .dll on Windows via
// CreateRemoteThread+LoadLibraryW. CMake picks the platform sources; the name differs per platform.
val nativeBootstrapLibName = if (hostIsWindows) "projectxbootstrap.dll" else "libprojectxbootstrap.so"
val nativeBootstrapLib = nativeBootstrapBuildDir.file(nativeBootstrapLibName)
val nativeBootstrapPreset = if (hostIsWindows) "windows-msvc" else "linux-clang"

// Resolved at configuration time so a machine without the toolchain still gets a working
// `./gradlew build` for the server modules instead of a hard cmake failure. macOS has no bootstrap
// port yet, so it is excluded rather than silently attempting a build that cannot link.
val hostIsSupported = hostOsName.contains("linux") || hostIsWindows
val cmakeOnPath = providers.environmentVariable("PATH")
    .map { path -> path.split(File.pathSeparator).any { dir ->
        File(dir, "cmake").canExecute() || File(dir, "cmake.exe").canExecute()
    } }
    .orElse(false)
val nativeToolchainAvailable = hostIsSupported && cmakeOnPath.get()

tasks.register<Exec>("configureNativeBootstrap") {
    // Invokes cmake via Exec, feeding JAVA_HOME (for the JNI headers) read through the providers
    // API so the value becomes a configuration-cache input rather than a captured script reference.
    val toolchain = nativeToolchainAvailable
    onlyIf("the host has a supported native toolchain and cmake is on PATH") { toolchain }
    workingDir = nativeBootstrapSourceDir.asFile
    commandLine = listOf("cmake", "-B", "build", "-DCMAKE_BUILD_TYPE=Debug")
    // Preset selection is informational for now: both presets configure into build/ with the same
    // cache variables, and CMakeLists picks the platform sources off WIN32 regardless.
    logger.lifecycle("configuring native bootstrap with the $nativeBootstrapPreset toolchain")
    environment("JAVA_HOME", javaHomeProvider.get())
}

// No declared outputs: cmake does its own incremental check, and an up-to-date shortcut here would
// silently keep a stale .so whenever a source outside the declared input set changed.
tasks.register<Exec>("buildNativeBootstrap") {
    dependsOn("configureNativeBootstrap")
    val toolchain = nativeToolchainAvailable
    onlyIf("the host has a supported native toolchain and cmake is on PATH") { toolchain }
    workingDir = nativeBootstrapBuildDir.asFile

    // No explicit --target: "all" is the Make/Ninja default-target name, but the Visual Studio
    // generator's default target is "ALL_BUILD" - omitting --target builds the default on every
    // generator instead of hardcoding a name that only exists on some of them.
    commandLine = listOf("cmake", "--build", ".")

    // Exec streams cmake output to the console by default; assigning System.out/err here would
    // capture non-serializable PrintStreams and force the configuration cache to be discarded.
    finalizedBy("copyNativeLibToBuild")
}

// The launcher's auto-injector and run-projectx.sh both resolve the bootstrap out of build/libs, so
// a build that compiled the .so but never placed it there turns into a silent injection failure at
// launch time — verify the copy landed instead of letting an empty Copy pass.
tasks.register<Copy>("copyNativeLibToBuild") {
    dependsOn("buildNativeBootstrap")
    val toolchain = nativeToolchainAvailable
    onlyIf("the host has a supported native toolchain and cmake is on PATH") { toolchain }
    val builtLib = nativeBootstrapLib.asFile
    val installedLib = layout.buildDirectory.file("libs/${builtLib.name}").get().asFile
    from(builtLib)
    into(installedLib.parentFile)

    doLast {
        if (!installedLib.isFile) {
            throw GradleException("buildNativeBootstrap produced no $builtLib to install at $installedLib")
        }
    }
}

tasks.withType<Zip> {
    dependsOn("copyNativeLibToBuild")
}

tasks.withType<Tar> {
    dependsOn("copyNativeLibToBuild")
}

tasks.named("startShadowScripts") {
    dependsOn("copyNativeLibToBuild")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    // The gameval id<->name dictionaries are read from the working tree when there is one. A
    // packaged artifact has no working tree, so it has to carry its own copy or every name lookup
    // in the engine fails at runtime - which is how a zip-only install used to die on its first
    // tick. Held as a local, not a script property: the configuration cache cannot serialise a
    // reference back into the build script from a task action.
    val dictionaries = rootProject.file("re-resources/gamevals")
    from("src/main/resources") {
        include("**/*")
    }
    from(dictionaries) {
        into("gamevals")
        include("*.json")
    }
    doFirst {
        val count = dictionaries.listFiles { f: File -> f.name.endsWith(".json") }?.size ?: 0
        if (count == 0) {
            throw GradleException(
                "no gameval dictionaries at $dictionaries - the shadow jar would ship without them and " +
                    "every gameval-backed feature would fail at runtime. Run: git submodule update --init re-resources"
            )
        }
    }
    // EngineHandle must exist ONLY in the supervisor jar (the shared parent loader). If the shadow
    // jar also carried it, the child loader would define a second copy → ClassCastException on the
    // (EngineHandle) cast in Supervisor.
    exclude("com/projectx/supervisor/**")
}

// Place the pure-Java supervisor jar next to the engine shadow jar (PROJECTX_HOME_DIR) under a
// fixed name so the native bootstrap can put it (and only it) on the JVM system classpath.
// Declare a precise single-file output (NOT a Copy into build/libs, which would claim the whole
// dir as output and trip Gradle's implicit-dependency check against startScripts/dist tasks that
// read the engine jars from the same dir).
val supervisorJarFile = project(":client-plugin-engine-supervisor").tasks.named<Jar>("jar").flatMap { it.archiveFile }
tasks.register("copySupervisorJar") {
    val src = supervisorJarFile
    val dst = layout.buildDirectory.file("libs/projectx-supervisor.jar")
    inputs.file(src)
    outputs.file(dst)
    doLast {
        src.get().asFile.copyTo(dst.get().asFile.apply { parentFile.mkdirs() }, overwrite = true)
    }
}

tasks.named("assemble") { dependsOn("copySupervisorJar", "copyNativeLibToBuild") }

// Every engine log opens by naming the build it came from, so a report from any platform says
// which jar produced it.
val engineBuildStamp by tasks.registering {
    // CI already knows the commit; only a local build asks git, and a checkout without git still builds.
    val fromGit = providers.exec { commandLine("git", "rev-parse", "--short=10", "HEAD"); isIgnoreExitValue = true }
        .standardOutput.asText.map { it.trim() }
    val commit = providers.environmentVariable("CI_COMMIT_SHA").map { it.take(10) }
        .orElse(fromGit)
        .map { it.ifEmpty { "unknown" } }
    val stamp = layout.buildDirectory.file("generated/engine-build/engine-build.properties")
    inputs.property("commit", commit)
    outputs.file(stamp)
    doLast {
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("commit=${commit.get()}\nbuiltAt=${Instant.now()}\n")
        }
    }
}

tasks.processResources {
    dependsOn(engineBuildStamp)
    from(engineBuildStamp.map { it.outputs.files.singleFile })
}
