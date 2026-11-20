#!/usr/bin/env python3
"""Build a host-native CPU translator for the desktop application distribution."""

import os
import platform
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    system = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}[platform.system()]
    machine = {"aarch64": "arm64", "arm64": "arm64", "amd64": "x64", "x86_64": "x64"}[
        platform.machine().lower()
    ]
    profile = f"{system}-{machine}"
    output = root / "build" / "offline-translator" / profile
    destination = root / "native" / profile / "translation"
    configure = [
        "cmake", "-S", str(root / "native" / "offline-translator"), "-B", str(output),
        "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
    ]
    if system == "macos":
        configure.append("-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0")
    subprocess.run(configure, check=True)
    subprocess.run([
        "cmake", "--build", str(output), "--config", "Release", "--target", "nnggram-translator",
        "--parallel", os.environ.get("JOBS", "4"),
    ], check=True)
    install = [
        "cmake", "--install", str(output), "--config", "Release", "--prefix", str(destination),
        "--component", "offline-translator",
    ]
    if system != "windows":
        install.append("--strip")
    subprocess.run(install, check=True)
    executable = "nnggram-translator.exe" if system == "windows" else "nnggram-translator"
    helper = str(destination / executable)
    subprocess.run([helper, "--version"], check=True)
    detected = subprocess.run(
        [helper, "--detect"], input="Hello, this is an English sentence.\n",
        cwd=destination, text=True, capture_output=True, check=True,
    ).stdout.strip()
    if detected != "en":
        raise RuntimeError(f"Language detection smoke test returned {detected!r}, expected 'en'.")


if __name__ == "__main__":
    main()
