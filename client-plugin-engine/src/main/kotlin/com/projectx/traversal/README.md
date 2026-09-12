# Traversal package usage

This package provides a tiny DSL to build movement/interaction sequences as a `State<T>` in your state machine.

Core types:
- `Traversal<T>`: a `State<T>` that processes a linked list of `TraversalNode`s until done, then transitions to a given `next` state.
- `TraversalBuilder<T>`: DSL to append nodes like paths, object interactions, UI clicks, doors, and lodestone teleports.
- `TraversalNode`: abstract node with `process()` and `reached()` implemented by concrete nodes.

Key files:
- `Traversal.kt` — `Traversal` state and `Traversal.traversal(...)` DSL entry.
- `TraversalBuilder.kt` — builder methods for nodes.
- `TraversalProcessor.kt` — executes nodes and handles transitions.
- `nodes/*.kt` — concrete nodes: `PathNode`, `ObjectNode`, `NpcNode`, `IFSlotNode`, `TileExactNode`, `DoorNode`, `LodestoneNode`, `ChebychevNode`.

---

## Table of contents

- Quick start
- Builder DSL reference
- Nodes overview
- Doors
- Example: Teleport, walk, interact
- Execution model (important)
- PlayerProfiles touch points
- Extending the DSL
- Custom node example
- Troubleshooting
- FAQ
- Tips

## Quick start

Use `Traversal.traversal(next, finishedCondition) { ... }` to build a traversal state and return it from a state's `checkNext()`.

```kotlin
import com.projectx.game.Tile
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Lodestone
import com.projectx.traversal.Traversal

class GoToVarrock : State<MyScript>() {
    override suspend fun MyScript.checkNext(): State<MyScript>? {
        val target = Tile.of(3210, 3424, 0)
        return Traversal.traversal(
            next = Arrived(),
            finishedCondition = { localPlayer.tile.getDistance(target) <= 5 }
        ) {
            // Optional: teleport first
            useLodestone(Lodestone.VARROCK) { localPlayer.tile.getDistance(target) <= 25 }

            // Walk the path to the target
            path(localPlayer.tile, target) { localPlayer.tile.getDistance(target) <= 5 }
        }
    }

    override suspend fun MyScript.stateLoop() { /* no-op: traversal drives itself */ }
}
```

Notes:
- `Traversal` is itself a `State<T>`. Returning it swaps into that traversal state until it completes, then transitions to `next`.
- Completion happens when either:
  - `finishedCondition(this)` becomes true (checked first), or
  - the last node is reached and there is no next node.

---

## Builder DSL reference
Builder lives in `TraversalBuilder.kt`.

- `interactObj(action: String, searchRadius: Int = 30)`
- `interactObj(id: Int, action: String, searchRadius: Int = 30)`
- `interactObj(name: String, action: String, searchRadius: Int = 30)`
- `interactObj(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean)`
- `interactObj(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean)`
  - Interact with a nearby `SceneObject` by id or name. Optional `reached` custom condition marks this node as completed.

- `interactNpc(action: String, searchRadius: Int = 30)`
- `interactNpc(id: Int, action: String, searchRadius: Int = 30)`
- `interactNpc(name: String, action: String, searchRadius: Int = 30)`
- `interactNpc(id: Int, action: String, searchRadius: Int = 30, reached: () -> Boolean)`
- `interactNpc(name: String, action: String, searchRadius: Int = 30, reached: () -> Boolean)`
  - Interact with a nearby `NPC` by id or name. Optional `reached` custom condition marks this node as completed.

- `clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, reached: (() -> Boolean)? = null)`
- `clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, destination: Area)`
  - Click a UI slot. You can finish the node by `reached` or by reaching a target `Area`.

- `path(start: Tile, end: Tile, reached: (() -> Boolean)? = null)`
  - Pathfinding walk from `start` to `end`. Recalculates if needed. Defaults to completed when within 5 tiles of `end`, unless you provide `reached`.
 
- `chebychevPath(start: Tile, end: List<Tile>, reached: (() -> Boolean)? = null)`
  - Walk a sequence of key tiles with Chebyshev-distance smoothing and far-future clicks. Defaults to completed when within 5 tiles of the last `end` tile, unless you provide `reached`.

- `walkExact(tile: Tile, minimap: Boolean = false, reached: () -> Boolean)`
  - Walk to an exact tile using world or minimap clicks. You must provide a `reached` check.

