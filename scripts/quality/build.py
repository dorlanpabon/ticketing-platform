#!/usr/bin/env python3
"""Verify unit builds in a disposable container without CI secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

from quality import QualityError, ROOT, SERVICES, check_coverage, execute, git, revision, snapshot, write_json


def verify(repo: Path, sha: str, output: Path) -> None:
    sha = revision(repo, sha)
    policy = json.loads((ROOT / "config/quality/tools.json").read_text(encoding="utf-8"))
    docker = shutil.which("docker")
    if not docker:
        raise QualityError("Docker is required for isolated candidate builds")
    results = {}
    with tempfile.TemporaryDirectory(prefix="ticketing-unit-") as temporary:
        root = Path(temporary)
        snapshot(repo, sha, root)
        originals = {p: hashlib.sha256(p.read_bytes()).digest() for p in root.rglob("*") if p.is_file()}
        for service in SERVICES:
            directory = root / "services" / service
            if not any(directory.glob("**/src/main/java/**/*.java")):
                results[service] = {"status": "not_applicable", "reason": "No Java source in this commit"}
                continue
            execute([docker, "run", "--rm", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--memory=4g",
                     "--pids-limit=512", "--cpus=2", "--user", str(os.getuid()) if hasattr(os, "getuid") else "1000:1000",
                     "--mount", f"type=bind,source={root},target=/workspace", "--env", "GRADLE_USER_HOME=/workspace/.quality-gradle-cache",
                     "--workdir", f"/workspace/services/{service}", policy["buildImage"],
                     "gradle", "--no-daemon", "--no-configuration-cache", "--console=plain", "build", "jacocoMergedReport", "unitCoverageGate"], timeout=1800)
            for path, digest in originals.items():
                if not path.is_file() or hashlib.sha256(path.read_bytes()).digest() != digest:
                    raise QualityError("Candidate build modified tracked source")
            report = directory / "build/reports/jacoco/merged/jacoco.xml"
            coverage = check_coverage(report)
            target = output / service
            target.mkdir(parents=True, exist_ok=True)
            shutil.copy2(report, target / "jacoco.xml")
            tests = list(directory.glob("**/build/test-results/test/TEST-*.xml"))
            if not tests:
                raise QualityError(f"No unit test evidence for {service}")
            for number, result in enumerate(tests):
                shutil.copy2(result, target / f"unit-{number}.xml")
            results[service] = {"status": "passed", "unitCoverage": coverage, "junitReports": len(tests)}
    write_json(output / "summary.json", {"commit": sha, "tree": git(repo, "rev-parse", sha + "^{tree}"),
                                          "services": results, "status": "passed"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify(args.repo.resolve(), args.sha, args.output.resolve())
    except QualityError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
