#!/usr/bin/env python3
"""Read-only candidate checks. Never builds, installs, signs or uploads an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from signing_policy import DEDICATED, ORIGINAL, expected_certificate, verify_certificate

PACKAGE = DEDICATED["application_id"]
CERTIFICATE = ORIGINAL["certificate_sha256"]
BRIDGE_SHA256 = "e88b1391fbafa7f590ed25a61e4702a38257d48e85e5eba73c3108d13d4dfd32"
PERMISSIONS = {
    "android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.REQUEST_INSTALL_PACKAGES",
    "moe.shizuku.manager.permission.API_V23", PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elements(tree):
    nodes, stack = [], []
    for line in tree.splitlines():
        element = re.match(r"(\s*)E: (\S+)", line)
        if element:
            depth = len(element[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = {"tag": element[2], "attrs": {}}
            nodes.append(node)
            stack.append((depth, node))
        attribute = re.match(r"\s*A: ([^\s(=]+)(?:\([^)]*\))?=(.*)", line)
        if attribute and stack:
            name = attribute[1].replace("http://schemas.android.com/apk/res/android:", "android:")
            stack[-1][1]["attrs"][name] = attribute[2]
    return nodes


def string(raw):
    match = re.match(r'"([^"]*)"', raw)
    return match[1] if match else raw


def boolean(raw):
    if raw == "true":
        return True
    if raw == "false":
        return False
    value = re.search(r"0x([0-9a-fA-F]+)$", raw)
    if value:
        return int(value[1], 16) != 0
    raise ValueError("Cannot determine compiled boolean: " + raw)


def inspect(badging, permissions, manifest, paths, resources, package=PACKAGE):
    require(package in (PACKAGE, ORIGINAL["application_id"]), "Unsupported hardened identity")
    require(re.search(r"^package: name='" + re.escape(package) + r"'", badging, re.M), "Wrong candidate package")
    require("application-debuggable" not in badging, "Candidate is debuggable")
    requested = set(re.findall(r"uses-permission: name='([^']+)'", permissions))
    expected_permissions = {p.replace(PACKAGE, package) for p in PERMISSIONS}
    if package == PACKAGE:
        expected_permissions.discard("moe.shizuku.manager.permission.API_V23")
    require(requested == expected_permissions, "Unexpected compiled permission inventory: " + str(sorted(requested)))
    nodes = elements(manifest)
    app = next(node["attrs"] for node in nodes if node["tag"] == "application")
    require(not boolean(app.get("android:debuggable", "false")), "Manifest enables debugging")
    for flag in ("android:allowBackup", "android:fullBackupContent", "android:usesCleartextTraffic"):
        require(flag in app and not boolean(app[flag]), "Unsafe or missing " + flag)
    require("android:dataExtractionRules" in app, "Missing transfer exclusions")
    providers = {string(n["attrs"].get("android:name", "")): n["attrs"] for n in nodes if n["tag"] == "provider"}
    file_provider = providers["androidx.core.content.FileProvider"]
    require(string(file_provider.get("android:authorities", "")) == package + ".files", "FileProvider authority collision")
    require(not boolean(file_provider.get("android:exported", "false")), "Exported FileProvider")
    if package == PACKAGE:
        require("rikka.shizuku.ShizukuProvider" not in providers, "Shizuku provider must be absent from reduced-privilege candidate")
    else:
        shizuku = providers["rikka.shizuku.ShizukuProvider"]
        require(string(shizuku.get("android:authorities", "")) == package + ".shizuku", "Shizuku authority collision")
        require(string(shizuku.get("android:permission", "")) == "android.permission.INTERACT_ACROSS_USERS_FULL", "Unguarded Shizuku provider")
    allowed_exports = {
        "com.automaker.app.MainActivity": None,

        "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
    }
    if package != PACKAGE:
        allowed_exports["rikka.shizuku.ShizukuProvider"] = "android.permission.INTERACT_ACROSS_USERS_FULL"
    for node in nodes:
        if node["tag"] not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        attrs = node["attrs"]
        if boolean(attrs.get("android:exported", "false")):
            name = string(attrs.get("android:name", ""))
            require(name in allowed_exports, "Unexpected exported component: " + name)
            permission = allowed_exports[name]
            if permission:
                require(string(attrs.get("android:permission", "")) == permission, "Missing exported component guard: " + name)
    path_nodes = [n for n in elements(paths) if n["tag"] != "paths"]
    require(len(path_nodes) == 1 and path_nodes[0]["tag"] == "cache-path", "Overbroad FileProvider paths")
    require(string(path_nodes[0]["attrs"].get("path", "")) == "install-share/", "Overbroad cache sharing")
    if package == PACKAGE:
        require(re.search(r"bool/force_side_by_side\s*\n\s*\(\) true", resources), "Candidate must force side-by-side conversion")


def verify(apk, unsigned=False, identity="candidate", expected_cert=None):
    require(identity in ("candidate", "existing-hardened"), "Unsupported identity")
    package = PACKAGE if identity == "candidate" else ORIGINAL["application_id"]
    variant = DEDICATED["variant"] if identity == "candidate" else "advancedHardenedRelease"
    expected_cert = expected_cert if expected_cert is not None else os.environ.get(DEDICATED["fingerprint_environment_variable"])
    expected = expected_certificate(package, variant, expected_cert)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    suffix = ".exe" if os.name == "nt" else ""
    candidates = [p for p in (sdk / "build-tools").iterdir() if (p / ("aapt2" + suffix)).is_file()]
    tools = max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
    aapt = tools / ("aapt2" + suffix)

    def run(*args):
        return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True).stdout

    resources = run(aapt, "dump", "resources", apk)
    paths = re.search(r"\bxml/file_paths\s*\n\s*\(\) \(file\) (\S+)", resources)
    require(paths is not None, "Cannot resolve compiled FileProvider paths XML")
    inspect(run(aapt, "dump", "badging", apk), run(aapt, "dump", "permissions", apk),
            run(aapt, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"),
            run(aapt, "dump", "xmltree", apk, "--file", paths[1]), resources, package=package)
    with zipfile.ZipFile(apk) as archive:
        require(hashlib.sha256(archive.read("assets/navigation.dex")).hexdigest() == BRIDGE_SHA256,
                "Bundled navigation bridge differs from reviewed hardened history")
    certificate = None
    if not unsigned:
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java" + suffix)
        signature = run(java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk)
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", signature)
        require(len(certificates) == 1, "Expected exactly one verified APK signer")
        certificate = verify_certificate(package, variant, certificates[0], expected_cert)
    reported_permissions = {p.replace(PACKAGE, package) for p in PERMISSIONS}
    if package == PACKAGE:
        reported_permissions.discard("moe.shizuku.manager.permission.API_V23")
    return {"application_id": package, "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "debuggable": False, "permissions": sorted(reported_permissions),
            "signature_verification": "NOT PERFORMED (explicit unsigned inspection)" if unsigned else "passed",
            "signing_certificate_sha256": certificate, "expected_certificate_sha256": expected, "device_validation": "not performed"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--unsigned", action="store_true", help="Static inspection only; does not certify a signed/installable APK")
    parser.add_argument("--identity", choices=("candidate", "existing-hardened"), default="candidate")
    parser.add_argument("--expected-cert-sha256", help="Out-of-band expected candidate signer; cannot override the original hardened pin")
    arguments = parser.parse_args()
    print(json.dumps(verify(arguments.apk, arguments.unsigned, arguments.identity, arguments.expected_cert_sha256), indent=2))
