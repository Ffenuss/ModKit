#!/usr/bin/env bash
set -euo pipefail
REPO_ROOT="$(pwd)"
FLUTTER_SDK="${RUNNER_TEMP:?}/modkit-flutter-sdk"
FLUTTER_PROJECT="${RUNNER_TEMP}/modkit-flutter-fixture"
git clone --depth 1 --branch 3.35.4 https://github.com/flutter/flutter.git "$FLUTTER_SDK"
"$FLUTTER_SDK/bin/flutter" config --no-analytics
"$FLUTTER_SDK/bin/flutter" create --org dev.modkit --project-name enginefixture --platforms android "$FLUTTER_PROJECT"
cp "$REPO_ROOT/enginefixture/flutter/pubspec.yaml" "$FLUTTER_PROJECT/pubspec.yaml"
cp "$REPO_ROOT/enginefixture/flutter/lib/main.dart" "$FLUTTER_PROJECT/lib/main.dart"
cp -r "$REPO_ROOT/enginefixture/flutter/assets" "$FLUTTER_PROJECT/assets"
# Emulator rendering is deterministic with Skia; this does not alter asset loading.
python3 - "$FLUTTER_PROJECT/android/app/src/main/AndroidManifest.xml" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(path.read_text().replace('</application>', '<meta-data android:name="io.flutter.embedding.android.EnableImpeller" android:value="false" /></application>'))
PY
cd "$FLUTTER_PROJECT"
"$FLUTTER_SDK/bin/flutter" build apk --debug --target-platform android-x64
mkdir -p "$REPO_ROOT/enginefixture/build"
cp build/app/outputs/flutter-apk/app-debug.apk "$REPO_ROOT/enginefixture/build/flutter-fixture.apk"
"$FLUTTER_SDK/bin/flutter" --version > "$REPO_ROOT/enginefixture/build/flutter-version.txt"
sha256sum "$REPO_ROOT/enginefixture/build/flutter-fixture.apk" > "$REPO_ROOT/enginefixture/build/flutter-fixture.apk.sha256"
