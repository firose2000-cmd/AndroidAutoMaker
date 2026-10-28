#!/usr/bin/env python3
"""Rebuild the injected navigation DEX using JDK 17 and an already-resolved Gradle cache.

Run an app compile first to populate dependency caches. Does not bundle AndroidX again;
the existing car/lifecycle/core/shizuku DEX assets remain separate.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
JDK = Path(os.environ["JAVA_HOME"])
CACHE = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
CAR_URL = "https://dl.google.com/dl/android/maven2/androidx/car/app/app/1.7.0/app-1.7.0.aar"
CAR_SHA256 = "143b2e38df20cf64a07be311081017245b6bac65312bca90b93305a1efb8d6c5"

with tempfile.TemporaryDirectory(prefix="automaker-navigation-") as temporary:
    work = Path(temporary)
    with urllib.request.urlopen(CAR_URL, timeout=60) as response:
        car = response.read()
    if hashlib.sha256(car).hexdigest() != CAR_SHA256:
        raise RuntimeError("AndroidX car archive checksum mismatch")
    (work / "car.aar").write_bytes(car)
    with zipfile.ZipFile(work / "car.aar") as archive:
        (work / "car.jar").write_bytes(archive.read("classes.jar"))
    jars = [work / "car.jar"]
    for group in ["androidx.lifecycle", "androidx.annotation", "androidx.core", "dev.rikka.shizuku", "org.jetbrains.kotlin"]:
        for dependency in sorted((CACHE / group).rglob("*")):
            if dependency.suffix == ".jar":
                jars.append(dependency)
            elif dependency.suffix == ".aar":
                with zipfile.ZipFile(dependency) as archive:
                    if "classes.jar" in archive.namelist():
                        target = work / (dependency.stem + ".jar")
                        target.write_bytes(archive.read("classes.jar"))
                        jars.append(target)
    android = SDK / "platforms/android-36/android.jar"
    classes = work / "classes"
    classes.mkdir()
    subprocess.run([str(JDK / "bin/javac"), "-source", "8", "-target", "8", "-bootclasspath", str(android),
                    "-cp", os.pathsep.join(map(str, jars)), "-d", str(classes),
                    *map(str, sorted((ROOT / "tools/src/com/automaker/nav").glob("*.java")))], check=True)
    dex = work / "dex"
    dex.mkdir()
    d8 = SDK / "build-tools/35.0.0/lib/d8.jar"
    subprocess.run([str(JDK / "bin/java"), "-cp", str(d8), "com.android.tools.r8.D8", "--min-api", "26",
                    "--lib", str(android), "--output", str(dex),
                    *map(str, sorted(classes.rglob("*.class")))], check=True)
    for destination in [ROOT / "tools/navigation.dex", ROOT / "android/app/src/main/assets/navigation.dex"]:
        shutil.copy2(dex / "classes.dex", destination)
    print("Rebuilt both navigation assets:", hashlib.sha256((dex / "classes.dex").read_bytes()).hexdigest())
