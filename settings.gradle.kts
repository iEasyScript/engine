rootProject.name = "projectx"

include("core")
include("tools")

// Packet data logging: local SQLite store, chunk codec and deferred decode replay.
// Depended on by both the engine and :tools, so it cannot live inside either.
include("packetlog")

// Project X injection engine (Kotlin/JVM + C++ native bootstrap) merged in as a first-class module.
// Scripts are not built here: they live in the public official-scripts and community-scripts repositories.
include("client-plugin-engine")
// Tiny pure-Java supervisor: the permanent layer that loads/unloads the engine via a disposable
// URLClassLoader for hot-reload. The ONLY engine code on the JVM system classpath.
include("client-plugin-engine-supervisor")
