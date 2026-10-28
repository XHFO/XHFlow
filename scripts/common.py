"""Shared host tooling; Android SDK binaries are never bundled with the repo."""
from pathlib import Path
import os
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"


def run(args, *, label=None, cwd=ROOT, env=None, timeout=180):
    if label:
        print(label, flush=True)
    try:
        result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env,
                                capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError((label or "Command") + " timed out.") from error
    if result.returncode:
        raise RuntimeError((label or "Command failed") + "\n" + result.stdout + result.stderr)
    if result.stdout.strip():
        print(result.stdout.strip(), flush=True)
    if result.stderr.strip():
        print(result.stderr.strip(), flush=True)
    return result.stdout + result.stderr


def java_tools():
    home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    tools = {}
    for name in ("java", "javac", "keytool"):
        executable = Path(home) / "bin" / (name + suffix) if home else shutil.which(name)
        if not executable or not Path(executable).is_file():
            raise RuntimeError("Install JDK 17+ and set JAVA_HOME, or put its bin directory on PATH.")
        tools[name] = Path(executable)
    result = subprocess.run([str(tools["javac"]), "-version"], capture_output=True, text=True, timeout=15)
    match = re.search(r"javac\s+(\d+)", result.stdout + result.stderr)
    if result.returncode or not match or int(match.group(1)) < 17:
        raise RuntimeError("JDK 17 or newer is required.")
    return tools


def reset_dir(path):
    path = path.resolve()
    if BUILD.resolve() not in path.parents:
        raise RuntimeError("Refusing to clean a directory outside build/.")
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)
