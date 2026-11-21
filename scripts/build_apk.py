#!/usr/bin/env python3
"""Build a test-signed APK using an installed Android SDK, without Maven dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION_CODE = "19"
VERSION_NAME = "0.1.9"


def run(args, **kwargs):
    print("+ " + " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), cwd=ROOT, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or
                        str(Path.home() / "elysia-dsu-tools/android-sdk"))
    options = parser.parse_args()
    sdk = Path(options.sdk).resolve()
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-36/android.jar"
    for path in (android, tools / "aapt2", tools / "d8", tools / "zipalign", tools / "apksigner"):
        if not path.is_file():
            parser.error(f"Missing SDK component: {path}")

    build = ROOT / "build/sdk-apk"
    if build.exists():
        shutil.rmtree(build)  # Only this script's generated build directory.
    for name in ("generated", "classes", "dex"):
        (build / name).mkdir(parents=True, exist_ok=True)
    output = ROOT / "output"
    output.mkdir(exist_ok=True)
    run([tools / "aapt2", "compile", "--dir", ROOT / "app/src/main/res", "-o", build / "resources.zip"])
    # Same package/version/SDK settings as app/build.gradle. Gradle normally merges these.
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    manifest = manifest.replace('<manifest xmlns:android=',
                                 f'<manifest package="dev.multisystem.client" android:versionCode="{VERSION_CODE}" '
                                 f'android:versionName="{VERSION_NAME}" xmlns:android=', 1)
    manifest = manifest.replace("    <application", '    <uses-sdk android:minSdkVersion="30" '
                                'android:targetSdkVersion="35" />\n    <application', 1)
    (build / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
    run([tools / "aapt2", "link", "-I", android, "--manifest", build / "AndroidManifest.xml",
         "--java", build / "generated", "-o", build / "base.apk", build / "resources.zip"])
    sources = sorted((ROOT / "app/src/main/java").rglob("*.java"))
    sources += sorted((build / "generated").rglob("*.java"))
    # Quoted argfile paths support the Chinese workspace name and spaces.
    (build / "sources.txt").write_text("\n".join('"' + str(p) + '"' for p in sources), encoding="utf-8")
    run(["javac", "-encoding", "UTF-8", "--release", "17", "-classpath", android,
         "-d", build / "classes", "@" + str(build / "sources.txt")])
    with zipfile.ZipFile(build / "classes.jar", "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted((build / "classes").rglob("*.class")):
            jar.write(path, path.relative_to(build / "classes").as_posix())
    run([tools / "d8", "--release", "--lib", android, "--min-api", "30",
         "--output", build / "dex", build / "classes.jar"])
    shutil.copy2(build / "base.apk", build / "unsigned.apk")
    with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for path in sorted((build / "dex").glob("*.dex")):
            apk.write(path, path.name)
        apk.write(ROOT / "THIRD_PARTY_NOTICES.md", "assets/THIRD_PARTY_NOTICES.md")
        apk.write(ROOT / "LICENSE", "assets/LICENSE")
    run([tools / "zipalign", "-f", "-p", "4", build / "unsigned.apk", build / "aligned.apk"])
    # Keep the dedicated development key locally in the ignored signing directory.
    key = ROOT / ".sign/elysia-debug.jks"
    key.parent.mkdir(parents=True, exist_ok=True)
    if not key.exists():
        run(["keytool", "-genkeypair", "-noprompt", "-keystore", key, "-storepass", "android",
             "-keypass", "android", "-alias", "elysia-debug", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=Elysia Development,O=Elysia,C=CN"])
        key.chmod(0o600)
    artifact = output / f"Elysia-MultiSystem-{VERSION_NAME}-debug.apk"
    run([tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "elysia-debug",
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", artifact,
         build / "aligned.apk"])
    verify = run([tools / "apksigner", "verify", "--verbose", "--print-certs", artifact],
                 capture_output=True, text=True)
    print(verify.stdout)
    (output / "signature.txt").write_text(verify.stdout, encoding="utf-8")
    run([tools / "zipalign", "-c", "-p", "4", artifact])
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {artifact.name}\n", encoding="utf-8")
    (output / "build-info.json").write_text(json.dumps({
        "name": "Elysia 多系统工具箱", "version": VERSION_NAME, "minSdk": 30, "targetSdk": 35,
        "compileSdk": 36, "buildTools": "36.0.0", "signing": "development key",
        "sha256": digest, "apkBytes": artifact.stat().st_size,
        "validation": "APK signature and alignment verified; no device flashing performed"
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"APK ready: {artifact} ({artifact.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
