#!/usr/bin/env python3
"""Restore the permanent key from Secrets without logging secret material."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess

required = ("DDZ_KEYSTORE_BASE64", "DDZ_STORE_PASSWORD", "DDZ_KEY_ALIAS", "DDZ_KEY_PASSWORD")
missing = [name for name in required if not os.environ.get(name, "").strip()]
if missing:
    raise SystemExit("Missing required repository Actions Secrets: " + ", ".join(missing))
pin = Path("signing/certificate.sha256").read_text().strip()
if not re.fullmatch(r"[0-9a-f]{64}", pin):
    raise SystemExit("Invalid public certificate fingerprint.")
root = Path(os.environ["RUNNER_TEMP"]) / "ddz-release-signing"
root.mkdir(mode=0o700, parents=True, exist_ok=True)
root.chmod(0o700)
keystore = root / "permanent.p12"
try:
    content = base64.b64decode("".join(os.environ["DDZ_KEYSTORE_BASE64"].split()), validate=True)
except ValueError:
    raise SystemExit("Invalid keystore encoding.")
keystore.write_bytes(content)
keystore.chmod(0o600)
certificate = root / "certificate.der"
result = subprocess.run([
    "keytool", "-exportcert", "-keystore", str(keystore), "-storetype", "PKCS12",
    "-alias", os.environ["DDZ_KEY_ALIAS"], "-storepass:env", "DDZ_STORE_PASSWORD",
    "-file", str(certificate),
], capture_output=True)
if result.returncode != 0:
    raise SystemExit("Cannot open the permanent signing certificate.")
if hashlib.sha256(certificate.read_bytes()).hexdigest() != pin:
    raise SystemExit("Signing certificate differs from the permanent public fingerprint.")
with open(os.environ["GITHUB_ENV"], "a") as environment:
    environment.write(f"DDZ_KEYSTORE_PATH={keystore}\n")
print("Permanent signing certificate matches the pinned public fingerprint.")
