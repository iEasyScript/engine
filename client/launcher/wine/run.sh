#!/usr/bin/env bash
#
# Run the Windows project-x-launcher inside the dedicated Wine prefix, with the
# environment the launch → patch → inject chain needs:
#
#   * the WebView2 runtime and Windows JDK provisioned by setup-prefix.sh
#   * a working directory of the repo root, so the launcher's `data/client/windows`
#     lookup slot resolves and it finds projectx_injector.exe / projectx_patcher.dll
#   * PROJECTX_HOME_DIR pointing at the engine build output, which must hold
#     projectxbootstrap.dll next to the supervisor and engine jars
#
# Any arguments are passed through to the launcher (e.g. --headless).
#
# Overridable:
#   PROJECTX_WINEPREFIX   prefix location  (default ~/.local/share/projectx-wine)
#   LAUNCHER_EXE        launcher binary  (default the cross-compiled release build)

set -euo pipefail

REPO_ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
PREFIX="${PROJECTX_WINEPREFIX:-$HOME/.local/share/projectx-wine}"
LAUNCHER_EXE="${LAUNCHER_EXE:-$REPO_ROOT/client/launcher/target/x86_64-pc-windows-gnu/release/project-x-launcher.exe}"
ENGINE_HOME="$REPO_ROOT/client-plugin-engine/build/libs"

die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$PREFIX/system.reg" ]] || die "no Wine prefix at $PREFIX — run $(dirname "$0")/setup-prefix.sh"
[[ -f "$LAUNCHER_EXE" ]] || die "no launcher at $LAUNCHER_EXE — build it with:
  cd $REPO_ROOT/client/launcher && cargo build --release --target x86_64-pc-windows-gnu"

jdk_home=$(compgen -G "$PREFIX/drive_c/Program Files/Eclipse Adoptium/jdk-*" | head -1) \
    || die "no Windows JDK in the prefix — run $(dirname "$0")/setup-prefix.sh"

export WINEPREFIX="$PREFIX"
export WINEDEBUG="${WINEDEBUG:--all}"
export JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\$(basename "$jdk_home")"

# The bootstrap DLL derives its home from its own module path, so the DLL has to
# sit with the jars; PROJECTX_HOME_DIR only steers the launcher's lookup.
export PROJECTX_HOME_DIR
PROJECTX_HOME_DIR="Z:$(printf '%s' "$ENGINE_HOME" | tr '/' '\\')"

# Gradle's copyNativeLibToBuild only installs the host's own bootstrap library, so
# the cross-compiled DLL has to be placed beside the jars separately or the engine
# would load against whatever stale copy was last left there.
BOOTSTRAP_DLL="$REPO_ROOT/client-plugin-engine/native-bootstrap/build-windows-mingw/projectxbootstrap.dll"
if [[ -f "$BOOTSTRAP_DLL" && "$BOOTSTRAP_DLL" -nt "$ENGINE_HOME/projectxbootstrap.dll" ]]; then
    cp "$BOOTSTRAP_DLL" "$ENGINE_HOME/"
fi

for artifact in projectxbootstrap.dll projectx-supervisor.jar; do
    [[ -f "$ENGINE_HOME/$artifact" ]] || printf \
        '\033[1;33mwarning: %s missing from %s — engine injection will fail\033[0m\n' \
        "$artifact" "$ENGINE_HOME" >&2
done

cd "$REPO_ROOT"
exec wine "$LAUNCHER_EXE" "$@"
