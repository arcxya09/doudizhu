#!/usr/bin/env python3
"""Load public signed APKs only after checking source, QA and permanent identity."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def fail(message):
    raise SystemExit(message)


def output(command):
    return subprocess.check_output(command, text=True).strip()


def api_pages(endpoint):
    return json.loads(output(["gh", "api", "--paginate", "--slurp", endpoint]))


inputs = Path("release-inputs")
manifest = json.loads((inputs / "manifest.json").read_text())
if not isinstance(manifest, dict):
    fail("Offline release manifest must be an object.")
expected_fields = {
    "source_sha", "version_name", "version_code", "package_name",
    "certificate_sha256", "candidate_sha256", "baseline_sha256",
}
if set(manifest) != expected_fields:
    fail("Offline release manifest has missing or unexpected fields.")
source = manifest["source_sha"]
if not isinstance(source, str) or not re.fullmatch(r"[0-9a-f]{40}", source):
    fail("Offline release source SHA must be a full lowercase Git commit SHA.")
parents = output(["git", "rev-list", "--parents", "-n", "1", "HEAD"]).split()
if len(parents) != 2 or parents[1] != source:
    fail("Artifact commit must have exactly the tested source commit as its parent.")
allowed_paths = {"release-inputs/candidate.apk", "release-inputs/baseline.apk", "release-inputs/manifest.json"}
changed_paths = set(output(["git", "diff", "--name-only", "--no-renames", source, "HEAD"]).splitlines())
if changed_paths != allowed_paths:
    fail("Artifact commit may change only its two public APKs and manifest.")

gradle = Path("app/build.gradle").read_text()
version_match = re.search(r"versionName '([^']+)'", gradle)
code_match = re.search(r"def applicationVersionCode = (\d+)", gradle)
if not version_match or not code_match:
    fail("Cannot read release version from the tested source.")
version_name, version_code = version_match[1], int(code_match[1])
pin = Path("signing/certificate.sha256").read_text().strip()
package = "com.arcxya.doudizhu"
if not re.fullmatch(r"[0-9a-f]{64}", pin):
    fail("Invalid permanent public certificate fingerprint.")
if (type(manifest["version_code"]) is not int or manifest["version_code"] != version_code
        or manifest["version_name"] != version_name or manifest["package_name"] != package
        or manifest["certificate_sha256"] != pin):
    fail("Offline release manifest differs from the tested source metadata.")
for kind in ("candidate", "baseline"):
    digest = manifest[kind + "_sha256"]
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        fail(f"Invalid {kind} APK SHA-256 in manifest.")
    if hashlib.sha256((inputs / (kind + ".apk")).read_bytes()).hexdigest() != digest:
        fail(f"Offline {kind} APK differs from the manifest checksum.")

repository = os.environ["GITHUB_REPOSITORY"]
if repository != "arcxya09/doudizhu":
    fail("Offline release QA must come from the original project repository.")
qa_run = None
for page in api_pages(f"repos/{repository}/actions/runs?head_sha={source}&per_page=100"):
    for run in page.get("workflow_runs", []):
        if (run.get("head_sha") != source or run.get("head_branch") != "main"
                or run.get("event") != "push" or run.get("name") != "Native Kotlin Android APK"):
            continue
        jobs = [job for page in api_pages(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
                for job in page.get("jobs", [])]
        native_jobs = [job for job in jobs if job.get("name") == "native-build-and-device-test"]
        if len(native_jobs) == 1 and native_jobs[0].get("status") == "completed" and native_jobs[0].get("conclusion") == "success":
            qa_run = run["id"]
            break
    if qa_run is not None:
        break
if qa_run is None:
    fail("Tested source has no successful native QA job from a main-branch push.")

previous = Path("previous-official.apk")
if previous.exists():
    previous.unlink()
subprocess.run(["python3", "scripts/find-previous-official.py"], check=True)
if previous.exists() and hashlib.sha256(previous.read_bytes()).hexdigest() != manifest["baseline_sha256"]:
    fail("Upgrade baseline must be the exact previously published permanent official APK.")

sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
for kind in ("candidate", "baseline"):
    metadata = output([str(sdk / "build-tools/35.0.0/aapt"), "dump", "badging", str(inputs / (kind + ".apk"))])
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    if not match or match[1] != package or "application-debuggable" in metadata:
        fail(f"Offline {kind} APK has the wrong package or is debuggable.")
    code = int(match[2])
    if kind == "candidate" and (code != version_code or match[3] != version_name):
        fail("Candidate APK version differs from the tested source.")
    if kind == "baseline" and not 1 <= code < version_code:
        fail("Baseline must have a positive, strictly lower versionCode.")
    if kind == "baseline" and not previous.exists() and code != version_code - 1:
        fail("First permanent release baseline must use the immediately preceding versionCode.")

candidate = Path("app/build/outputs/apk/release/app-release.apk")
candidate.parent.mkdir(parents=True, exist_ok=True)
baseline = Path("release-baseline.apk")
shutil.copyfile(inputs / "candidate.apk", candidate)
shutil.copyfile(inputs / "baseline.apk", baseline)
subprocess.run(["python3", "scripts/verify-release-apk.py", str(candidate),
                "--version-code", str(version_code), "--previous", str(baseline)], check=True)
Path("release-upgrade-baseline.txt").write_text(str(baseline) + "\n")
print(f"Verified signed public APKs from source {source}; native QA run {qa_run} succeeded.")
