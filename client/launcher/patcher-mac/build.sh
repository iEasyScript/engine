#!/usr/bin/env bash
# Build libprojectx_patcher.dylib from Linux and deploy it beside the macOS client.
#
# No Apple SDK is needed: cargo-zigbuild links against the libSystem stubs bundled with zig. The zig
# release is pinned because newer ones mis-parse the exported-symbols/dead-strip linker arguments rustc
# emits for a Mach-O dylib and the link fails.
#
# Usage:  client/launcher/patcher-mac/build.sh

set -euo pipefail

LIB_NAME="libprojectx_patcher.dylib"
TARGET="x86_64-apple-darwin"
ZIG_VERSION="0.13.0.post1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
VENV="$SCRIPT_DIR/target/zig-venv"
BUILT="$SCRIPT_DIR/target/$TARGET/release/$LIB_NAME"
DEST="$REPO_ROOT/data/client/macos/$LIB_NAME"
SRC_LIB="$SCRIPT_DIR/../patcher-common/src/lib.rs"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
fail()  { red "ERROR: $*" >&2; exit 1; }

# The first 8 hex chars of the login-modulus prefix land in one contiguous immediate in the built
# library, so they identify which client build a copy was compiled for.
CURRENT_PREFIX="$(sed -n 's/^pub const LOGIN_MODULUS_PREFIX: &\[u8\] = b"\([0-9a-f]\{32\}\)";.*/\1/p' "$SRC_LIB")"
[[ ${#CURRENT_PREFIX} -eq 32 ]] || fail "could not read LOGIN_MODULUS_PREFIX from $SRC_LIB"
CURRENT_MARK="${CURRENT_PREFIX:0:8}"

bold "==> Toolchain"
command -v cargo-zigbuild >/dev/null 2>&1 || cargo install cargo-zigbuild
[[ -x "$VENV/bin/python" ]] || python3 -m venv "$VENV"
if ! "$VENV/bin/python" -m ziglang version 2>/dev/null | grep -qx "${ZIG_VERSION%%.post*}"; then
    "$VENV/bin/pip" -q install "ziglang==$ZIG_VERSION"
fi
rustup target list --installed | grep -qx "$TARGET" || rustup target add "$TARGET"

bold "==> Building $LIB_NAME for $TARGET"
# cargo-zigbuild drops a placeholder object into the working directory; keep that out of the repo root.
(cd "$SCRIPT_DIR/target" && PATH="$VENV/bin:$PATH" cargo zigbuild --release --target "$TARGET" --manifest-path "$SCRIPT_DIR/Cargo.toml")
[[ -f "$BUILT" ]] || fail "expected build artifact missing: $BUILT"
[[ "$(grep -ac "$CURRENT_MARK" "$BUILT")" -ge 1 ]] || fail "$BUILT does not embed the current login-key marker $CURRENT_MARK"
green "    built OK: $BUILT"

bold "==> Deploying"
install -m 0755 "$BUILT" "$DEST"
green "    $DEST (marker $CURRENT_MARK)"
