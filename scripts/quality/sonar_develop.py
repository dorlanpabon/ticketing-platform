#!/usr/bin/env python3
"""Analyze an exact develop SHA: trusted CI push, or an explicitly selected local run."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.parse

from quality import QualityError, ROOT, SERVICES, SHA, SECRET_NAMES, check_coverage, execute, git, redact, revision, sonar_evidence, sonar_request, write_json


def verify_checkout(expected: str, local: bool, source: Path | None = None) -> None:
    if revision(ROOT, "HEAD") != expected:
        raise QualityError("Develop HEAD changed during analysis")
    if local and git(ROOT, "symbolic-ref", "--quiet", "--short", "HEAD") != "develop":
        raise QualityError("Local Sonar requires the current develop branch")
    if git(ROOT, "status", "--porcelain", "--untracked-files=all"):
        raise QualityError("Sonar requires a clean checkout, including untracked source files")
    if source is not None and source != ROOT:
        if revision(source, "HEAD") != expected or git(source, "status", "--porcelain", "--untracked-files=all"):
            raise QualityError("Isolated analysis source changed")


def execution_identity(local: bool) -> tuple[str, str]:
    if local:
        markers = ("GITHUB_ACTIONS", "GITHUB_REF", "GITHUB_EVENT_NAME", "GITHUB_SHA", "GITHUB_HEAD_REF", "GITHUB_BASE_REF")
        if any(os.environ.get(key) for key in markers) or os.environ.get("CI", "").lower() == "true":
            raise QualityError("Local mode cannot impersonate a CI or pull-request execution")
        expected = revision(ROOT, "HEAD")
        context = "local"
    else:
        if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("GITHUB_REF") != "refs/heads/develop" \
                or os.environ.get("GITHUB_EVENT_NAME") != "push" or os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_BASE_REF"):
            raise QualityError("Only a trusted GitHub Actions develop push may update Community projects")
        expected = os.environ.get("GITHUB_SHA", "")
        if not SHA.fullmatch(expected):
            raise QualityError("CI requires a full workflow commit SHA")
        context = "ci"
    verify_checkout(expected, local)
    return expected, context


@contextmanager
def analysis_checkout(expected: str, local: bool):
    if not local:
        yield ROOT
        return
    base = (ROOT / ".tools/sonar-local").resolve()
    if not base.is_relative_to(ROOT.resolve()):
        raise QualityError("Local analysis path escaped the repository")
    base.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="analysis-", dir=base) as temporary:
        checkout = Path(temporary).resolve() / "checkout"
        execute(["git", "-C", str(ROOT), "worktree", "add", "--detach", str(checkout), expected])
        try:
            yield checkout
        finally:
            if not checkout.resolve().is_relative_to(base):
                raise QualityError("Refusing to remove an analysis checkout outside its workspace")
            execute(["git", "-C", str(ROOT), "worktree", "remove", "--force", str(checkout)])


def build_environment() -> dict[str, str]:
    allowed = {"PATH", "JAVA_HOME", "GRADLE_USER_HOME", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "TEMP", "TMP",
               "APPDATA", "LOCALAPPDATA", "SYSTEMROOT", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "WINDIR", "LANG", "LC_ALL", "TZ"}
    return {key: value for key, value in os.environ.items() if key.upper() in allowed}


def report_files(directory: Path, complete: bool = False) -> dict[str, list[Path]]:
    reports = {kind: sorted(directory.glob(pattern)) for kind, pattern in {
        "jacoco": "**/build/reports/jacoco/**/*.xml",
        "junit": "**/build/test-results/**/TEST-*.xml",
        "pit": "**/build/reports/pitest/**/*.xml"}.items()}
    required = [directory / "build/reports/jacoco/merged/jacoco.xml",
                *(directory / f"domain/{module}/build/reports/pitest/mutations.xml" for module in ("model", "usecase"))]
    if complete and (not reports["junit"] or any(not path.is_file() for path in required)):
        raise QualityError("Successful build is missing mandatory JaCoCo, JUnit or domain PIT XML")
    return reports


def scanner_diagnostics(directory: Path, host: str, reader: str, project: str) -> dict:
    result = {"diagnosticOnly": True, "project": project}
    tasks = list(directory.glob("**/report-task.txt"))
    result["reportTaskCount"] = len(tasks)
    if len(tasks) != 1:
        return result
    properties = dict(line.split("=", 1) for line in tasks[0].read_text(encoding="utf-8").splitlines() if "=" in line)
    task_id = properties.get("ceTaskId", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task_id) or redact(task_id) != task_id:
        return result
    result["ceTaskId"] = task_id
    try:
        task = sonar_request(host, "/api/ce/task?" + urllib.parse.urlencode({"id": task_id}), reader)["task"]
        if task.get("componentKey") != project:
            result["projectMismatch"] = True
            return result
        if task.get("status") in ("PENDING", "IN_PROGRESS", "SUCCESS", "FAILED", "CANCELED"):
            result["computeStatus"] = task["status"]
        analysis_id = task.get("analysisId", "")
        if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", analysis_id) and redact(analysis_id) == analysis_id:
            result["analysisId"] = analysis_id
            gate = sonar_request(host, "/api/qualitygates/project_status?" + urllib.parse.urlencode({"analysisId": analysis_id}), reader)["projectStatus"]
            if gate.get("status") in ("OK", "WARN", "ERROR", "NONE"):
                result["qualityGate"] = gate["status"]
    except (QualityError, KeyError, TypeError):
        result["remoteMetadataUnavailable"] = True
    return result


def preserve_reports(source: Path, output: Path, identity: dict, host: str, reader: str, completed: set[str]) -> None:
    names = set(SECRET_NAMES) | {key for key in os.environ if key.startswith("SONAR_TOKEN_")}
    secrets = [os.environ[key].encode() for key in names if os.environ.get(key)]
    manifest = identity | {"diagnosticOnly": True, "services": {}}
    for service in SERVICES:
        directory = source / "services" / service
        reports = report_files(directory)
        artifacts = []
        for kind, paths in reports.items():
            for path in paths:
                if not path.resolve().is_relative_to(directory.resolve()):
                    raise QualityError("Report path escaped the analysis source")
                content = path.read_bytes()
                if any(secret in content for secret in secrets):
                    raise QualityError("Report contains credential material and cannot be retained")
                relative = Path("reports") / service / path.relative_to(directory)
                destination = output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content)
                artifacts.append({"kind": kind, "path": relative.as_posix(),
                                  "sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)})
        manifest["services"][service] = {"buildCompleted": service in completed, "reports": artifacts,
            "scanner": scanner_diagnostics(directory, host, reader, "ticketing-" + service)}
    write_json(output / "report-manifest.json", manifest)


def analyze_projects(source: Path, expected: str, host: str, reader: str, local: bool, completed: set[str]) -> dict:
    results = {}
    for service in SERVICES:
        verify_checkout(expected, local, source)
        token = os.environ.get("SONAR_TOKEN_" + service.upper()) or os.environ.get("SONAR_TOKEN", "")
        if not token:
            raise QualityError(f"A scoped Sonar analysis token is required for {service}")
        directory = source / "services" / service
        wrapper = str(directory / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        environment = build_environment()
        execute([wrapper, "--no-daemon", "--no-configuration-cache", "clean", "build", "jacocoMergedReport"], cwd=directory, env=environment, timeout=1800)
        completed.add(service)
        report_files(directory, complete=True)
        verify_checkout(expected, local, source)
        report = directory / "build/reports/jacoco/merged/jacoco.xml"
        coverage = check_coverage(report)
        scan_environment = environment | {"SONAR_TOKEN": token}
        execute([wrapper, "--no-daemon", "--no-configuration-cache", "sonar", f"-Dsonar.projectKey=ticketing-{service}",
                 f"-Dsonar.host.url={host}", f"-Dsonar.scm.revision={expected}",
                 f"-Dsonar.coverage.jacoco.xmlReportPaths={report}",
                 "-Dsonar.scanner.javaOpts=-Xmx512m",
                 "-Dsonar.qualitygate.wait=true", "-Dsonar.qualitygate.timeout=600"], cwd=directory, env=scan_environment, timeout=1800)
        verify_checkout(expected, local, source)
        tasks = list(directory.glob("**/report-task.txt"))
        if len(tasks) != 1:
            raise QualityError(f"Expected one scanner task identity for {service}")
        properties = dict(line.split("=", 1) for line in tasks[0].read_text(encoding="utf-8").splitlines() if "=" in line)
        task_id = properties.get("ceTaskId")
        if not task_id:
            raise QualityError("Scanner did not provide ceTaskId")
        evidence = sonar_evidence(host, reader, "ticketing-" + service, task_id, expected)
        verify_checkout(expected, local, source)
        evidence["unitCoverage"] = coverage
        results[service] = evidence
    return results


def analyze(local: bool = False) -> None:
    expected, context = execution_identity(local)
    if any(os.environ.get(key) for key in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE")):
        raise QualityError("The Sonar runner must not receive AWS deployment credentials")
    host = os.environ.get("SONAR_HOST_URL") or "http://localhost:19000"
    reader = os.environ.get("SONAR_READ_TOKEN", "")
    if not reader:
        raise QualityError("SONAR_READ_TOKEN is required to verify private project metrics")
    output = ROOT / ("build/quality/sonar-local/" + expected if local else "build/quality/sonar")
    identity = {"commit": expected, "tree": git(ROOT, "rev-parse", expected + "^{tree}"), "executionContext": context, "branch": "develop"}
    write_json(output / "summary.json", identity | {"status": "running"})
    for filename in ("report-manifest.json", *(service + ".json" for service in SERVICES)):
        (output / filename).unlink(missing_ok=True)
    try:
        with analysis_checkout(expected, local) as source:
            completed = set()
            try:
                results = analyze_projects(source, expected, host, reader, local, completed)
            finally:
                preserve_reports(source, output, identity, host, reader, completed)
        verify_checkout(expected, local)
        for service, evidence in results.items():
            write_json(output / f"{service}.json", evidence | identity)
        verify_checkout(expected, local)
        write_json(output / "summary.json", identity | {"status": "passed", "projects": ["ticketing-" + s for s in SERVICES]})
    except BaseException:
        write_json(output / "summary.json", identity | {"status": "failed"})
        raise
    print(json.dumps(identity | {"services": list(SERVICES), "status": "passed"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local", action="store_true", help="Analyze a clean local develop checkout without CI environment markers")
    args = parser.parse_args()
    try:
        analyze(local=args.local)
    except QualityError as error:
        print(redact(str(error)), file=sys.stderr)
        raise SystemExit(1)
