#!/usr/bin/env bash
# Attaches sources to a script-api release, so a script author can ctrl+click from their script into
# the API's own Kotlin instead of landing in decompiled bytecode.
#
#   .github/ci/publish-api-sources.sh <apiVersion> [engineTag]
#   .github/ci/publish-api-sources.sh 1.16.0 v1.0.45
#   .github/ci/publish-api-sources.sh --stage out/ 1.16.0  # build the assets into out/v1.16.0, upload nothing
#
# Run it as part of making a script-api release, after the jars are attached. Four assets go up:
#
#   projectx-engine-api-<v>-sources.jar   the Kotlin behind the engine-api jar
#   projectx-core-<v>-sources.jar         the Kotlin behind the core jar
#   ivy-projectx-engine-api-<v>.xml       tells Gradle those sources exist
#   ivy-projectx-core-<v>.xml
#
# The descriptors are the reason this works at all. The release URLs are consumed as an Ivy
# repository, and with `metadataSources { artifact() }` Gradle has no metadata to read, so it never
# looks for a sources jar however the asset is named. An Ivy descriptor declaring a `sources`
# configuration is what makes the IDE's "Download Sources" find it. They declare no dependencies,
# so what a script compiles against does not change.
#
# Sources are read from the engine tag the release was built from with `git archive`, so the working
# tree is never touched. With the tag left out it comes from the release notes' "Requires engine X".
set -euo pipefail

STAGE_DIR=""
if [ "${1:-}" = "--stage" ]; then STAGE_DIR="${2:?--stage needs a directory}"; shift 2; fi

API_VERSION="${1:?usage: publish-api-sources.sh [--dry-run] <apiVersion> [engineTag]}"
REPO="iEasyScript/script-api"
ENGINE_DIR="${PROJECTX_ENGINE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
JAR="${JAVA_HOME:-/usr}/bin/jar"

[ -d "$ENGINE_DIR/.git" ] || { echo "no engine checkout at $ENGINE_DIR (set PROJECTX_ENGINE_DIR)" >&2; exit 1; }

ENGINE_TAG="${2:-}"
if [ -z "$ENGINE_TAG" ]; then
    # The oldest releases predate the "Requires engine X" line, so a miss here is reported rather
    # than left to pipefail killing the script without saying why.
    ENGINE_TAG="v$(gh release view "v$API_VERSION" --repo "$REPO" --json body -q .body |
        grep -oiE "requires engine [0-9]+\.[0-9]+\.[0-9]+" | head -1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" || true)"
    [ "$ENGINE_TAG" != "v" ] || { echo "release v$API_VERSION does not say which engine it needs; pass the tag" >&2; exit 1; }
fi
git -C "$ENGINE_DIR" rev-parse -q --verify "refs/tags/$ENGINE_TAG" >/dev/null ||
    { echo "$ENGINE_DIR has no tag $ENGINE_TAG (git fetch --tags)" >&2; exit 1; }

# Ivy wants a publication timestamp; the release's own date keeps it honest.
PUBLISHED="$(gh release view "v$API_VERSION" --repo "$REPO" --json publishedAt -q .publishedAt |
    tr -d '\-:TZ' | cut -c1-14)"

echo "api $API_VERSION <- engine $ENGINE_TAG"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# :client-plugin-engine is the engine-api jar and :core is the core jar, but the engine only became a
# module of this repository partway through, so the path is resolved per tag rather than assumed.
src_root_for() {
    local module="$1" candidate
    for candidate in "$module/src/main/kotlin" "src/main/kotlin"; do
        if git -C "$ENGINE_DIR" cat-file -e "$ENGINE_TAG:$candidate" 2>/dev/null; then echo "$candidate"; return; fi
    done
    echo "tag $ENGINE_TAG has no source root for $module" >&2
    exit 1
}
ENGINE_SRC="$(src_root_for client-plugin-engine)"
CORE_SRC="$(src_root_for core)"
git -C "$ENGINE_DIR" archive "$ENGINE_TAG" "$ENGINE_SRC" "$CORE_SRC" | tar -x -C "$WORK"

build_module() {
    local artifact="$1"
    local src_root="$2"
    "$JAR" --create --file "$WORK/$artifact-$API_VERSION-sources.jar" -C "$WORK/$src_root" .
    cat > "$WORK/ivy-$artifact-$API_VERSION.xml" <<IVY
<ivy-module version="2.0" xmlns:m="http://ant.apache.org/ivy/maven">
  <info organisation="com.projectx" module="$artifact" revision="$API_VERSION" status="release" publication="$PUBLISHED"/>
  <configurations>
    <conf name="default" visibility="public"/>
    <conf name="sources" visibility="public"/>
  </configurations>
  <publications>
    <artifact name="$artifact" type="jar" ext="jar" conf="default"/>
    <artifact name="$artifact" type="source" ext="jar" conf="sources" m:classifier="sources"/>
  </publications>
</ivy-module>
IVY
    echo "  built $artifact-$API_VERSION-sources.jar ($(du -h "$WORK/$artifact-$API_VERSION-sources.jar" | cut -f1))"
}

build_module projectx-engine-api "$ENGINE_SRC"
build_module projectx-core "$CORE_SRC"

# Staging lays the assets out under v<version>/ exactly as the release serves them, so the same
# tree can be handed to a local web server to check resolution before anything is published.
if [ -n "$STAGE_DIR" ]; then
    mkdir -p "$STAGE_DIR/v$API_VERSION"
    cp "$WORK"/*-sources.jar "$WORK"/ivy-*.xml "$STAGE_DIR/v$API_VERSION/"
    echo "  staged into $STAGE_DIR/v$API_VERSION"
    exit 0
fi

gh release upload "v$API_VERSION" --repo "$REPO" --clobber \
    "$WORK/projectx-engine-api-$API_VERSION-sources.jar" \
    "$WORK/projectx-core-$API_VERSION-sources.jar" \
    "$WORK/ivy-projectx-engine-api-$API_VERSION.xml" \
    "$WORK/ivy-projectx-core-$API_VERSION.xml"
echo "attached sources to $REPO v$API_VERSION"
