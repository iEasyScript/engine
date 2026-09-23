# Project X — Setup Guide

An all-in-one platform for the **RS3 (RuneScape 3) NXT client** (`rs2client`): a Kotlin/JVM private
server, a Rust launcher + runtime patcher, and a C++/Kotlin injection engine.

This document is **setup only** — how to get from a fresh machine to a running stack. For
architecture, the agent roster, and the reverse-engineering workflow, see [`CLAUDE.md`](CLAUDE.md).

> **Discord:** https://discord.gg/UUwfkXFcub

## Licence and provenance

Project X is derived from [project-undercut/engine](https://gitlab.com/project-undercut/engine) and is
licensed under the **GNU General Public License, version 3** — the full text is in
[`LICENSE`](LICENSE). It is the same licence the work it derives from carries, and it carries forward to
anything built from this repository.

This repository, together with its `re-resources` submodule
([iEasyScript/reclass-data](https://github.com/iEasyScript/reclass-data)), is the Corresponding Source
for every binary distributed through the Project X launcher — the engine jar, the supervisor, the native
bootstrap and the launcher itself. Both are required to build: the release pipeline asserts the submodule
is populated before it will produce a jar.

---

## Table of Contents

- [Supported platforms](#supported-platforms)
- [1. Before you start](#1-before-you-start)
- [2. Install system packages](#2-install-system-packages)
  - [Arch Linux](#arch-linux)
  - [Debian / Ubuntu](#debian--ubuntu)
  - [Fedora](#fedora)
  - [Windows (WSL 2)](#windows-wsl-2)
  - [macOS](#macos)
- [3. Install JDK 25 + Rust](#3-install-jdk-25--rust)
- [4. Clone, configure, and build](#4-clone-configure-and-build)
- [5. Get the NXT client binaries](#5-get-the-nxt-client-binaries)
- [6. Download the game cache](#6-download-the-game-cache)
- [7. Run it](#7-run-it)
  - [Option A — GUI launcher](#option-a--gui-launcher)
  - [Option B — Manual dev flow](#option-b--manual-dev-flow)
  - [Option C — Native Windows](#option-c--native-windows)
- [8. Configuration reference (`.env`)](#8-configuration-reference-env)
- [9. Troubleshooting](#9-troubleshooting)
- [10. Releases and CI/CD](#10-releases-and-cicd)
  - [What a pipeline produces](#what-a-pipeline-produces)
  - [Cutting a release](#cutting-a-release)
  - [Public download links](#public-download-links)
  - [The launcher's Plugins tab](#the-launchers-plugins-tab)
- [11. Developer tooling](#11-developer-tooling)
  - [Cache and content](#cache-and-content)
  - [Clientscripts (CS2)](#clientscripts-cs2)
  - [Reverse engineering](#reverse-engineering)
- [Documentation](#documentation)
- [License](#license)

---

## Supported platforms

| Platform | Server | Launcher + patcher | Engine injection |
|---|:--:|:--:|:--:|
| **Linux x86-64** | ✅ | ✅ | ✅ (GDB `dlopen`) |
| **Windows** (native) | ✅ | ✅ (entry-point patcher) | ✅ (`CreateRemoteThread`) |
| **Windows** (via [WSL 2](#windows-wsl-2)) | ✅ | ✅ | ✅ |
| **macOS** | ✅ | ✅ (dylib patcher) | ❌ |

Linux is the reference platform and the one the shell entry points below target. Native Windows is
supported end-to-end, so WSL 2 is an alternative, not a requirement. macOS runs the server and patches
a client, but has no engine bootstrap.

The two platforms apply the patcher at different moments, and the difference matters:

- **Linux** preloads `libprojectx_patcher.so` with `LD_PRELOAD`, which propagates `rs3linux` → `rs2client`.
- **Windows** spawns the launch chain from `projectx_injector.exe` under `DEBUG_PROCESS` and patches each
  process at `CREATE_PROCESS_DEBUG_EVENT`, then detaches before the client starts rendering.

Both land the patch **before the client's C++ static initializers run**, which is the requirement:
`rs2client` parses its RSA moduli into bignums during static init, so a patcher that arrives after the
process is already executing rewrites the `.rdata` strings but not the keys the client actually
verifies with. Injecting the patcher with `CreateRemoteThread(LoadLibraryW)` is therefore *not* an
equivalent of `LD_PRELOAD` — the client rejects the RSA-signed JS5 master index, ends up with an empty
index table, and dies in graphics initialisation. Engine injection is unaffected and still uses
`CreateRemoteThread`, because the engine is deliberately loaded into an already-running client.

Unless a section says otherwise, the commands below assume a Linux shell (native or WSL 2); the
Windows equivalents are in [Section 7](#7-run-it).

---

## 1. Before you start

The `re-resources` submodule is a **private GitLab repo pulled over SSH**. Register an SSH key with
GitLab that can read `iEasyScript/reclass-data` **before** cloning:

```bash
# Create a key if you don't have one, then add ~/.ssh/id_ed25519.pub to GitLab → Settings → SSH Keys
ssh-keygen -t ed25519 -C "$(whoami)@$(hostname)"
ssh -T git@gitlab.com    # should greet you by username
```

You will also need roughly **30 GB free disk** — ~24 GB of that is the JS5 game cache.

---

## 2. Install system packages

Pick your platform, then continue to [Section 3](#3-install-jdk-25--rust).

### Arch Linux

```bash
sudo pacman -Syu --needed git base-devel cmake clang gdb curl zip unzip sdl2 webkit2gtk-4.1
```

### Debian / Ubuntu

```bash
sudo apt update
sudo apt install -y git build-essential cmake clang gdb curl zip unzip libsdl2-dev libwebkit2gtk-4.1-dev
```

### Fedora

```bash
sudo dnf install -y git @development-tools cmake clang gdb curl zip unzip SDL2-devel webkit2gtk4.1-devel
```

### Windows (WSL 2)

Native Windows works (see [Section 7](#7-run-it)), but WSL 2 gets you the Linux entry points and the
reference toolchain. Run the whole stack inside **WSL 2 with Arch Linux**.
**Video guide:** https://www.youtube.com/watch?v=ql959hpUTP0

1. [Enable virtualization in BIOS](https://www.youtube.com/watch?v=rrpzWCPatLo) and
   [enable WSL 2 in Windows Features](https://www.youtube.com/watch?v=eId6K8d0v6o).
2. Install [Arch Linux from the Microsoft Store](https://apps.microsoft.com/detail/9mznmnksm73x),
   launch it, and create a user.
3. Set up an X server for the ImGui overlay (e.g. VcXsrv) and point `DISPLAY` at it.
4. Run the [Arch Linux](#arch-linux) package command above, then continue normally.

If you hit `No Authorization protocol found` under an X-based WM:

```bash
sudo pacman -S xorg-xhost && xhost +local:
```

### macOS

Server and patcher only — no engine injection. With [Homebrew](https://brew.sh):

```bash
xcode-select --install
brew install git cmake llvm sdl2
```

Skip the native-bootstrap and injection steps throughout the rest of this guide.

---

## 3. Install JDK 25 + Rust

Distro JDK packages are often too old. Install **JDK 25** via SDKMAN and **Rust** via rustup:

```bash
# --- JDK 25 (Temurin) via SDKMAN ---
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem

# --- Rust (stable) via rustup ---
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"

# --- Make JAVA_HOME persistent (CMake/JNI + the inject scripts need it) ---
echo 'source "$HOME/.sdkman/bin/sdkman-init.sh"' >> "$HOME/.bashrc"
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
echo "export JAVA_HOME=\"$JAVA_HOME\"" >> "$HOME/.bashrc"

# --- Verify ---
java -version    # 25.x
cargo --version
cmake --version
```

---

## 4. Clone, configure, and build

```bash
# --- Clone WITH submodules (re-resources, funchook, imgui) ---
git clone --recursive git@gitlab.com:iEasyScript/engine.git project-x
cd project-x

# --- Configuration: copy the working dev .env (RSA keys, ports, tokens) ---
cp .env.example .env

# --- Build the server + engine (JVM modules + native bootstrap .so + shadow/supervisor jars) ---
./gradlew build

# --- Build the GUI launcher (Rust) ---
( cd client/launcher && cargo build --release )

# --- Build + deploy the LD_PRELOAD patcher (use this script, not bare cargo) ---
client/launcher/patcher/build.sh
```

If `git clone` complains about the submodule, fix SSH access ([Section 1](#1-before-you-start)) and
run `git submodule update --init --recursive`.

**What got built:**

| Artifact | What it is |
|---|---|
| `client-plugin-engine/build/libs/com.projectx-1.0.0-all.jar` | The injected engine jar |
| `client-plugin-engine/build/libs/libprojectxbootstrap.so` | Native bootstrap (funchook + imgui) |
| `client/launcher/target/release/project-x-launcher` | The GUI launcher |
| `data/client/linux/libprojectx_patcher.so` | LD_PRELOAD patcher (deployed to every slot) |

The code is now compiled, but `data/client/` and `data/cache/` are **gitignored and empty**. The next
two sections fill them.

---

## 5. Get the NXT client binaries

> **On native Windows** the equivalents live in `data/client/windows/` and are named `rs2client.exe`
> and `rs3windows.exe`; deploy the patcher with `client\launcher\patcher-win\build.ps1` instead of
> `patcher/build.sh`. See [Native Windows](#option-c--native-windows) for the full sequence.

You need two files in `data/client/linux/`:

- `rs2client` — the NXT game client the engine injects into (~18 MB)
- `rs3linux` — Jagex's bootstrapper binary (~9 MB)

These aren't shipped. The simplest source is the GUI launcher you just built — run it and log in once
and it downloads the current client from the Jagex CDN:

```bash
./client/launcher/target/release/project-x-launcher    # log in once so it downloads the client, then quit

mkdir -p data/client/linux
cp ~/.local/share/project-x-launcher/Jagex/launcher/rs2client  data/client/linux/rs2client
cp ~/.local/share/project-x-launcher/rs3linux                  data/client/linux/rs3linux   # path may vary
chmod +x data/client/linux/rs2client data/client/linux/rs3linux

# Re-deploy the patcher now that data/client/linux exists (build.sh also targets that slot)
client/launcher/patcher/build.sh
```

If you already run RS3 via [Bolt Launcher](https://github.com/Adamcake/Bolt) or an official install,
copy the equivalent `rs2client` / `rs3linux` from there instead.

> **The engine and patcher are build-specific.** The client binary must match the offsets and keys
> baked into them. When Jagex bumps the build, both must be re-ported — see
> `re-resources/docs/re-methodology/UPDATING.md`.
>
> The client currently staged in `data/client/` is the **949** major. Note that the *protocol*
> sub-revision is **949.1** — that is what the client sends on the JS5 handshake and at lobby login,
> what Jagex's `jav_config` reports as `launcher_sub_version`, and what Jagex's own JS5 accepts (it
> rejects 949.5). Keep `MINOR_VERSION=1` in `.env`; `EnvVars` still defaults to `5`, which makes
> `JS5Server` log a version mismatch on every connection.

---

## 6. Download the game cache

The `:tools` cache downloader pulls the JS5 cache from Jagex's content servers into `./data/cache`
(the path the lobby/world/JS5 servers read — `CACHE_PATH` in `.env`):

```bash
./gradlew :tools:run --args="content.runescape.com 43594 949 1 8 ./data/cache"
#                             host                  port  maj min conns output
```

This is a large, long-running download — make sure you have ~24 GB free.

> Running `./gradlew :tools:run` with no `--args` falls back to built-in defaults that still point at
> the older **948-2** cache revision. Pass the version explicitly as shown above.

---

## 7. Run it

### Option A — GUI launcher

```bash
./client/launcher/target/release/project-x-launcher
```

Handles Jagex OAuth2/PKCE login, downloads/updates the client, lets you pick a server (Live vs. local
custom), patches the client at launch, and can inject the engine — all from the UI. This is the
recommended path on **native Windows** — see [Native Windows](#option-c--native-windows) for the
prerequisites.

### Option B — Manual dev flow

Best for server/engine development.

**1. Start the server** (two terminals, from the repo root). With `MONGO_IN_MEMORY=true` in `.env`
(the default in `.env.example`) the account store is an in-process mock — no MongoDB install needed.

```bash
./gradlew :lobby:run      # login + JS5 + config server (port 8829) + worldlist + social
./gradlew :world:run      # world login, player/NPC sync, scene, game packets
```

**2. Patch + launch + inject:**

```bash
launch/run-projectx.sh                # patch + launch the client, then inject the engine (default)
launch/run-projectx.sh --no-engine    # patch + launch only (server testing, no injection)
launch/run-projectx.sh --no-patch     # inject into an already-running rs2client only
```

It sources `.env`, redirects `HOME` to `~/.projectx` (so a custom-server run never shares cache or user
data with a live install), LD_PRELOADs `libprojectx_patcher.so` (rewriting the client's RSA keys + server
URLs to point at your local server), waits for `rs2client` to appear, then GDB-`dlopen`s the bootstrap
(**needs `sudo` for GDB attach**). Once injected you get the ImGui overlay, the in-process MCP server
on `:7882`, the scripting framework, and the `TcpIn` network sniffer. Knobs: `INJECT_DELAY=<sec>`
(default 8), `CONFIG_URI=<url>` (default `http://localhost:$PROJECTX_HTTP_PORT/jav_config.ws`).

**Other entry points:**

```bash
( cd client-plugin-engine && ./inject ) # manually inject into a running rs2client (lists PIDs, prompts; uses sudo)
```

### Option C — Native Windows

`launch/run-projectx.sh` is bash-only and has no Windows counterpart, so on Windows the GUI launcher
is the entry point and the servers are started separately. From the repo root in PowerShell:

```powershell
# 1. Patcher + injectors. Deploys projectx_patcher.dll, projectx_injector.exe and
#    projectx_engine_injector.exe to data\client\windows\ and the %APPDATA%/%LOCALAPPDATA% slots.
client\launcher\patcher-win\build.ps1

# 2. Client binaries in data\client\windows\ : rs2client.exe + rs3windows.exe
#    (log into the GUI launcher once in Live mode; it fetches Jagex's installer and unpacks
#    rs3windows.exe itself, and rs3windows.exe then downloads rs2client.exe. Copy both out of
#    C:\ProgramData\Jagex\launcher\ if a server-side copy is wanted.)

# 3. Cache (~24 GB) — same download as Section 6
.\gradlew.bat :tools:run --args="content.runescape.com 43594 949 1 8 ./data/cache"

# 4. Both servers, one per terminal. The world is a separate process; without it the
#    client reaches the lobby and then fails when you pick a world.
.\gradlew.bat :lobby:run     # login + JS5 + config server (8829) + worldlist + social
.\gradlew.bat :world:run     # world login, player/NPC sync, scene, game packets

# 5. Launch, then pick the Private realm in the UI
.\client\launcher\target\release\project-x-launcher.exe
```

The launcher invokes `projectx_injector.exe`, which patches `rs3windows.exe` and `rs2client.exe` at
their entry points and then detaches — see [Supported platforms](#supported-platforms) for why the
timing matters. Never start `rs2client.exe` yourself: launched directly it is unpatched, keeps Jagex's
RSA keys, and cannot reach a local server.

> ⚠️ **A private-server run shares the official install's cache on Windows.** `rs3windows.exe`
> installs and runs the client from `C:\ProgramData\Jagex\launcher\` and reads its `preferences.cfg`
> from there, so `cache_folder` points at `C:\ProgramData\Jagex` and both realms use the same
> `RuneScape` JS5 cache and the same user folder. Linux avoids this because the launcher redirects
> `HOME` per mode; Windows has no equivalent yet. Alternating between the Live and Private realms
> makes each overwrite the other's cached reference tables, so expect re-downloads after a switch.

**Engine scripts.** Scripts are Kotlin or Java classes (annotated `@ScriptDescription`) packaged as JARs in
`~/.projectx/scripts/`, auto-discovered at runtime. They are not built in this repository: see
`iEasyScript/official-scripts`, `iEasyScript/community-scripts` and the `iEasyScript/script-template`
starter.

> After **any** engine code change, rebuild the real artifact with `./gradlew :client-plugin-engine:shadowJar` —
> `compileKotlin` alone leaves a stale injected jar. Never rebuild the shadow jar while injected;
> uninject first.

Building them yourself is the default and stays the default. If you would rather not keep a JDK and a
Gradle build in the loop, the launcher's **Plugins** tab installs the same jars from the newest GitLab
release — see [The launcher's Plugins tab](#the-launchers-plugins-tab).

---

## 8. Configuration reference (`.env`)

`.env` (copied from `.env.example`) ships a working, internally-consistent key set. See
`core/src/main/kotlin/org/projectx/core/EnvVars.kt` for every variable and its default.

| Variable | Purpose |
|---|---|
| `RSA_JS5_MODULUS` / `_EXPONENT` | 4096-bit key signing the JS5 master index |
| `RSA_LOGIN_MODULUS` / `_EXPONENT` | 1024-bit key for login-block encryption |
| `PROJECTX_RSA_MODULUS` / `PROJECTX_JS5_RSA_MODULUS` | **Hex** moduli the patcher writes into `rs2client` |
| `PROJECTX_HTTP_PORT` | Config/ConfigServer HTTP port (default `8829`) |
| `MONGO_IN_MEMORY` | `true` → in-process account store mock (no MongoDB install) |
| `CACHE_PATH` | JS5 cache location (default `./data/cache`) |
| `SOCIAL_GATEWAY_TOKEN` / `WORLD_LOGIN_TOKEN_SECRET` | Lobby↔world secrets (required unless `DEBUG=true`) |

> **Rotating keys (advanced):** run `./gradlew :tools:rsaKeyGen`, update **both** the decimal moduli
> (server) and the hex `PROJECTX_*` moduli (patcher) in `.env`, then rebuild the patcher.

---

## 9. Troubleshooting

**Submodule clone fails / `re-resources` is empty.** You need a GitLab SSH key with access to
`iEasyScript/reclass-data` ([Section 1](#1-before-you-start)). Then
`git submodule update --init --recursive`.

**`JAVA_HOME must be set` during inject.** Export `JAVA_HOME` to a JDK 25 home before running
`launch/run-projectx.sh` or `client-plugin-engine/inject` ([Section 3](#3-install-jdk-25--rust) makes it persistent).

**Native bootstrap fails to configure.** Confirm `cmake`, `clang`/`clang++`, the JNI headers (via
`JAVA_HOME`), and `SDL2` are installed, and that the `funchook`/`imgui` submodules are checked out
(`git submodule update --init --recursive`).

**The client launches but isn't patched / disconnects after the master index.** A stale
`libprojectx_patcher.so` is shadowing a fresh build, or patcher env vars are missing. Always rebuild via
`client/launcher/patcher/build.sh` (it verifies the current-build marker and refreshes every deploy
slot), and make sure `.env` sets `PROJECTX_RSA_MODULUS`, `PROJECTX_JS5_RSA_MODULUS`, and
`PROJECTX_HTTP_PORT`. Diagnose via `~/.projectx/launcher-client.log`.

**(Windows) The client asks about compatibility mode and then won't start.** That dialog comes from
`rs3windows.exe` *after* the client has already crashed (`nxt_graphics_prompt1_init`, "an error during
graphics initialisation"), so declining it just stops the retry — it is a symptom, not the cause. The
underlying failure is an unpatched client: `rs2client` parses its RSA moduli during static
initialisation, so if the patcher arrives after the process is running, the client verifies our
RSA-signed JS5 master index with Jagex's key, ends up with an empty index table, and faults. Confirm
from `%APPDATA%\project-x-launcher\data\custom\launcher-client.log` that the injector reports
`patched rs2client (pid N) at its entry point`; if it instead fell back to post-start DLL injection,
rebuild with `client\launcher\patcher-win\build.ps1`. The lobby log shows the same failure from the
server side: the client fetches `index=255 archive=255` and nothing else, then POSTs a crash report.

**(Windows) The client hangs on "loading application resources".** It is blocked connecting to
`127.0.0.1:80` — the JS5-over-HTTP content URL still has the literal port `80` because the HTTP-port
patch didn't land. Check with `Get-NetTCPConnection -OwningProcess <pid>` for a `SynSent` entry on
port 80, and confirm `PROJECTX_HTTP_PORT` is set in `.env`.

**The client is randomly `SIGKILL`ed (no core, no crash log).** The kernel is enforcing
`RLIMIT_RTTIME` (a 200 ms realtime-thread cap set by `rtkit-daemon`) on the game's render thread
during a long GPU stall — it happens uninjected too. Immediate fix:

```bash
sudo prlimit --pid "$(pgrep -nx rs2client)" --rttime=unlimited:unlimited
```

Persistent fix: `systemctl edit rtkit-daemon` and raise `--rttime-usec-max` on `ExecStart`.

**No core dump on an engine crash.** The inject scripts already `prlimit --core=unlimited`; the dump
goes wherever `/proc/sys/kernel/core_pattern` points (`coredumpctl debug` with systemd-coredump).

**Attaching a debugger.** The engine runs in the client's JVM with a JDWP listener on port **5005**.
Add a *Remote JVM Debug* run configuration on that port in IntelliJ, start the client and inject
first, then connect. Point the Logs tab at `~/.projectx/` to see `println` output and stack traces.

---

## 10. Releases and CI/CD

`.gitlab-ci.yml` (jobs in `.gitlab/ci/`) builds every installable artifact on every pipeline and, on a
`v*` tag, publishes them to the Generic Package Registry and attaches them to a GitLab Release.

### What a pipeline produces

| Job | Artifact | Runner |
|-----|----------|--------|
| `plugins:official` | `official-scripts-<version>.jar` | any Linux (JDK 25 image) |
| `plugins:community` | `community-scripts-<version>.jar` | any Linux (JDK 25 image) |
| `engine:jar` | `projectx-engine-<version>.jar` (the engine shadow jar) + the supervisor jar | any Linux (JDK 25 image) |
| `engine:bootstrap:linux` | `libprojectxbootstrap.so` | any Linux (cmake + clang) |
| `engine:bootstrap:windows` | `projectxbootstrap.dll` | any Linux, cross-compiled with mingw-w64 |
| `launcher:linux` | `project-x-launcher` + `libprojectx_patcher.so` | any Linux (GTK3 + WebKitGTK) |
| `launcher:windows` | `project-x-launcher.exe`, `projectx_patcher.dll`, `projectx_injector.exe`, `projectx_engine_injector.exe` | any Linux, cross-compiled with cargo-xwin |
| `launcher:macos` | `project-x-launcher-macos-universal-<version>` (x86_64 + arm64) | a macOS runner, opt-in |
| `bundle:linux` | `project-x-launcher-linux-x86_64-<version>.tar.gz` | any Linux |
| `bundle:windows` | `project-x-launcher-windows-x86_64-<version>.zip` | any Linux |

### What a bundle contains

The Linux and Windows downloads are archives, not bare binaries — a launcher on its own cannot patch
a client or inject the engine:

```
project-x-launcher-<platform>-<version>/
  project-x-launcher[.exe]                      the launcher
  libprojectx_patcher.so | projectx_patcher.dll  RSA/URL patcher for custom-server play
  projectx_injector.exe, projectx_engine_injector.exe Windows only (patch-at-spawn, engine inject)
  engine/
    libprojectxbootstrap.so | projectxbootstrap.dll
    projectx-supervisor.jar
```

The engine's shadow jar is ~150 MB and is deliberately NOT in the bundle: the launcher copies
`engine/` into its own data dir on first launch with the engine enabled, then downloads
`projectx-engine.jar` from the same release beside it. That keeps the bundle in the tens of megabytes
and lets the engine update without a new launcher. A source tree overrides all of it — when
`client-plugin-engine/build/libs/` already holds a bootstrap and an engine jar, that directory is the
engine home and nothing is copied or downloaded.

macOS has no patcher or engine port yet, so it stays a bare binary.

One `version` job decides the version string and every other job stamps it, so a pipeline can never
emit a launcher and a plugin jar that disagree. A tag `v1.2.3` yields `1.2.3`; every other pipeline
yields `0.0.0-dev.<pipeline>.<sha>`.

The macOS job needs a macOS runner, so it only enters the pipeline when the CI/CD variable
`PROJECTX_ENABLE_MACOS_BUILD` is set to `true`. Without it the Release simply carries no macOS asset.
The cross-compiled Windows `.exe` carries no icon resource — `build.rs` embeds `assets/projectx.rc` only
when the build host is Windows.

### Cutting a release

```bash
git tag v1.2.3 && git push origin v1.2.3
```

`publish:packages` then uploads every artifact to
`packages/generic/projectx/<version>/`, generates `plugins-manifest.json` (each artifact's version,
size, sha256 and download URL — the plugin jars, the engine jar, and every launcher bundle), and
`publish:release` creates the Release with version-free asset links — `official-scripts.jar`,
`community-scripts.jar`, `projectx-engine.jar`, `plugins-manifest.json`, one per launcher platform.

Those stable link names are the contract the launcher reads: it looks a link up by name on whatever
release is current, so nothing in the client has to parse a version out of a filename.

### Public download links

Each asset link is published with a direct asset path, which makes GitLab's latest-release permalink
resolve to it. These URLs are permanent — they always serve the newest release and never need
reissuing when a version ships, so they are the ones to hand out:

| | Link |
|---|---|
| Windows | `https://github.com/iEasyScript/engine/releases/latest/download/project-x-launcher-windows-x86_64.zip` |
| Linux | `https://github.com/iEasyScript/engine/releases/latest/download/project-x-launcher-linux-x86_64.tar.gz` |
| macOS | `https://github.com/iEasyScript/engine/releases/latest/download/project-x-launcher-macos-universal` |
| All assets | `https://github.com/iEasyScript/engine/releases/latest` |

The macOS link only resolves on releases built with `PROJECTX_ENABLE_MACOS_BUILD`.

### The launcher's Plugins tab

The puzzle-piece button in the launcher's banner opens **Plugins**, which manages the script jars in
`~/.projectx/scripts/`.

**Every channel starts opted out.** The launcher downloads nothing until you ask it to — building the
modules yourself remains the default. Each row offers a one-shot **Install**/**Update**, a **Remove**
that only deletes what the launcher itself installed, and a **Keep updated** toggle that brings that
channel to the newest release on every launcher start.

Installing a channel makes its jar the only one for that channel in the scripts folder, including one
you built: two jars for one channel put every script class on the engine's scan path twice. A jar the
launcher did not install is labelled *Your build* and called out before anything replaces it.

Downloads are checksum-verified against the manifest. To point the tab at a fork, set `plugins.gitlab_host`
and `plugins.project_path` in the launcher's `config.json`.

Below the plugin rows, **Launcher downloads** lists the launcher builds the same release published, one
row per platform with *Copy link* and *Download* — the links to hand to someone who does not have the
launcher yet. The section only appears once a release exists.

> A branch pipeline builds every artifact but publishes nothing: `publish:packages` and
> `publish:release` are tag-only. Until a `v*` tag is pushed the tab correctly reports
> *no releases published yet*, however many green pipelines `dev` has.

## 11. Developer tooling

Everything below runs from the repo root through the Gradle wrapper. Each takes its arguments as a
single `-Pargs` string, split on whitespace.

### Cache and content

```bash
# Is our cache up to date, and what did the last game update change?
./gradlew :tools:betaScanner -Pargs="--host content.runescape.com --major <maj> --minor <min> --token <tok> --live ./data/cache --scan"

# Decode every cache type we have a decoder for into field-named JSONL
./gradlew :tools:cacheUnpack           # -> data/cache-unpacked/<label>/types/

# Snapshot / diff the cache around an update
./gradlew :tools:cacheSnapshot
./gradlew :tools:cacheDiff
```

Name an id before guessing what it means — the gameval dictionaries are bidirectional:

```bash
./re-resources/gamevals/gameval.py npc 7987      # -> sum1_ghost_erik_bonde_no_wander
./re-resources/gamevals/gameval.py obj -n coins  # -> 995
./re-resources/gamevals/gameval.py -s magic_logs # search every type
```

Details: [`docs/cache/cache-update-flow.md`](docs/cache/cache-update-flow.md),
[`docs/cache/decode-coverage.md`](docs/cache/decode-coverage.md).

### Clientscripts (CS2)

`:tools:cs2` decompiles cache index 12 into TypeScript that recompiles to **byte-identical** bytecode,
and can simulate and hot-reload a script. The cache is opened **read-only**, so it is safe to run
with the servers or the client live.

```bash
./gradlew -q :tools:cs2                                   # list every sub-command
./gradlew -q :tools:cs2 -Pargs="decompile 10000"          # read one script
./gradlew -q :tools:cs2 -Pargs="decompile-all cs2-dump"   # the whole corpus, then grep it
./gradlew -q :tools:cs2 -Pargs="verify"                   # codec round-trip over every script
./gradlew -q :tools:cs2 -Pargs="roundtrip"                # full decompile -> recompile -> byte compare
```

It needs a cache (`./data/cache`, or `--cache <dir>`) and an opcode table for that cache — the table
is keyed by the index-12 CRC, and every run prints which one it loaded. A new client build renumbers
the opcodes, so the table is re-derived on update day rather than carried forward.

Usage and requirements: [`docs/cache/cs2-toolchain.md`](docs/cache/cs2-toolchain.md).
The format itself: [`docs/cache/clientscript-cs2.md`](docs/cache/clientscript-cs2.md).

### Reverse engineering

The Ghidra MCP is vendored at `ghidra-mcp/`. Build and install it with
`ghidra-mcp/build-install.sh` (needs `$GHIDRA_INSTALL_DIR`); it runs inside the Ghidra GUI or fully
headless via `start-headless-mcp.sh` / `stop-headless-mcp.sh`. The local project holds a
single-process lock, so the headless host, the GUI, and `analyzeHeadless` runs are mutually
exclusive.

⛔ The Ghidra DB is the only source of truth for addresses, offsets and packet opcodes. They are
never written into documentation, source comments, or generated tables checked into git.

Methodology: [`docs/re-methodology/UPDATING.md`](docs/re-methodology/UPDATING.md),
[`docs/re-methodology/AUTO_UPDATER.md`](docs/re-methodology/AUTO_UPDATER.md).

---

## Documentation

Full index with every document: **[`docs/README.md`](docs/README.md)**. The `docs/` tree is a symlink
into the `re-resources` submodule, so it is shared with the engine and versioned separately.

| Area | Start here | Covers |
|---|---|---|
| **Network protocol** | [`docs/net/README.md`](docs/net/README.md) | Packet class, crypto, transport, framing, login, JS5, ClientProt/ServerProt handlers |
| **Cache and content** | [`docs/cache/cache-update-flow.md`](docs/cache/cache-update-flow.md) | How updates reach us, master index, LZMA, MAPSV2, gamevals, decoder coverage |
| **Clientscripts** | [`docs/cache/cs2-toolchain.md`](docs/cache/cs2-toolchain.md) | Running `:tools:cs2`; the [format itself](docs/cache/clientscript-cs2.md) alongside it |
| **Client binary** | [`docs/binary/jav-config.md`](docs/binary/jav-config.md) | `jav_config`, CDN downloads, RSA key locations, per-OS patch targets, the input layer |
| **Update day** | [`docs/re-methodology/UPDATING.md`](docs/re-methodology/UPDATING.md) | Moving to a new client build; the offset auto-updater |
| **Mobile (Android)** | [`docs/mobile/README.md`](docs/mobile/README.md) | APK, `launchurl` config redirect, login/JS5 handshakes, capture hooks, cross-arch porting |
| **Engine** | [`docs/engine/engine-CLAUDE.md`](docs/engine/engine-CLAUDE.md) | Project X engine guidelines and the `Offsets.kt` cross-reference |

Architecture, the agent roster, and the reverse-engineering workflow live in
[`CLAUDE.md`](CLAUDE.md).

---

## License

See [LICENSE](LICENSE).