- `door(doorInfo: DoorInfo, direction: DoorDirection, reached: (() -> Boolean)? = null)`
- `doorIn(doorInfo: DoorInfo, reached: (() -> Boolean)? = null)`
- `doorOut(doorInfo: DoorInfo, reached: (() -> Boolean)? = null)`
  - Open/transition through a door and walk to the inside/outside tile. See Door section below.

- `useLodestone(lodestone: Lodestone, reached: (() -> Boolean)? = null)`
  - Teleport via lodestone and wait for arrival. Defaults to reached when near the lodestone tile and not animating.

- `build(next: State<T>, finishedCondition: T.() -> Boolean): Traversal<T>`
  - Automatically called by `Traversal.traversal(...)`.

---

## Nodes overview

### PathNode — `nodes/PathNode.kt`
* __Purpose__: Walk along a path from `start` to `end` using pathfinding.
* __Builder__: `path(start: Tile, end: Tile, reached: (() -> Boolean)? = null)`
* __Reached (default)__: within 5 tiles of `end` or your custom `reached`.
* __Internals__:
  - Computes a `Route` in `init` and recalculates if `!route.success` during `process()`.
  - Click pacing via `PlayerProfiles.walkPathClickTime` and `gaussian(...)` jitter.
  - Chooses a future waypoint from the route based on `PlayerProfiles.futurePathStepMin/Max` and injects deviation using `PlayerProfiles.walkPathDeviation` if a short detour is valid.
  - Mixes world vs minimap clicks based on `PlayerProfiles.minimapWalkPerc`.
* __Example__:
```kotlin
path(localPlayer.tile, Tile.of(3210, 3424, 0)) {
    localPlayer.tile.getDistance(Tile.of(3210, 3424, 0)) <= 5
}
```

### ChebychevNode — `nodes/ChebychevNode.kt`
* __Purpose__: Walk a sequence of key tiles using Chebyshev-distance smoothing and far-future clicks.
* __Builder__: `chebychevPath(start: Tile, end: List<Tile>, reached: (() -> Boolean)? = null)`
* __Reached (default)__: within 5 tiles of the last tile in `end`, or your custom `reached`.
* __Internals__:
  - Builds an interpolated route with `smoothPathChebyshev(...)` using a step size sampled from `PlayerProfiles.futurePathStepMin/Max`.
  - Chooses the farthest future tile within ~1.5× the step size; applies deviation via `PlayerProfiles.walkPathDeviation`.
  - Uses world vs minimap clicks based on `PlayerProfiles.minimapWalkPerc`; click pacing via `PlayerProfiles.walkPathClickTime`.
  - Waits until near the clicked tile (< 3) before issuing the next click.
* __Example__:
```kotlin
val keypoints = listOf(
    Tile.of(3205, 3420, 0),
    Tile.of(3210, 3424, 0),
    Tile.of(3215, 3428, 0)
)
chebychevPath(start = localPlayer.tile, end = keypoints) {
    localPlayer.tile.getDistance(keypoints.last()) <= 5
}
```

### ObjectNode — `nodes/ObjectNode.kt`
* __Purpose__: Interact with world objects by id, name, or direct reference.
* __Builder__:
  - `interactObj(action: String, searchRadius: Int = 30)` (any object with that option)
  - `interactObj(id: Int, action: String, searchRadius: Int = 30, reached?: () -> Boolean)`
  - `interactObj(name: String, action: String, searchRadius: Int = 30, reached?: () -> Boolean)`
* __Matching__:
  - By name: closest reachable object whose `name().contains(name)` and (if provided) has `action`.
  - By id: closest reachable object with matching `realId` (id of `-1` means any id) and (if provided) has `action`.
  - Search radius defaults to 30 in the builder (node default is 20; builder overrides it).
* __Reached (default)__: `false` unless you provide `destination: Area` or a custom `reached` predicate.
  - Implementation: `customReached?.invoke() ?: destination?.inside(localPlayer.tile)` must be true.
  - Implication: Without one of these, the node keeps interacting until traversal finishes via the global `finishedCondition`.
* __Internals__:
  - Rate-limited clicks via `nextClick` and `PlayerProfiles.walkPathClickTime`.
  - Invokes `obj.interact(action)` if an action is provided, else `obj.interact(0)`.
  - Waits until the player stops moving (`delayUntil { !localPlayer.isMoving }`).
