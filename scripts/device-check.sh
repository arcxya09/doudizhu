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
for size in compact large largefont; do
  adb shell settings put system font_scale 1.0
  if [ "$size" = compact ] || [ "$size" = largefont ]; then
    adb shell wm size 1280x720
    adb shell wm density 320
  else
    adb shell wm size 1920x1080
    adb shell wm density 320
  fi
  if [ "$size" = largefont ]; then adb shell settings put system font_scale 1.3; fi
  # Reboot after changing display metrics: emulator input coordinates otherwise
  # retain the previous resolution even when the app has already relaid out.
  adb reboot
  adb wait-for-device
  for attempt in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
    sleep 2
  done
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell am force-stop com.arcxya.doudizhu
  adb shell am instrument -w -r com.arcxya.doudizhu.test/androidx.test.runner.AndroidJUnitRunner | tee "device-results/$size-tests.txt"
  if ! grep -q 'OK (1 test)' "device-results/$size-tests.txt"; then adb exec-out screencap -p > "device-results/native-$size-failure.png"; exit 1; fi
  adb pull /sdcard/Android/data/com.arcxya.doudizhu/files/screenshots/native-table.png "device-results/native-$size.png"
done
adb logcat -d -s AndroidRuntime:E > device-results/crashes.txt
