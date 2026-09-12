plugins {
    // kotlin.jvm + java-library come from the root `subprojects {}` block; repositories from
    // `allprojects {}`. Only the application plugin is module-specific here.
    application
}

group = "com.projectx"

application {
    mainClass.set("com.projectx.ApplicationKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation(project(":client-plugin-engine"))
    // :client-plugin-engine depends on :core as `implementation`, so core types (world.gregs.voidps.type.Tile, the
    // cache library) aren't exposed transitively — scripts that use them need core on the compile path.
    implementation(project(":core"))
    // Dungeoneering publishes debug snapshots the engine's MCP relays; building them needs the JSON DSL.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Copy>("copyJarToProjectXScripts") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs")) {
        include("*.jar")
    }
    // user.home resolved lazily (configuration-cache safe).
    into(providers.systemProperty("user.home").map { "$it/.projectx/scripts" })
}

tasks.named("jar") {
    finalizedBy("copyJarToProjectXScripts")
}
