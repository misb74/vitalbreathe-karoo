#!/bin/sh
set -eu

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH='' cd -- "$script_directory/.." && pwd -P)

resolve_named_adb() {
    requested=$1
    case "$requested" in
        */*)
            if [ -x "$requested" ]; then
                printf '%s\n' "$requested"
                return 0
            fi
            ;;
        *)
            resolved=$(command -v "$requested" 2>/dev/null || true)
            if [ -n "$resolved" ] && [ -x "$resolved" ]; then
                printf '%s\n' "$resolved"
                return 0
            fi
            ;;
    esac
    return 1
}

resolve_sdk_root() {
    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        if [ -n "$sdk_root" ] && [ -x "$sdk_root/platform-tools/adb" ]; then
            printf '%s\n' "$sdk_root"
            return 0
        fi
    done

    if [ -f "$repository_root/local.properties" ]; then
        local_sdk=$(sed -n \
            's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
            "$repository_root/local.properties" | sed -n '1p')
        if [ -n "$local_sdk" ]; then
            local_sdk=$(printf '%s\n' "$local_sdk" | sed 's/\\ / /g; s/\\\\/\\/g')
            if [ -x "$local_sdk/platform-tools/adb" ]; then
                printf '%s\n' "$local_sdk"
                return 0
            fi
        fi
    fi

    for sdk_root in \
        "/opt/homebrew/share/android-commandlinetools" \
        "/usr/local/share/android-commandlinetools" \
        "${HOME:+${HOME}/Library/Android/sdk}"
    do
        if [ -n "$sdk_root" ] && [ -x "$sdk_root/platform-tools/adb" ]; then
            printf '%s\n' "$sdk_root"
            return 0
        fi
    done

    return 1
}

if [ -n "${ADB:-}" ]; then
    if ! adb_path=$(resolve_named_adb "$ADB"); then
        echo "ADB points to a command that is not executable: $ADB" >&2
        exit 2
    fi
else
    adb_path=$(command -v adb 2>/dev/null || true)
    if [ -z "$adb_path" ] || [ ! -x "$adb_path" ]; then
        if sdk_root=$(resolve_sdk_root); then
            adb_path="$sdk_root/platform-tools/adb"
        else
            echo "Android platform-tools were not found." >&2
            echo "Install platform-tools, set ANDROID_SDK_ROOT, or add sdk.dir to local.properties." >&2
            exit 2
        fi
    fi
fi

exec "$adb_path" "$@"
