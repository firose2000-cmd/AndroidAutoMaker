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

PACKAGE = "com.automaker.app.adv.hardenedsparseresourcetest"
CERTIFICATE = "1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4"
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


def inspect(badging, permissions, manifest, paths, resources):
    require(re.search(r"^package: name='" + re.escape(PACKAGE) + r"'", badging, re.M), "Wrong candidate package")
    require("application-debuggable" not in badging, "Candidate is debuggable")
    requested = set(re.findall(r"uses-permission: name='([^']+)'", permissions))
    require(requested == PERMISSIONS, "Unexpected compiled permission inventory: " + str(sorted(requested)))
    nodes = elements(manifest)
    app = next(node["attrs"] for node in nodes if node["tag"] == "application")
    require(not boolean(app.get("android:debuggable", "false")), "Manifest enables debugging")
    for flag in ("android:allowBackup", "android:fullBackupContent", "android:usesCleartextTraffic"):
        require(flag in app and not boolean(app[flag]), "Unsafe or missing " + flag)
    require("android:dataExtractionRules" in app, "Missing transfer exclusions")
    providers = {string(n["attrs"].get("android:name", "")): n["attrs"] for n in nodes if n["tag"] == "provider"}
    file_provider = providers["androidx.core.content.FileProvider"]
    require(string(file_provider.get("android:authorities", "")) == PACKAGE + ".files", "FileProvider authority collision")
    require(not boolean(file_provider.get("android:exported", "false")), "Exported FileProvider")
    shizuku = providers["rikka.shizuku.ShizukuProvider"]
    require(string(shizuku.get("android:authorities", "")) == PACKAGE + ".shizuku", "Shizuku authority collision")
    require(string(shizuku.get("android:permission", "")) == "android.permission.INTERACT_ACROSS_USERS_FULL", "Unguarded Shizuku provider")
    allowed_exports = {
        "com.automaker.app.MainActivity": None,
        "rikka.shizuku.ShizukuProvider": "android.permission.INTERACT_ACROSS_USERS_FULL",
        "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
    }
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
    require(re.search(r"bool/force_side_by_side\s*\n\s*\(\) true", resources), "Candidate must force side-by-side conversion")


def verify(apk, unsigned=False):
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
            run(aapt, "dump", "xmltree", apk, "--file", paths[1]), resources)
    with zipfile.ZipFile(apk) as archive:
        require(hashlib.sha256(archive.read("assets/navigation.dex")).hexdigest() == BRIDGE_SHA256,
                "Bundled navigation bridge differs from reviewed hardened history")
    certificate = None
    if not unsigned:
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java" + suffix)
        signature = run(java, "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk)
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", signature)
        require(certificates == [CERTIFICATE], "Signature or signing certificate mismatch")
        certificate = certificates[0]
    return {"application_id": PACKAGE, "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "debuggable": False, "permissions": sorted(PERMISSIONS),
            "signature_verification": "NOT PERFORMED (explicit unsigned inspection)" if unsigned else "passed",
            "signing_certificate_sha256": certificate, "device_validation": "not performed"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--unsigned", action="store_true", help="Static inspection only; does not certify a signed/installable APK")
    arguments = parser.parse_args()
    print(json.dumps(verify(arguments.apk, arguments.unsigned), indent=2))