* __Example__:
```kotlin
// Interact until the bank opens (custom reached), searching within 30 tiles
interactObj(name = "Bank booth", action = "Bank", searchRadius = 30) { /* e.g. bank interface opened */ bank.isOpen }
```

### NpcNode — `nodes/NpcNode.kt`
* __Purpose__: Interact with NPCs by id, name, or direct reference.
* __Builder__:
  - `interactNpc(action: String, searchRadius: Int = 30)` (any NPC with that option)
  - `interactNpc(id: Int, action: String, searchRadius: Int = 30, reached?: () -> Boolean)`
  - `interactNpc(name: String, action: String, searchRadius: Int = 30, reached?: () -> Boolean)`
* __Matching__:
  - By name: closest reachable NPC whose `name().contains(name)` and (if provided) has `action`.
  - By id: closest reachable NPC with matching `realId` (id of `-1` means any id) and (if provided) has `action`.
  - Search radius defaults to 30 in the builder (node default is 20; builder overrides it).
* __Reached (default)__: `false` unless you provide `destination: Area` or a custom `reached` predicate.
  - Implementation: `customReached?.invoke() ?: destination?.inside(localPlayer.tile)` must be true.
  - Implication: Without one of these, the node keeps interacting until traversal finishes via the global `finishedCondition`.
* __Internals__:
  - Rate-limited clicks via `nextClick` and `PlayerProfiles.walkPathClickTime`.
  - Invokes `npc.interact(action)` if an action is provided, else `npc.interact(0)`.
  - Waits until the player stops moving (`delayUntil { !localPlayer.isMoving }`).
* __Example__:
```kotlin
// Talk to a banker until the bank opens (custom reached), within 30 tiles
interactNpc(name = "Banker", action = "Bank", searchRadius = 30) { bank.isOpen }
```

### IFSlotNode — `nodes/IFSlotNode.kt`
* __Purpose__: Click an interface slot, optionally with a particular option.
* __Builder__:
  - `clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, reached: (() -> Boolean)? = null)`
  - `clickIFSlot(ifSlot: IFSlot, optionNum: Int = 1, destination: Area)`
* __Reached (default)__: `false` unless you provide `destination` or `reached`.
  - Implementation mirrors `ObjectNode` (`customReached ?: destination.inside(...)`).
* __Internals__:
  - `ifSlot.click(optionNum)` and then waits until not moving; click pacing identical to `ObjectNode`.
* __Example__:
```kotlin
clickIFSlot(ifSlot = mySlot, optionNum = 2) {
    someInterface.isOpen
}
```

### TileExactNode — `nodes/TileExactNode.kt`
* __Purpose__: Walk to an exact tile using world or minimap clicks.
* __Builder__: `walkExact(tile: Tile, minimap: Boolean = false, reached: () -> Boolean)`
* __Reached__: required. Provide a predicate that returns true when you consider arrival complete (e.g., exact tile match or UI state).
* __Internals__:
  - Uses `script.walkTo(tile, minimap)` and waits until the player stops moving; click pacing via profiles.
* __Example__:
```kotlin
walkExact(targetTile, minimap = false) { localPlayer.tile.matches(targetTile) }
```

### DoorNode — `nodes/DoorNode.kt`
* __Purpose__: Open a door if needed and move to the inside/outside tile.
* __Builder__:
  - `door(doorInfo: DoorInfo, direction: DoorDirection, reached?: () -> Boolean)`
  - `doorIn(doorInfo: DoorInfo, reached?: () -> Boolean)` / `doorOut(doorInfo: DoorInfo, reached?: () -> Boolean)`
* __DoorInfo fields__:
  - `realIdOpen`, `realIdClosed`, `locationOpen`, `locationClosed`, `tileInside`, `tileOutside`, `openAction? = "Open"`.
* __Reached (default)__: player is exactly on the target tile (`tileInside` for IN, `tileOutside` for OUT). Override via `reached` if needed.
* __Internals__:
  - Searches for the door by real id near the specified locations, preferring the closed door.
  - If already open: `walkTo(targetTile)` then wait until not moving.
  - If closed: `doorObj.interact(openAction)` → wait until open → small delay → `walkTo(targetTile)`.
  - Click pacing via `PlayerProfiles.walkPathClickTime`.
* __Example__:
```kotlin
doorIn(doorInfo) { localPlayer.tile.matches(doorInfo.tileInside) }
```

