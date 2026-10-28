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
TEST_RELEASE_SHA256 = "42c00a0e4fc2bb8e43a3d4f18cdfa640d72c2849e21c1ada18d0dac832b7a48b"
TEST_RELEASE_CERT_SHA256 = "1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4"
TEST_RELEASE_COMMIT = "2fde36345139033506c70b37821be13ddbd5ce2d"


def prepare(mode, apk, output):
    test_release = mode.endswith("test-release")
    rebuilt = mode.startswith("rebuild")
    package = "com.automaker.app.adv.hardenedtestrelease" if test_release else PACKAGE
    version = "1.1.15-security-test-release" if test_release else "1.1.15-security-test"
    expected_digest = TEST_RELEASE_SHA256 if test_release else ORIGINAL_SHA256
    expected_certificate = TEST_RELEASE_CERT_SHA256 if test_release else ORIGINAL_CERT_SHA256
    source_commit = TEST_RELEASE_COMMIT if test_release else ORIGINAL_COMMIT
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if not rebuilt and digest != expected_digest:
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
    if not rebuilt and certificate != expected_certificate:
        raise ValueError("Existing APK signing certificate differs from the recorded test build.")
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    if not re.search(r"^package: name='" + re.escape(package) + r"'.*versionName='" + re.escape(version) + "'", badging, re.M):
        raise ValueError("Unexpected APK application ID or version name.")
    manifest = subprocess.run([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                              check=True, capture_output=True, text=True).stdout
    debug_attribute = re.search(r"A: android:debuggable\([^)]*\)=\(type 0x12\)(0x[0-9a-fA-F]+)", manifest)
    if "android:debuggable" in manifest and debug_attribute is None:
        raise ValueError("Cannot determine the compiled android:debuggable flag.")
    debuggable = "application-debuggable" in badging or bool(debug_attribute and int(debug_attribute[1], 16))
    if test_release and debuggable:
        raise ValueError("Test-release APK is debuggable; refusing upload.")
    permissions = subprocess.run([str(tools / "aapt"), "dump", "permissions", str(apk)],
                                 check=True, capture_output=True, text=True).stdout
    requested = set(re.findall(r"uses-permission: name='([^']+)'", permissions))
    expected_permissions = {"android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
                           "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.REQUEST_INSTALL_PACKAGES",
                           "moe.shizuku.manager.permission.API_V23", package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    if requested != expected_permissions:
        raise ValueError("Compiled APK permission inventory changed; refusing upload.")
    checkout_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    # New runner directory only: never mix files from a previous attempt into an upload.
    output.mkdir(parents=True, exist_ok=False)
    target = output / f"AndroidAutoMaker-Advanced-1.1.15-hardened-{mode}.apk"
    shutil.copyfile(apk, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError("APK changed while copying.")
    metadata = {
        "mode": mode,
        "rebuilt": rebuilt,
        "non_debuggable_test_release": test_release,
        "android_debuggable": bool(debuggable),
        "debuggable_attribute": "false" if debug_attribute and not debuggable else ("true" if debuggable else "absent (release default)"),
        "artifact_apk": target.name,
        "apk_sha256": digest,
        "signing_certificate_sha256": certificate,
        "original_apk_sha256": ORIGINAL_SHA256,
        "matches_original_apk": digest == ORIGINAL_SHA256,
        "original_application_commit": ORIGINAL_COMMIT,
        "checkout_commit": checkout_commit,
        "application_source_commit": checkout_commit if rebuilt else source_commit,
        "application_id": package,
        "version_name": version,
        "permissions": sorted(requested),
        "signature_verification": "passed",
        "precaution": "Stationary test build; Android 17, USB/wireless and vehicle compatibility unverified.",
    }
    (output / "SHA256SUMS").write_text(f"{digest}  {target.name}\n")
    (output / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "signature-verification.txt").write_text(signature)
    (output / "compiled-manifest.txt").write_text(manifest)
    (output / "permissions.txt").write_text(permissions)
    if test_release:
        notes = Path(__file__).resolve().parents[1] / "artifacts/test-release/README.md"
        shutil.copyfile(notes, output / "SHIZUKU-AND-DOWNLOAD-NOTES.md")
        if not rebuilt:
            shutil.copyfile(notes.with_name("validation.json"), output / "validation.json")
    print(json.dumps(metadata, indent=2))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"## Verified hardened test APK\n\nMode: **{mode}**. Rebuilt: **{rebuilt}**.\n\n"
                         f"APK SHA-256: `{digest}`\n\nSigning certificate SHA-256: `{certificate}`\n\n"
                         f"Matches original APK bytes: **{digest == ORIGINAL_SHA256}**.\n\n"
                         f"Application source commit: `{metadata['application_source_commit']}`.\n\n"
                         f"Compiled android:debuggable: **{metadata['debuggable_attribute']}**.\n\n"
                         f"Workflow checkout commit: `{checkout_commit}`.\n\n"
                         "Download the run's artifact below. Test key material is never uploaded.\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=["existing", "rebuild", "existing-test-release", "rebuild-test-release"])
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.mode, args.apk, args.output)
