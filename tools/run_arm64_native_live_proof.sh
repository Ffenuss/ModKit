#!/usr/bin/env bash
set -euo pipefail

# Opt-in only on a dedicated, expendable ARM64 Android device.
# Never modify the user's installed release ModKit.
die() { printf 'ARM64 proof aborted: %s\n' "$*" >&2; exit 1; }

[[ "${MODKIT_ARM64_DISPOSABLE_DEVICE:-}" == "YES" ]] ||
  die "Set MODKIT_ARM64_DISPOSABLE_DEVICE=YES only for a dedicated disposable device"
[[ -n "${ADB_SERIAL:-}" ]] ||
  die "Select an exact authorized device via ADB_SERIAL=<serial>"
command -v adb >/dev/null || die "adb not installed"
command -v gradle >/dev/null || die "gradle not installed"
command -v python3 >/dev/null || die "python3 not installed"
ADB=(adb -s "$ADB_SERIAL")
[[ "$("${ADB[@]}" get-state 2>/dev/null | tr -d '\r')" == "device" ]] ||
  die "ADB_SERIAL is not connected and authorized"
[[ "$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')" == "arm64-v8a" ]] ||
  die "Device primary ABI must be arm64-v8a (not an x86 translation layer)"
[[ "$("${ADB[@]}" shell uname -m | tr -d '\r')" == "aarch64" ]] ||
  die "Device kernel must be aarch64"
API="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$API" =~ ^[0-9]+$ ]] && (( API >= 29 )) ||
  die "Expected Android API 29+"

APP="io.github.ffenuss.modkit.test"
HOST="io.github.ffenuss.modkit.spacecompanion"
NATIVE="dev.modkit.nativefixture"
TEST="$APP.test"
PACKAGES=("$TEST" "$APP" "$HOST" "$NATIVE")
for package in "${PACKAGES[@]}"; do
  existing="$("${ADB[@]}" shell pm path "$package" | tr -d '\r')"
  [[ -z "$existing" ]] ||
    die "Package $package already exists. No overwrite or uninstall of existing data is allowed."
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
NONCE="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
OUT="$ROOT/build/arm64-live-proof/$NONCE"
mkdir -p "$OUT"
INSTALLED=()
cleanup() {
  local code=$?
  trap - EXIT
  for ((i=${#INSTALLED[@]}-1; i>=0; i--)); do
    "${ADB[@]}" uninstall "${INSTALLED[$i]}" >/dev/null 2>&1 || true
  done
  if (( code != 0 )); then
    printf 'ARM64 proof FAILED. Local logs: %s\n' "$OUT" >&2
  fi
  exit "$code"
}
trap cleanup EXIT

gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  :spacecompanion:assembleDebug :nativefixture:assembleDebug

APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
HOST_APK="$ROOT/spacecompanion/build/outputs/apk/debug/spacecompanion-debug.apk"
NATIVE_APK="$ROOT/nativefixture/build/outputs/apk/debug/nativefixture-debug.apk"
for apk in "$APP_APK" "$TEST_APK" "$HOST_APK" "$NATIVE_APK"; do
  [[ -s "$apk" ]] || die "Missing built APK: $apk"
done

"${ADB[@]}" install "$APP_APK"; INSTALLED+=("$APP")
"${ADB[@]}" install "$TEST_APK"; INSTALLED+=("$TEST")
"${ADB[@]}" install "$HOST_APK"; INSTALLED+=("$HOST")
"${ADB[@]}" install "$NATIVE_APK"; INSTALLED+=("$NATIVE")
"${ADB[@]}" shell pm list instrumentation | tr -d '\r' |
  grep -Fq "instrumentation:$TEST/androidx.test.runner.AndroidJUnitRunner (target=$APP)" ||
  die "Exact instrumentation runner/target pair is not installed"

# Permissions for temporary packages on the explicitly opted-in test device.
# Firmware can reject appops: never use workarounds that bypass OEM policy.
"${ADB[@]}" shell appops set "$APP" REQUEST_INSTALL_PACKAGES allow ||
  die "Grant install access to test ModKit through Android Settings"
"${ADB[@]}" shell appops set "$HOST" SYSTEM_ALERT_WINDOW allow ||
  die "Grant temporary overlay permission through Android Settings"
if (( API >= 33 )); then
  "${ADB[@]}" shell pm grant "$HOST" android.permission.POST_NOTIFICATIONS ||
    die "Notifications permission denied for temporary Companion"
fi

METHOD="io.github.ffenuss.modkit.AutoModDeviceTest#q_preparedNativeProbeChangesRealIndependentGame"
RUNNER="$TEST/androidx.test.runner.AndroidJUnitRunner"
echo "Running exact native game proof on $ADB_SERIAL, API $API, nonce $NONCE"
echo "Unlock the disposable device and confirm its own PackageInstaller dialog if prompted."

set +e
"${ADB[@]}" shell am instrument -w -r \
  -e class "$METHOD" \
  -e modkit_arm64_proof true \
  -e modkit_proof_nonce "$NONCE" \
  "$RUNNER" | tee "$OUT/instrumentation.txt"
instrument_exit=${PIPESTATUS[0]}
set -e
(( instrument_exit == 0 )) ||
  die "Instrumentation process exited $instrument_exit"

REPORT="$OUT/arm64-native-live-$NONCE.json"
"${ADB[@]}" pull "/sdcard/Download/ModKit-validation/arm64-native-live-$NONCE.json" "$REPORT" ||
  die "No fresh ARM64 live proof was exported by the device"
python3 tools/verify_arm64_live_evidence.py "$REPORT" "$OUT/instrumentation.txt" "$NONCE" |
  tee "$OUT/verified-proof.json"
echo "PASS: live ARM64 game/overlay proof verified. Local reports: $OUT"
