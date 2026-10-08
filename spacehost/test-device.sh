#!/usr/bin/env bash
# Keep result variables in one shell; emulator-runner starts a shell per script line.
set -uo pipefail
adb logcat -c
timeout 240s gradle --no-daemon :spaceengine:connectedDebugAndroidTest
SPACE_ENGINE_RESULT=$?
adb logcat -d > space-engine-device-logcat.txt
# The next owned test APK uses the same provider authority; remove the old fixture only.
adb uninstall io.github.ffenuss.modkit.spaceengine.test >/dev/null 2>&1 || true
adb logcat -c
timeout 240s gradle --no-daemon :nativefixture:connectedDebugAndroidTest
SPACE_MENU_RESULT=$?
adb logcat -d > guest-menu-device-logcat.txt
test "$SPACE_ENGINE_RESULT" -eq 0 && test "$SPACE_MENU_RESULT" -eq 0
