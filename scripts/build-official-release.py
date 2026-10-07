#!/usr/bin/env python3
"""Build a signed baseline if needed, then the real Release APK."""
from pathlib import Path
import re
import shutil
import subprocess

code = int(re.search(r"def applicationVersionCode = (\d+)", Path("app/build.gradle").read_text())[1])
apk = Path("app/build/outputs/apk/release/app-release.apk")
baseline = Path("previous-official.apk")
if not baseline.exists():
    if code <= 1:
        raise SystemExit("A positive lower versionCode is required for the initial baseline.")
    subprocess.run(["gradle", "--no-daemon", ":app:assembleRelease", f"-PddzVersionCode={code - 1}"], check=True)
    baseline = Path("release-baseline.apk")
    shutil.copyfile(apk, baseline)
subprocess.run(["gradle", "--no-daemon", ":app:assembleRelease", ":app:lintRelease"], check=True)
subprocess.run(["python3", "scripts/verify-release-apk.py", str(apk), "--version-code", str(code), "--previous", str(baseline)], check=True)
Path("release-upgrade-baseline.txt").write_text(str(baseline) + "\n")
