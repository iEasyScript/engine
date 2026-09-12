# Script modules: official-scripts (default) + community-scripts (opt-in)

The concrete automation scripts do **NOT** live in `:client-plugin-engine` anymore. The old
`example-script-module` was renamed to **`:client-plugin-engine:official-scripts`** and the whole
`com.projectx.script.impl` package was moved OUT of `:client-plugin-engine` into two subprojects
(`client-plugin-engine/src/main/kotlin/com/projectx/script/impl/` no longer exists):

- **`:client-plugin-engine:official-scripts`** (`client-plugin-engine/official-scripts/`) —
  first-party, **built by default**. Holds `impl/trent` + `impl/devin` (incl. the
  `impl/trent/dungeoneering` suite that already lived here) and the
  `com.projectx.debugger.ExampleModuleScript` demo. Deps: `:client-plugin-engine`, `:core`,
  `libs.kotlinx.serialization.json`.
- **`:client-plugin-engine:community-scripts`** (`client-plugin-engine/community-scripts/`) —
  community-contributed (`impl/qb`, `impl/bp`, `impl/gibson`, `impl/mel`, `impl/pineapple`,
  `impl/BugAbuser`, `impl/TestScript.kt`). **Excluded from the default build** —
  `settings.gradle.kts` only `include`s it when the `communityScripts` Gradle property **or**
  `COMMUNITY_SCRIPTS` env var is set
  (`./gradlew -PcommunityScripts :client-plugin-engine:community-scripts:build`). Deps:
  `:client-plugin-engine`, `:core`, serialization-json, **`:client-plugin-engine:official-scripts`**
  (mel/DeepSeaFishing reuses `trent.SpiritAttractionPotion`).

Packages were NOT renamed — everything stays `com.projectx.script.impl.*`; both jars land in
`~/.projectx/scripts/` and are discovered via ClassGraph (`ScriptExecutor`/`ScriptLoader`), no
engine change. The engine only provides the framework now.

## Two non-obvious gotchas the move exposed (do not "fix" by reverting)

1. **Engine script-facing API had to be widened `internal`→public.** While scripts lived inside
   `:client-plugin-engine`, they could call `internal` members. Moving them out broke that. Widened:
   `ui.backend.dsl.scopes.LayoutScope` interface + **all** `LayoutScopeExtensions.kt` funcs
   (the whole ImGui UI DSL: `text`/`image`/`xpProgressBar`/`table`/`tree`/… now public);
   `StateMachineScript.currentState` `internal`→`protected` (subclasses read it for a "current
   stage" render). If a new engine helper is script-facing, make it public/protected, not internal.
2. **Cross-module smart-cast fails on `ManualDoAction.target: Any`.** Kotlin refuses to smart-cast
   a `val` property declared in a *different* module (can't assume a stable getter). So
   `if (event.target !is SceneObject) return; val x = event.target` no longer narrows `x` — it
   stays `Any` → "unresolved reference hasOption/name/tile/defs". Fix in the script: explicit
   `val x = event.target as SceneObject` (or `as NPC`) — safe after the `is` guard; or bind a local
   first (`val t = event.target; if (t !is SceneObject) return` — a *local* val smart-casts fine).
3. **`:client-plugin-engine` keeps gson as a non-transitive `implementation`.** Any engine interface
   a script module overrides that exposes a gson type in its signature forces that module to declare
   gson itself. Prefer keeping script-facing signatures free of gson types.

⛔ Stale `~/.projectx/scripts/example-script-module*.jar` from before the rename must be deleted, or
ScriptExecutor discovers BOTH old and new jars → duplicate `impl.*` classes. Related:
[[script-authoring-principles]] [[engine-build-inject-workflow]] [[hard-rules]]
