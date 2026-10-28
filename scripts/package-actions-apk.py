#!/usr/bin/env python3
"""Verify and package an existing signed test APK; never builds or changes the APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ORIGINAL_SHA256 = "503f3a3f6c581a871db5f032f561a8881fe8a70b638bba459993d4dc29311705"
ORIGINAL_CERT_SHA256 = "8f19dd6ed340086cf4d701841dab913170a7add143f15c0987556e285cb5bf4d"
ORIGINAL_COMMIT = "39078d9e5440c6ee6fb69069100ddaeec6b10f42"
PACKAGE = "com.automaker.app.adv.hardenedtest"


def prepare(mode, apk, output):
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if mode == "existing" and digest != ORIGINAL_SHA256:
        raise ValueError("Existing APK checksum mismatch. Refusing upload; no automatic rebuild fallback.")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    tools = sdk / "build-tools/35.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    signature = subprocess.run(
        [str(java), "-jar", str(tools / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", signature)
    if len(certificates) != 1:
        raise ValueError("Expected exactly one verified signing certificate.")
    certificate = certificates[0]
    if mode == "existing" and certificate != ORIGINAL_CERT_SHA256:
        raise ValueError("Existing APK signing certificate differs from the recorded test build.")
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    if not re.search(r"^package: name='" + re.escape(PACKAGE) + r"'.*versionName='1\.1\.15-security-test'", badging, re.M):
        raise ValueError("Unexpected APK application ID or version name.")
    checkout_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    # New runner directory only: never mix files from a previous attempt into an upload.
    output.mkdir(parents=True, exist_ok=False)
    target = output / f"AndroidAutoMaker-Advanced-1.1.15-hardened-{mode}.apk"
    shutil.copyfile(apk, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError("APK changed while copying.")
    metadata = {
        "mode": mode,
        "rebuilt": mode == "rebuild",
        "artifact_apk": target.name,
        "apk_sha256": digest,
        "signing_certificate_sha256": certificate,
        "original_apk_sha256": ORIGINAL_SHA256,
        "matches_original_apk": digest == ORIGINAL_SHA256,
        "original_application_commit": ORIGINAL_COMMIT,
        "checkout_commit": checkout_commit,
        "application_id": PACKAGE,
        "version_name": "1.1.15-security-test",
        "signature_verification": "passed",
        "precaution": "Stationary test build; Android 17, USB/wireless and vehicle compatibility unverified.",
    }
    (output / "SHA256SUMS").write_text(f"{digest}  {target.name}\n")
    (output / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "signature-verification.txt").write_text(signature)
    print(json.dumps(metadata, indent=2))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"## Verified hardened test APK\n\nMode: **{mode}**. Rebuilt: **{mode == 'rebuild'}**.\n\n"
                         f"APK SHA-256: `{digest}`\n\nSigning certificate SHA-256: `{certificate}`\n\n"
                         f"Matches original APK bytes: **{digest == ORIGINAL_SHA256}**.\n\n"
                         f"Original application commit: `{ORIGINAL_COMMIT}`.\n\n"
                         f"Workflow checkout commit: `{checkout_commit}`.\n\n"
                         "Download the run's artifact below. Test key material is never uploaded.\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=["existing", "rebuild"])
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.mode, args.apk, args.output)
