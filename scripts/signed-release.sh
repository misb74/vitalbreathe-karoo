#!/bin/sh
set -eu

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH='' cd -- "$script_directory/.." && pwd -P)

if [ -z "$repository_root" ] || [ "$repository_root" = "/" ]; then
    echo "Refusing to resolve the signed release from an unsafe repository root" >&2
    exit 2
fi
if [ ! -f "$repository_root/gradlew" ] || \
    [ ! -x "$repository_root/gradlew" ] || \
    [ ! -f "$repository_root/settings.gradle.kts" ]; then
    echo "Refusing to clear a release outside the expected VitalBreathe project root" >&2
    exit 2
fi

dist_path="$repository_root/dist"
if [ -e "$dist_path" ] || [ -L "$dist_path" ]; then
    rm -rf -- "$dist_path"
fi

"$repository_root/gradlew" \
    --dependency-verification=strict \
    --no-daemon \
    --no-build-cache \
    "$@" \
    clean

exec "$repository_root/gradlew" \
    --dependency-verification=strict \
    --no-daemon \
    --no-build-cache \
    "$@" \
    signedRelease
