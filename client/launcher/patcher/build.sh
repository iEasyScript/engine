#!/usr/bin/env bash
# Build libprojectx_patcher.so and deploy it to EVERY location the launcher's
# find_patcher_library (client/launcher/src/game/process.rs) can resolve to.
#
# Why this script exists:
#   The launcher loads the patcher .so from the first of several candidate
#   locations that exists (next to the launcher exe, next to the client binary,
#   ~/projectx-3/, the dev build dir). If ANY of those holds a stale copy, it
#   shadows a fresh build. A stale March-23 build (still searching for the old
#   947 login-key prefix 8f389edb) once sat in ~/.local/share/project-x-launcher/ and was
#   loaded over a current build, so the client was never patched. This script
#   makes "build the patcher" atomically refresh every deploy slot so stale
#   copies can never win again.
#
# Usage:  client/launcher/patcher/build.sh
#         (run from anywhere; all paths are derived, no cwd assumptions)
#
# All destination paths are $HOME-relative — no hardcoded /home/<user> paths.

set -euo pipefail

LIB_NAME="libprojectx_patcher.so"

# --- Locate the patcher crate (this script lives in it) -----------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$SCRIPT_DIR"
BUILT_SO="$CRATE_DIR/target/release/$LIB_NAME"

# Repo root is DERIVED from this script's own location — the crate lives at
# <repo>/client/launcher/patcher, so three parents up is the repo root. NEVER
# hardcode a project path: the checkout can live anywhere (this repo is
# project-x; an older one was ~/projects/projectx-3) and the build must
# deploy into THIS checkout's data/client tree, not some unrelated path.
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# --- Source-of-truth RSA login-key markers ------------------------------------
# The compiled .so does NOT store these 32-char hex prefixes as one contiguous
# ASCII run: rustc lowers the byte-string literals into inline x86 immediates,
# splitting them into 8-byte chunks interleaved with instruction bytes. So a
# grep for the full 32-char literal returns 0 even on a perfectly current build.
# What IS reliably contiguous (it lands in a single immediate) is the FIRST
# 8 hex chars of each prefix. We therefore verify with the 8-char markers:
#   - CURRENT build must contain CURRENT_MARK and must NOT contain OLD_MARK
#   - the full 32-char literal is checked against the SOURCE instead, which is
#     the authoritative statement of what we are building.
CURRENT_PREFIX_FULL="b2c61c4772bf8882cab71c2b07ccac16"  # rev 950 login modulus prefix (32 chars, in lib.rs)
CURRENT_MARK="b2c61c47"                                  # first 8 chars — contiguous in the built .so
OLD_MARK="83fec000"                                      # rev 949 login prefix start — would be contiguous in a stale 949 .so
# The key prefixes live in the shared patcher-common crate, which all three
# platform patchers compile in.
SRC_LIB="$CRATE_DIR/../patcher-common/src/lib.rs"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

fail() { red "ERROR: $*" >&2; exit 1; }

# --- Pre-flight: confirm the SOURCE is the current build ----------------------
[[ -f "$SRC_LIB" ]] || fail "patcher source not found at $SRC_LIB"
if ! grep -q "$CURRENT_PREFIX_FULL" "$SRC_LIB"; then
    fail "$SRC_LIB does not contain the current login prefix ($CURRENT_PREFIX_FULL).
       The source is not on the expected revision — refusing to build/deploy a stale patcher.
       (If the login key rotated again, update CURRENT_* in this script and lib.rs together.)"
fi
if grep -q "$OLD_MARK" "$SRC_LIB"; then
    fail "$SRC_LIB still references the OLD login prefix ($OLD_MARK...).
       Update lib.rs to the current key before deploying."
fi

# --- Build --------------------------------------------------------------------
bold "==> Building $LIB_NAME (cargo build --release)"
echo "    crate: $CRATE_DIR"
cargo build --release --manifest-path "$CRATE_DIR/Cargo.toml"

[[ -f "$BUILT_SO" ]] || fail "expected build artifact missing: $BUILT_SO"

# Sanity-check the freshly built artifact before we fan it out everywhere.
if [[ "$(grep -ac "$CURRENT_MARK" "$BUILT_SO")" -lt 1 ]]; then
    fail "freshly built $BUILT_SO does not contain current marker '$CURRENT_MARK' — build is wrong."
fi
if [[ "$(grep -ac "$OLD_MARK" "$BUILT_SO")" -ne 0 ]]; then
    fail "freshly built $BUILT_SO still contains OLD marker '$OLD_MARK' — build is stale/wrong."
