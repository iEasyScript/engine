#!/bin/sh
# Emits the plugins manifest the launcher's Plugins tab reads.
#
# The Release's asset links alone would tell the launcher where a jar lives, but
# not what it should hash to — so the manifest is what makes an install
# verifiable and an "is there a new version?" check answerable without
# downloading anything.
#
# usage: build-manifest.sh <dist-dir> <out-file>
set -eu

DIST="${1:?dist dir}"
OUT="${2:?output file}"

: "${PROJECTX_VERSION:?}" "${CI_API_V4_URL:?}" "${CI_PROJECT_ID:?}" "${CI_PROJECT_PATH:?}" "${PACKAGE_NAME:?}"

package_url() {
    echo "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/generic/${PACKAGE_NAME}/${PROJECTX_VERSION}/$1"
}

# Human-facing identity for a plugin channel, resolved into plugin_id/plugin_name/
# plugin_description. Kept here rather than in the launcher so adding a channel
# needs no launcher release for it to show up correctly.
plugin_meta() {
    case "$1" in
        official-scripts-*)
            plugin_id='official'
            plugin_name='Official Scripts'
            plugin_description='First-party scripts maintained by the Project X team. Built from client-plugin-engine/official-scripts.'
            ;;
        community-scripts-*)
            plugin_id='community'
            plugin_name='Community Scripts'
            plugin_description='Community-contributed scripts. Built from client-plugin-engine/community-scripts.'
            ;;
        *) return 1 ;;
    esac
}

plugins='[]'
launchers='[]'
# The three engine-home artifacts are not plugins — the launcher installs them
# into the engine home rather than the scripts dir — but they ride the same
# manifest so one fetch answers "what does this release offer?", and so the
# launcher can never install an engine jar without the bootstrap that loads it.
#
# The bootstrap and the supervisor land under fixed names the injector and the
# bootstrap resolve by, so their checksum here is the only thing that can tell a
# stale installed copy from a current one. Emitting them without one would be
# worse than omitting them: the launcher would have no way to compare.
engine='null'
supervisor='null'
bootstraps='[]'

# The manifest platform tag a native bootstrap filename implies.
bootstrap_platform() {
    case "$1" in
        libprojectxbootstrap-*.so) echo 'linux-x86_64' ;;
        projectxbootstrap-*.dll) echo 'windows-x86_64' ;;
        libprojectxbootstrap-*.dylib) echo 'macos-universal' ;;
        *) return 1 ;;
    esac
}

for path in "$DIST"/*; do
    [ -f "$path" ] || continue
    file="$(basename "$path")"
    case "$file" in
        *.sha256) continue ;;
    esac

    sha="$(sha256sum "$path" | cut -d' ' -f1)"
    size="$(wc -c < "$path" | tr -d ' ')"
    url="$(package_url "$file")"

    case "$file" in
        official-scripts-*.jar|community-scripts-*.jar)
            plugin_meta "$file"
            plugins="$(printf '%s' "$plugins" | jq \
                --arg id "$plugin_id" --arg name "$plugin_name" --arg description "$plugin_description" \
                --arg file "$file" --arg version "$PROJECTX_VERSION" \
                --arg sha256 "$sha" --argjson size "$size" --arg url "$url" \
                '. + [{id: $id, name: $name, description: $description, file: $file,
                       version: $version, sha256: $sha256, size: $size, url: $url}]')"
            ;;
        projectx-engine-*.jar)
            engine="$(jq -n --arg file "$file" --arg version "$PROJECTX_VERSION" \
                --arg sha256 "$sha" --argjson size "$size" --arg url "$url" \
                '{file: $file, version: $version, sha256: $sha256, size: $size, url: $url}')"
            ;;
        projectx-supervisor-*.jar)
            supervisor="$(jq -n --arg file "$file" --arg version "$PROJECTX_VERSION" \
                --arg sha256 "$sha" --argjson size "$size" --arg url "$url" \
                '{file: $file, version: $version, sha256: $sha256, size: $size, url: $url}')"
            ;;
        libprojectxbootstrap-*.so|projectxbootstrap-*.dll|libprojectxbootstrap-*.dylib)
            platform="$(bootstrap_platform "$file")"
            bootstraps="$(printf '%s' "$bootstraps" | jq \
                --arg platform "$platform" --arg file "$file" --arg version "$PROJECTX_VERSION" \
                --arg sha256 "$sha" --argjson size "$size" --arg url "$url" \
                '. + [{platform: $platform, file: $file, version: $version,
                       sha256: $sha256, size: $size, url: $url}]')"
            ;;
        project-x-launcher-*)
            platform="${file#project-x-launcher-}"
            platform="${platform%.tar.gz}"
            platform="${platform%.zip}"
            platform="${platform%.exe}"
            platform="${platform%-"$PROJECTX_VERSION"}"
            launchers="$(printf '%s' "$launchers" | jq \
                --arg platform "$platform" --arg file "$file" --arg version "$PROJECTX_VERSION" \
                --arg sha256 "$sha" --argjson size "$size" --arg url "$url" \
                '. + [{platform: $platform, file: $file, version: $version,
                       sha256: $sha256, size: $size, url: $url}]')"
            ;;
    esac
done

jq -n \
    --arg version "$PROJECTX_VERSION" \
    --arg project "$CI_PROJECT_PATH" \
    --arg pipeline "${CI_PIPELINE_URL:-}" \
    --argjson plugins "$plugins" \
    --argjson launcher "$launchers" \
    --argjson engine "$engine" \
    --argjson supervisor "$supervisor" \
    --argjson bootstrap "$bootstraps" \
    '{schema: 1, version: $version, project: $project, pipeline: $pipeline,
      plugins: $plugins, launcher: $launcher, engine: $engine,
      supervisor: $supervisor, bootstrap: $bootstrap}' > "$OUT"

cat "$OUT"
