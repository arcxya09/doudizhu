#!/usr/bin/env bash
# This test deliberately creates a clean baseline only on a disposable emulator.
set -euo pipefail
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
mkdir -p release-results
rm -f release-results/upgrade-passed.txt
exec > >(tee release-results/release-upgrade.txt) 2>&1

baseline_apk=${1:-release-baseline.apk}
candidate_apk=${2:-app/build/outputs/apk/release/app-release.apk}
package=com.arcxya.doudizhu
component=$package/.MainActivity
data_dir=/data/user/0/$package
build_tools=${DDZ_BUILD_TOOLS:-${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/35.0.0}
work_dir=$(mktemp -d)
emulator_ready=false
cleanup() {
  local result=$?
  if [ "$result" -ne 0 ] && [ "$emulator_ready" = true ]; then
    adb logcat -d -s AndroidRuntime:E > release-results/release-upgrade-crashes.txt 2>/dev/null || true
    adb exec-out screencap -p > release-results/release-upgrade-failure.png 2>/dev/null || true
  fi
  rm -rf "$work_dir"
  exit "$result"
}
trap cleanup EXIT

inspect_apk() {
  local apk=$1 label=$2
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" > "$work_dir/$label-signers.txt"
  "$build_tools/aapt" dump badging "$apk" > "$work_dir/$label-badging.txt"
  python3 - "$work_dir/$label-signers.txt" "$work_dir/$label-badging.txt" signing/certificate.sha256 "$package" <<'PY'
import pathlib, re, sys
signers, badging, certificate, expected_package = sys.argv[1:]
pin = pathlib.Path(certificate).read_text().strip().lower()
if not re.fullmatch(r"[0-9a-f]{64}", pin):
    raise SystemExit("Invalid public signing certificate pin")
fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", pathlib.Path(signers).read_text())
if len(fingerprints) != 1 or fingerprints[0].lower() != pin:
    raise SystemExit("APK signer does not match the permanent public certificate")
metadata = pathlib.Path(badging).read_text()
match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
if not match or match[1] != expected_package or "application-debuggable" in metadata:
    raise SystemExit("APK must have the expected package ID and must not be debuggable")
print(match[2])
PY
}

baseline_code=$(inspect_apk "$baseline_apk" baseline)
candidate_code=$(inspect_apk "$candidate_apk" candidate)
if [ "$candidate_code" -le "$baseline_code" ]; then
  echo "Candidate versionCode must increase: $baseline_code -> $candidate_code" >&2
  exit 1
fi
echo "Verified permanent signer, non-debuggable APKs and versionCode $baseline_code -> $candidate_code."

# Never allow this script's initial uninstall or fixture writes on a physical phone.
serial=$(adb get-serialno | tr -d '\r')
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
  echo "Upgrade test requires a disposable Android emulator; refusing device $serial." >&2
  exit 1
fi
export ANDROID_SERIAL=$serial
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
  echo "Emulator property check failed; refusing destructive baseline setup." >&2
  exit 1
fi
adb root
adb wait-for-device
if [ "$(adb shell id -u | tr -d '\r')" != 0 ]; then
  echo "A root-capable AOSP emulator is required to verify private data." >&2
  exit 1
fi
emulator_ready=true
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb uninstall "$package" >/dev/null 2>&1 || true
if [ -n "$(adb shell pm path "$package" | tr -d '\r')" ]; then
  echo "Could not remove the previous emulator installation for a clean baseline." >&2
  exit 1
fi
adb install "$baseline_apk"

package_uid() {
  adb shell cmd package list packages -U --user 0 "$package" | tr -d '\r' | python3 -c 'import re,sys; pattern=r"^package:"+re.escape(sys.argv[1])+r"\s+uid:(\d+)\s*$"; matches=re.findall(pattern,sys.stdin.read(),re.M); print(matches[0]) if len(matches)==1 else sys.exit("Expected exactly one UID for the installed package")' "$package"
}
installed_code() {
  adb shell dumpsys package "$package" | tr -d '\r' | python3 -c 'import re,sys; m=re.search(r"\bversionCode=(\d+)",sys.stdin.read()); print(m[1]) if m else sys.exit("Installed versionCode not found")'
}
owner_uid() { adb shell stat -c '%u' "$data_dir" | tr -d '\r'; }
baseline_uid=$(package_uid)
baseline_owner=$(owner_uid)
if [ "$baseline_uid" != "$baseline_owner" ] || [ "$(installed_code)" != "$baseline_code" ]; then
  echo "Baseline package identity or private data owner is incorrect." >&2
  exit 1
fi

cat > "$work_dir/settings.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <int name="level" value="2" />
  <long name="speed" value="2800" />
  <boolean name="music" value="false" />
  <boolean name="effects" value="false" />
  <int name="volume" value="73" />
</map>
XML
printf 'release-upgrade-preserve-v1\n' > "$work_dir/upgrade-marker.txt"
printf '{"file":"upgrade-test.audio","name":"升级验收音乐"}\n' > "$work_dir/selection.json"
python3 - "$baseline_apk" "$work_dir/upgrade-test.audio" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    audio = apk.read("assets/audio/table_loop.ogg")
if not audio.startswith(b"OggS"):
    raise SystemExit("Baseline APK is missing the real bundled Ogg fixture")
pathlib.Path(sys.argv[2]).write_bytes(audio)
PY
adb shell "mkdir -p '$data_dir/shared_prefs' '$data_dir/files/local-music'"
adb push "$work_dir/settings.xml" "$data_dir/shared_prefs/settings.xml" >/dev/null
adb push "$work_dir/upgrade-marker.txt" "$data_dir/files/upgrade-marker.txt" >/dev/null
adb push "$work_dir/selection.json" "$data_dir/files/local-music/selection.json" >/dev/null
adb push "$work_dir/upgrade-test.audio" "$data_dir/files/local-music/upgrade-test.audio" >/dev/null
adb shell "chown -R '$baseline_uid:$baseline_uid' '$data_dir/shared_prefs' '$data_dir/files'; chmod 700 '$data_dir/shared_prefs' '$data_dir/files' '$data_dir/files/local-music'; chmod 600 '$data_dir/shared_prefs/settings.xml' '$data_dir/files/upgrade-marker.txt' '$data_dir/files/local-music/selection.json' '$data_dir/files/local-music/upgrade-test.audio'; restorecon -RF '$data_dir'"

wait_for_human_turn() {
  local xml=$1
  for attempt in $(seq 1 30); do
    # Remove the previous dump so a failed command can never pass with stale UI.
    adb shell rm -f /sdcard/ddz-release-upgrade-ui.xml >/dev/null
    if adb shell uiautomator dump --compressed /sdcard/ddz-release-upgrade-ui.xml >/dev/null 2>&1 &&
       adb pull /sdcard/ddz-release-upgrade-ui.xml "$xml" >/dev/null 2>&1; then
      if python3 - "$xml" "$package" <<'PY'
import sys, xml.etree.ElementTree as ET
# Captions of the action row, i.e. the buttons only the human can press, mapped to the phase they
# belong to. The two builds are compared by phase rather than by caption because a newer build may
# rename a button: 叫地主 became the three score calls, and comparing labels then failed a healthy
# upgrade. Keep the captions in step with the action row built in MainActivity's render(); an older
# baseline still shows 叫地主 while a current build offers the three scores instead.
HUMAN_ACTIONS = {
    "叫地主": "bid", "不叫": "bid", "1分": "bid", "2分": "bid", "3分": "bid",
    "提示": "play", "无可出": "play", "不出": "play", "出牌": "play",
}
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if (node.get("package") == sys.argv[2] and node.get("enabled") == "true"
            and node.get("clickable") == "true" and node.get("text") in HUMAN_ACTIONS):
        print(HUMAN_ACTIONS[node.get("text")])
        break
else:
    raise SystemExit(1)
PY
      then
        return 0
      fi
    fi
    sleep 1
  done
  echo "No enabled human-turn action appeared in the native app." >&2
  return 1
}

data_hashes() {
  adb shell "sha256sum '$data_dir/$snapshot' '$data_dir/shared_prefs/settings.xml' '$data_dir/files/upgrade-marker.txt' '$data_dir/files/local-music/selection.json' '$data_dir/files/local-music/upgrade-test.audio'" | tr -d '\r'
}
capture_state() {
  local label=$1 table=$2
  adb pull "$data_dir/$table" "$work_dir/$label-table.bin" >/dev/null
  if adb shell test -f "$data_dir/shared_prefs/record.xml"; then
    adb pull "$data_dir/shared_prefs/record.xml" "$work_dir/$label-record.xml" >/dev/null
  else
    # Legacy v2 embeds its own record; newer snapshots must have the independent record mirror.
    printf '<map/>\n' > "$work_dir/$label-record.xml"
  fi
}
pause_and_stop() {
  adb shell input keyevent KEYCODE_HOME
  for attempt in $(seq 1 20); do
    if adb shell dumpsys activity activities | python3 -c 'import re,sys; package=sys.argv[1]; lines=[line for line in sys.stdin if re.search(r"(?:topResumedActivity|mResumedActivity)\s*[:=]",line)]; sys.exit(0 if lines and all(package not in line for line in lines) else 1)' "$package"; then
      adb shell am force-stop "$package"
      return 0
    fi
    sleep 0.2
  done
  echo "App did not finish pausing after Home; refusing an unreliable data snapshot." >&2
  return 1
}
adb shell am start -W -n "$component" > "$work_dir/baseline-start.txt"
if ! grep -q '^Status: ok' "$work_dir/baseline-start.txt"; then
  cat "$work_dir/baseline-start.txt"
  exit 1
fi
baseline_turn=$(wait_for_human_turn "$work_dir/baseline-ui.xml")
# Human turns have no timer. Home cancels pending AI callbacks and persists the table.
pause_and_stop
# Installation must preserve every byte. After the candidate runs, its current snapshot may gain
# optional fields; the standalone verifier compares all game fields and statistics across schemas.
snapshot=files/native-table-v2
if adb shell test -s "$data_dir/files/native-table-v3"; then snapshot=files/native-table-v3; fi
adb shell test -s "$data_dir/$snapshot"
data_hashes > "$work_dir/before.sha256"
if [ "$(wc -l < "$work_dir/before.sha256")" -ne 5 ]; then
  echo "All five persistent data fixtures must exist before upgrading." >&2
  exit 1
fi
capture_state before "$snapshot"

# This is the upgrade being tested. There is deliberately no uninstall fallback.
adb install -r "$candidate_apk"
if [ "$(package_uid)" != "$baseline_uid" ] || [ "$(owner_uid)" != "$baseline_owner" ] || [ "$(installed_code)" != "$candidate_code" ]; then
  echo "Candidate changed the package UID/data owner or was not installed." >&2
  exit 1
fi
data_hashes > "$work_dir/after-install.sha256"
diff -u "$work_dir/before.sha256" "$work_dir/after-install.sha256"
adb logcat -c
adb shell am start -W -n "$component" > release-results/release-upgrade-launch.txt
if ! grep -q '^Status: ok' release-results/release-upgrade-launch.txt; then
  cat release-results/release-upgrade-launch.txt
  exit 1
fi
candidate_turn=$(wait_for_human_turn "$work_dir/candidate-ui.xml")
if [ "$candidate_turn" != "$baseline_turn" ]; then
  echo "Candidate restored the $candidate_turn turn, not the baseline's $baseline_turn turn." >&2
  exit 1
fi
if [ -z "$(adb shell pidof "$package" | tr -d '\r')" ]; then
  echo "Candidate process is not running after restoration." >&2
  exit 1
fi
adb logcat -d -s AndroidRuntime:E > release-results/release-upgrade-crashes.txt
if grep -q 'FATAL EXCEPTION' release-results/release-upgrade-crashes.txt; then
  cat release-results/release-upgrade-crashes.txt
  exit 1
fi
adb exec-out screencap -p > release-results/release-upgrade.png
python3 - <<'PY'
from pathlib import Path
image = Path("release-results/release-upgrade.png").read_bytes()
if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) < 1024:
    raise SystemExit("Upgrade screenshot is missing or invalid")
