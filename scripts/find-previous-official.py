#!/usr/bin/env python3
"""Find a published APK from the permanent signing series."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

pin = Path("signing/certificate.sha256").read_text().strip()
tag = "v" + re.search(r"versionName '([^']+)'", Path("app/build.gradle").read_text())[1]
pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp", f"repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100"], text=True))
for release in [release for page in pages for release in page]:
    if release["draft"] or release["prerelease"] or release["tag_name"] == tag:
        continue
    if f"DDZ-OFFICIAL-SIGNER-SHA256: {pin}" not in (release.get("body") or ""):
        continue
    assets = [a["name"] for a in release.get("assets", []) if re.fullmatch(r"doudizhu-[0-9.]+-native\.apk", a["name"])]
    if len(assets) != 1:
        raise SystemExit("Previous official release must contain exactly one APK.")
    directory = Path("previous-official")
    directory.mkdir(exist_ok=True)
    subprocess.run(["gh", "release", "download", release["tag_name"], "--pattern", assets[0], "--dir", str(directory)], check=True)
    shutil.copyfile(directory / assets[0], "previous-official.apk")
    print("Downloaded the previous permanent official APK for upgrade verification.")
    break
else:
    print("First permanent release: build a lower-version signed baseline from the same source.")