fi
green "    built OK: $BUILT_SO"

# --- Canonical deploy locations (every slot find_patcher_library can hit) ------
# Kept in lockstep with client/launcher/src/game/process.rs::find_patcher_library
# and the two launch flows in ui/ipc.rs (custom = ~/projectx-3, live = data dir),
# plus run-client.sh (HOME=~/.projectx). ALL $HOME-relative.
DEST_PATHS=(
    # Launcher data-dir root (ProjectDirs "project-x-launcher" => $XDG_DATA_HOME or ~/.local/share).
    # find_patcher_library hits this as "next to client binary" in LIVE mode. THE one that went stale.
    "$HOME/.local/share/project-x-launcher/$LIB_NAME"
    # Next to the downloaded client binary (rs2client) in the launcher data dir.
    "$HOME/.local/share/project-x-launcher/Jagex/launcher/$LIB_NAME"
    # Alternate data dir used by run-client.sh (it sets HOME=~/.projectx).
    "$HOME/.projectx/$LIB_NAME"
    "$HOME/.projectx/Jagex/launcher/$LIB_NAME"
    # Launcher CUSTOM-mode data dir (ServerMode::Custom => <data dir>/custom).
    # This is the rs3linux CWD + "next to client binary" slot for a custom server
    # launch — the exact path that was unpatched in the "Error saving file" repro.
    "$HOME/.local/share/project-x-launcher/custom/$LIB_NAME"
    "$HOME/.local/share/project-x-launcher/custom/Jagex/launcher/$LIB_NAME"
    # ~/projectx-3: explicit search path in find_patcher_library AND the custom-mode
    # cwd / rs3linux location in ui/ipc.rs (find_patcher_library hits it as
    # "next to client binary" for rs3linux). Also noted as required in project memory.
    "$HOME/projectx-3/$LIB_NAME"
    "$HOME/projectx-3/Jagex/launcher/$LIB_NAME"
    # THIS checkout's project tree (repo-root-derived — NOT a hardcoded path).
    # data/client/linux is run-client.sh's PATCHER_SO and find_patcher_library's
    # first (data/client/<host-os>/) slot; project root is a legacy fallback slot.
    "$REPO_ROOT/$LIB_NAME"
    "$REPO_ROOT/data/client/linux/$LIB_NAME"
    # Next to the launcher executable itself (release + debug) — find_patcher_library's
    # "next to the launcher exe" candidate, and the dev "../patcher/target/release" one.
    "$REPO_ROOT/client/launcher/target/release/$LIB_NAME"
    "$REPO_ROOT/client/launcher/target/debug/$LIB_NAME"
)

# --- Deploy -------------------------------------------------------------------
bold "==> Deploying to ${#DEST_PATHS[@]} locations"
for dest in "${DEST_PATHS[@]}"; do
    # Never clobber the build artifact with itself.
    if [[ "$dest" == "$BUILT_SO" ]]; then
        echo "    skip (build artifact): $dest"
        continue
    fi
    mkdir -p "$(dirname "$dest")"
    cp -f "$BUILT_SO" "$dest"
    chmod 0755 "$dest"
done

# --- Verify every deployed copy -----------------------------------------------
# Each deployed .so MUST embed the current marker and MUST NOT
# embed the old 947 marker. Fail loudly (non-zero exit) on any miss/mismatch.
bold "==> Verifying deployed copies"
verify_failed=0
for dest in "${DEST_PATHS[@]}"; do
    if [[ ! -f "$dest" ]]; then
        red "    MISSING  $dest"
        verify_failed=1
        continue
    fi
    have_cur="$(grep -ac "$CURRENT_MARK" "$dest" || true)"
    have_old="$(grep -ac "$OLD_MARK" "$dest" || true)"
    if [[ "$have_cur" -ge 1 && "$have_old" -eq 0 ]]; then
        green "    OK       $dest  (current=$have_cur, old=$have_old)"
    else
        red   "    BAD      $dest  (current=$have_cur expected>=1, old=$have_old expected 0)"
        verify_failed=1
    fi
done

echo
if [[ "$verify_failed" -ne 0 ]]; then
    fail "one or more deployed patcher copies are missing or stale (see BAD/MISSING above)."
fi

# --- Summary ------------------------------------------------------------------
bold "==> Done. libprojectx_patcher.so (marker $CURRENT_MARK) deployed to:"
for dest in "${DEST_PATHS[@]}"; do
    # Print $HOME-relative for readability.
    echo "    ${dest/#$HOME/\~}"
done
green "All ${#DEST_PATHS[@]} copies verified current. No stale copies remain."
