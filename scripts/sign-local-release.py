#!/usr/bin/env python3
"""Sign prepared APKs with the existing private backup; emit public APKs only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--input-dir", type=Path, required=True)
parser.add_argument("--key-dir", type=Path, required=True)
parser.add_argument("--output-dir", type=Path, required=True)
args = parser.parse_args()
metadata = json.loads((args.input_dir / "preparation.json").read_text())
pin = Path("signing/certificate.sha256").read_text().strip()
if metadata["certificate_sha256"] != pin or not re.fullmatch(r"[0-9a-f]{64}", pin):
    raise SystemExit("Preparation does not belong to this permanent signing identity.")
jar = args.input_dir / "apksigner.jar"
if hashlib.sha256(jar.read_bytes()).hexdigest() != metadata["apksigner_sha256"]:
    raise SystemExit("Android SDK signing tool checksum mismatch.")
key = args.key_dir / "doudizhu-release.p12"
environment = dict(os.environ,
    DDZ_STORE_PASSWORD=(args.key_dir / "store-password.txt").read_text().strip(),
    DDZ_KEY_PASSWORD=(args.key_dir / "key-password.txt").read_text().strip())
alias = (args.key_dir / "key-alias.txt").read_text().strip()
args.output_dir.mkdir(parents=True, exist_ok=True)

def verify(path):
    result = subprocess.run(["java", "-jar", str(jar), "verify", "--verbose", "--print-certs", str(path)],
                            capture_output=True, text=True, check=True)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise SystemExit("APK differs from the permanent signing certificate.")

def sign(source, target):
    result = subprocess.run(["java", "-jar", str(jar), "sign", "--ks", str(key),
        "--ks-key-alias", alias, "--ks-pass", "env:DDZ_STORE_PASSWORD",
        "--key-pass", "env:DDZ_KEY_PASSWORD", "--out", str(target), str(source)],
        env=environment, capture_output=True)
    if result.returncode != 0:
        raise SystemExit("Local permanent APK signing failed.")
    verify(target)

candidate = args.output_dir / "candidate.apk"
baseline = args.output_dir / "baseline.apk"
sign(args.input_dir / "candidate-unsigned.apk", candidate)
previous = args.input_dir / metadata["baseline_file"]
if metadata["baseline_file"] == "baseline-signed.apk":
    verify(previous)
    shutil.copyfile(previous, baseline)
else:
    sign(previous, baseline)
manifest = {name: metadata[name] for name in
            ("source_sha", "version_name", "version_code", "package_name", "certificate_sha256")}
manifest["candidate_sha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
manifest["baseline_sha256"] = hashlib.sha256(baseline.read_bytes()).hexdigest()
(args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print("Candidate and baseline signed with the original permanent certificate; no private files exported.")
