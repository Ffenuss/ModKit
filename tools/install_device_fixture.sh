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
  FIXTURE_KEY="$FIXTURE_OUTPUT/fixture.jks"
  if [[ ! -f "$FIXTURE_KEY" ]]; then
    keytool -genkeypair -keystore "$FIXTURE_KEY" -storetype JKS -storepass android -keypass android -alias fixture -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=ModKit disposable test fixture"
  fi
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$FIXTURE_KEY" --ks-pass pass:android --key-pass pass:android --out "$FIXTURE_OUTPUT/base.apk" "$BASE"
  for INDEX in 1 2 3 4; do
    cat > "$FIXTURE_OUTPUT/manifest-$INDEX.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.modkit.fixture" split="config.fixture$INDEX" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><application android:hasCode="false"/></manifest>
XML
    "$ANDROID_HOME/build-tools/36.0.0/aapt2" link -I "$ANDROID_HOME/platforms/android-37.0/android.jar" --manifest "$FIXTURE_OUTPUT/manifest-$INDEX.xml" -o "$FIXTURE_OUTPUT/raw-$INDEX.apk"
    "$ANDROID_HOME/build-tools/36.0.0/zipalign" -f 4 "$FIXTURE_OUTPUT/raw-$INDEX.apk" "$FIXTURE_OUTPUT/aligned-$INDEX.apk"
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$FIXTURE_KEY" --ks-pass pass:android --key-pass pass:android --out "$FIXTURE_OUTPUT/config.fixture$INDEX.apk" "$FIXTURE_OUTPUT/aligned-$INDEX.apk"
  done
  adb install-multiple -r "$FIXTURE_OUTPUT/base.apk" "$FIXTURE_OUTPUT"/config.fixture*.apk
fi