### LodestoneNode — `nodes/LodestoneNode.kt`
* __Purpose__: Teleport using a lodestone, then continue.
* __Builder__: `useLodestone(lodestone: Lodestone, reached: (() -> Boolean)? = null)`
* __Reached (default)__: near the lodestone tile (<= 10) and not animating; or your custom predicate.
* __Internals__:
  - Uses `script.useLodestone(lodestone)` with rate limiting via `nextClick` and `PlayerProfiles.walkPathClickTime`.
  - Brief delay after invocation to allow teleportation to start.
* __Example__:
```kotlin
useLodestone(Lodestone.VARROCK) {
    localPlayer.tile.getDistance(Lodestone.VARROCK.tile) <= 15
}
```

---

## Doors

See `nodes/DoorNode.kt` for data requirements.

```kotlin
import com.projectx.game.Tile
import com.projectx.traversal.nodes.DoorDirection
import com.projectx.traversal.nodes.DoorInfo

val door = DoorInfo(
    realIdOpen = 12345,
    realIdClosed = 12346,
    locationOpen = Tile.of(3200, 3400, 0),
    locationClosed = Tile.of(3200, 3401, 0),
    tileInside = Tile.of(3201, 3400, 0),
    tileOutside = Tile.of(3199, 3400, 0),
    openAction = "Open" // optional
)

val state = Traversal.traversal(next = AfterDoor(), finishedCondition = { false }) {
    doorIn(door) // or: door(door, DoorDirection.IN)
}
```

`DoorNode` automatically:
- Finds the door by `realId` around the specified tiles.
- Opens it if closed (`openAction`), then walks to the target side.
- Marks reached when the player stands on the target tile (or your custom `reached`).

---

## Example: Teleport, walk, interact

```kotlin
val bankTile = Tile.of(3185, 3436, 0)
val state = Traversal.traversal(next = Banking(), finishedCondition = { localPlayer.tile.getDistance(bankTile) <= 4 }) {
    useLodestone(Lodestone.VARROCK) { localPlayer.tile.getDistance(bankTile) <= 25 }
    path(localPlayer.tile, bankTile) { localPlayer.tile.getDistance(bankTile) <= 4 }
    interactObj(name = "Bank booth", action = "Bank", searchRadius = 30) { /* e.g. bank interface opened */ bank.isOpen }
}
```

---

## Execution model (important)

The `Traversal` is itself a `State<T>`. Control flow in `Traversal.kt` and `TraversalProcessor.kt` is:

- `Traversal.checkNext()` returns `next` when `processor.process(script)` returns `false` (traversal finished). Otherwise it returns `null` to keep running current traversal.
- `TraversalProcessor.process(script): Boolean` advances according to this exact logic:
  - If `script.finishedCondition()` is `true` → return `false` (immediate transition to `next`).
  - Else if `curr.reached(script)` is `true`:
    - If `curr.next != null` → set `curr = curr.next!!`, return `true` (continue).
    - Else → return `false` (no more nodes → transition).
  - Else if `curr.process(script)` returns `true` → return `true` (stay on same node).
  - Else (node signaled it cannot progress right now):
    - If `curr.next != null` → set `curr = curr.next!!`, return `true` (early-advance to next node).
    - Else → return `false` (no next node → transition).

Implications:
- Node authors have two levers: `reached(script)` to mark completion, and `process(script)` to drive work. Returning `false` from `process` can be used to request an early hop to the next node even if `reached` is still `false`.
- The traversal always starts at the list head (`TraversalNodeList.head`). `TraversalProcessor` uses a copy of the node list, so the builder can be reused safely per traversal instance.
- Rate limiting and human-like delays are driven by `PlayerProfiles` and `gaussian(...)` across nodes.

### Integration in a State<T>

Return a `Traversal` from `checkNext()` when you want to hand off control until done:

```kotlin
class GoSomewhere : State<MyScript>() {
    override suspend fun MyScript.checkNext(): State<MyScript>? =
        Traversal.traversal(next = After(), finishedCondition = { arrived() }) {
            path(localPlayer.tile, destination)
        }
}
```

### Node contract and copy semantics

- `process(script: Script): Boolean` should do one small step and return `true` if it will continue working on this node; `false` if it cannot/should not continue right now.
- `reached(script: Script): Boolean` must be fast and side-effect free; it is polled frequently to decide advancement.
- `copy(): TraversalNode` must deep-copy all mutable fields used during execution (e.g., `route`, `nextClick`, thresholds). The traversal engine calls `TraversalNodeList.copy()` and then starts from `head` of the copy.

