rootProject.name = "projectx"

include("core")
include("tools")

// Packet data logging: local SQLite store, chunk codec and deferred decode replay.
// Depended on by both the engine and :tools, so it cannot live inside either.
include("packetlog")

// Project X injection engine (Kotlin/JVM + C++ native bootstrap) merged in as a first-class module.
include("client-plugin-engine")
// First-party scripts shipped by default (trent + devin authorship).
include("client-plugin-engine:official-scripts")
// Community-contributed scripts: optional, NOT part of the default build. Enable with
// -PcommunityScripts (or the COMMUNITY_SCRIPTS env var) to include & build them.
if (startParameter.projectProperties.containsKey("communityScripts") ||
    System.getenv("COMMUNITY_SCRIPTS") != null
) {
    include("client-plugin-engine:community-scripts")
}
// Tiny pure-Java supervisor: the permanent layer that loads/unloads the engine via a disposable
// URLClassLoader for hot-reload. The ONLY engine code on the JVM system classpath.
include("client-plugin-engine-supervisor")
