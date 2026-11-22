#!/usr/bin/env python3
"""Build the Compose app with Gradle, preserving the Elysia signing identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUNDLE_NAME = "dsu-permissive-v0.7.0.sh"
BUNDLE_BYTES = 11999539
BUNDLE_SHA256 = "86b7c4eaaed8d11211c15c71302f0ecc620de1b0647c510299895ed6ec01c04d"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or
                        str(Path.home() / "elysia-dsu-tools/android-sdk"))
    options = parser.parse_args()
    sdk = Path(options.sdk).resolve()
    config = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version = re.search(r'versionName\s+"([^"]+)"', config).group(1)
    version_code = int(re.search(r'versionCode\s+(\d+)', config).group(1))
    compile_sdk = int(re.search(r'compileSdk\s+(\d+)', config).group(1))
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    if not env.get("JAVA_HOME") and Path("/usr/lib/jvm/java-21-openjdk-amd64/bin/javac").is_file():
        env["JAVA_HOME"] = "/usr/lib/jvm/java-21-openjdk-amd64"
    key = ROOT / ".sign/elysia-debug.jks"
    if not key.is_file():
        parser.error("Missing Elysia development key. Restore it locally to preserve APK update identity.")
    bundle = ROOT / "app/src/main/assets" / BUNDLE_NAME
    if not bundle.is_file() or bundle.stat().st_size != BUNDLE_BYTES or hashlib.sha256(bundle.read_bytes()).hexdigest() != BUNDLE_SHA256:
        parser.error("Bundled DSU compatibility tool is missing or does not match the reviewed v0.7.0 checksum.")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradle), ":app:assembleDebug", ":app:testDebugUnitTest", "--console=plain"],
                   cwd=ROOT, env=env, check=True)
    output = ROOT / "output"
    output.mkdir(exist_ok=True)
    artifact = output / f"Elysia-MultiSystem-{version}-debug.apk"
    shutil.copy2(ROOT / "app/build/outputs/apk/debug/app-debug.apk", artifact)
    with zipfile.ZipFile(artifact) as apk:
        packaged_bundle = apk.read(f"assets/{BUNDLE_NAME}")
    if len(packaged_bundle) != BUNDLE_BYTES or hashlib.sha256(packaged_bundle).hexdigest() != BUNDLE_SHA256:
        parser.error("Packaged DSU compatibility tool failed its size/SHA-256 verification.")
    tools_dirs = sorted((sdk / "build-tools").glob("*"), key=lambda p: tuple(map(int, re.findall(r'\d+', p.name))))
    if not tools_dirs:
        parser.error("Missing Android SDK build-tools")
    tools = tools_dirs[-1]
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    verify = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(artifact)],
                            env=env, check=True, capture_output=True, text=True)
    print(verify.stdout)
    (output / "signature.txt").write_text(verify.stdout, encoding="utf-8")
    align = tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    subprocess.run([str(align), "-c", "-p", "4", str(artifact)], check=True)
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {artifact.name}\n", encoding="utf-8")
    (output / "build-info.json").write_text(json.dumps({
        "name": "Elysia 多系统工具箱", "version": version, "versionCode": version_code,
        "minSdk": 30, "targetSdk": 35, "compileSdk": compile_sdk,
        "ui": "Kotlin / Compose / Miuix 0.9.4 / AndroidLiquidGlass Backdrop / Capsule", "build": "Gradle",
        "compatibilityBundleSha256": BUNDLE_SHA256,
        "signing": "Elysia development key", "sha256": digest, "apkBytes": artifact.stat().st_size,
        "validation": "Unit tests, bundled script hash, APK signature and alignment; no device write or reboot performed"
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"APK ready: {artifact} ({artifact.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
