#!/usr/bin/env python3
"""Install pinned quality CLIs in an explicit task-local directory.

Release archives are checked against committed SHA-256 digests retrieved from
official upstream release metadata. No downloaded shell scripts are executed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import venv
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSETS = {
    "pmd": ("https://github.com/pmd/pmd/releases/download/pmd_releases/7.29.0/pmd-dist-7.29.0-bin.zip",
            "d27177c185b837fa57f097e482d1253468aa035379c5eb2671fa0a279109b179"),
    "gitleaks-linux": ("https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz",
                       "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"),
    "gitleaks-windows": ("https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip",
                         "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e"),
    "trivy-linux": ("https://github.com/aquasecurity/trivy/releases/download/v0.76.2/trivy_0.76.2_Linux-64bit.tar.gz",
                    "bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf"),
}


def unpack(archive: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                path = PurePosixPath(entry.filename)
                if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
                    raise ValueError("Unsafe release archive path")
                source.extract(entry, target)
                mode = entry.external_attr >> 16
                if mode & 0o111 and not entry.is_dir():
                    (target / entry.filename).chmod(0o755)
    else:
        with tarfile.open(archive) as source:
            source.extractall(target, filter="data")


def install_asset(name: str, destination: Path) -> Path:
    url, checksum = ASSETS[name]
    archive = destination / "downloads" / url.rsplit("/", 1)[1]
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        request = urllib.request.Request(url, headers={"User-Agent": "ticketing-quality-bootstrap/1"})
        with urllib.request.urlopen(request, timeout=120) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise ValueError(f"Checksum mismatch for {name}")
    target = destination / name
    unpack(archive, target)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--only", nargs="+", choices=("pmd", "gitleaks", "checkov", "trivy"),
                        default=["pmd", "gitleaks", "checkov", "trivy"])
    parser.add_argument("--github-path", action="store_true")
    args = parser.parse_args()
    if platform.machine().lower() not in ("amd64", "x86_64"):
        raise SystemExit("Pinned bootstrap currently supports x86_64; add verified assets before another architecture")
    system = "windows" if os.name == "nt" else "linux"
    if platform.system() not in ("Windows", "Linux"):
        raise SystemExit("Supported hosts: Windows and Linux")
    destination = args.destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    paths = []
    if "pmd" in args.only:
        paths.append(install_asset("pmd", destination) / "pmd-bin-7.29.0" / "bin")
    if "gitleaks" in args.only:
        paths.append(install_asset("gitleaks-" + system, destination))
    if "trivy" in args.only and system == "linux":
        paths.append(install_asset("trivy-linux", destination))
    elif "trivy" in args.only:
        print("Windows: Trivy scans use the pinned aquasec/trivy:0.76.2 container")
    if "checkov" in args.only:
        environment = destination / "checkov-venv"
        venv.EnvBuilder(with_pip=True).create(environment)
        scripts = environment / ("Scripts" if os.name == "nt" else "bin")
        python = scripts / ("python.exe" if os.name == "nt" else "python")
        subprocess.run([str(python), "-m", "pip", "install", "--disable-pip-version-check", "checkov==3.4.5"], check=True)
        paths.append(scripts)
    for path in paths:
        if not path.is_dir():
            raise SystemExit(f"Expected tool directory missing: {path}")
    (destination / "paths.json").write_text(json.dumps([str(p) for p in paths], indent=2) + "\n", encoding="utf-8")
    if args.github_path:
        target = os.environ.get("GITHUB_PATH")
        if not target:
            raise SystemExit("GITHUB_PATH is not defined")
        with open(target, "a", encoding="utf-8") as output:
            output.write("\n".join(str(p) for p in paths) + "\n")
    print("Tool directories:")
    print("\n".join(str(p) for p in paths))


if __name__ == "__main__":
    main()
