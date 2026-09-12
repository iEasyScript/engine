#!/usr/bin/env bash
#
# Provision a dedicated Wine prefix that can run the Windows project-x-launcher:
# the WebView2 runtime the launcher's webview needs, and a Windows JDK for the
# Project X engine's JVM bootstrap.
#
# Idempotent — each step is skipped when its artifact is already in the prefix,
# so re-running after a Wine upgrade or a partial failure is safe.
#
#   ./setup-prefix.sh              provision (or top up) the prefix
#   ./setup-prefix.sh --recreate   delete the prefix first, then provision
#
# Overridable:
#   PROJECTX_WINEPREFIX   prefix location   (default ~/.local/share/projectx-wine)
#   PROJECTX_WINE_CACHE   download cache    (default ~/.cache/projectx-wine)

set -euo pipefail

PREFIX="${PROJECTX_WINEPREFIX:-$HOME/.local/share/projectx-wine}"
CACHE="${PROJECTX_WINE_CACHE:-$HOME/.cache/projectx-wine}"

# Evergreen WebView2 Runtime standalone installer. The bootstrapper (LinkId 2124703)
# downloads its payload at run time through the Edge updater's own network stack,
# which does not survive Wine; the standalone installer carries the payload.
WEBVIEW2_URL="https://go.microsoft.com/fwlink/p/?LinkId=2124701"
WEBVIEW2_CLIENT_GUID="{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

JDK_FEATURE=25
JDK_API="https://api.adoptium.net/v3/assets/latest/${JDK_FEATURE}/hotspot?architecture=x64&image_type=jdk&os=windows"
JDK_PARENT_WIN='C:\Program Files\Eclipse Adoptium'

export WINEPREFIX="$PREFIX"
export WINEARCH=win64
export WINEDEBUG="${WINEDEBUG:--all}"
# Suppress the Mono/Gecko install prompts — neither is used, and both block an
# unattended run on a dialog.
export WINEDLLOVERRIDES="mscoree,mshtml="

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

# Wine writes a torrent of libEGL/pci-id noise to stderr on this class of host.
# It says nothing about whether the command worked, so drop it and let real
# failures surface through exit status.
quiet() { "$@" 2>&1 | grep -vE 'libEGL|pci id for fd|^\s*$' || true; }

command -v wine >/dev/null || die "wine is not installed"
command -v curl >/dev/null || die "curl is not installed"
command -v unzip >/dev/null || die "unzip is not installed"

if [[ "${1:-}" == "--recreate" ]]; then
    step "Removing $PREFIX"
    rm -rf "$PREFIX"
fi

mkdir -p "$CACHE"

# --- 1. The prefix itself -----------------------------------------------------

if [[ ! -f "$PREFIX/system.reg" ]]; then
    step "Creating 64-bit Wine prefix at $PREFIX"
    mkdir -p "$PREFIX"
    quiet wineboot --init
    wineserver -w
else
    step "Wine prefix already exists at $PREFIX"
fi

# --- 2. WebView2 runtime ------------------------------------------------------
#
# wry resolves the runtime through the Edge updater's registration, not by path,
# so the presence of the `pv` value under the WebView2 client GUID is the real
# installed-check — a stray directory is not enough.

# The installed version lives at Clients\<guid>\pv. Section headers are matched on
# their unescaped parts only — `awk -v` collapses backslash escapes, so a literal
# registry path passed that way would never match the doubled separators on disk.
webview2_version() {
    [[ -f "$PREFIX/system.reg" ]] || return 1
    local version
    version=$(awk -v guid="$WEBVIEW2_CLIENT_GUID" '
        /^\[/ {
            insection = index($0, "EdgeUpdate") && index($0, "Clients") \
                        && index($0, guid) && !index($0, "ClientState")
            next
        }
        insection && /^"pv"=/ { gsub(/^"pv"="|"$/, ""); print; exit }
    ' "$PREFIX/system.reg")
    [[ -n "$version" ]] && printf '%s\n' "$version"
}

if version=$(webview2_version); then
    step "WebView2 runtime already installed ($version)"
else
    step "Installing the WebView2 runtime"
    installer="$CACHE/MicrosoftEdgeWebView2RuntimeInstallerX64.exe"
    if [[ ! -s "$installer" ]]; then
        info "Downloading (~195 MiB)..."
        curl -fL --progress-bar -o "$installer.part" "$WEBVIEW2_URL"
        mv "$installer.part" "$installer"
    else
        info "Using cached $installer"
    fi

    info "Running the installer under Wine (several minutes)..."
    quiet wine "$installer" /silent /install

    # The Edge updater finishes registration in a background process; the `pv`
    # value is the last thing written, so poll for it rather than racing it.
    for _ in $(seq 60); do
        version=$(webview2_version) && break
        sleep 5
    done
    wineserver -w
    version=$(webview2_version) \
        || die "WebView2 installed but never registered a version — check $PREFIX"
    info "Registered WebView2 runtime $version"
fi

# --- 3. Windows JDK -----------------------------------------------------------
#
# The engine's native bootstrap loads %JAVA_HOME%\bin\server\jvm.dll inside the
# client process. Installing under the Adoptium root also lets the bootstrap's
# own JDK discovery find it with no JAVA_HOME set at all.

jdk_installed() {
    compgen -G "$PREFIX/drive_c/Program Files/Eclipse Adoptium/jdk-*/bin/server/jvm.dll" >/dev/null
}

if jdk_installed; then
    step "Windows JDK already installed"
    info "$(compgen -G "$PREFIX/drive_c/Program Files/Eclipse Adoptium/jdk-*" | head -1)"
else
    step "Installing a Windows JDK ${JDK_FEATURE}"
    read -r jdk_name jdk_url < <(
        curl -fsSL "$JDK_API" | python3 -c '
import json, sys
assets = json.load(sys.stdin)
if not assets:
    raise SystemExit("no Adoptium asset for this query")
pkg = assets[0]["binary"]["package"]
print(pkg["name"], pkg["link"])
'
    )
    archive="$CACHE/$jdk_name"
    if [[ ! -s "$archive" ]]; then
        info "Downloading $jdk_name..."
        curl -fL --progress-bar -o "$archive.part" "$jdk_url"
        mv "$archive.part" "$archive"
    else
        info "Using cached $archive"
    fi

    dest="$PREFIX/drive_c/Program Files/Eclipse Adoptium"
    mkdir -p "$dest"
    unzip -q -o "$archive" -d "$dest"
    jdk_installed || die "JDK archive extracted but no jvm.dll landed under $dest"
    info "$(compgen -G "$dest/jdk-*" | head -1)"
fi

step "Prefix ready"
info "prefix : $PREFIX"
info "webview: $(webview2_version)"
info "jdk    : ${JDK_PARENT_WIN}\\$(basename "$(compgen -G "$PREFIX/drive_c/Program Files/Eclipse Adoptium/jdk-*" | head -1)")"
printf '\n    Launch it with: %s\n\n' "$(dirname "$0")/run.sh"
