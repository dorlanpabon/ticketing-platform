#!/usr/bin/env python3
"""Analyze the exact trusted develop SHA. Never used for PRs or other branches."""
import json
import os
from pathlib import Path
import sys

from quality import QualityError, ROOT, SERVICES, check_coverage, execute, git, sonar_evidence, write_json


def analyze() -> None:
    if os.environ.get("GITHUB_REF") != "refs/heads/develop" or os.environ.get("GITHUB_EVENT_NAME") != "push":
        raise QualityError("Only a trusted develop push may update Community projects")
    expected = os.environ.get("GITHUB_SHA", "")
    if git(ROOT, "rev-parse", "HEAD") != expected:
        raise QualityError("Develop checkout does not match the workflow SHA")
    if os.environ.get("AWS_ACCESS_KEY_ID") or os.environ.get("AWS_WEB_IDENTITY_TOKEN_FILE"):
        raise QualityError("The Sonar runner must not receive AWS deployment credentials")
    host = os.environ.get("SONAR_HOST_URL") or "http://localhost:19000"
    reader = os.environ.get("SONAR_READ_TOKEN", "")
    if not reader:
        raise QualityError("SONAR_READ_TOKEN is required to verify private project metrics")
    output = ROOT / "build/quality/sonar"
    for service in SERVICES:
        token = os.environ.get("SONAR_TOKEN_" + service.upper()) or os.environ.get("SONAR_TOKEN", "")
        if not token:
            raise QualityError(f"A scoped Sonar analysis token is required for {service}")
        directory = ROOT / "services" / service
        wrapper = str(directory / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        build_environment = os.environ.copy()
        for key in list(build_environment):
            if key.startswith("SONAR_TOKEN") or key in ("SONAR_READ_TOKEN", "SONAR_ADMIN_TOKEN", "SONAR_LOCAL_ADMIN_PASSWORD", "SONAR_LOCAL_READER_PASSWORD", "GH_TOKEN", "GITHUB_TOKEN"):
                build_environment.pop(key)
        execute([wrapper, "--no-daemon", "--no-configuration-cache", "clean", "build", "jacocoMergedReport"], cwd=directory, env=build_environment, timeout=1800)
        report = directory / "build/reports/jacoco/merged/jacoco.xml"
        coverage = check_coverage(report)
        scan_environment = build_environment | {"SONAR_TOKEN": token}
        execute([wrapper, "--no-daemon", "--no-configuration-cache", "sonar", f"-Dsonar.projectKey=ticketing-{service}",
                 f"-Dsonar.host.url={host}", f"-Dsonar.scm.revision={expected}",
                 f"-Dsonar.coverage.jacoco.xmlReportPaths={report}",
                 "-Dsonar.qualitygate.wait=true", "-Dsonar.qualitygate.timeout=600"], cwd=directory, env=scan_environment, timeout=1800)
        tasks = list(directory.glob("**/report-task.txt"))
        if len(tasks) != 1:
            raise QualityError(f"Expected one scanner task identity for {service}")
        properties = dict(line.split("=", 1) for line in tasks[0].read_text(encoding="utf-8").splitlines() if "=" in line)
        task_id = properties.get("ceTaskId")
        if not task_id:
            raise QualityError("Scanner did not provide ceTaskId")
        evidence = sonar_evidence(host, reader, "ticketing-" + service, task_id, expected)
        evidence["unitCoverage"] = coverage
        write_json(output / f"{service}.json", evidence)
    write_json(output / "summary.json", {"commit": expected, "status": "passed", "projects": ["ticketing-" + s for s in SERVICES]})
    print(json.dumps({"commit": expected, "services": list(SERVICES), "status": "passed"}))


if __name__ == "__main__":
    try:
        analyze()
    except QualityError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
