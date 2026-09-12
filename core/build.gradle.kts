import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.serialization)
    // Shares CacheFixture with every module's tests, so the "is there a cache?" guard is written once.
    `java-test-fixtures`
}

dependencies {
    // api: these types appear in core's public API (buffer/codec/model/mongo signatures),
    // so dependent modules (lobby/world/tools) need them on their compile classpath.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.io.core)
    api(libs.ktor.io)
    api(libs.ktor.network)
    api(libs.mongodb.driver.kotlin.coroutine)
    api(libs.fastutil)

    implementation(libs.kotlin.reflect)
    implementation(libs.argon2.jvm)
    implementation(libs.dotenv.kotlin)
    implementation(libs.lzma.java)
    implementation(libs.classgraph)
    implementation(libs.sqlite.jdbc)
    implementation(libs.commons.compress)

    testImplementation(kotlin("test"))
    testFixturesApi(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/** The cache source tree's parity suite: every test in it needs a real game cache and reads all of it. */
val cacheParityTests = listOf(
    "world.gregs.voidps.cache.source.*ParityTest",
    "world.gregs.voidps.cache.source.WholeCacheSourceTest"
)

fun Test.cacheScratch() {
    // The cache, the gamevals and the solved CS2 opcode tables are all named relative to the root.
    workingDir = rootProject.projectDir
    // Whole-cache round trips write tens of gigabytes; keep them off the NVMe and off the tmpfs.
    val scratch = File(System.getenv("PROJECTX_TEST_TMP") ?: "/mnt/trent/ssd1tb1/projectx-tmp/tests")
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    maxHeapSize = System.getenv("PROJECTX_TEST_HEAP") ?: "8g"
}

tasks.test {
    useJUnitPlatform()
    cacheScratch()
    // The parity suite is 94% of this task's runtime for 6% of its tests, and it silently skips
    // wherever no cache exists - so it proves nothing on CI and costs ten minutes locally.
    filter { cacheParityTests.forEach { pattern -> excludeTestsMatching(pattern) } }
}

/** The cache parity suite, run on demand rather than on every build. */
tasks.register<Test>("cacheParityTest") {
    group = "verification"
    description = "Round-trips the real game cache through the source tree. Needs a cache; takes minutes."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    cacheScratch()
    filter { cacheParityTests.forEach { pattern -> includeTestsMatching(pattern) } }
    outputs.upToDateWhen { false }
}
