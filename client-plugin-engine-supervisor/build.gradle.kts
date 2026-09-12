// Pure-Java supervisor (no Kotlin sources, no third-party deps). It is loaded ONCE on the JVM
// system classpath and loads the engine into a disposable URLClassLoader, so a rebuilt engine
// shadow jar can be hot-reloaded without recreating the JVM. Keeping it dependency-free is what
// lets the engine's own kotlin-stdlib/coroutines/etc. load fresh in the child loader each reload.
base {
    archivesName = "projectx-supervisor"
}
