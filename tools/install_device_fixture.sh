#!/usr/bin/env bash
set -euo pipefail
# All generated/configuration APKs belong to the repository's disposable fixture.
MODE="${1:-single}"
BASE="testgame/build/outputs/apk/debug/testgame-debug.apk"
if [[ "$MODE" == single ]]; then
  adb install -r "$BASE"
else
  FIXTURE_OUTPUT="testgame/build/fixture-splits"
  mkdir -p "$FIXTURE_OUTPUT"
  for INDEX in 1 2 3 4; do
    cat > "$FIXTURE_OUTPUT/manifest-$INDEX.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.modkit.fixture" split="config.fixture$INDEX" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><application android:hasCode="false"/></manifest>
XML
    "$ANDROID_HOME/build-tools/36.0.0/aapt2" link -I "$ANDROID_HOME/platforms/android-37.0/android.jar" --manifest "$FIXTURE_OUTPUT/manifest-$INDEX.xml" -o "$FIXTURE_OUTPUT/raw-$INDEX.apk"
    "$ANDROID_HOME/build-tools/36.0.0/zipalign" -f 4 "$FIXTURE_OUTPUT/raw-$INDEX.apk" "$FIXTURE_OUTPUT/aligned-$INDEX.apk"
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$FIXTURE_OUTPUT/config.fixture$INDEX.apk" "$FIXTURE_OUTPUT/aligned-$INDEX.apk"
  done
  adb install-multiple -r "$BASE" "$FIXTURE_OUTPUT"/config.fixture*.apk
fi
