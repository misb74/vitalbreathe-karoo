#!/bin/sh
set -eu

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH='' cd -- "$script_directory/.." && pwd -P)
adb_command="$script_directory/karoo-adb.sh"
package_name="com.moraybrown.vitalbreathe"
apk_path=""
install_apk=false
output_directory=""

usage() {
    cat <<'EOF'
Usage: ./scripts/karoo-test-capture.sh [--apk APK] [--install] [--output DIRECTORY]

By default this only reads device and app state, then captures VitalBreathe logs
until Ctrl-C. --install explicitly installs or upgrades the APK supplied by
--apk; it never uninstalls an existing app or clears its settings.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --apk)
            [ "$#" -ge 2 ] || { echo "--apk needs a file path" >&2; exit 2; }
            apk_path=$2
            shift 2
            ;;
        --install)
            install_apk=true
            shift
            ;;
        --output)
            [ "$#" -ge 2 ] || { echo "--output needs a directory path" >&2; exit 2; }
            output_directory=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [ "$install_apk" = true ] && [ -z "$apk_path" ]; then
    echo "--install requires --apk APK" >&2
    exit 2
fi
if [ -n "$apk_path" ] && [ ! -f "$apk_path" ]; then
    echo "APK not found: $apk_path" >&2
    exit 2
fi
if [ ! -x "$adb_command" ]; then
    echo "ADB helper is not executable: $adb_command" >&2
    exit 2
fi

connected_devices=$("$adb_command" devices -l)
authorized_serials=$(printf '%s\n' "$connected_devices" | awk 'NR > 1 && $2 == "device" { print $1 }')
authorized_count=$(printf '%s\n' "$authorized_serials" | awk 'NF { count += 1 } END { print count + 0 }')

if [ "$authorized_count" -ne 1 ]; then
    echo "Exactly one authorised Karoo must be connected; found $authorized_count." >&2
    printf '%s\n' "$connected_devices" >&2
    echo "Connect one Karoo, unlock it, and accept its USB debugging prompt." >&2
    exit 2
fi
device_serial=$(printf '%s\n' "$authorized_serials" | sed -n '1p')
device_manufacturer=$("$adb_command" -s "$device_serial" \
    shell getprop ro.product.manufacturer | tr -d '\r')
device_model=$("$adb_command" -s "$device_serial" \
    shell getprop ro.product.model | tr -d '\r')
device_name=$("$adb_command" -s "$device_serial" \
    shell getprop ro.product.device | tr -d '\r')
device_identity=$(printf '%s %s %s\n' \
    "$device_manufacturer" "$device_model" "$device_name" | tr '[:upper:]' '[:lower:]')
case "$device_identity" in
    *hammerhead*|*karoo*) ;;
    *)
        echo "The authorised Android device does not identify itself as a Hammerhead Karoo." >&2
        echo "Detected manufacturer='$device_manufacturer' model='$device_model' device='$device_name'." >&2
        echo "No APK was installed and no device settings were changed." >&2
        exit 2
        ;;
esac

if [ -z "$output_directory" ]; then
    timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
    output_directory="$repository_root/captures/$timestamp"
fi
if [ -e "$output_directory" ] || [ -L "$output_directory" ]; then
    echo "Output path already exists; choose a new directory: $output_directory" >&2
    exit 2
fi
mkdir -p -- "$output_directory"
if [ -f "$repository_root/docs/HARDWARE_TEST_TEMPLATE.md" ]; then
    cp "$repository_root/docs/HARDWARE_TEST_TEMPLATE.md" \
        "$output_directory/HARDWARE_TEST_RECORD.md"
fi

hash_file() {
    target_file=$1
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$target_file" | awk '{ print $1 }'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$target_file" | awk '{ print $1 }'
    else
        echo "A SHA-256 tool is required (shasum or sha256sum)." >&2
        return 1
    fi
}

