#!/usr/bin/env python3
"""Publish a verified stable Release without overwriting published APKs."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

version = re.search(r"versionName '([^']+)'", Path("app/build.gradle").read_text())[1]
tag = "v" + version
filename = f"doudizhu-{version}-native.apk"
apk = Path("app/build/outputs/apk/release/app-release.apk")
pin = Path("signing/certificate.sha256").read_text().strip()
evidence = Path("release-results/upgrade-passed.txt")
if not evidence.is_file() or not evidence.read_text().startswith("PASS"):
    raise SystemExit("Cannot publish without a successful real upgrade test.")
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
existing = subprocess.run(["gh", "release", "view", tag, "--json", "targetCommitish,isDraft,isPrerelease"], capture_output=True, text=True)
if existing.returncode == 0:
    release = json.loads(existing.stdout)
    if release["targetCommitish"] != os.environ["GITHUB_SHA"] or release["isDraft"] or release["isPrerelease"]:
        raise SystemExit("A different or non-stable release already uses this version. Increase the version.")
    directory = Path("published-apk")
    directory.mkdir(exist_ok=True)
    subprocess.run(["gh", "release", "download", tag, "--pattern", filename, "--dir", str(directory)], check=True)
    if hashlib.sha256((directory / filename).read_bytes()).hexdigest() != digest:
        raise SystemExit("Published APK differs. Increase the version instead of overwriting.")
    print("This exact stable Release APK is already published.")
else:
    shutil.copyfile(apk, filename)
    notes = Path(f"docs/release-{tag}.md").read_text()
    notes += f"\n\nDDZ-OFFICIAL-SIGNER-SHA256: {pin}\n\nAPK SHA256: {digest}\n"
    Path("release-notes.txt").write_text(notes)
    subprocess.run(["gh", "release", "create", tag, filename, str(evidence),
                    "--target", os.environ["GITHUB_SHA"], "--title", f"闲来斗地主 {tag} · 正式版",
                    "--notes-file", "release-notes.txt", "--latest"], check=True)
