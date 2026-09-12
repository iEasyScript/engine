# Scripts live outside the engine repository

No script is built in this monorepo. First-party scripts are in the public `iEasyScript/official-scripts`
repository and community scripts in `iEasyScript/community-scripts`; both compile against the jars published
by `iEasyScript/script-api` and release their own jar, which the launcher installs into `~/.projectx/scripts/`.
The engine only provides the framework and discovers jars at runtime via ClassGraph.

The Undercut-era script modules (`impl/trent`, `impl/devin`, `impl/qb`, `impl/bp`, …) were deliberately removed
at the user's request: the script catalogue starts fresh. Do not restore them from git history.

## Gotchas that apply to any script built outside the engine module

1. **Engine script-facing API must be public.** Scripts in another module cannot call `internal` members.
   A new script-facing helper is public/protected (e.g. the whole `LayoutScope` UI DSL,
   `StateMachineScript.currentState` as `protected`).
2. **Cross-module smart-cast fails on `ManualDoAction.target: Any`.** Kotlin will not smart-cast a `val`
   declared in another module. Bind a local first (`val t = event.target; if (t !is SceneObject) return`)
   or cast explicitly after the `is` guard.
3. **`:client-plugin-engine` keeps gson as a non-transitive `implementation`.** Keep script-facing
   signatures free of gson types, or every script repository has to declare gson itself.

⛔ Stale jars in `~/.projectx/scripts/` are all discovered, so an old jar left beside a new one yields
duplicate `impl.*` classes. Related: [[script-authoring-principles]] [[engine-build-inject-workflow]] [[hard-rules]]
