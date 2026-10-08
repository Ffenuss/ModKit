#!/usr/bin/env bash
# Build the space host, never a game APK. Keep the existing host package and kernel.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo 'Usage: build.sh ORIGINAL_HOST.apk OUTPUT.apk' >&2; exit 2; fi
SPACE_SOURCE="$(cd "$(dirname "$0")" && pwd)"
SPACE_INPUT="$(realpath "$1")"
SPACE_OUTPUT="$(realpath -m "$2")"
[ "$SPACE_INPUT" != "$SPACE_OUTPUT" ] || { echo 'Input and output must differ' >&2; exit 2; }
: "${MODKIT_SPACE_ENGINE_APK:?Build :spaceengine:assembleDebug and set its APK path}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${MODKIT_SPACE_KEYSTORE:?Use the existing host keystore to preserve its app data}"
: "${MODKIT_SPACE_ALIAS:?Set the keystore alias}"
: "${MODKIT_SPACE_STORE_PASSWORD:?Set the keystore password}"
SPACE_TOOLS="${MODKIT_SPACE_BUILD_TOOLS:-$ANDROID_SDK_ROOT/build-tools/35.0.0}"
SPACE_ANDROID_JAR="${MODKIT_SPACE_ANDROID_JAR:-$ANDROID_SDK_ROOT/platforms/android-35/android.jar}"
[ -f "$SPACE_ANDROID_JAR" ] && [ -f "$MODKIT_SPACE_KEYSTORE" ] && [ -f "$MODKIT_SPACE_ENGINE_APK" ] || { echo 'SDK or existing keystore missing' >&2; exit 2; }
for SPACE_TOOL in d8 zipalign apksigner; do [ -x "$SPACE_TOOLS/$SPACE_TOOL" ] || { echo "Missing SDK tool: $SPACE_TOOL" >&2; exit 2; }; done
command -v apktool >/dev/null || { echo 'Install apktool 2.12.1+ before building' >&2; exit 2; }
SPACE_WORK="$(mktemp -d)"
trap 'rm -rf "$SPACE_WORK"' EXIT
mkdir -p "$SPACE_WORK/classes" "$SPACE_WORK/dex"
python3 "$SPACE_SOURCE/patch_host.py" ads "$SPACE_INPUT" "$SPACE_WORK/host-no-insert-ads.apk" --report "$SPACE_WORK/ad-patches.json"
apktool d --no-res -f -o "$SPACE_WORK/decoded" "$SPACE_WORK/host-no-insert-ads.apk"
python3 "$SPACE_SOURCE/patch_host.py" bootstrap "$SPACE_WORK/decoded"
# Application bootstrap and launch Runnable both reside in classes2.dex.
apktool b -f "$SPACE_WORK/decoded" -o "$SPACE_WORK/rebuilt.apk"
mapfile -t SPACE_JAVA < <(find "$SPACE_SOURCE/src" -name '*.java' -type f | sort)
SPACE_JAVA+=("$SPACE_SOURCE/../runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeBridge.java" "$SPACE_SOURCE/../runtimeprobe/src/main/java/io/github/ffenuss/modkit/runtimeprobe/RuntimeNativeTraceBuffer.java")
java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -classpath "$SPACE_ANDROID_JAR" -d "$SPACE_WORK/classes" "${SPACE_JAVA[@]}"
mapfile -t SPACE_CLASSES < <(find "$SPACE_WORK/classes" -name '*.class' -type f | sort)
"$SPACE_TOOLS/d8" --lib "$SPACE_ANDROID_JAR" --min-api 26 --output "$SPACE_WORK/dex" "${SPACE_CLASSES[@]}"
python3 "$SPACE_SOURCE/patch_host.py" pack "$SPACE_INPUT" "$SPACE_WORK/rebuilt.apk" "$SPACE_WORK/dex/classes.dex" "$SPACE_WORK/unsigned.apk" --report "$SPACE_WORK/host-audit.json" --engine "$MODKIT_SPACE_ENGINE_APK"
"$SPACE_TOOLS/zipalign" -f -p 4 "$SPACE_WORK/unsigned.apk" "$SPACE_WORK/aligned.apk"
# Stage the verified APK beside the destination; preserve an earlier output on failure.
mkdir -p "$(dirname "$SPACE_OUTPUT")"
SPACE_CANDIDATE="$(mktemp "${SPACE_OUTPUT}.candidate.XXXXXX")"
trap 'rm -rf "$SPACE_WORK"; rm -f "$SPACE_CANDIDATE"' EXIT
"$SPACE_TOOLS/apksigner" sign --ks "$MODKIT_SPACE_KEYSTORE" --ks-key-alias "$MODKIT_SPACE_ALIAS" --ks-pass env:MODKIT_SPACE_STORE_PASSWORD --out "$SPACE_CANDIDATE" "$SPACE_WORK/aligned.apk"
"$SPACE_TOOLS/apksigner" verify --verbose "$SPACE_CANDIDATE"
"$SPACE_TOOLS/zipalign" -c -p 4 "$SPACE_CANDIDATE"
python3 "$SPACE_SOURCE/verify_host.py" "$SPACE_INPUT" "$SPACE_CANDIDATE"
mv "$SPACE_CANDIDATE" "$SPACE_OUTPUT"
cp "$SPACE_WORK/host-audit.json" "${SPACE_OUTPUT}.audit.json"
cp "$SPACE_WORK/ad-patches.json" "${SPACE_OUTPUT}.ad-patches.json"
echo "Space host built: $SPACE_OUTPUT. Android device validation still required."
