#!/usr/bin/env python3
"""Build a release APK with the official SDK; never installs or publishes it."""
from pathlib import Path
import argparse
import os
import secrets
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from common import ROOT, BUILD, java_tools, reset_dir, run

PLATFORM = "android-34"
BUILD_TOOLS = "35.0.0"
WEB_ASSETS = ("index.html", "styles.css", "model.js", "app.js", "console.js", "mark.svg")
ANDROID = "{http://schemas.android.com/apk/res/android}"


def signing(java):
    env = os.environ.copy()
    private_path = env.get("XHFLOW_KEYSTORE")
    if private_path:
        key = Path(private_path).expanduser().resolve()
        if ROOT in key.parents:
            raise RuntimeError("Keep private release keystores outside the checkout.")
        if not key.is_file():
            raise RuntimeError("XHFLOW_KEYSTORE does not point to a file.")
        if not env.get("XHFLOW_KEY_ALIAS") or not env.get("XHFLOW_STORE_PASSWORD"):
            raise RuntimeError("Set XHFLOW_KEY_ALIAS and XHFLOW_STORE_PASSWORD for private signing.")
        env.setdefault("XHFLOW_KEY_PASSWORD", env["XHFLOW_STORE_PASSWORD"])
        return key, env["XHFLOW_KEY_ALIAS"], "XHFLOW_STORE_PASSWORD", "XHFLOW_KEY_PASSWORD", env
    if any(env.get(name) for name in ("XHFLOW_KEY_ALIAS", "XHFLOW_STORE_PASSWORD", "XHFLOW_KEY_PASSWORD")):
        raise RuntimeError("Signing variables were supplied without XHFLOW_KEYSTORE.")
    directory = BUILD / "signing"
    directory.mkdir(parents=True, exist_ok=True)
    key, secret_file = directory / "local-test.p12", directory / "local-test-password.txt"
    if key.exists() != secret_file.exists():
        raise RuntimeError("Incomplete local test signing material; restore both files or remove build/signing/.")
    if not key.exists():
        password = secrets.token_urlsafe(32)
        secret_file.write_text(password, encoding="utf-8")
        secret_file.chmod(0o600)
        env["XHFLOW_LOCAL_PASSWORD"] = password
        run([java["keytool"], "-genkeypair", "-noprompt", "-storetype", "PKCS12",
             "-keystore", key, "-storepass:env", "XHFLOW_LOCAL_PASSWORD", "-keypass:env", "XHFLOW_LOCAL_PASSWORD",
             "-alias", "xhflow-local-test", "-dname", "CN=XHFlow local testing", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "3650"], label="Create an ignored local test signing key", env=env)
        key.chmod(0o600)
    env["XHFLOW_LOCAL_PASSWORD"] = secret_file.read_text(encoding="utf-8").strip()
    print("Signing with a local test key in ignored build/signing/; no release key is included.", flush=True)
    return key, "xhflow-local-test", "XHFLOW_LOCAL_PASSWORD", "XHFLOW_LOCAL_PASSWORD", env


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    sdk_value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_value:
        raise RuntimeError("Set ANDROID_SDK_ROOT or ANDROID_HOME to an official Android SDK installation.")
    sdk = Path(sdk_value).expanduser().resolve()
    platform = sdk / "platforms" / PLATFORM / "android.jar"
    tools = sdk / "build-tools" / BUILD_TOOLS
    suffix = ".exe" if os.name == "nt" else ""
    required = [platform, tools / ("aapt2" + suffix), tools / ("zipalign" + suffix),
                tools / "lib/d8.jar", tools / "lib/apksigner.jar"]
    if not all(path.is_file() for path in required):
        raise RuntimeError('Install official SDK packages with sdkmanager "platforms;android-34" "build-tools;35.0.0".')
    java = java_tools()
    app = ROOT / "app"
    manifest = app / "AndroidManifest.xml"
    application = ET.parse(manifest).getroot().find("application")
    if application is None or application.get(ANDROID + "debuggable", "false") != "false":
        raise RuntimeError("The public release manifest must use android:debuggable=false.")
    classes, dex, generated, assets = [BUILD / name for name in ("classes", "dex", "generated", "assets")]
    for directory in (classes, dex, generated, assets):
        reset_dir(directory)
    for name in WEB_ASSETS:
        shutil.copyfile(ROOT / "web-console" / name, assets / name)
    shutil.copyfile(app / "assets/THIRD_PARTY_NOTICES.txt", assets / "THIRD_PARTY_NOTICES.txt")
    aapt = tools / ("aapt2" + suffix)
    run([aapt, "compile", "--dir", app / "res", "-o", BUILD / "compiled-res.zip"], label="Compile Android resources")
    run([aapt, "link", "-I", platform, "--manifest", manifest, "--java", generated,
         "-A", assets, BUILD / "compiled-res.zip", "-o", BUILD / "base.apk"], label="Link release resources and allowlisted assets")
    sources = sorted((app / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
    if not sources:
        raise RuntimeError("No Android Java sources found in app/src/.")
    run([java["javac"], "--release", "8", "-encoding", "UTF-8", "-classpath", platform,
         "-d", classes, *sources], label="Compile Java sources")
    run([java["java"], "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
         "--min-api", "26", "--lib", platform, "--output", dex, *sorted(classes.rglob("*.class"))], label="Generate DEX")
    unsigned = BUILD / "unsigned.apk"
    shutil.copyfile(BUILD / "base.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for source in sorted(dex.glob("*.dex")):
            apk.write(source, source.name)
    aligned, output = BUILD / "aligned.apk", BUILD / "xh-flow.apk"
    run([tools / ("zipalign" + suffix), "-f", "4", unsigned, aligned], label="Align APK")
    key, alias, store_env, key_env, env = signing(java)
    signer = [java["java"], "-cp", tools / "lib/apksigner.jar", "com.android.apksigner.ApkSignerTool"]
    run([*signer, "sign", "--ks", key, "--ks-key-alias", alias, "--ks-pass", "env:" + store_env,
         "--key-pass", "env:" + key_env, "--out", output, aligned], label="Sign APK", env=env)
    run([*signer, "verify", "--verbose", output], label="Verify APK signature")
    badging = run([aapt, "dump", "badging", output], label="Inspect release APK")
    if "application-debuggable" in badging:
        raise RuntimeError("Refusing a debuggable public build.")
    print("APK: build/xh-flow.apk (" + str(output.stat().st_size) + " bytes)")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ET.ParseError, ValueError) as error:
        print("Build failed: " + str(error), file=sys.stderr)
        sys.exit(1)
