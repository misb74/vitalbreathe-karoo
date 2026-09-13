#!/bin/sh
set -eu

repository="misb74/vitalbreathe-karoo"

usage() {
    cat <<'EOF'
Usage: ./scripts/publish-release.sh --check
       ./scripts/publish-release.sh --publish

--check validates the committed source, successful CI run, signed dist files,
GitHub repository, and release availability without changing GitHub.

--publish rebuilds and verifies the signed candidate, repeats every remote
check, then creates the version from version.properties as the non-prerelease
Latest GitHub release. The release commit must already be pushed to origin/main.
EOF
}

fail() {
    printf 'Release refused: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH='' cd -- "$script_directory/.." && pwd -P)

case "${1:-}" in
    --check | --publish)
        operation=$1
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

if [ "$#" -ne 1 ]; then
    usage >&2
    exit 2
fi

if [ -z "$repository_root" ] || [ "$repository_root" = "/" ]; then
    fail "unable to resolve a safe VitalBreathe repository root"
fi
if [ ! -f "$repository_root/version.properties" ] || \
    [ ! -x "$repository_root/gradlew" ] || \
    [ ! -f "$repository_root/scripts/signed-release.sh" ]; then
    fail "expected VitalBreathe release files are missing from $repository_root"
fi

require_command awk
require_command cmp
require_command git
require_command gh
require_command grep
require_command sed

cd "$repository_root"

git_root=$(git rev-parse --show-toplevel 2>/dev/null) || \
    fail "the project is not inside a Git worktree"
git_root=$(CDPATH='' cd -- "$git_root" && pwd -P)
[ "$git_root" = "$repository_root" ] || \
    fail "the script is not running from the VitalBreathe Git root"

property_value() {
    property_name=$1
    property_file=$2
    property_count=$(awk -F= -v key="$property_name" '$1 == key { count += 1 } END { print count + 0 }' "$property_file")
    [ "$property_count" -eq 1 ] || \
        fail "$property_file must contain exactly one $property_name entry"
    sed -n "s/^${property_name}=//p" "$property_file"
}

version_name=$(property_value VERSION_NAME version.properties)
version_code=$(property_value VERSION_CODE version.properties)
pinned_signer_sha256=$(property_value RELEASE_SIGNER_SHA256 version.properties)
printf '%s\n' "$version_name" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$' || \
    fail "version.properties VERSION_NAME must be a semantic version"
release_tag="v$version_name"
printf '%s\n' "$version_code" | grep -Eq '^[1-9][0-9]*$' || \
    fail "version.properties VERSION_CODE must be a positive integer"
printf '%s\n' "$pinned_signer_sha256" | grep -Eq '^[0-9a-f]{64}$' || \
    fail "version.properties RELEASE_SIGNER_SHA256 must be one lowercase fingerprint"

notes_file="docs/releases/$version_name.md"
[ -s "$notes_file" ] || fail "release notes are missing or empty: $notes_file"
grep -Fq "$version_name" "$notes_file" || \
    fail "release notes do not name version $version_name"
if grep -Fq "## [$version_name] - Unreleased" CHANGELOG.md; then
    fail "CHANGELOG.md still marks $version_name as Unreleased"
fi

current_branch=$(git symbolic-ref --quiet --short HEAD 2>/dev/null) || \
    fail "the release must be made from the main branch, not detached HEAD"
[ "$current_branch" = "main" ] || fail "current branch is $current_branch; expected main"

if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
    fail "the Git worktree is not clean; commit every release input first"
fi

for tracked_file in \
    version.properties \
    manifest.json \
    icon.png \
    THIRD_PARTY_NOTICES.md \
    karoo-ext/LICENSE \
    "$notes_file"
do
    git ls-files --error-unmatch "$tracked_file" >/dev/null 2>&1 || \
        fail "release input is not tracked by Git: $tracked_file"
done

origin_url=$(git remote get-url origin 2>/dev/null) || fail "Git remote origin is missing"
case "$origin_url" in
    https://github.com/misb74/vitalbreathe-karoo | \
    https://github.com/misb74/vitalbreathe-karoo.git | \
    git@github.com:misb74/vitalbreathe-karoo.git | \
    ssh://git@github.com/misb74/vitalbreathe-karoo.git)
        ;;
    *)
        fail "origin points to $origin_url instead of github.com/$repository"
        ;;
esac

