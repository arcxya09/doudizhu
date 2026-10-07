#!/usr/bin/env python3
"""Verify package, permanent certificate, debug status and monotonic version."""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--version-code", type=int)
parser.add_argument("--previous", type=Path)
args = parser.parse_args()
pin = Path("signing/certificate.sha256").read_text().strip()
if not re.fullmatch(r"[0-9a-f]{64}", pin):
    raise SystemExit("Invalid public certificate fingerprint.")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools" / "35.0.0"

def inspect(apk):
    certificate = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certificate)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise SystemExit(f"{apk.name}: unexpected signing certificate.")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.arcxya.doudizhu" or "application-debuggable" in badging:
        raise SystemExit(f"{apk.name}: wrong package identity or debuggable APK.")
    return int(match[2]), match[3]

code, name = inspect(args.apk)
if args.version_code is not None and code != args.version_code:
    raise SystemExit("APK versionCode differs from the source release version.")
if args.previous and inspect(args.previous)[0] >= code:
    raise SystemExit("The official APK must increase versionCode.")
print(f"Verified permanent Release APK: versionName={name}, versionCode={code}.")
