#!/usr/bin/env python3
"""Run portable Node/host-Java tests; never contacts a TV or WLED controller."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import sys
import urllib.request
from common import ROOT, BUILD, java_tools, reset_dir, run

JSON_URL = "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
WEB_TESTS = ("tests.js", "geometry-tests.js", "console-tests.js", "console-api-tests.js")


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_jar(local):
    if local:
        path = Path(local).expanduser().resolve()
    else:
        path = BUILD / "deps/json-20240303.jar"
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            print("Download the pinned host-test dependency from official Maven Central", flush=True)
            with urllib.request.urlopen(JSON_URL, timeout=30) as response:
                data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != JSON_SHA256:
                raise RuntimeError("Downloaded JSON-java dependency failed size/SHA256 verification.")
            temporary = path.with_suffix(".download")
            temporary.write_bytes(data)
            temporary.replace(path)
    if not path.is_file() or checksum(path) != JSON_SHA256:
        raise RuntimeError("Expected the exact official org.json:json:20240303 JAR with the documented SHA256.")
    return path


def web_tests():
    node = shutil.which("node")
    if not node:
        raise RuntimeError("Install Node.js 20+ to run web-console tests.")
    for name in WEB_TESTS:
        run([node, name], cwd=ROOT / "web-console", label="Web: " + name)


def java_tests(local):
    java, dependency = java_tools(), json_jar(local)
    classes, output = BUILD / "test-classes", BUILD / "test-output"
    reset_dir(classes)
    reset_dir(output)
    sdk_value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_value:
        raise RuntimeError("Java contracts require ANDROID_SDK_ROOT or ANDROID_HOME with platforms/android-34/android.jar.")
    platform = Path(sdk_value).expanduser().resolve() / "platforms/android-34/android.jar"
    if not platform.is_file():
        raise RuntimeError('Install the official SDK package with sdkmanager "platforms;android-34".')
    # Compile the real application classes: source-gate contracts call service/server static logic.
    # JSON-java must precede android.jar so the host uses a working parser, not Android SDK stubs.
    compile_classpath = os.pathsep.join([str(dependency), str(platform)])
    sources = sorted((ROOT / "app/src").rglob("*.java"))
    tests = sorted((ROOT / "app/tests").rglob("*.java"))
    if not tests:
        raise RuntimeError("No Java test sources found in app/tests/.")
    run([java["javac"], "--release", "8", "-encoding", "UTF-8", "-cp", compile_classpath,
         "-d", classes, *sources, *tests], label="Compile real application sources and host contracts")
    classpath = os.pathsep.join([str(classes), str(dependency), str(platform)])
    mains = []
    for path in tests:
        text = path.read_text(encoding="utf-8")
        if not re.search(r"public\s+static\s+void\s+main\s*\(", text):
            continue
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.MULTILINE)
        name = (package.group(1) + "." if package else "") + path.stem
        mains.append(name)
        args = [str(output / "synthetic-qr.png"), str(output / "synthetic-qr.txt")] if name == "QrCodeFixture" else []
        run([java["java"], "-Djava.awt.headless=true", "-cp", classpath, name, *args], label="Java: " + name)
    if not mains:
        raise RuntimeError("No executable Java contract tests were found.")
    print("Host Java suites passed: " + str(len(mains)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--web-only", action="store_true")
    mode.add_argument("--java-only", action="store_true")
    parser.add_argument("--json-jar", default=os.environ.get("XHFLOW_JSON_JAR"), help="Use the exact pinned local host-test JAR instead of downloading")
    args = parser.parse_args()
    if not args.java_only:
        web_tests()
    if not args.web_only:
        java_tests(args.json_jar)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        print("Tests failed: " + str(error), file=sys.stderr)
        sys.exit(1)
