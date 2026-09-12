plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.lzma.java)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Imports a legacy text dump into a packet database.
//   ./gradlew :packetlog:textLogImport -Pargs="~/.projectx/logs/packets-<date>.log /tmp/out.db live"
tasks.register<JavaExec>("textLogImport") {
    mainClass.set("org.projectx.packetlog.tool.TextLogImportKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

// Enrol this machine with an ingest server, or push an archive to it.
//   ./gradlew :packetlog:packetUpload -Pargs="enrol <invite-code>"
//   ./gradlew :packetlog:packetUpload -Pargs="upload ~/.projectx/packetlog/live/archive.db --dry-run"
tasks.register<JavaExec>("packetUpload") {
    mainClass.set("org.projectx.packetlog.upload.UploadCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

// Run the capture agent in the foreground - the same entry point the engine spawns detached.
//   ./gradlew :packetlog:packetAgent
//   ./gradlew :packetlog:packetAgent -Pargs="--once --endpoint http://localhost:8099"
tasks.register<JavaExec>("packetAgent") {
    mainClass.set("org.projectx.packetlog.agent.PacketAgentKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

// Build and query the investigation index.
//   ./gradlew :packetlog:packetQuery -Pargs="index /tmp/q.db ~/.projectx/packetlog/live/archive.db"
//   ./gradlew :packetlog:packetQuery -Pargs="around /tmp/q.db --path varp --value 7267 --after 3"
tasks.register<JavaExec>("packetQuery") {
    mainClass.set("org.projectx.packetlog.query.QueryCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    val rawArgs = providers.gradleProperty("args").getOrElse("")
    if (rawArgs.isNotBlank()) args(rawArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    // The bake-off measures real captures, whose path only the developer running it knows.
    System.getProperty("projectx.bakeoff.log")?.let { systemProperty("projectx.bakeoff.log", it) }
    System.getProperty("projectx.archive")?.let { systemProperty("projectx.archive", it) }
    System.getProperty("projectx.endpoint")?.let { systemProperty("projectx.endpoint", it) }
    System.getProperty("projectx.regeneratePacketVector")
        ?.let { systemProperty("projectx.regeneratePacketVector", it) }
}