gh auth status --hostname github.com >/dev/null 2>&1 || \
    fail "GitHub CLI is not authenticated to github.com"

verify_remote_state() {
    git fetch --quiet --no-tags origin main || fail "unable to fetch origin/main"

    source_revision=$(git rev-parse HEAD)
    origin_revision=$(git rev-parse refs/remotes/origin/main 2>/dev/null) || \
        fail "origin/main does not exist"
    [ "$source_revision" = "$origin_revision" ] || \
        fail "local main ($source_revision) does not exactly match origin/main ($origin_revision)"

    default_branch=$(gh api "repos/$repository" --jq '.default_branch') || \
        fail "unable to inspect github.com/$repository"
    [ "$default_branch" = "main" ] || \
        fail "GitHub default branch is $default_branch; expected main"

    successful_ci_revision=$(
        gh run list \
            --repo "$repository" \
            --workflow android.yml \
            --commit "$source_revision" \
            --status success \
            --limit 1 \
            --json headSha \
            --jq '.[0].headSha'
    ) || fail "unable to inspect the Android verification run"
    [ "$successful_ci_revision" = "$source_revision" ] || \
        fail "no successful Android verification run exists for $source_revision"

    successful_security_revision=$(
        gh run list \
            --repo "$repository" \
            --workflow security.yml \
            --commit "$source_revision" \
            --status success \
            --limit 1 \
            --json headSha \
            --jq '.[0].headSha'
    ) || fail "unable to inspect the Security workflow run"
    [ "$successful_security_revision" = "$source_revision" ] || \
        fail "no successful Security workflow run exists for $source_revision"
}

verify_release_is_new() {
    if git show-ref --verify --quiet "refs/tags/$release_tag"; then
        fail "local tag already exists: $release_tag"
    fi

    remote_tag=$(git ls-remote --tags origin "refs/tags/$release_tag") || \
        fail "unable to inspect remote tags"
    [ -z "$remote_tag" ] || fail "remote tag already exists: $release_tag"

    existing_release_tags=$(
        gh api --paginate "repos/$repository/releases?per_page=100" --jq '.[].tag_name'
    ) || fail "unable to inspect existing GitHub releases"
    if printf '%s\n' "$existing_release_tags" | grep -Fx "$release_tag" >/dev/null 2>&1; then
        fail "GitHub release already exists: $release_tag"
    fi
}

verify_remote_state
verify_release_is_new
source_revision=$(git rev-parse HEAD)

if [ "$operation" = "--publish" ]; then
    printf 'Rebuilding the signed release from the clean published commit.\n'
    "$repository_root/scripts/signed-release.sh"
fi

dist_directory="$repository_root/dist"
apk_name="vitalbreathe-karoo-$version_name.apk"
checksum_name="$apk_name.sha256"

set -- \
    "$dist_directory/$apk_name" \
    "$dist_directory/$checksum_name" \
    "$dist_directory/manifest.json" \
    "$dist_directory/icon.png" \
    "$dist_directory/THIRD_PARTY_NOTICES.md" \
    "$dist_directory/Apache-2.0.txt" \
    "$dist_directory/SIGNER_CERT_SHA256.txt" \
    "$dist_directory/RELEASE_INFO.txt"

for asset_path in "$@"; do
    [ -f "$asset_path" ] || fail "required release asset is missing: $asset_path"
    [ ! -L "$asset_path" ] || fail "release assets must not be symbolic links: $asset_path"
    [ -s "$asset_path" ] || fail "release asset is empty: $asset_path"
done

dist_entry_count=$(find "$dist_directory" -mindepth 1 -maxdepth 1 -print | awk 'END { print NR + 0 }')
[ "$dist_entry_count" -eq 8 ] || \
    fail "dist must contain exactly the eight public assets; found $dist_entry_count entries"

cmp -s manifest.json "$dist_directory/manifest.json" || \
    fail "dist/manifest.json does not match the committed manifest.json"
cmp -s icon.png "$dist_directory/icon.png" || \
    fail "dist/icon.png does not match the committed icon.png"
cmp -s THIRD_PARTY_NOTICES.md "$dist_directory/THIRD_PARTY_NOTICES.md" || \
    fail "dist/THIRD_PARTY_NOTICES.md does not match the committed notices"
cmp -s karoo-ext/LICENSE "$dist_directory/Apache-2.0.txt" || \
    fail "dist/Apache-2.0.txt does not match the vendored Apache licence"

