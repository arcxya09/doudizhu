#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for size in compact large largefont; do
  adb shell settings put system font_scale 1.0
  if [ "$size" = compact ] || [ "$size" = largefont ]; then
    adb shell wm size 720x1280
    adb shell wm density 320
  else
    adb shell wm size 1080x1920
    adb shell wm density 320
  fi
  if [ "$size" = largefont ]; then adb shell settings put system font_scale 1.3; fi
  adb shell am force-stop com.arcxya.doudizhu
  adb shell am instrument -w -r com.arcxya.doudizhu.test/androidx.test.runner.AndroidJUnitRunner | tee "device-results/$size-tests.txt"
  if ! grep -q 'OK (1 test)' "device-results/$size-tests.txt"; then exit 1; fi
  adb pull /sdcard/Android/data/com.arcxya.doudizhu/files/screenshots/native-table.png "device-results/native-$size.png"
done
adb logcat -d -s AndroidRuntime:E > device-results/crashes.txt