sdk_root_from_properties() {
    if [ -f "$repository_root/local.properties" ]; then
        sdk_root=$(sed -n \
            's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
            "$repository_root/local.properties" | sed -n '1p')
        if [ -n "$sdk_root" ]; then
            printf '%s\n' "$sdk_root" | sed 's/\\ / /g; s/\\\\/\\/g'
            return 0
        fi
    fi
    return 1
}

resolve_build_tool() {
    tool_name=$1
    resolved=$(command -v "$tool_name" 2>/dev/null || true)
    if [ -n "$resolved" ] && [ -x "$resolved" ]; then
        printf '%s\n' "$resolved"
        return 0
    fi

    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        candidate="$sdk_root/build-tools/34.0.0/$tool_name"
        if [ -n "$sdk_root" ] && [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    sdk_root=$(sdk_root_from_properties 2>/dev/null || true)
    if [ -n "$sdk_root" ] && [ -x "$sdk_root/build-tools/34.0.0/$tool_name" ]; then
        printf '%s\n' "$sdk_root/build-tools/34.0.0/$tool_name"
        return 0
    fi

    for sdk_root in \
        "/opt/homebrew/share/android-commandlinetools" \
        "/usr/local/share/android-commandlinetools" \
        "${HOME:+${HOME}/Library/Android/sdk}"
    do
        candidate="$sdk_root/build-tools/34.0.0/$tool_name"
        if [ -n "$sdk_root" ] && [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

run_apksigner() {
    signer_command=$1
    shift
    if [ -n "${JAVA_HOME:-}" ]; then
        "$signer_command" "$@"
    elif [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
        env JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
            "$signer_command" "$@"
    else
        "$signer_command" "$@"
    fi
}

extract_build_identity() {
    manifest_dump=$1
    awk '
        /com\.moraybrown\.vitalbreathe\.BUILD_ID/ { found = 1; next }
        found && /A: android:value/ {
            line = $0
            sub(/^.*="/, "", line)
            sub(/".*$/, "", line)
            print line
            exit
        }
    ' "$manifest_dump"
}

validate_clean_build_identity() {
    identity=$1
    version_name=$2
    version_code=$3
    label=$4
    if ! printf '%s\n' "$identity" | grep -Eq \
        '^versionName=[^;]+;versionCode=[0-9]+;sourceRevision=[0-9a-fA-F]{40}([0-9a-fA-F]{24})?;sourceTree=clean;buildType=(debug|release)$'; then
        echo "$label does not have a clean, reproducible VitalBreathe build identity." >&2
        return 1
    fi
    case "$identity" in
        "versionName=$version_name;versionCode=$version_code;"*) ;;
        *)
            echo "$label build identity disagrees with its Android version metadata." >&2
            return 1
            ;;
    esac
}

if ! aapt_command=$(resolve_build_tool aapt); then
    echo "Android Build Tools 34.0.0 aapt was not found." >&2
    exit 2
fi
if ! apksigner_command=$(resolve_build_tool apksigner); then
    echo "Android Build Tools 34.0.0 apksigner was not found." >&2
    exit 2
fi

{
    echo "capture_started_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "package=$package_name"
    echo "local_repository_head=$(git -C "$repository_root" rev-parse HEAD 2>/dev/null || echo unknown)"
    if git -C "$repository_root" diff --quiet --ignore-submodules -- 2>/dev/null && \
        git -C "$repository_root" diff --cached --quiet --ignore-submodules -- 2>/dev/null && \
        [ -z "$(git -C "$repository_root" ls-files --others --exclude-standard 2>/dev/null)" ]; then
        echo "local_repository_tree=clean"
    else
        echo "local_repository_tree=dirty"
    fi
} > "$output_directory/SESSION_INFO.txt"

if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$device_serial" | shasum -a 256 | awk '{ print "adb_serial_sha256=" $1 }' \
        >> "$output_directory/SESSION_INFO.txt"
elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$device_serial" | sha256sum | awk '{ print "adb_serial_sha256=" $1 }' \
        >> "$output_directory/SESSION_INFO.txt"
fi

if [ -n "$apk_path" ]; then
    apk_hash=$(hash_file "$apk_path")
    {
        echo "apk_path=$apk_path"
        echo "apk_sha256=$apk_hash"
    } >> "$output_directory/SESSION_INFO.txt"

    checksum_file="$apk_path.sha256"
    if [ -f "$checksum_file" ]; then
        expected_hash=$(awk 'NR == 1 { print tolower($1) }' "$checksum_file")
        if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9a-f]{64}$'; then
            echo "Invalid APK checksum sidecar: $checksum_file" >&2
            exit 2
        fi
        if [ "$apk_hash" != "$expected_hash" ]; then
            echo "APK checksum does not match $checksum_file" >&2
            exit 2
        fi
        echo "apk_checksum_sidecar=verified" >> "$output_directory/SESSION_INFO.txt"
    else
        echo "apk_checksum_sidecar=not_provided" >> "$output_directory/SESSION_INFO.txt"
    fi

    "$aapt_command" dump badging "$apk_path" > "$output_directory/APK_BADGING.txt"
    "$aapt_command" dump xmltree "$apk_path" AndroidManifest.xml \
        > "$output_directory/APK_MANIFEST.txt"
    run_apksigner "$apksigner_command" verify --verbose --print-certs "$apk_path" \
        > "$output_directory/APK_SIGNATURE.txt"

    apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" \
        "$output_directory/APK_BADGING.txt" | sed -n '1p')
    if [ "$apk_package" != "$package_name" ]; then
        echo "Refusing unexpected APK package: $apk_package" >&2
        exit 2
    fi
    if ! grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" \
        "$output_directory/APK_SIGNATURE.txt"; then
        echo "APK is missing the expected v2 signature." >&2
        exit 2
    fi
    if ! grep -Fq "Number of signers: 1" "$output_directory/APK_SIGNATURE.txt"; then
        echo "APK does not have exactly one signer." >&2
        exit 2
    fi

    apk_version_code=$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" \
        "$output_directory/APK_BADGING.txt" | sed -n '1p')
    apk_version_name=$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" \
        "$output_directory/APK_BADGING.txt" | sed -n '1p')
    apk_min_sdk=$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" \
        "$output_directory/APK_BADGING.txt" | sed -n '1p')
    apk_target_sdk=$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" \
        "$output_directory/APK_BADGING.txt" | sed -n '1p')
    apk_signer_sha256=$(sed -n \
        's/^Signer #1 certificate SHA-256 digest: //p' \
        "$output_directory/APK_SIGNATURE.txt" | sed -n '1p')
    apk_build_identity=$(extract_build_identity "$output_directory/APK_MANIFEST.txt")
    if ! validate_clean_build_identity \
        "$apk_build_identity" "$apk_version_name" "$apk_version_code" "The supplied APK"; then
        exit 2
    fi
    {
        echo "apk_package=$apk_package"
        echo "apk_version_code=$apk_version_code"
        echo "apk_version_name=$apk_version_name"
        echo "apk_min_sdk=$apk_min_sdk"
        echo "apk_target_sdk=$apk_target_sdk"
        echo "apk_signer_sha256=$apk_signer_sha256"
        echo "apk_build_identity=$apk_build_identity"
        echo "apk_v2_signature=verified"
        echo "apk_signer_count=1"
    } >> "$output_directory/SESSION_INFO.txt"
fi

if [ "$install_apk" = true ]; then
    echo "Installing the verified APK without uninstalling the existing app..."
    if ! "$adb_command" -s "$device_serial" install -r "$apk_path"; then
        echo "Install failed. The script did not uninstall the app or clear its settings." >&2
        exit 1
    fi
fi

installed_path=$("$adb_command" -s "$device_serial" shell pm path "$package_name" 2>/dev/null | tr -d '\r')
if [ -z "$installed_path" ]; then
    echo "VitalBreathe is not installed on the connected Karoo." >&2
    echo "Install it first, or rerun with --apk APK --install." >&2
    exit 2
fi
installed_apk_device_path=$(printf '%s\n' "$installed_path" | \
    sed -n 's/^package:\(.*\/base\.apk\)$/\1/p' | sed -n '1p')
if [ -z "$installed_apk_device_path" ]; then
    installed_apk_device_path=$(printf '%s\n' "$installed_path" | \
        sed -n 's/^package://p' | sed -n '1p')
fi
if [ -z "$installed_apk_device_path" ]; then
    echo "Unable to identify the installed VitalBreathe APK path." >&2
    exit 2
fi

installed_apk="$output_directory/INSTALLED_VITALBREATHE.apk"
if ! "$adb_command" -s "$device_serial" pull \
    "$installed_apk_device_path" "$installed_apk" \
    > "$output_directory/INSTALLED_APK_PULL.txt" 2>&1; then
    echo "Unable to copy the installed VitalBreathe APK for identity verification." >&2
    exit 2
fi
installed_apk_hash=$(hash_file "$installed_apk")
"$aapt_command" dump badging "$installed_apk" \
    > "$output_directory/INSTALLED_APK_BADGING.txt"
"$aapt_command" dump xmltree "$installed_apk" AndroidManifest.xml \
    > "$output_directory/INSTALLED_APK_MANIFEST.txt"
run_apksigner "$apksigner_command" verify --verbose --print-certs "$installed_apk" \
    > "$output_directory/INSTALLED_APK_SIGNATURE.txt"
installed_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" \
    "$output_directory/INSTALLED_APK_BADGING.txt" | sed -n '1p')
if [ "$installed_package" != "$package_name" ]; then
    echo "The installed APK has an unexpected package identity: $installed_package" >&2
    exit 2
fi
if ! grep -Fq "Verified using v2 scheme (APK Signature Scheme v2): true" \
    "$output_directory/INSTALLED_APK_SIGNATURE.txt"; then
    echo "The installed VitalBreathe APK is missing its expected v2 signature." >&2
    exit 2
fi
if ! grep -Fq "Number of signers: 1" \
    "$output_directory/INSTALLED_APK_SIGNATURE.txt"; then
    echo "The installed VitalBreathe APK does not have exactly one signer." >&2
    exit 2
fi
installed_version_code=$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" \
    "$output_directory/INSTALLED_APK_BADGING.txt" | sed -n '1p')
installed_version_name=$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" \
    "$output_directory/INSTALLED_APK_BADGING.txt" | sed -n '1p')
installed_signer_sha256=$(sed -n \
    's/^Signer #1 certificate SHA-256 digest: //p' \
    "$output_directory/INSTALLED_APK_SIGNATURE.txt" | sed -n '1p')
installed_build_identity=$(extract_build_identity \
    "$output_directory/INSTALLED_APK_MANIFEST.txt")
if ! validate_clean_build_identity \
    "$installed_build_identity" "$installed_version_name" "$installed_version_code" \
    "The installed APK"; then
    echo "Install the current test candidate before collecting validation evidence." >&2
    exit 2
fi
{
    echo "installed_apk_sha256=$installed_apk_hash"
    echo "installed_package=$installed_package"
    echo "installed_version_code=$installed_version_code"
    echo "installed_version_name=$installed_version_name"
    echo "installed_signer_sha256=$installed_signer_sha256"
    echo "installed_build_identity=$installed_build_identity"
    echo "installed_v2_signature=verified"
    echo "installed_signer_count=1"
} >> "$output_directory/SESSION_INFO.txt"
if [ -n "$apk_path" ]; then
    if [ "$installed_apk_hash" != "$apk_hash" ]; then
        echo "The supplied APK is not the exact build installed on this Karoo." >&2
        echo "Rerun with --install, or supply the installed build's APK." >&2
        exit 2
    fi
    echo "installed_apk_matches_supplied_apk=yes" \
        >> "$output_directory/SESSION_INFO.txt"
fi

{
    echo "manufacturer=$device_manufacturer"
    echo "model=$device_model"
    echo "device=$device_name"
    echo "android_release=$("$adb_command" -s "$device_serial" shell getprop ro.build.version.release | tr -d '\r')"
    echo "android_api=$("$adb_command" -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "build_display=$("$adb_command" -s "$device_serial" shell getprop ro.build.display.id | tr -d '\r')"
    echo "build_incremental=$("$adb_command" -s "$device_serial" shell getprop ro.build.version.incremental | tr -d '\r')"
    echo "installed_path=$installed_path"
} > "$output_directory/DEVICE_INFO.txt"

"$adb_command" -s "$device_serial" shell dumpsys package "$package_name" \
    > "$output_directory/PACKAGE_BEFORE.txt"
"$adb_command" -s "$device_serial" shell dumpsys activity services "$package_name" \
    > "$output_directory/SERVICES_BEFORE.txt" 2>&1 || true
"$adb_command" -s "$device_serial" shell dumpsys meminfo "$package_name" \
    > "$output_directory/MEMORY_BEFORE.txt" 2>&1 || true
"$adb_command" -s "$device_serial" shell dumpsys battery \
    > "$output_directory/KAROO_BATTERY_BEFORE.txt" 2>&1 || true

capture_finished=false
capture_exit_status=unknown
# shellcheck disable=SC2329 # Called by the EXIT trap installed below.
finish_capture() {
    if [ "$capture_finished" = true ]; then
        return
    fi
    capture_finished=true
    {
        echo "capture_finished_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        echo "capture_exit_status=$capture_exit_status"
        case "$capture_exit_status" in
            130) echo "capture_result=stopped_by_tester" ;;
            0) echo "capture_result=log_stream_ended_unexpectedly" ;;
            *) echo "capture_result=log_stream_failed_or_interrupted" ;;
        esac
    } >> "$output_directory/SESSION_INFO.txt"
    "$adb_command" -s "$device_serial" shell dumpsys package "$package_name" \
        > "$output_directory/PACKAGE_AFTER.txt" 2>&1 || true
    "$adb_command" -s "$device_serial" shell dumpsys activity services "$package_name" \
        > "$output_directory/SERVICES_AFTER.txt" 2>&1 || true
    "$adb_command" -s "$device_serial" shell dumpsys meminfo "$package_name" \
        > "$output_directory/MEMORY_AFTER.txt" 2>&1 || true
    "$adb_command" -s "$device_serial" shell dumpsys battery \
        > "$output_directory/KAROO_BATTERY_AFTER.txt" 2>&1 || true
    if [ -f "$output_directory/VITALBREATHE_LOGCAT.txt" ]; then
        echo "logcat_sha256=$(hash_file "$output_directory/VITALBREATHE_LOGCAT.txt" 2>/dev/null || echo unavailable)" \
            >> "$output_directory/SESSION_INFO.txt"
    fi
    echo "Evidence saved to $output_directory"
    case "$capture_exit_status" in
        130) ;;
        *) echo "Warning: the log stream ended unexpectedly; check LOGCAT_STDERR.txt." >&2 ;;
    esac
    echo "Review logs and screenshots for private sensor, location, and health data before sharing."
}

# shellcheck disable=SC2329 # Called by the EXIT trap installed below.
on_exit() {
    exit_status=$?
    capture_exit_status=$exit_status
    trap - 0 2 15
    finish_capture
    exit "$exit_status"
}
trap on_exit 0
trap 'exit 130' 2
trap 'exit 143' 15

echo "Capturing VitalBreathe diagnostics. Reproduce the test now, then press Ctrl-C."
echo "The Karoo log buffer is not being cleared."
"$adb_command" -s "$device_serial" logcat -T 1 -v threadtime \
    VitalBreathe:V \
    AndroidRuntime:E \
    '*:S' \
    > "$output_directory/VITALBREATHE_LOGCAT.txt" \
    2> "$output_directory/LOGCAT_STDERR.txt"
echo "The log stream ended before the tester stopped it." >&2
exit 1