PY
pause_and_stop
data_hashes > "$work_dir/after-restore.sha256"
# Settings, marker, copied music and its selection remain byte-identical. The old v2 file is also
# read-only; only the current v3 snapshot is permitted to be rewritten after a successful restore.
tail -n +2 "$work_dir/before.sha256" > "$work_dir/before-stable.sha256"
tail -n +2 "$work_dir/after-restore.sha256" > "$work_dir/after-stable.sha256"
diff -u "$work_dir/before-stable.sha256" "$work_dir/after-stable.sha256"
if [ "$snapshot" = files/native-table-v2 ]; then
  diff -u "$work_dir/before.sha256" "$work_dir/after-restore.sha256"
fi
candidate_snapshot=files/native-table-v3
adb shell test -s "$data_dir/$candidate_snapshot"
capture_state after "$candidate_snapshot"
java scripts/UpgradeSnapshotCheck.java "$work_dir/before-table.bin" "$work_dir/before-record.xml" \
  "$work_dir/after-table.bin" "$work_dir/after-record.xml" | tee release-results/release-upgrade-state.txt
printf 'PASS: permanent certificate, non-debuggable APK, versionCode %s -> %s, adb install -r, unchanged UID/data owner, restored human turn, all game fields and record preserved, atomic record mirror consistent, settings and local music unchanged.\n' "$baseline_code" "$candidate_code" | tee release-results/upgrade-passed.txt
