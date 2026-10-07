#!/usr/bin/env python3
"""Prepare non-debuggable unsigned APKs; this step cannot publish a Release."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

source = Path("app/build.gradle").read_text()
code = int(re.search(r"def applicationVersionCode = (\d+)", source)[1])
version = re.search(r"versionName '([^']+)'", source)[1]
directory = Path("local-signing-input")
directory.mkdir(exist_ok=True)
unsigned = Path("app/build/outputs/apk/release/app-release-unsigned.apk")
sdk_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
previous = Path("previous-official.apk")
if previous.exists():
    subprocess.run(["python3", "scripts/verify-release-apk.py", str(previous)], check=True)
    baseline_file = "baseline-signed.apk"
    shutil.copyfile(previous, directory / baseline_file)
else:
    if code <= 1:
        raise SystemExit("Initial baseline needs a positive lower versionCode.")
    subprocess.run(["gradle", "--no-daemon", ":app:assembleRelease",
                    "-PddzPrepareUnsignedRelease=true", f"-PddzVersionCode={code - 1}"], check=True)
    baseline_file = "baseline-unsigned.apk"
    shutil.copyfile(unsigned, directory / baseline_file)
subprocess.run(["gradle", "--no-daemon", ":app:assembleRelease", ":app:lintRelease",
                "-PddzPrepareUnsignedRelease=true"], check=True)
shutil.copyfile(unsigned, directory / "candidate-unsigned.apk")
for path in (directory / baseline_file, directory / "candidate-unsigned.apk"):
    badging = subprocess.check_output([str(sdk_tools / "aapt"), "dump", "badging", str(path)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.arcxya.doudizhu" or "application-debuggable" in badging:
        raise SystemExit("Preparation must retain the package ID and disable debugging.")
    if path.name == "candidate-unsigned.apk" and (int(match[2]) != code or match[3] != version):
        raise SystemExit("Candidate version metadata mismatch.")
shutil.copyfile(sdk_tools / "lib/apksigner.jar", directory / "apksigner.jar")
metadata = {
    "source_sha": os.environ["GITHUB_SHA"], "version_name": version, "version_code": code,
    "package_name": "com.arcxya.doudizhu",
    "certificate_sha256": Path("signing/certificate.sha256").read_text().strip(),
    "baseline_file": baseline_file,
    "apksigner_sha256": hashlib.sha256((directory / "apksigner.jar").read_bytes()).hexdigest(),
}
(directory / "preparation.json").write_text(json.dumps(metadata, indent=2) + "\n")
print("Prepared unsigned Release artifacts for local permanent signing. Nothing was published.")
