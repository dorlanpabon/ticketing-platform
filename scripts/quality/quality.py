#!/usr/bin/env python3
"""Fail-closed quality orchestration. The executing checkout must be trusted.

Candidate Git trees are data. Their hooks, quality configurations and Python
scripts are never executed. Gradle runs only in a separate unprivileged container.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PROTECTED = ("config/quality/", "scripts/quality/", ".github/", ".githooks/", "gradle/")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SERVICES = ("catalog", "orders", "inventory")
SECRET_NAMES = ("SONAR_TOKEN", "SONAR_READ_TOKEN", "SONAR_ADMIN_TOKEN", "GITHUB_TOKEN", "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")


class QualityError(RuntimeError):
    """A mandatory validation could not be established."""


def redact(text: str) -> str:
    names = set(SECRET_NAMES) | {key for key in os.environ if key.startswith("SONAR_TOKEN_")} | {"SONAR_LOCAL_ADMIN_PASSWORD"}
    for name in names:
        value = os.environ.get(name)
        if value:
            text = text.replace(value, "[REDACTED]")
    text = re.sub(r"(?i)(authorization\s*[:=]\s*)([^\r\n]+)", r"\1[REDACTED]", text)
    return text


def execute(args: list[str], *, cwd: Path | None = None, timeout: int = 900,
            env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise QualityError(f"Could not complete {Path(args[0]).name}: {type(exc).__name__}") from exc
    if result.returncode:
        detail = redact(result.stderr.decode("utf-8", errors="replace"))[-4000:]
        raise QualityError(f"{Path(args[0]).name} failed ({result.returncode}): {detail}")
    return result


def git(repo: Path, *args: str) -> str:
    return execute(["git", "-C", str(repo), *args]).stdout.decode("utf-8").strip()


def revision(repo: Path, value: str) -> str:
    result = git(repo, "rev-parse", "--verify", f"{value}^{{commit}}")
    if not SHA.fullmatch(result):
        raise QualityError("Expected a full SHA-1 Git commit identifier")
    return result


def introduced_commits(repo: Path, before: str | None, after: str, base: str | None = None) -> list[str]:
    """Include intermediate commits and merges, including divergent histories.

    New branches deliberately traverse their entire reachable history: omitting
    commits because they appear on a base branch would assume unverified evidence.
    """
    head = revision(repo, after)
    exclusions = []
    if before and set(before) != {"0"}:
        exclusions.append("^" + revision(repo, before))
    elif base:
        exclusions.append("^" + revision(repo, base))
    result = git(repo, "rev-list", "--reverse", "--topo-order", head, *exclusions)
    return result.splitlines() if result else []


def safe_extract(archive: bytes, destination: Path) -> None:
    """Do not materialize Git symlinks or archive paths outside the snapshot."""
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as source:
        for entry in source.getmembers():
            relative = PurePosixPath(entry.name)
            if relative.is_absolute() or ".." in relative.parts or "\\" in entry.name:
                raise QualityError("Unsafe path in Git snapshot")
            if entry.issym() or entry.islnk() or not (entry.isfile() or entry.isdir()):
                raise QualityError(f"Unsupported snapshot entry: {entry.name}")
            target = destination.joinpath(*relative.parts)
            if entry.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                stream = source.extractfile(entry)
                if stream is None:
                    raise QualityError("Unreadable archive entry")
                with target.open("wb") as output:
                    shutil.copyfileobj(stream, output)
                target.chmod(entry.mode & 0o777)


def snapshot(repo: Path, treeish: str, destination: Path) -> None:
    # Read blobs directly: git archive honors export-ignore/export-subst, which
    # would let a candidate omit or rewrite staged files during its own check.
    entries = execute(["git", "-C", str(repo), "ls-tree", "-rz", "--full-tree", treeish]).stdout
    objects = []
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        metadata, filename = entry.split(b"\t", 1)
        mode, kind, oid = metadata.decode("ascii").split()
        name = filename.decode("utf-8")
        relative = PurePosixPath(name)
        if mode not in ("100644", "100755") or kind != "blob":
            raise QualityError("Symlinks and submodules require an explicit trusted policy")
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise QualityError("Unsafe snapshot path")
        objects.append((mode, oid, relative))
    if not objects:
        return
    data = subprocess.run(["git", "-C", str(repo), "cat-file", "--batch"],
                          input="".join(oid + "\n" for _, oid, _ in objects).encode(),
                          capture_output=True, check=True).stdout
    stream = io.BytesIO(data)
    for mode, expected_oid, relative in objects:
        oid, kind, size = stream.readline().decode("ascii").strip().split()
        if oid != expected_oid or kind != "blob":
            raise QualityError("Git blob does not match the exact index/tree")
        content = stream.read(int(size))
        if len(content) != int(size) or stream.read(1) != b"\n":
            raise QualityError("Truncated Git blob")
        target = destination.joinpath(*relative.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        target.chmod(int(mode[-3:], 8))


def content_hash(root: Path, protected_only: bool = False) -> str:
    digest = hashlib.sha256()
    candidates = []
    for current, directories, filenames in os.walk(root):
        directories[:] = [name for name in directories if name not in (".git", ".gradle", "build", "__pycache__", ".quality-gradle-cache")]
        candidates.extend(Path(current) / name for name in filenames)
    for path in sorted(candidates):
        name = path.relative_to(root).as_posix()
        build_policy = path.name in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "sonar-project.properties")
        if protected_only and not name.startswith(PROTECTED) and not build_policy:
            continue
        if "__pycache__" in path.parts or path.suffix == ".pyc":
            continue
        digest.update(name.encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def policy_guard(trusted: Path, candidate: Path, approved: str | None = None) -> str:
    expected, actual = content_hash(trusted, True), content_hash(candidate, True)
    if actual != expected and actual != approved:
        raise QualityError(f"POLICY_CHANGE_REQUIRES_APPROVAL: exact protected-tree hash {actual}")
    return expected


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def tool(name: str, expected: str) -> str:
    executable = shutil.which(name)
    if not executable and os.name == "nt":
        executable = shutil.which(name + ".bat")
    if not executable and name == "checkov":
        executable = next((str(Path(folder) / "checkov") for folder in os.environ.get("PATH", "").split(os.pathsep)
                           if (Path(folder) / "checkov").is_file()), None)
    if not executable:
        raise QualityError(f"Required tool is missing: {name} {expected}")
    version_flag = "version" if name == "gitleaks" else "--version"
    result = execute(cli_command(executable, [version_flag]), timeout=60)
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    if not re.search(r"(?<![\d.])" + re.escape(expected) + r"(?![\d.])", output):
        raise QualityError(f"Unexpected {name} version; require {expected}")
    return executable


def cli_command(executable: str, arguments: list[str]) -> list[str]:
    # Checkov distributes a shebang script even in some Windows virtualenvs.
    if os.name == "nt" and Path(executable).name == "checkov":
        interpreter = Path(executable).parent / "python.exe"
        if not interpreter.is_file():
            raise QualityError("Checkov requires its own virtualenv Python interpreter")
        return [str(interpreter), executable, *arguments]
    return [executable, *arguments]


def check_coverage(path: Path, thresholds: dict[str, float] | None = None) -> dict:
    thresholds = thresholds or {"LINE": .9, "BRANCH": .9}
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise QualityError("Missing or invalid JaCoCo XML") from exc
    counters = {c.attrib["type"]: c.attrib for c in root.findall("counter")}
    result = {}
    for kind, threshold in thresholds.items():
        if kind not in counters:
            raise QualityError(f"Missing JaCoCo {kind} counter")
        covered, missed = int(counters[kind]["covered"]), int(counters[kind]["missed"])
        total = covered + missed
        if total == 0:
            raise QualityError(f"Empty JaCoCo {kind} coverage cannot establish the gate")
        ratio = covered / total
        if ratio < threshold:
            raise QualityError(f"JaCoCo {kind} coverage {ratio:.2%} is below {threshold:.0%}")
        result[kind] = {"covered": covered, "missed": missed, "ratio": ratio}
    return result


def scanner_report(executable: str, arguments: list[str], report: Path, cwd: Path) -> None:
    result = subprocess.run([executable, *arguments], cwd=cwd, capture_output=True, timeout=900, check=False)
    # Scanner configurations enforce redaction; raw logs are never artifacts.
    if result.returncode:
        raise QualityError(f"{Path(executable).name} rejected snapshot (exit {result.returncode}); inspect {report.name}")
    if not report.is_file() or not report.stat().st_size:
        raise QualityError(f"Missing scanner evidence: {report.name}")


def reject_candidate_suppressions(root: Path, files: list[Path]) -> None:
    patterns = re.compile(r"(?:\bNOPMD\b|checkov\s*:\s*skip|gitleaks\s*:\s*allow|trivy\s*:\s*ignore|\bNOSONAR\b|SuppressWarnings\s*\([^\n]*[\"']PMD)", re.IGNORECASE)
    forbidden = {".gitleaksignore", ".trivyignore", ".trivyignore.yaml"}
    for path in files:
        relative = path.relative_to(root).as_posix()
        # Trusted scanner/test source naturally contains directive strings used
        # to verify rejection. Those files already require policy approval.
        if relative.startswith(("scripts/quality/", "config/quality/", "docs/quality/")):
            continue
        if path.name in forbidden:
            raise QualityError(f"Candidate-owned scanner ignore file is forbidden: {relative}")
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if patterns.search(content):
            raise QualityError(f"Inline scanner suppression requires a reviewed trusted policy: {relative}")


def compile_classpath(snapshot_root: Path, service: str, image: str) -> list[str]:
    docker = shutil.which("docker")
    if not docker:
        raise QualityError("Docker is required to compile untrusted Java in isolation")
    execute([docker, "run", "--rm", "--cap-drop=ALL", "--security-opt=no-new-privileges",
             "--pids-limit=512", "--memory=4g", "--cpus=2", "--user", str(os.getuid()) if hasattr(os, "getuid") else "1000:1000",
             "--mount", f"type=bind,source={snapshot_root},target=/workspace",
             "--env", "GRADLE_USER_HOME=/workspace/.quality-gradle-cache",
             "--workdir", f"/workspace/services/{service}", image,
             "gradle", "--no-daemon", "--no-configuration-cache", "--console=plain", "classes", "qualityClasspath"], timeout=900)
    output = snapshot_root / "services" / service / "build/quality/auxclasspath.txt"
    if not output.is_file():
        raise QualityError(f"Missing qualityClasspath output for {service}")
    paths = []
    for entry in re.split(r"[:\r\n]+", output.read_text(encoding="utf-8").strip()):
        if not entry:
            continue
        if not entry.startswith("/workspace/") or ".." in PurePosixPath(entry).parts:
            raise QualityError("Classpath escaped the isolated workspace")
        mapped = snapshot_root / entry.removeprefix("/workspace/")
        if not mapped.exists():
            raise QualityError("Classpath dependency is missing")
        paths.append(str(mapped))
    if not paths:
        raise QualityError("Empty Java auxiliary classpath")
    return paths


def pmd_in_container(candidate: Path, trusted: Path, output: Path, service: str,
                     sources: list[Path], classpath: list[str], image: str, version: str) -> None:
    executable = shutil.which("pmd") or shutil.which("pmd.bat")
    docker = shutil.which("docker")
    if not executable or not docker:
        raise QualityError("Pinned PMD distribution and Docker are required")
    installation = Path(executable).resolve().parent.parent
    if not (installation / "lib").is_dir():
        raise QualityError("PMD must come from the verified standalone distribution")
    java_files = output / f"{service}-sources.txt"
    java_files.write_text("\n".join("/workspace/" + p.relative_to(candidate).as_posix() for p in sources), encoding="utf-8")
    # Container uses POSIX separators even when the host is Windows.
    aux = ":".join("/workspace/" + Path(p).relative_to(candidate).as_posix() for p in classpath)
    base = [docker, "run", "--rm", "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--pids-limit=256", "--memory=2g", "--cpus=2", "--user", str(os.getuid()) if hasattr(os, "getuid") else "1000:1000",
            "--mount", f"type=bind,source={candidate},target=/workspace,readonly",
            "--mount", f"type=bind,source={installation},target=/opt/pmd,readonly",
            "--mount", f"type=bind,source={trusted / 'config/quality'},target=/policy,readonly",
            "--mount", f"type=bind,source={output},target=/reports", image, "/opt/pmd/bin/pmd"]
    actual = execute([*base, "--version"], timeout=90).stdout.decode(errors="replace")
    if version not in actual:
        raise QualityError("Container PMD version does not match policy")
    execute([*base, "check", "--file-list", f"/reports/{service}-sources.txt", "--rulesets", "/policy/pmd.xml",
             "--use-version", "java-25", "--aux-classpath", aux, "--format", "json", "--report-file",
             f"/reports/pmd-{service}.json", "--no-cache", "--no-progress"])
    if not (output / f"pmd-{service}.json").is_file():
        raise QualityError("PMD did not produce evidence")


def scan_snapshot(candidate: Path, trusted: Path, output: Path, *, commit: str,
                  tree: str) -> dict:
    policy = json.loads((trusted / "config/quality/tools.json").read_text(encoding="utf-8"))
    versions = policy["tools"]
    output.mkdir(parents=True, exist_ok=True)
    report = {"schemaVersion": 1, "commit": commit, "tree": tree,
              "policyHash": content_hash(trusted, True), "tools": versions, "checks": {}}
    files = [p for p in candidate.rglob("*") if p.is_file()]
    reject_candidate_suppressions(candidate, files)
    originals = {p: hashlib.sha256(p.read_bytes()).digest() for p in files}
    gitleaks = tool("gitleaks", versions["gitleaks"])
    secret_report = output / "gitleaks.json"
    scanner_report(gitleaks, ["dir", str(candidate), "--config", str(trusted / "config/quality/gitleaks.toml"),
                             "--gitleaks-ignore-path", str(trusted / "config/quality"), "--ignore-gitleaks-allow",
                             "--redact=100", "--report-format=json", "--report-path", str(secret_report),
                             "--no-banner", "--exit-code=1"], secret_report, candidate)
    report["checks"]["gitleaks"] = "passed"
    java_services = [s for s in SERVICES if any((candidate / "services" / s).glob("**/src/main/java/**/*.java"))]
    if java_services:
        for service in java_services:
            sources = sorted((candidate / "services" / service).glob("**/src/main/java/**/*.java"))
            auxiliary = compile_classpath(candidate, service, policy["buildImage"])
            for path, original in originals.items():
                if not path.is_file() or hashlib.sha256(path.read_bytes()).digest() != original:
                    raise QualityError("Candidate build changed tracked content before static analysis")
            pmd_in_container(candidate, trusted, output, service, sources, auxiliary, policy["buildImage"], versions["pmd"])
        report["checks"]["pmd"] = {"status": "passed", "services": java_services}
    else:
        report["checks"]["pmd"] = {"status": "not_applicable", "reason": "No production Java in snapshot"}
    config_present = any(p.suffix == ".tf" or p.name.startswith("Dockerfile") or ".github/workflows" in p.as_posix() for p in files)
    if config_present:
        checkov = tool("checkov", versions["checkov"])
        checkov_report = output / "checkov.json"
        result = subprocess.run(cli_command(checkov, ["--directory", str(candidate), "--config-file", str(trusted / "config/quality/checkov.yaml"),
                                 "--output", "json"]), capture_output=True, timeout=900, check=False)
        # Checkov JSON may include source lines: redact all values from environment.
        checkov_report.write_text(redact(result.stdout.decode("utf-8", errors="replace")), encoding="utf-8")
        if result.returncode or not checkov_report.stat().st_size:
            raise QualityError("Checkov failed or did not produce evidence")
        report["checks"]["checkov"] = "passed"
    else:
        report["checks"]["checkov"] = {"status": "not_applicable", "reason": "No supported IaC, Dockerfile or workflow"}
    dependency_present = any(p.name in ("build.gradle", "build.gradle.kts", "package-lock.json", "requirements.txt", "gradle.lockfile") for p in files)
    if dependency_present:
        trivy_report = output / "trivy.json"
        if shutil.which("trivy"):
            trivy = tool("trivy", versions["trivy"])
            scanner_report(trivy, ["fs", "--scanners", "vuln", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
                                   "--format", "json", "--output", str(trivy_report), str(candidate)], trivy_report, candidate)
        else:
            docker = shutil.which("docker")
            if not docker:
                raise QualityError("Trivy CLI or Docker is required")
            image = "aquasec/trivy:" + versions["trivy"]
            execute([docker, "run", "--rm", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                     "--mount", f"type=bind,source={candidate},target=/scan,readonly",
                     "--mount", f"type=bind,source={output},target=/reports", image,
                     "fs", "--scanners", "vuln", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
                     "--format", "json", "--output", "/reports/trivy.json", "/scan"], timeout=900)
            if not trivy_report.is_file():
                raise QualityError("Trivy did not produce evidence")
        report["checks"]["trivy"] = "passed"
    else:
        report["checks"]["trivy"] = {"status": "not_applicable", "reason": "No supported dependency manifest"}
    report["status"] = "passed"
    write_json(output / "evidence.json", report)
    return report


def branch_gate(base: str, head: str, same_repo: bool, frozen: bool = False) -> None:
    allowed = {"release": head == "develop", "main": head == "release",
               "develop": bool(re.fullmatch(r"(?:feature|fix|chore)/[a-z0-9][a-z0-9._/-]*", head))}
    if not same_repo or not allowed.get(base, False):
        raise QualityError("Invalid repository or branch promotion path")
    if frozen and base == "develop" and not head.startswith("fix/"):
        raise QualityError("Release stabilization permits only fix branches into develop")


def sonar_request(host: str, route: str, token: str, *, data: dict | None = None) -> dict:
    parsed = urllib.parse.urlparse(host)
    if parsed.scheme not in ("http", "https") or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise QualityError("Invalid Sonar URL")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1", "sonarqube"):
        raise QualityError("Plain HTTP Sonar is restricted to the local quality network")
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    request = urllib.request.Request(host.rstrip("/") + route, data=body, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
        return json.loads(payload) if payload else {}
    except (urllib.error.URLError, json.JSONDecodeError) as exc:
        raise QualityError("Sonar request failed; promotion remains blocked") from exc


def validate_sonar_metrics(metrics: dict[str, str]) -> None:
    numeric = {}
    for name, raw in metrics.items():
        try:
            value = float(raw)
        except (TypeError, ValueError, OverflowError) as exc:
            raise QualityError(f"Invalid Sonar metric: {name}") from exc
        if not math.isfinite(value):
            raise QualityError(f"Non-finite Sonar metric: {name}")
        numeric[name] = value
    required = {"coverage": (90.0, "min"), "duplicated_lines_density": (3.0, "max"),
                "reliability_rating": (1.0, "max"), "security_rating": (1.0, "max"),
                "sqale_rating": (1.0, "max"), "bugs": (0.0, "max"), "vulnerabilities": (0.0, "max"),
                "ncloc": (1.0, "min"), "lines_to_cover": (1.0, "min")}
    for name, (limit, mode) in required.items():
        if name not in metrics:
            raise QualityError(f"Missing Sonar metric: {name}")
        value = numeric[name]
        if (mode == "min" and value < limit) or (mode == "max" and value > limit):
            raise QualityError(f"Sonar metric rejected: {name}")
    if numeric.get("security_hotspots", 0) > 0 and numeric.get("security_hotspots_reviewed", 0) < 100:
        raise QualityError("Security hotspots require documented review")
    if "new_coverage" in numeric and numeric["new_coverage"] < 90:
        raise QualityError("New-code coverage is below 90%")


def sonar_evidence(host: str, token: str, project: str, task_id: str, sha: str, timeout: int = 600) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = sonar_request(host, "/api/ce/task?" + urllib.parse.urlencode({"id": task_id}), token)["task"]
        if task.get("componentKey") != project:
            raise QualityError("Sonar task belongs to another project")
        if task["status"] == "SUCCESS":
            break
        if task["status"] in ("FAILED", "CANCELED"):
            raise QualityError("Sonar compute task did not succeed")
        time.sleep(2)
    else:
        raise QualityError("Sonar compute timeout")
    analysis_id = task.get("analysisId")
    if not analysis_id:
        raise QualityError("Sonar did not return an analysis ID")
    analyses = sonar_request(host, "/api/project_analyses/search?" + urllib.parse.urlencode({"project": project, "ps": 100}), token)["analyses"]
    analysis = next((item for item in analyses if item.get("key") == analysis_id), None)
    if not analysis or analysis.get("revision") != sha:
        raise QualityError("Sonar evidence does not match the exact candidate SHA")
    if not analyses or analyses[0].get("key") != analysis_id:
        raise QualityError("Concurrent Sonar analysis replaced the candidate; retry serially")
    gate = sonar_request(host, "/api/qualitygates/project_status?" + urllib.parse.urlencode({"analysisId": analysis_id}), token)["projectStatus"]
    if gate.get("status") != "OK":
        raise QualityError("Sonar Quality Gate is not green")
    keys = "coverage,new_coverage,duplicated_lines_density,reliability_rating,security_rating,sqale_rating,bugs,vulnerabilities,ncloc,lines_to_cover,security_hotspots,security_hotspots_reviewed"
    measures = sonar_request(host, "/api/measures/component?" + urllib.parse.urlencode({"component": project, "metricKeys": keys}), token)["component"]["measures"]
    metrics = {item["metric"]: item.get("value", item.get("period", {}).get("value")) for item in measures}
    metrics = {key: value for key, value in metrics.items() if value is not None}
    validate_sonar_metrics(metrics)
    # Measures endpoint is current-project scoped: verify it was not replaced
    # while reading, rather than attributing another analysis's metrics to ours.
    latest = sonar_request(host, "/api/project_analyses/search?" + urllib.parse.urlencode({"project": project, "ps": 1}), token)["analyses"]
    if not latest or latest[0].get("key") != analysis_id:
        raise QualityError("Sonar candidate changed during metric validation")
    return {"project": project, "commit": sha, "ceTaskId": task_id, "analysisId": analysis_id,
            "qualityGate": "OK", "metrics": metrics}


def verify_manifest(manifest: dict, tree: str, expected_sha: str | None = None, cloud: bool = False) -> None:
    if manifest.get("schemaVersion") != 1 or manifest.get("tree") != tree:
        raise QualityError("Candidate content changed or manifest schema is invalid")
    if not SHA.fullmatch(manifest.get("sourceCommit", "")):
        raise QualityError("Invalid candidate source commit")
    if expected_sha and manifest["sourceCommit"] != expected_sha:
        raise QualityError("Manifest belongs to a different source commit")
    if cloud and not SHA.fullmatch(manifest.get("infraGitSha", "")):
        raise QualityError("Cloud promotion requires the exact external infrastructure commit")
    if set(manifest.get("images", {})) != set(SERVICES):
        raise QualityError("Manifest must contain all three service images")
    for image in manifest["images"].values():
        if not isinstance(image, str) or "@" not in image or not DIGEST.fullmatch(image.rsplit("@", 1)[1]):
            raise QualityError("Every promoted image must use an immutable digest")
    sonar = manifest.get("sonar", {})
    if set(sonar) != set(SERVICES):
        raise QualityError("Missing Sonar service evidence")
    for service, evidence in sonar.items():
        if evidence.get("project") != "ticketing-" + service or evidence.get("commit") != manifest["sourceCommit"]:
            raise QualityError("Sonar evidence belongs to another candidate or service")
        if evidence.get("qualityGate") != "OK" or not evidence.get("analysisId") or not evidence.get("ceTaskId"):
            raise QualityError("Incomplete or failed Sonar evidence")
        validate_sonar_metrics(evidence.get("metrics", {}))
    required = ("unit", "integration", "architecture", "concurrency", "recovery", "containerSmoke", "expiry", "static", "configuration", "security", "sbom")
    if any(manifest.get("checks", {}).get(name) != "passed" for name in required):
        raise QualityError("Candidate evidence is incomplete")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest.get("policyHash", "")):
        raise QualityError("Candidate has no exact quality policy identity")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--trusted-root", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    staged = commands.add_parser("staged")
    staged.add_argument("--output", type=Path)
    commits = commands.add_parser("commits")
    commits.add_argument("--before")
    commits.add_argument("--after", required=True)
    commits.add_argument("--base")
    scan = commands.add_parser("scan-range")
    scan.add_argument("--before")
    scan.add_argument("--after", required=True)
    scan.add_argument("--base")
    scan.add_argument("--integration")
    scan.add_argument("--output", type=Path, required=True)
    scan.add_argument("--approved-policy-hash", default=os.environ.get("QUALITY_APPROVED_POLICY_HASH"))
    commands.add_parser("policy-hash")
    branch = commands.add_parser("branch")
    branch.add_argument("--base", required=True)
    branch.add_argument("--head", required=True)
    branch.add_argument("--same-repository", action="store_true")
    branch.add_argument("--frozen", action="store_true")
    cov = commands.add_parser("coverage")
    cov.add_argument("xml", type=Path)
    sonar = commands.add_parser("sonar-evidence")
    sonar.add_argument("--project", choices=["ticketing-" + s for s in SERVICES], required=True)
    sonar.add_argument("--task-id", required=True)
    sonar.add_argument("--sha", required=True)
    sonar.add_argument("--output", type=Path, required=True)
    manifest = commands.add_parser("verify-manifest")
    manifest.add_argument("manifest", type=Path)
    manifest.add_argument("--revision", required=True)
    manifest.add_argument("--source-sha")
    manifest.add_argument("--cloud", action="store_true")
    args = parser.parse_args(argv)
    repo, trusted = args.repo.resolve(), args.trusted_root.resolve()
    try:
        if args.command == "policy-hash":
            print(content_hash(trusted, True))
        elif args.command == "commits":
            print(json.dumps(introduced_commits(repo, args.before, args.after, args.base)))
        elif args.command == "branch":
            branch_gate(args.base, args.head, args.same_repository, args.frozen)
        elif args.command == "coverage":
            print(json.dumps(check_coverage(args.xml)))
        elif args.command == "sonar-evidence":
            if os.environ.get("GITHUB_REF") != "refs/heads/develop" or os.environ.get("GITHUB_EVENT_NAME") != "push":
                raise QualityError("Community Sonar only accepts trusted develop push snapshots")
            sha = revision(repo, args.sha)
            if sha != git(repo, "rev-parse", "HEAD"):
                raise QualityError("Checkout does not match requested Sonar SHA")
            token = os.environ.get("SONAR_TOKEN", "")
            if not token:
                raise QualityError("SONAR_TOKEN is required")
            reader = os.environ.get("SONAR_READ_TOKEN") or token
            write_json(args.output, sonar_evidence(os.environ.get("SONAR_HOST_URL") or "http://localhost:19000", reader,
                                                   args.project, args.task_id, sha))
        elif args.command == "verify-manifest":
            value = json.loads(args.manifest.read_text(encoding="utf-8"))
            verify_manifest(value, git(repo, "rev-parse", f"{revision(repo, args.revision)}^{{tree}}"), args.source_sha, args.cloud)
        elif args.command == "staged":
            if git(repo, "ls-files", "--unmerged"):
                raise QualityError("Resolve index conflicts before scanning")
            tree = git(repo, "write-tree")
            output = (args.output or Path(git(repo, "rev-parse", "--absolute-git-dir")) / "quality" / tree).resolve()
            with tempfile.TemporaryDirectory(prefix="ticketing-index-") as directory:
                candidate = Path(directory)
                snapshot(repo, tree, candidate)
                # The local hook is advisory. CI controls policy approval independently.
                scan_snapshot(candidate, trusted, output, commit="staged", tree=tree)
            print(f"Staged tree {tree} passed; evidence: {output}")
        elif args.command == "scan-range":
            head = revision(repo, args.after)
            selected = introduced_commits(repo, args.before, head, args.base)
            if head not in selected:
                selected.append(head)
            if args.integration:
                merge = revision(repo, args.integration)
                if merge not in selected:
                    selected.append(merge)
            with tempfile.TemporaryDirectory(prefix="ticketing-policy-") as directory:
                snapshot(repo, head, Path(directory))
                policy_guard(trusted, Path(directory), args.approved_policy_hash)
            evidence = []
            for commit in selected:
                with tempfile.TemporaryDirectory(prefix="ticketing-commit-") as directory:
                    candidate = Path(directory)
                    snapshot(repo, commit, candidate)
                    evidence.append(scan_snapshot(candidate, trusted, args.output.resolve() / commit,
                                                  commit=commit, tree=git(repo, "rev-parse", commit + "^{tree}")))
            write_json(args.output / "commits.json", {"head": head, "commits": evidence, "status": "passed"})
        return 0
    except (QualityError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print(redact(f"QUALITY FAILED: {exc}"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
