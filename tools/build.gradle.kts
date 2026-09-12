plugins {
    application
}

application {
    mainClass.set(providers.gradleProperty("mainClass").getOrElse("org.projectx.tools.cachedownloader.MainKt"))
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    implementation(libs.ktor.io)
    // client-updater tool: decode the LZMA-alone stream Jagex serves the NXT binary in
    // (same lzma.sdk decoder the cache library uses for container type 3).
    implementation(libs.lzma.java)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/** The CS2 half of the cache source tree's parity suite: needs a real game cache and reads every clientscript. */
val cacheParityPackage = "org.projectx.tools.cachesource.*"

fun Test.cacheScratch() {
    workingDir = rootProject.projectDir
    maxHeapSize = System.getenv("PROJECTX_TEST_HEAP") ?: "8g"
    // Whole-cache round trips write tens of gigabytes; keep them off the NVMe and off the tmpfs.
    val scratch = File(System.getenv("PROJECTX_TEST_TMP") ?: "/mnt/trent/ssd1tb1/projectx-tmp/tests")
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
}

tasks.test {
    useJUnitPlatform()
    cacheScratch()
    // The parity test is this task's only test and takes over a minute against the real cache, and
    // it silently skips wherever no cache exists - so it proves nothing on CI and costs a minute locally.
    filter {
        excludeTestsMatching(cacheParityPackage)
        isFailOnNoMatchingTests = false
    }
}

/** The CS2 cache parity test, run on demand rather than on every build. */
tasks.register<Test>("cacheParityTest") {
    group = "verification"
    description = "Round-trips every clientscript through the source tree. Needs a cache; takes a minute."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    cacheScratch()
    filter { includeTestsMatching(cacheParityPackage) }
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("rsaKeyGen") {
    mainClass.set("org.projectx.tools.keygen.RsaKeyGenKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// JS5 beta-cache scanner — isolated tool that scans/diffs/downloads a JS5 host's cache
// without ever touching the live game cache. Pass flags via -Pargs="...".
//   ./gradlew :tools:betaScanner -Pargs="--host content.runescape.com --scan"
tasks.register<JavaExec>("betaScanner") {
    mainClass.set("org.projectx.tools.betascanner.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

// Gameval JSON exporter — decode cache index 67 (gameval/RSCM) into our own prettified per-type JSON.
//   ./gradlew :tools:gamevalExport -Pargs="--cache ./data/betacache --out re-resources/gamevals"
tasks.register<JavaExec>("gamevalExport") {
    mainClass.set("org.projectx.tools.gamevalexport.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

// NPC coordinate fetcher — the cache has no npc positions, so the RS3 wiki is queried offline and
// the result is read by the engine at runtime. Never called from the injected client.
//   ./gradlew :tools:npcLocationFetch -Pargs="--cache ./data/cache --out ~/.projectx/leagues/npc-locations.json"
tasks.register<JavaExec>("npcLocationFetch") {
    mainClass.set("org.projectx.tools.npclocations.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}





tasks.register<JavaExec>("cacheSnapshot") {
    mainClass.set("org.projectx.tools.cachesnapshot.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.register<JavaExec>("cacheDiff") {
    mainClass.set("org.projectx.tools.cachediff.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.register<JavaExec>("decodeCheck") {
    mainClass.set("org.projectx.tools.decodecheck.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.register<JavaExec>("cs2") {
    mainClass.set("org.projectx.tools.cs2.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.register<JavaExec>("cacheUnpack") {
    mainClass.set("org.projectx.tools.cacheunpack.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.register<JavaExec>("cacheSource") {
    mainClass.set("org.projectx.tools.cachesource.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Xmx12g")
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}
