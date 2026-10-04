#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for size in compact large largefont cutout; do
  # Relaunch with a fresh instrumentation process after changing display settings.
  # A reboot is unnecessary: test coordinates come from newly measured native views.
  adb shell am force-stop com.arcxya.doudizhu
  width=1280
  height=720
  font_scale=1.0
  if [ "$size" = large ]; then width=1920; height=1080; fi
  if [ "$size" = largefont ]; then font_scale=1.3; fi
  if [ "$size" = cutout ]; then
    width=1600
    adb shell cmd overlay enable com.android.internal.display.cutout.emulation.corner
  fi
  adb shell wm size "${width}x${height}"
  adb shell wm density 320
  adb shell settings put system font_scale "$font_scale"
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell am instrument -w -r \
    -e expectedCutout "$([ "$size" = cutout ] && echo true || echo false)" -e expectedWidth "$width" -e expectedHeight "$height" -e expectedFontScale "$font_scale" \
    com.arcxya.doudizhu.test/androidx.test.runner.AndroidJUnitRunner | tee "device-results/$size-tests.txt"
  for frame in table selected played long-play three-seats bidding; do
    adb pull "/sdcard/Android/data/com.arcxya.doudizhu/files/screenshots/native-$frame.png" "device-results/native-$size-$frame.png" 2>/dev/null || true
  done
  if ! grep -q 'OK (1 test)' "device-results/$size-tests.txt"; then
    adb exec-out screencap -p > "device-results/native-$size-failure.png"
    adb logcat -d -s AndroidRuntime:E > device-results/crashes.txt
    exit 1
  fi

done
adb logcat -d -s AndroidRuntime:E > device-results/crashes.txt

adb shell cmd overlay disable com.android.internal.display.cutout.emulation.corner