sha256_file() {
    file_to_hash=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file_to_hash" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file_to_hash" | awk '{ print $1 }'
    else
        fail "sha256sum or shasum is required to verify the APK"
    fi
}

apk_sha256=$(sha256_file "$dist_directory/$apk_name")
printf '%s\n' "$apk_sha256" | grep -Eq '^[0-9a-f]{64}$' || \
    fail "unable to calculate a valid APK SHA-256"
checksum_line=$(sed -n '1p' "$dist_directory/$checksum_name")
[ "$checksum_line" = "$apk_sha256  $apk_name" ] || \
    fail "$checksum_name does not contain the exact APK checksum and filename"
checksum_line_count=$(awk 'END { print NR + 0 }' "$dist_directory/$checksum_name")
[ "$checksum_line_count" -eq 1 ] || fail "$checksum_name must contain exactly one line"

signer_sha256=$(sed -n '1p' "$dist_directory/SIGNER_CERT_SHA256.txt")
printf '%s\n' "$signer_sha256" | grep -Eq '^[0-9a-f]{64}$' || \
    fail "SIGNER_CERT_SHA256.txt does not contain one lowercase SHA-256 fingerprint"
signer_line_count=$(awk 'END { print NR + 0 }' "$dist_directory/SIGNER_CERT_SHA256.txt")
[ "$signer_line_count" -eq 1 ] || \
    fail "SIGNER_CERT_SHA256.txt must contain exactly one line"
[ "$signer_sha256" = "$pinned_signer_sha256" ] || \
    fail "release signer does not match the fingerprint pinned in version.properties"

release_info="$dist_directory/RELEASE_INFO.txt"
release_info_value() {
    release_key=$1
    release_key_count=$(awk -F= -v key="$release_key" '$1 == key { count += 1 } END { print count + 0 }' "$release_info")
    [ "$release_key_count" -eq 1 ] || \
        fail "RELEASE_INFO.txt must contain exactly one $release_key entry"
    sed -n "s/^${release_key}=//p" "$release_info"
}

[ "$(release_info_value artifact)" = "$apk_name" ] || \
    fail "RELEASE_INFO.txt names a different APK"
[ "$(release_info_value apkSha256)" = "$apk_sha256" ] || \
    fail "RELEASE_INFO.txt contains a different APK checksum"
[ "$(release_info_value signerCertificateSha256)" = "$signer_sha256" ] || \
    fail "RELEASE_INFO.txt contains a different signer fingerprint"
[ "$(release_info_value packageName)" = "com.moraybrown.vitalbreathe" ] || \
    fail "RELEASE_INFO.txt contains the wrong package name"
[ "$(release_info_value versionCode)" = "$version_code" ] || \
    fail "RELEASE_INFO.txt versionCode does not match version.properties"
[ "$(release_info_value versionName)" = "$version_name" ] || \
    fail "RELEASE_INFO.txt versionName does not match version.properties"
[ "$(release_info_value sourceRevision)" = "$source_revision" ] || \
    fail "RELEASE_INFO.txt source revision does not match HEAD"
[ "$(release_info_value sourceTree)" = "clean" ] || \
    fail "RELEASE_INFO.txt does not record a clean source tree"
expected_build_identity="versionName=$version_name;versionCode=$version_code;sourceRevision=$source_revision;sourceTree=clean;buildType=release"
[ "$(release_info_value buildIdentity)" = "$expected_build_identity" ] || \
    fail "RELEASE_INFO.txt build identity does not match the release commit"

if [ "$operation" = "--check" ]; then
    printf 'Release checks passed for %s at %s.\n' "$release_tag" "$source_revision"
    printf 'All eight public assets are complete and no tag or release exists.\n'
    printf 'No GitHub changes were made. Run this command again with --publish to publish.\n'
    exit 0
fi

# Close the small window between the initial remote checks and publication.
verify_remote_state
verify_release_is_new
[ "$(git rev-parse HEAD)" = "$source_revision" ] || \
    fail "HEAD changed during release validation"
[ -z "$(git status --porcelain --untracked-files=normal)" ] || \
    fail "the Git worktree changed during release validation"

printf 'Publishing %s from %s with eight verified assets.\n' "$release_tag" "$source_revision"
gh release create "$release_tag" \
    --repo "$repository" \
    --target "$source_revision" \
    --title "VitalBreathe $version_name" \
    --notes-file "$notes_file" \
    --latest \
    --fail-on-no-commits \
    "$@"
