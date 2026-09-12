#!/bin/sh
# Uploads every pipeline artifact to the Generic Package Registry and emits the
# asset links for the Release.
#
# Link names are deliberately version-free (`official-scripts.jar`, not
# `official-scripts-1.2.3.jar`): the launcher looks a link up by name on whatever
# release is current, so a version baked into the name would make every lookup a
# parsing problem.
#
# Each link also gets a direct asset path of `/<name>`. GitLab serves that under
# `<project>/-/releases/permalink/latest/downloads/<name>` — note it supplies the
# `/downloads` prefix itself, so the path here must not repeat it. That URL is a
# permanent public link per platform, never reissued when a version ships.
#
# usage: publish-packages.sh <dist-dir> <manifest-file> <links-out>
set -eu

DIST="${1:?dist dir}"
MANIFEST="${2:?manifest file}"
LINKS="${3:?links output}"

: "${PROJECTX_VERSION:?}" "${CI_API_V4_URL:?}" "${CI_PROJECT_ID:?}" "${CI_JOB_TOKEN:?}" "${PACKAGE_NAME:?}"

BASE="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/packages/generic/${PACKAGE_NAME}/${PROJECTX_VERSION}"

: > "$LINKS"

# The version-free name a Release link is published under.
link_name() {
    case "$1" in
        plugins-manifest.json) echo 'plugins-manifest.json' ;;
        projectx-engine-*.jar) echo 'projectx-engine.jar' ;;
        *.tar.gz) echo "$(echo "${1%.tar.gz}" | sed "s/-${PROJECTX_VERSION}\$//").tar.gz" ;;
        *.zip) echo "$(echo "${1%.zip}" | sed "s/-${PROJECTX_VERSION}\$//").zip" ;;
        *.exe) echo "$(echo "${1%.exe}" | sed "s/-${PROJECTX_VERSION}\$//").exe" ;;
        *.jar) echo "$(echo "${1%.jar}" | sed "s/-${PROJECTX_VERSION}\$//").jar" ;;
        *.so) echo "$(echo "${1%.so}" | sed "s/-${PROJECTX_VERSION}\$//").so" ;;
        *.dll) echo "$(echo "${1%.dll}" | sed "s/-${PROJECTX_VERSION}\$//").dll" ;;
        *.dylib) echo "$(echo "${1%.dylib}" | sed "s/-${PROJECTX_VERSION}\$//").dylib" ;;
        *) echo "$1" | sed "s/-${PROJECTX_VERSION}\$//" ;;
    esac
}

upload() {
    path="$1"
    file="$(basename "$path")"
    echo "uploading $file"
    curl --fail --silent --show-error --retry 3 --retry-delay 2 \
        --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
        --upload-file "$path" \
        "${BASE}/${file}" > /dev/null
    echo "  -> ${BASE}/${file}"
}

record_link() {
    file="$1"
    name="$(link_name "$file")"
    # `direct_asset_path` only: release-cli rejects an asset that also carries the
    # deprecated `filepath` rather than ignoring one of the two.
    jq -nc --arg name "$name" --arg url "${BASE}/${file}" --arg path "/${name}" \
        '{name: $name, url: $url, link_type: "package", direct_asset_path: $path}' >> "$LINKS"
}

for path in "$DIST"/*; do
    [ -f "$path" ] || continue
    upload "$path"
    case "$(basename "$path")" in
        # Checksums ride along in the registry but would only clutter the Release.
        *.sha256) ;;
        *) record_link "$(basename "$path")" ;;
    esac
done

upload "$MANIFEST"
record_link "$(basename "$MANIFEST")"

echo "release asset links:"
cat "$LINKS"