### PlayerProfiles touch points (humanization)

Used fields (see node implementations):
- `futurePathStepMin`, `futurePathStepMax` — look-ahead and Chebyshev smoothing granularity.
- `walkPathDeviation` — random target perturbation around the ideal tile.
- `minimapWalkPerc` — probability of minimap vs world click.
- `walkPathClickTime` — base delay between clicks (jittered via `gaussian`).

Tune these to globally alter click pacing and path smoothness without changing node code.

### Gotchas and best practices

- `ObjectNode` and `IFSlotNode` default to not being "reached" unless you supply a `destination: Area` or a custom `reached`. Without that, they rely on the traversal’s `finishedCondition` or an early hop.
- `DoorNode` considers arrival reached when the player stands exactly on the target tile (`distance == 0`). Provide a `reached` override if you want a tolerance.
- `TileExactNode` requires a `reached` predicate; it never completes by distance automatically.
- `TraversalBuilder.build(...)` throws if no nodes were added.

### Chebyshev path details (`ChebychevNode`)

- Uses a single sampled `maxDistanceUsed` across the route (`futurePathStepMin..futurePathStepMax`).
- Builds a smoothed list with Chebyshev interpolation (`smoothPathChebyshev`), then repeatedly clicks the farthest future tile within ~1.5× `maxDistanceUsed` from current position, jittered by `walkPathDeviation`.
- Waits until near the clicked tile (< 3) before issuing the next click. Default completion is within 5 tiles of the last keypoint unless overridden.

### Extending the DSL

1) Create a new `TraversalNode` implementing `process`, `reached`, and `copy` (deep copy).
2) Add a builder convenience in `TraversalBuilder.kt` that instantiates and `nodes.add(...)`s your node.
3) Keep `process` work small and idempotent across calls, with rate limiting similar to existing nodes.

### Custom node example

Skeleton of a simple wait node and builder wiring (example only):

```kotlin
// nodes/WaitUntilNode.kt
class WaitUntilNode(private val reachedCheck: () -> Boolean, private val pollMs: Int = 100) : TraversalNode() {
    override suspend fun process(script: Script): Boolean {
        script.delay(pollMs.toLong(), pollMs.toLong())
        return true
    }
    override fun reached(script: Script) = reachedCheck()
    override fun copy(): TraversalNode = WaitUntilNode(reachedCheck, pollMs)
}

// TraversalBuilder.kt
fun waitUntil(pollMs: Int = 100, reached: () -> Boolean) = nodes.add(WaitUntilNode(reached, pollMs))
```

Use when you need time for an external condition to settle without clicks.

## Troubleshooting

- __Node never advances__: Ensure a `reached` or `destination` is provided for `ObjectNode`/`IFSlotNode`, or rely on a proper `finishedCondition`.
- __Path keeps recalculating__: `PathNode` logs “Recalculating path...” when `route.success` is false. Check walkability or endpoints; consider `chebychevPath` for direct keypoints.
- __Door not found__: Verify `DoorInfo.realIdOpen/Closed` and `locationOpen/Closed`. Search radius inside `DoorNode` is 10 tiles.
- __Teleport not recognized__: Default `LodestoneNode` completion is `<= 10` tiles and not animating. Provide a custom `reached` if your script needs a different threshold/UI state.
- __Excessive clicking__: Reduce `PlayerProfiles.walkPathClickTime` or increase it for slower pacing; adjust `minimapWalkPerc`.

## FAQ

- __How do I cancel a traversal early?__
  Set `finishedCondition` to return true when your script decides to abort; `TraversalProcessor` transitions immediately to `next`.

- __Can I reuse a builder instance?__
  Create traversals via `Traversal.traversal { ... }` each time. Internally the node list is copied per run, isolating state.

- __Do nodes run concurrently?__
  No. Only the current node runs; traversal progresses when it is reached, or it signals early-advance by returning `false` from `process` and a next node exists.

---

## Tips

- Prefer `path(...)` for general movement; use `walkExact(...)` when you must land on an exact tile.
- Provide explicit `reached` predicates when you depend on UI or areas instead of proximity.
- Keep `searchRadius` realistic in `interactObj(...)` / `interactNpc(...)` to avoid unintended targets.
- Use `Area` when clicks should complete upon entering a region (via `clickIFSlot(..., destination)` or custom `reached`).